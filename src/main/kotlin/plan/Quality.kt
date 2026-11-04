package plan

import config.MixedOutput
import config.RunConfiguration
import data.GameConstants
import data.Item
import data.Product
import data.Recipe
import data.RootData
import data.craftingCategories
import emit.splitQuality
import emit.withQuality

/**
 * Quality production ([config.QualityProductionConfig]): items of a better quality are items of their own, named
 * "name@quality" ("iron-gear-wheel@rare"; normal keeps the plain name), and so are recipes ("iron-gear-wheel@rare"
 * takes rare plates and makes rare gears, or better). Every recipe that makes what the targets need gets a variant per
 * quality; its machine's quality modules ([qualityChance]) spread each item product over that quality and the ones
 * above it, the 2.0 way: one tier up with the chance, each further tier with that tier's next probability (10 %).
 * Recyclers (with their own quality modules) take what is not wanted back to a quarter of its ingredients, of the
 * same quality or better: the planner may loop items through them to climb (upcycling). Fluids have no quality.
 *
 * Everything after this sees plain recipes and items: the planner, the router (a machine with several products gets
 * an inserter per product it hands on), the validator, and the blueprint (recipe "x@rare" is recipe x of quality
 * rare, a filter "x@rare" matches rare x only). What the plan sends out of the block that is not a target (the other
 * qualities) leaves together on one mixed belt on [config.QualityProductionConfig.leftovers].
 */
fun withQualityProduction(data: RootData, config: RunConfiguration): Pair<RootData, RunConfiguration> {
    val qp = config.qualityProduction ?: return data to config
    val tiers = GameConstants.QUALITIES
    val next = tiers.map { data.quality[it]?.nextProbability ?: 0.0 }
    val machines = effectiveMachines(config)
    fun machineFor(r: Recipe) = machines.firstOrNull { r.category in data.craftingCategories(it) }
    val raw = (config.rawResources?.toSet() ?: BASE_RAW) + config.sources.map { it.name }

    // The recipes that make what the targets need, down to raw resources (recycling apart, added below).
    val needed = ArrayDeque(config.targets.map { splitQuality(it.name).first })
    val seen = HashSet(needed)
    val chain = LinkedHashSet<Recipe>()
    while (needed.isNotEmpty()) {
        val item = needed.removeFirst()
        if (item in raw) continue
        data.recipes.values.filter { r ->
            r.category !in RECYCLING_CATEGORIES && r.products.any { it.name == item } && r.name !in config.forbiddenRecipes &&
                config.recipeOverrides[item].let { it == null || it == r.name } && machineFor(r) != null
        }.forEach { r ->
            if (chain.add(r)) r.ingredients.filter { !it.isFluid }.forEach { if (seen.add(it.name)) needed += it.name }
        }
    }
    val chainItems = chain.flatMap { r -> r.ingredients.filter { !it.isFluid }.map { it.name } + r.products.filter { it.type != "fluid" }.map { it.name } }.toSet()
    val recycling = if (!qp.recycle) emptyList() else data.recipes.values.filter { r ->
        r.category in RECYCLING_CATEGORIES && !selfRecycling(r) && r.ingredients.singleOrNull()?.name in chainItems
    }

    /** Shares of tiers [i] and up a product of tier [i] lands in, with quality chance [q]. */
    fun spread(i: Int, q: Double): List<Pair<Int, Double>> {
        if (q <= 0 || i == tiers.size - 1) return listOf(i to 1.0)
        val out = arrayListOf(i to 1 - q)
        var reach = q   // chance of getting to tier j at least
        for (j in i + 1 until tiers.size) {
            val further = if (j == tiers.size - 1) 0.0 else next[j]
            out += j to reach * (1 - further)
            reach *= further
        }
        return out
    }
    fun variant(r: Recipe, i: Int): Recipe {
        val q = qualityChance(data, config, r, machineFor(r) ?: "recycler")
        fun item(name: String) = withQuality(name, tiers[i])
        return r.copy(
            name = withQuality(r.name, tiers[i]),
            ingredients = r.ingredients.map { if (it.isFluid) it else it.copy(name = item(it.name)) },
            products = r.products.flatMap { p ->
                if (p.type == "fluid") listOf(p) else spread(i, q).map { (j, share) ->
                    p.copy(name = withQuality(p.name, tiers[j]), probability = (p.probability ?: 1.0) * share,
                        extraCountFraction = p.extraCountFraction?.let { it * share })
                }
            },
            mainProduct = r.mainProduct?.let { mp -> if (mp.type == "fluid") mp else mp.copy(name = item(mp.name)) },
        )
    }
    val recipes = HashMap(data.recipes)
    (chain + recycling).forEach { r -> tiers.indices.forEach { i -> variant(r, i).let { recipes[it.name] = it } } }
    val items = HashMap(data.items)
    val variantItems = ArrayList<String>()
    (chainItems + recycling.flatMap { r -> r.products.filter { it.type != "fluid" }.map { it.name } }).forEach { name ->
        val base = data.items[name] ?: return@forEach
        tiers.drop(1).forEach { t -> val n = withQuality(name, t); items[n] = base.copy(name = n); variantItems += n }
    }
    // What leaves that is not a target (other qualities, normal included) shares one mixed belt.
    val leftovers = (variantItems + chainItems).distinct().filter { n -> config.targets.none { it.name == n } && n !in raw }
    val mixed = MixedOutput(leftovers.filter { n -> config.mixedOutputs.none { n in it.items } }, edge = qp.leftovers)
    val cfg = config.copy(
        mixedOutputs = config.mixedOutputs + mixed,
        // Recyclers do the climbing: allowed (only the recipes here), and in the machines.
        machines = if (qp.recycle && config.machines != null && "recycler" !in config.machines) config.machines + "recycler" else config.machines,
    )
    return data.copy(recipes = recipes, items = items) to cfg
}

/**
 * Chance a craft of [recipe] in [machine] comes out one tier better: the quality effects of its modules (quality
 * modules add, speed modules take away; bonuses times the module quality's multiplier) and of planned beacons, an
 * effect of 0.25 being 2.5 %. [W] VERIFY (v28)
 */
fun qualityChance(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): Double {
    if (!recipe.allowedEffects.quality) return 0.0
    val mult = GameConstants.moduleBonusMultiplier(data.quality[config.moduleQuality()]?.level ?: 0)
    val own = machineModules(data, config, recipe, machine).sumOf { m ->
        val q = moduleEffects(data, m).quality ?: 0.0
        if (q > 0) q * mult else q
    }
    val beacons = plannedBeacons(data, config, recipe, machine)
    val fromBeacons = beaconSpec(data, config)?.takeIf { beacons > 0 }?.let { b ->
        b.effectivity * kotlin.math.sqrt(beacons.toDouble()) * b.modules.sumOf { moduleEffects(data, it).quality?.let { q -> if (q > 0) q * mult else q } ?: 0.0 }
    } ?: 0.0
    return ((own + fromBeacons) / 10.0).coerceIn(0.0, 1.0)
}
