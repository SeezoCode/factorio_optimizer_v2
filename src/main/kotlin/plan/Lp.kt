package plan

import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import config.PlannerTuning
import config.RunConfiguration
import data.FurnaceEntity
import data.RocketSiloEntity
import data.AssemblingMachineEntity
import data.Recipe
import data.RootData
import data.craftingCategories
import data.craftingSpeed
import data.gameName
import config.AUTO_MACHINES

/** Resources the base game mines or pumps; they may always be supplied through a port. */
val BASE_RAW = setOf("iron-ore", "copper-ore", "stone", "coal", "wood", "crude-oil", "water", "uranium-ore", "raw-fish")

/** Categories no base crafting machine should take even if listed (placeholders; recycling unless the job asks). */
private val EXCLUDED_CATEGORIES = setOf("parameters")
internal val RECYCLING_CATEGORIES = setOf("recycling", "recycling-or-hand-crafting")

/** A recycling recipe that turns an item into a quarter of itself (the way recyclers destroy items). */
fun selfRecycling(r: Recipe): Boolean =
    r.inAny(RECYCLING_CATEGORIES) && r.ingredients.size == 1 && r.products.size == 1 && r.products[0].name == r.ingredients[0].name

/** Whether the job's [config.RecyclingConfig] allows recycling recipe [r] (true for any other recipe). */
fun recyclingAllowed(r: Recipe, config: RunConfiguration): Boolean {
    if (!r.inAny(RECYCLING_CATEGORIES)) return true
    // Quality production climbs by recycling what is not wanted.
    if (config.qualityProduction?.recycle == true && !selfRecycling(r)) return true
    val rc = config.recycling
    return when (rc.mode) {
        "off" -> false
        "void" -> selfRecycling(r) && r.ingredients[0].name in rc.voidItems
        else -> if (selfRecycling(r)) r.ingredients[0].name in rc.voidItems else true
    }
}
// Emptying barrels gains nothing, except for a fluid nothing else makes (Py's milk: see solveRates); filling them
// only when a recipe takes a filled barrel (Py does), which the program sees itself.
private val EXCLUDED_SUBGROUPS = setOf("empty-barrel")

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
fun effectiveMachines(config: RunConfiguration, data: RootData? = null): List<String> =
    (config.machines ?: (listOf(config.machine) + listOf("electric-furnace", "chemical-plant", "oil-refinery", "centrifuge")))
        .flatMap { m -> if (m == AUTO_MACHINES) (data?.let { autoMachines(it, config, config.machines!!.filter { x -> x != m }) } ?: emptyList()) else listOf(m) }
        .let { if ((config.recycling.mode != "off" || config.qualityProduction?.recycle == true) && "recycler" !in it) it + "recycler" else it }
        .distinct()

/**
 * "auto" in a job's machines: per recipe category that [listed] machines do not craft, the game data's best machine
 * for it ([RunConfiguration.machineTier]: fastest or slowest), electric machines and farms (with their modules)
 * before burners, never a hidden one, one with a fixed recipe, or one matching the job's skip list.
 */
fun autoMachines(data: RootData, config: RunConfiguration, listed: List<String>): List<String> {
    val covered = listed.flatMap { data.craftingCategories(it) }.toSet()
    // 0: electric (and farms), 1: burns a fuel fluid, 2: burns fuel items.
    data class Cand(val name: String, val cats: List<String>, val burner: Int, val speed: Double)
    val cands = data.entities.values.mapNotNull { e ->
        val (cats, source, hidden, fixed) = when (e) {
            is AssemblingMachineEntity -> listOf(e.craftingCategories, e.energySource, e.hidden, e.fixedRecipe)
            is FurnaceEntity -> listOf(e.craftingCategories, e.energySource, e.hidden, null)
            // Rocket silos (for launch products, see withLaunchProducts); not Py's mega farm, a silo of one part.
            is RocketSiloEntity -> if (e.rocketPartsRequired > 1) listOf(e.craftingCategories, e.energySource, e.hidden, e.fixedRecipe) else return@mapNotNull null
            else -> return@mapNotNull null
        }
        @Suppress("UNCHECKED_CAST") cats as List<String>
        if (hidden == true || config.skipRecipes.any { it in e.name }) return@mapNotNull null
        if (source != "electric" && source != "burner" && !(source == "fluid" && fluidFuel(data, e.name, config) != null)) return@mapNotNull null
        val base = data.craftingSpeed(e.name, config.quality)
        val speed = if (needsModules(data, e.name)) {
            val m = farmModule(data, e.name) ?: return@mapNotNull null
            base * e.moduleInventorySize * (data.items[m]?.moduleEffects?.speed ?: 0.0)
        } else base
        // A machine with a fixed recipe (Py's clay pit) makes only that one: after the others of its category.
        Cand(e.name, cats.filter { it != "parameters" }, when (source) { "burner" -> 2; "fluid" -> 1; else -> 0 } + (if (fixed != null) 10 else 0), speed)
    }
    val out = LinkedHashSet<String>()
    cands.flatMap { it.cats }.distinct().filter { it !in covered }.sorted().forEach { cat ->
        val ranked = cands.filter { cat in it.cats }.sortedWith(compareBy<Cand>({ it.burner }, { if (config.machineTier == "slowest") it.speed else -it.speed }, { it.name }))
        ranked.firstOrNull()?.let { out += it.name }
        // Fixed-recipe machines each make their own recipe, so every one of them is a choice.
        ranked.filter { it.burner >= 10 }.groupBy { fixedRecipeOf(data, it.name) }.values.forEach { out += it.first().name }
    }
    return out.toList()
}

/** The one recipe a machine is built to make, if any (Py's clay pit, the rocket silo). */
fun fixedRecipeOf(data: RootData, machine: String): String? = when (val e = data.entities[machine]) {
    is AssemblingMachineEntity -> e.fixedRecipe
    is RocketSiloEntity -> e.fixedRecipe
    else -> null
}

/** Raw resources when the job lists none: the game data's mined and pumped resources (exporter data), else the base game's. */
fun defaultRaw(data: RootData): Set<String> =
    data.resources?.values?.flatMap { r -> r.products.orEmpty().map { it.name } }?.toSet()?.plus(BASE_RAW) ?: BASE_RAW

/**
 * The fuel a burner machine burns: the job's [RunConfiguration.fuel] when the machine takes its category (coal for
 * furnaces), else the item named after one of the machine's fuel categories (nutrients for a biochamber), else
 * any item of such a category. Null for electric machines.
 */
/**
 * The fuel fluid a machine burning one takes (Py's glassworks): the job's [RunConfiguration.fluidFuel], else the
 * raw fluid that holds the most energy, else any fuel fluid. Null for other machines, or when none burns.
 */
fun fluidFuel(data: RootData, machine: String, config: RunConfiguration): String? {
    val fe = when (val e = data.entities[machine]) {
        is AssemblingMachineEntity -> e.fluidEnergy.takeIf { e.energySource == "fluid" }
        is FurnaceEntity -> e.fluidEnergy.takeIf { e.energySource == "fluid" }
        else -> null
    } ?: return null
    if (fe.burnsFluid == false) return null
    fe.fluidBox.filter?.let { return it }
    config.fluidFuel?.let { return it }
    val raw = config.rawResources?.toSet() ?: defaultRaw(data)
    val fuels = data.fluids.values.filter { it.fuelValue > 0 }
    return (fuels.filter { it.name in raw }.ifEmpty { fuels }).maxWithOrNull(compareBy({ it.fuelValue }, { it.name }))?.name
}

fun fuelItem(data: RootData, machine: String, config: RunConfiguration): String? {
    fluidFuel(data, machine, config)?.let { return it }
    val e = data.entities[machine]
    val burner = (e as? FurnaceEntity)?.energySource == "burner" || (e as? AssemblingMachineEntity)?.energySource == "burner"
    if (!burner) return null
    val cats = (e as? FurnaceEntity)?.fuelCategories ?: (e as? AssemblingMachineEntity)?.fuelCategories ?: listOf("chemical")
    if (data.items[config.fuel]?.allFuelCategories?.any { it in cats } == true) return config.fuel
    cats.forEach { c -> if (data.items[c]?.allFuelCategories?.contains(c) == true) return c }
    return data.items.values.filter { it.allFuelCategories.any { c -> c in cats } && it.fuelValue > 0 }.minByOrNull { it.name }?.name
        ?: error("no fuel for '$machine' (fuel categories $cats)")
}

/** Fuel per craft for burner machines (items of [fuelItem]); 0 for electric machines. */
fun fuelPerCraft(data: RootData, choice: RecipeChoice, fuelItem: String?): Double {
    if (fuelItem == null) return 0.0
    val e = data.entities[choice.machine]
    val watts = (e as? FurnaceEntity)?.energyConsumption ?: (e as AssemblingMachineEntity).energyConsumption
    // A fuel fluid burns at the machine's effectivity.
    data.fluids[fuelItem]?.let { f ->
        val eff = ((e as? AssemblingMachineEntity)?.fluidEnergy ?: (e as? FurnaceEntity)?.fluidEnergy)?.effectivity ?: 1.0
        val value = f.fuelValue.toDouble().takeIf { it > 0 } ?: error("'$fuelItem' is not a fuel")
        return watts * choice.machineTime / (value * eff)
    }
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
    val researched = config.research.productivity[emit.splitQuality(c.recipe.name).first] ?: 0.0
    return minOf(base + researched + moduleProductivity(data, config, c.recipe, c.machine),
        c.recipe.maximumProductivity.toDouble())
}

/** What burning [fuel] of [fuelItem] per craft leaves behind (Py's ash), or null: the fuel's burnt result, one per fuel item. */
fun burntPerCraft(data: RootData, fuelItem: String?, fuel: Double): Pair<String, Double>? =
    if (fuel <= 0 || fuelItem == null) null else data.items[fuelItem]?.burntResult?.let { it to fuel }

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
    val machines = effectiveMachines(config, data)
    val sourced = config.sources.map { it.name }.toSet()
    val overrides = config.recipeOverrides
    val categoryFilter = config.recipeCategories?.toSet()

    // Fluids only a barrel holds: Py's milk and arqad jelly come from emptying barrels the animals fill.
    val onlyBarrelled = data.recipes.values.filter { it.subgroup in EXCLUDED_SUBGROUPS }.flatMap { r -> r.products.filter { it.type == "fluid" }.map { it.name } }
        .filter { f -> data.recipes.values.none { r -> r.subgroup !in EXCLUDED_SUBGROUPS && r.products.any { it.name == f } } }.toSet()
    val choices = data.recipes.values.mapNotNull { r ->
        val barrelOnly = r.subgroup in EXCLUDED_SUBGROUPS && r.products.any { it.name in onlyBarrelled }
        if (r.inAny(EXCLUDED_CATEGORIES) || (r.subgroup in EXCLUDED_SUBGROUPS && !barrelOnly) || r.name in config.forbiddenRecipes) return@mapNotNull null
        // Hidden recipes (recycling excepted) and the job's skip list (Py's upgrade and voiding recipes),
        // unless a recipe override names it.
        val named = r.name in config.recipeOverrides.values
        if (!named && r.hidden == true && !r.inAny(RECYCLING_CATEGORIES)) return@mapNotNull null
        if (!named && config.skipRecipes.any { it in r.name }) return@mapNotNull null
        if (!recyclingAllowed(r, config)) return@mapNotNull null
        if (categoryFilter != null && !r.inAny(categoryFilter)) return@mapNotNull null
        if (r.products.isEmpty()) return@mapNotNull null
        // Items the job supplies are not made inside the block.
        val main = r.mainProduct?.name
        if (main != null && main in sourced) return@mapNotNull null
        // recipe_overrides: only the chosen recipe may make that item.
        if (overrides.any { (item, chosen) -> chosen != r.name && r.products.any { it.name == item } }) return@mapNotNull null
        // A farm with no module it can take does not work.
        val m = machines.firstOrNull { r.inAny(data.craftingCategories(it)) && (!needsModules(data, it) || farmModule(data, it) != null) &&
            fixedRecipeOf(data, it).let { f -> f == null || f == gameName(r.name) } } ?: return@mapNotNull null
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
        burntPerCraft(data, fuelItem, fuel)?.let { (b, n) -> m.merge(b, n, Double::plus) }
        m
    }
    val items = (net.values.flatMap { it.keys } + targets.keys + sourced).toSortedSet()

    Loader.loadNativeLibraries()
    val solver = MPSolver.createSolver("GLOP") ?: error("GLOP solver unavailable")
    val inf = MPSolver.infinity()
    val x = choices.associateWith { solver.makeNumVar(0.0, inf, "x_${it.recipe.name}") }
    val raw = config.rawResources?.toSet() ?: defaultRaw(data)
    // A target the block can make is made, not passed through from a raw input (sulfuric acid on Vulcanus).
    val makeable = choices.flatMap { c -> c.recipe.products.map { it.name } }.toSet()
    val supplyAllowed = items.filter { it in sourced || (it in raw && !(it in targets && it in makeable)) }.toSet()
    val s = supplyAllowed.associateWith { solver.makeNumVar(0.0, inf, "s_$it") }
    // Items the job voids by recycling may not leave the block (unless they are targets): their surplus is destroyed.
    val voided = if (config.recycling.mode == "off") emptySet() else config.recycling.voidItems.toSet()
    val out = items.associateWith { solver.makeNumVar(0.0, if (it in voided && it !in targets) 0.0 else inf, "o_$it") }
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
    // Items the job lets leave on a mixed belt (quality production's other qualities among them) leave almost free:
    // otherwise the planner would rather turn them into something else that leaves in smaller numbers.
    val mayLeave = config.mixedOutputs.flatMap { it.items }.toSet()
    out.forEach { (item, v) ->
        if (item !in targets) obj.setCoefficient(v, (if (item in fluids) tun.surplusCostFluid else tun.surplusCostItem) * (if (item in mayLeave) 0.01 else 1.0))
    }
    obj.setMinimization()
    val status = solver.solve()
    if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
        val missing = targets.keys.filter { t -> choices.none { c -> c.recipe.products.any { it.name == t } } && t !in supplyAllowed }
        // What the targets need that nothing can make from what may enter: walk down from each target through
        // recipes whose ingredients cannot all be made, to items no usable recipe makes at all.
        val reach = supplyAllowed.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            choices.forEach { c -> if (c.recipe.ingredients.all { it.name in reach }) c.recipe.products.forEach { p -> if (reach.add(p.name)) grew = true } }
        }
        val leaves = sortedSetOf<String>(); val seen = HashSet<String>()
        fun down(item: String, depth: Int) {
            if (item in reach || !seen.add(item) || depth > 40 || leaves.size >= 12) return
            val makers = choices.filter { c -> c.recipe.products.any { it.name == item } }
            if (makers.isEmpty()) { leaves += item; return }
            makers.minByOrNull { c -> c.recipe.ingredients.count { it.name !in reach } }!!.recipe.ingredients.filter { it.name !in reach }.forEach { down(it.name, depth + 1) }
        }
        targets.keys.forEach { down(it, 0) }
        error("No production plan: LP status $status" + (if (missing.isNotEmpty()) "; nothing can make $missing" else "") +
            (if (leaves.isNotEmpty()) "; it needs, and nothing it may use makes: $leaves (add them to raw_resources or sources, or allow a machine that makes them)" else ""))
    }
    val eps = 1e-9
    // Machines are whole: a recipe run at 1% of a machine costs a machine, not 1% of one. With many recipes to choose
    // from (Pyanodon's 10,000) the program above would rather run hundreds of recipes at slivers of a machine, each to
    // use up some by-product. So, for big recipe sets, it is solved again a few times with each recipe it used costed
    // per craft at its whole machines (ceil(m) / m times as much at m machines' worth); the solution whose whole
    // machines, inputs and leftovers cost least is kept.
    fun area(c: RecipeChoice) = data.entities.getValue(c.machine).let { c.machineTime * it.width * it.height / 9.0 }
    fun snapshot() = Triple(x.mapValues { it.value.solutionValue() }, s.mapValues { it.value.solutionValue() }, out.mapValues { it.value.solutionValue() })
    fun trueCost(sol: Triple<Map<RecipeChoice, Double>, Map<String, Double>, Map<String, Double>>): Double =
        sol.first.entries.sumOf { (c, v) -> if (v <= eps) 0.0 else kotlin.math.ceil(v * c.machineTime - 1e-6) / c.machineTime * area(c) } +
            sol.second.entries.sumOf { (i, v) -> v * (if (i in fluids) tun.supplyCostFluid else tun.supplyCostItem) } +
            sol.third.entries.sumOf { (i, v) -> if (i in targets) 0.0 else v * (if (i in fluids) tun.surplusCostFluid else tun.surplusCostItem) * (if (i in mayLeave) 0.01 else 1.0) }
    var best = snapshot()
    if (choices.size > 1000) {
        var bestCost = trueCost(best)
        var cur = best
        val touched = best.first.filterValues { it > eps }.keys.toMutableSet()
        val snapshotUsed = touched.size
        // Each round at most 20 s (a re-weighted program can stall the simplex); one that runs out ends the rounds.
        solver.setTimeLimit(20_000)
        var stalled = false
        repeat(6) {
            if (stalled) return@repeat
            choices.forEach { c ->
                val v = cur.first[c] ?: 0.0
                val m = v * c.machineTime
                // At most 1,000 times a machine's area (a sliver of 1e-6 of a machine would weigh a million times and
                // leave the simplex with numbers it cannot handle).
                obj.setCoefficient(x.getValue(c), if (v > eps) area(c) * kotlin.math.ceil(m - 1e-6).coerceAtLeast(1.0) / m.coerceAtLeast(1e-3) else area(c))
            }
            // From scratch: GLOP restarting from the last round's basis stalls or gives up on the numbers ("abnormal")
            // on Py red science, where a fresh solve takes under a second.
            solver.reset()
            val st = solver.solve()
            if (st != MPSolver.ResultStatus.OPTIMAL) { stalled = true; return@repeat }
            val next = snapshot()
            val used = next.first.filterValues { it > eps }.keys
            touched += used
            if (used == cur.first.filterValues { it > eps }.keys) { cur = next; return@repeat }
            cur = next
            val cost = trueCost(next)
            if (cost < bestCost) { best = next; bestCost = cost }
        }
        // Then a fixed charge per recipe: among the recipes those rounds touched, an integer program where a recipe
        // used at all costs at least one machine. Kept if its whole machines, inputs and leftovers cost less.
        // Only worth it when the rounds found other recipes to use: otherwise every recipe used is needed.
        val firstUsed = snapshotUsed
        val fc = if (touched.size == firstUsed) null
            else fixedCharge(touched.toList(), net, items, targets, supplyAllowed, fluids, mayLeave, voided, tun, ::area, best.first)
        val before = bestCost
        fc?.let {
            val full = Triple(choices.associateWith { c -> fc.first[c] ?: 0.0 }, fc.second, fc.third)
            val cost = trueCost(full)
            if (cost < bestCost) { best = full; bestCost = cost }
        }
        if (touched.size > firstUsed) warn("planner: ${touched.size} recipes tried; whole-machine cost ${"%.1f".format(before)} -> ${"%.1f".format(bestCost)}" +
            (if (fc == null) " (integer program: no solution in time)" else "") + ", ${best.first.count { it.value > eps }} recipes used")
    }
    // A recipe under a millionth of a craft per second (Py's hotair tin cable at 1e-8/s: a craft a year) is not
    // built: its machine would wait for inputs nothing routes at that rate. Unless taking it out leaves something
    // short by more than that: a recipe used rarely per craft (a catalyst nearly always returned) still needs its
    // maker, however slow.
    val crafts = best.first.filterValues { it > eps }.toMutableMap()
    var dropped = crafts.filterValues { it <= 1e-6 }.keys.toMutableSet()
    // Until nothing more comes back: a recipe brought back needs its own inputs' makers too.
    repeat(50) {
        val kept = crafts.filterKeys { it !in dropped }
        val bal = HashMap<String, Double>()
        kept.forEach { (c, v) -> net.getValue(c).forEach { (item, a) -> bal.merge(item, a * v, Double::plus) } }
        best.second.forEach { (item, v) -> bal.merge(item, v, Double::plus) }
        targets.forEach { (item, v) -> bal.merge(item, -v, Double::plus) }
        val short = bal.filterValues { it < -1e-6 }.keys
        val back = dropped.filter { c -> net.getValue(c).any { (item, a) -> a > 0 && item in short } }
        if (back.isEmpty()) return@repeat
        dropped -= back.toSet()
    }
    dropped.forEach { crafts.remove(it) }
    val supply = best.second.filterValues { it > eps }
    // What leaves the block: each target plus any surplus.
    val output = best.third.mapValues { it.value + (targets[it.key] ?: 0.0) }.filterValues { it > eps }
    output.filter { (k, v) -> k !in targets && v > 1e-6 }.forEach { (k, v) -> warn("by-product $k leaves the block at ${"%.3f".format(v)}/s") }
    return LpResult(crafts, supply, output, targets, fluids)
}


/**
 * The planner's balance as a mixed-integer program over [cands] only: each recipe's crafts x, and y = 1 if it is used
 * at all, x <= big * y, at a cost of one machine's area for y. Solved by SCIP for at most a minute; the best solution
 * found by then, or null.
 */
private fun fixedCharge(
    cands: List<RecipeChoice>, net: Map<RecipeChoice, Map<String, Double>>, items: Set<String>, targets: Map<String, Double>,
    supplyAllowed: Set<String>, fluids: Set<String>, mayLeave: Set<String>, voided: Set<String>, tun: PlannerTuning,
    area: (RecipeChoice) -> Double, start: Map<RecipeChoice, Double>,
): Triple<Map<RecipeChoice, Double>, Map<String, Double>, Map<String, Double>>? {
    val solver = MPSolver.createSolver("SCIP") ?: return null
    val inf = MPSolver.infinity()
    val x = cands.associateWith { solver.makeNumVar(0.0, inf, "x_${it.recipe.name}") }
    val y = cands.associateWith { solver.makeIntVar(0.0, 1.0, "y_${it.recipe.name}") }
    cands.forEach { c ->
        // At most 500 machines of a recipe.
        val row = solver.makeConstraint(-inf, 0.0, "use_${c.recipe.name}")
        row.setCoefficient(x.getValue(c), 1.0); row.setCoefficient(y.getValue(c), -500.0 / c.machineTime)
    }
    val used = cands.flatMap { net.getValue(it).keys }.toSet() + targets.keys
    val its = items.filter { it in used }
    val s = supplyAllowed.filter { it in used }.associateWith { solver.makeNumVar(0.0, inf, "s_$it") }
    val out = its.associateWith { solver.makeNumVar(0.0, if (it in voided && it !in targets) 0.0 else inf, "o_$it") }
    its.forEach { item ->
        val t = targets[item] ?: 0.0
        val row = solver.makeConstraint(t, t, "bal_$item")
        cands.forEach { c -> net.getValue(c)[item]?.let { row.setCoefficient(x.getValue(c), it) } }
        s[item]?.let { row.setCoefficient(it, 1.0) }
        row.setCoefficient(out.getValue(item), -1.0)
    }
    val obj = solver.objective()
    cands.forEach { c -> obj.setCoefficient(x.getValue(c), area(c)); obj.setCoefficient(y.getValue(c), area(c) / c.machineTime) }
    s.forEach { (item, v) -> obj.setCoefficient(v, if (item in fluids) tun.supplyCostFluid else tun.supplyCostItem) }
    out.forEach { (item, v) -> if (item !in targets) obj.setCoefficient(v, (if (item in fluids) tun.surplusCostFluid else tun.surplusCostItem) * (if (item in mayLeave) 0.01 else 1.0)) }
    obj.setMinimization()
    // The best solution so far as a start, so the solver has one to improve on.
    val hintVars = cands.flatMap { listOf(x.getValue(it), y.getValue(it)) }.toTypedArray()
    val hintVals = cands.flatMap { val v = start[it] ?: 0.0; listOf(v, if (v > 1e-9) 1.0 else 0.0) }.toDoubleArray()
    solver.setHint(hintVars, hintVals)
    solver.setTimeLimit(60_000)
    val st = solver.solve()
    if (st != MPSolver.ResultStatus.OPTIMAL && st != MPSolver.ResultStatus.FEASIBLE) return null
    return Triple(x.mapValues { it.value.solutionValue() }, s.mapValues { it.value.solutionValue() }, out.mapValues { it.value.solutionValue() })
}
