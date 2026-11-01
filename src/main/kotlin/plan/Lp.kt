package plan

import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import config.PlannerTuning
import config.RunConfiguration
import data.FurnaceEntity
import data.AssemblingMachineEntity
import data.Recipe
import data.RootData
import data.craftingCategories

/** Resources the base game mines or pumps; they may always be supplied through a port. */
val BASE_RAW = setOf("iron-ore", "copper-ore", "stone", "coal", "wood", "crude-oil", "water", "uranium-ore", "raw-fish")

/** Categories no base crafting machine should take even if listed (placeholders, recycling). */
private val EXCLUDED_CATEGORIES = setOf("parameters", "recycling", "recycling-or-hand-crafting")
private val EXCLUDED_SUBGROUPS = setOf("fill-barrel", "empty-barrel")

/** A recipe the planner may use, with the machine that will craft it. */
data class RecipeChoice(val recipe: Recipe, val machine: String, val speed: Double) {
    /** Seconds of one machine per craft. */
    val machineTime get() = recipe.energy / speed
}

data class LpResult(
    /** Crafts per second per recipe used. */
    val crafts: Map<RecipeChoice, Double>,
    /** Items/s (fluids: units/s) supplied through ports. */
    val supply: Map<String, Double>,
    /** Items/s leaving the block beyond what stays inside: targets plus surplus by-products. */
    val output: Map<String, Double>,
    val targets: Map<String, Double>,
    val fluids: Set<String>,
)

/** The machines a job may use, in preference order (first machine able to craft a category wins). */
fun effectiveMachines(config: RunConfiguration): List<String> =
    config.machines ?: (listOf(config.machine) + listOf("electric-furnace", "chemical-plant", "oil-refinery", "centrifuge"))
        .distinct()

/**
 * The fuel a burner machine burns: the job's [RunConfiguration.fuel] when the machine takes its category (coal for
 * furnaces), else the item named after one of the machine's fuel categories (nutrients for a biochamber), else
 * any item of such a category. Null for electric machines.
 */
fun fuelItem(data: RootData, machine: String, config: RunConfiguration): String? {
    val e = data.entities[machine]
    val burner = (e as? FurnaceEntity)?.energySource == "burner" || (e as? AssemblingMachineEntity)?.energySource == "burner"
    if (!burner) return null
    val cats = (e as? FurnaceEntity)?.fuelCategories ?: (e as? AssemblingMachineEntity)?.fuelCategories ?: listOf("chemical")
    if (data.items[config.fuel]?.fuelCategory in cats) return config.fuel
    cats.forEach { c -> if (data.items[c]?.fuelCategory == c) return c }
    return data.items.values.filter { it.fuelCategory in cats && it.fuelValue > 0 }.minByOrNull { it.name }?.name
        ?: error("no fuel for '$machine' (fuel categories $cats)")
}

/** Fuel per craft for burner machines (items of [fuelItem]); 0 for electric machines. */
fun fuelPerCraft(data: RootData, choice: RecipeChoice, fuelItem: String?): Double {
    if (fuelItem == null) return 0.0
    val e = data.entities[choice.machine]
    val watts = (e as? FurnaceEntity)?.energyConsumption ?: (e as AssemblingMachineEntity).energyConsumption
    val fuelValue = data.items[fuelItem]?.fuelValue?.takeIf { it > 0 } ?: error("'$fuelItem' is not a fuel")
    return watts * choice.machineTime / fuelValue
}

/**
 * Productivity of a recipe in its machine: the machine's own bonus (+50% in a foundry, electromagnetic plant or
 * biochamber) plus research bonuses from the job, capped at the recipe's maximum; none if the recipe allows none.
 */
fun productivity(data: RootData, c: RecipeChoice, config: RunConfiguration): Double {
    if (!c.recipe.allowedEffects.productivity) return 0.0
    val e = data.entities[c.machine]
    val base = (e as? AssemblingMachineEntity)?.effectReceiver?.baseEffect?.get("productivity")
        ?: (e as? FurnaceEntity)?.effectReceiver?.baseEffect?.get("productivity") ?: 0.0
    return minOf(base + (config.research.productivity[c.recipe.name] ?: 0.0) + moduleProductivity(data, config, c.recipe, c.machine),
        c.recipe.maximumProductivity.toDouble())
}

/**
 * Products per craft with productivity. The bonus applies to the part of a product above the same item's
 * ingredient amount, so catalysts (Kovarex, asteroid chunks) gain nothing from it. [P] VERIFY
 */
fun productsPerCraft(data: RootData, c: RecipeChoice, config: RunConfiguration): Map<String, Double> {
    val p = productivity(data, c, config)
    val ingredients = c.recipe.ingredients.groupBy { it.name }.mapValues { (_, l) -> l.sumOf { it.amount } }
    return c.recipe.products.groupBy { it.name }.mapValues { (name, ps) ->
        val amount = ps.sumOf { it.expectedAmount }
        amount + p * maxOf(0.0, amount - (ingredients[name] ?: 0.0))
    }
}

/**
 * Stage 1 rates as a linear program: crafts/s per recipe, supply per input, output per item, minimising
 * machine area plus small costs for inputs and larger ones for surplus by-products. Handles recipes with
 * several products (oil processing, cracking, Kovarex) and several recipes per item.
 */
fun solveRates(data: RootData, config: RunConfiguration, tun: PlannerTuning, warn: (String) -> Unit): LpResult {
    val machines = effectiveMachines(config)
    val sourced = config.sources.map { it.name }.toSet()
    val overrides = config.recipeOverrides
    val categoryFilter = config.recipeCategories?.toSet()

    val choices = data.recipes.values.mapNotNull { r ->
        if (r.category in EXCLUDED_CATEGORIES || r.subgroup in EXCLUDED_SUBGROUPS || r.name in config.forbiddenRecipes) return@mapNotNull null
        if (categoryFilter != null && r.category !in categoryFilter) return@mapNotNull null
        if (r.products.isEmpty()) return@mapNotNull null
        // Items the job supplies are not made inside the block.
        val main = r.mainProduct?.name
        if (main != null && main in sourced) return@mapNotNull null
        // recipe_overrides: only the chosen recipe may make that item.
        if (overrides.any { (item, chosen) -> chosen != r.name && r.products.any { it.name == item } }) return@mapNotNull null
        val m = machines.firstOrNull { r.category in data.craftingCategories(it) } ?: return@mapNotNull null
        // Modules and the planned beacons make the machine faster (see Modules.kt).
        RecipeChoice(r, m, moduleSpeed(data, config, r, m, plannedBeacons(data, config, r, m)))
    }

    val fluids = HashSet<String>()
    data.recipes.values.forEach { r ->
        r.ingredients.filter { it.isFluid }.forEach { fluids += it.name }
        r.products.filter { it.type == "fluid" }.forEach { fluids += it.name }
    }
    val targets = config.targets.groupBy { it.name }.mapValues { (_, t) -> t.sumOf { it.amount } }

    // Net amount per craft for every item a recipe touches (catalysts cancel).
    val net = choices.associateWith { c ->
        val m = HashMap<String, Double>()
        productsPerCraft(data, c, config).forEach { (item, amount) -> m.merge(item, amount, Double::plus) }
        c.recipe.ingredients.forEach { m.merge(it.name, -it.amount, Double::plus) }
        val fuelItem = fuelItem(data, c.machine, config)
        val fuel = fuelPerCraft(data, c, fuelItem)
        if (fuel > 0) m.merge(fuelItem!!, -fuel, Double::plus)
        m
    }
    val items = (net.values.flatMap { it.keys } + targets.keys + sourced).toSortedSet()

    Loader.loadNativeLibraries()
    val solver = MPSolver.createSolver("GLOP") ?: error("GLOP solver unavailable")
    val inf = MPSolver.infinity()
    val x = choices.associateWith { solver.makeNumVar(0.0, inf, "x_${it.recipe.name}") }
    val raw = config.rawResources?.toSet() ?: BASE_RAW
    // A target the block can make is made, not passed through from a raw input (sulfuric acid on Vulcanus).
    val makeable = choices.flatMap { c -> c.recipe.products.map { it.name } }.toSet()
    val supplyAllowed = items.filter { it in sourced || (it in raw && !(it in targets && it in makeable)) }.toSet()
    val s = supplyAllowed.associateWith { solver.makeNumVar(0.0, inf, "s_$it") }
    val out = items.associateWith { solver.makeNumVar(0.0, inf, "o_$it") }
    items.forEach { item ->
        val t = targets[item] ?: 0.0
        val row = solver.makeConstraint(t, t, "bal_$item")
        choices.forEach { c -> net.getValue(c)[item]?.let { row.setCoefficient(x.getValue(c), it) } }
        s[item]?.let { row.setCoefficient(it, 1.0) }
        row.setCoefficient(out.getValue(item), -1.0)
    }
    val obj = solver.objective()
    choices.forEach { c ->
        val e = data.entities.getValue(c.machine)
        obj.setCoefficient(x.getValue(c), c.machineTime * e.width * e.height / 9.0)
    }
    s.forEach { (item, v) -> obj.setCoefficient(v, if (item in fluids) tun.supplyCostFluid else tun.supplyCostItem) }
    out.forEach { (item, v) -> if (item !in targets) obj.setCoefficient(v, if (item in fluids) tun.surplusCostFluid else tun.surplusCostItem) }
    obj.setMinimization()
    val status = solver.solve()
    if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
        val missing = targets.keys.filter { t -> choices.none { c -> c.recipe.products.any { it.name == t } } && t !in supplyAllowed }
        error("No production plan: LP status $status" + if (missing.isNotEmpty()) "; nothing can make $missing" else "")
    }
    val eps = 1e-9
    val crafts = x.mapValues { it.value.solutionValue() }.filterValues { it > eps }
    val supply = s.mapValues { it.value.solutionValue() }.filterValues { it > eps }
    // What leaves the block: each target plus any surplus.
    val output = out.mapValues { it.value.solutionValue() + (targets[it.key] ?: 0.0) }.filterValues { it > eps }
    output.filter { (k, v) -> k !in targets && v > 1e-6 }.forEach { (k, v) -> warn("by-product $k leaves the block at ${"%.3f".format(v)}/s") }
    return LpResult(crafts, supply, output, targets, fluids)
}
