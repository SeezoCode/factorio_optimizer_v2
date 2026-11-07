package plan

import config.RunConfiguration
import data.AssemblingMachineEntity
import data.BeaconEntity
import data.FurnaceEntity
import data.GameConstants
import data.ModuleEffects
import data.Recipe
import data.RootData
import data.craftingSpeed
import kotlin.math.sqrt

/*
 * Modules and beacons ([config.BeaconConfig]). A machine's speed is its crafting speed times
 *   max(0.2, 1 + sum of its modules' speed + e * sqrt(n) * sum of one beacon's modules' speed),
 * n beacons in range, e the beacon's distribution effectivity (1.5), sqrt(n) = n * 1/sqrt(n) the 2.0 beacon
 * profile; productivity comes from the machine's own modules, capped with the rest at the recipe's maximum.
 * An effect the recipe or machine does not allow does not apply. [P] VERIFY (verify kit v20)
 */

/** A beacon as placement, routing and validation see it. */
data class BeaconSpec(
    val name: String,
    val size: Int,
    /** Tiles the square supply area reaches beyond the beacon's edge. */
    val supply: Int,
    val effectivity: Double,
    val slots: Int,
    /** Modules in each beacon. */
    val modules: List<String>,
)

/** The job's beacon, or null when it places none. */
fun beaconSpec(data: RootData, config: RunConfiguration): BeaconSpec? {
    val b = config.beacons ?: return null
    if (b.perMachine == 0) return null
    val e = data.entities[b.beacon] as? BeaconEntity ?: error("'${b.beacon}' is not a beacon")
    require(b.modules.size <= e.moduleInventorySize) { "a ${b.beacon} holds ${e.moduleInventorySize} modules, beacons.modules lists ${b.modules.size}" }
    b.modules.forEach { m ->
        val fx = moduleEffects(data, m)
        require((fx.productivity ?: 0.0) <= 0.0) { "beacons.modules: '$m' has productivity; productivity modules go in machine_modules" }
        val off = effectKeys(fx).filter { it !in e.allowedEffects }
        require(off.isEmpty()) { "a ${b.beacon} does not take '$m' (effects $off)" }
    }
    // A better beacon passes on more (distribution effectivity per quality level, from recipes.json).
    val level = data.quality[b.quality]?.level ?: 0
    return BeaconSpec(b.beacon, e.width, e.supplyAreaDistance[b.quality] ?: e.supplyAreaDistance["normal"] ?: 3,
        e.distributionEffectivity + e.distributionEffectivityBonusPerQualityLevel * level, e.moduleInventorySize, b.modules)
}

/** A module's effects. */
fun moduleEffects(data: RootData, name: String): ModuleEffects =
    data.items[name]?.moduleEffects ?: error("'$name' is not a module")

/** Effects a module has (quality is not modelled, so its quality penalty does not count). */
private fun effectKeys(fx: ModuleEffects) = listOfNotNull(
    "speed".takeIf { (fx.speed ?: 0.0) != 0.0 }, "productivity".takeIf { (fx.productivity ?: 0.0) != 0.0 },
    "consumption".takeIf { (fx.consumption ?: 0.0) != 0.0 }, "pollution".takeIf { (fx.pollution ?: 0.0) != 0.0 },
)

private class Receiver(val slots: Int, val allowed: Set<String>, val modules: Boolean, val beacons: Boolean)

/** How a machine takes modules: electric crafting machines with module slots or beacon effects. */
private fun receiver(data: RootData, machine: String): Receiver? = when (val e = data.entities[machine]) {
    is AssemblingMachineEntity -> if (e.energySource != "electric") null
        else Receiver(e.moduleInventorySize, e.allowedEffects.toSet(), e.effectReceiver.usesModuleEffects, e.effectReceiver.usesBeaconEffects)
    is FurnaceEntity -> if (e.energySource != "electric") null
        else Receiver(e.moduleInventorySize, e.allowedEffects.toSet(), e.effectReceiver.usesModuleEffects, e.effectReceiver.usesBeaconEffects)
    else -> null
}

private fun recipeAllows(r: Recipe, key: String) = when (key) {
    "speed" -> r.allowedEffects.speed
    "productivity" -> r.allowedEffects.productivity
    "consumption" -> r.allowedEffects.consumption
    "pollution" -> r.allowedEffects.pollution
    else -> r.allowedEffects.quality
}

/** Can [module] go into [machine] crafting [recipe]? */
private fun fits(data: RootData, recipe: Recipe, rc: Receiver, module: String) =
    effectKeys(moduleEffects(data, module)).all { it in rc.allowed && recipeAllows(recipe, it) }

/** A machine's base speed effect (Py's farms: -1, so they work only with modules). */
private fun baseSpeedEffect(data: RootData, machine: String): Double = when (val e = data.entities[machine]) {
    is AssemblingMachineEntity -> e.effectReceiver.baseEffect["speed"] ?: 0.0
    is FurnaceEntity -> e.effectReceiver.baseEffect["speed"] ?: 0.0
    else -> 0.0
}

/** Whether [machine] works only with modules in its slots (a base speed effect of -100%: Pyanodon's farms). */
fun needsModules(data: RootData, machine: String): Boolean = baseSpeedEffect(data, machine) <= -0.99

/**
 * The module a farm is filled with: the mod's default for that farm (Pyanodon's mod-data "farm_buildings", keyed by
 * the building's name without its tier), when the farm takes it; else the slowest speed module of a category it
 * takes (the plainest animal or plant). Null when none fits.
 */
fun farmModule(data: RootData, machine: String): String? {
    val e = data.entities[machine] as? AssemblingMachineEntity ?: return null
    val allowed = e.allowedModuleCategories?.toSet()
    fun takes(m: String) = data.items[m]?.let { it.type == "module" && (allowed == null || it.category in allowed) && (it.moduleEffects?.speed ?: 0.0) > 0 } == true
    val farms = data.modData?.get("pyanodons")?.data?.takeIf { it.isJsonObject }?.asJsonObject?.get("farm_buildings")?.takeIf { it.isJsonObject }?.asJsonObject
    val default = farms?.entrySet()?.filter { (k, _) -> machine.startsWith(k) }?.maxByOrNull { it.key.length }
        ?.value?.takeIf { it.isJsonObject }?.asJsonObject?.get("default_module")?.takeIf { it.isJsonPrimitive }?.asString
    if (default != null && takes(default)) return default
    if (allowed == null) return null
    return data.items.values.filter { takes(it.name) }.minWithOrNull(compareBy({ it.moduleEffects?.speed ?: 0.0 }, { it.name }))?.name
}

/** The modules in a machine's own slots: [config.BeaconConfig.machineModules], one the recipe refuses replaced by the fallback. */
fun machineModules(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): List<String> {
    // A farm is full of its animals or plants, whatever the job says about modules.
    if (needsModules(data, machine)) {
        val m = farmModule(data, machine) ?: return emptyList()
        val slots = (data.entities[machine] as? AssemblingMachineEntity)?.moduleInventorySize ?: 0
        return List(slots) { m }
    }
    // Quality production: quality modules in every machine that takes them (recyclers their own), whatever else.
    config.qualityProduction?.let { qp ->
        val rc = receiver(data, machine)?.takeIf { it.modules && it.slots > 0 } ?: return emptyList()
        if (!recipe.allowedEffects.quality || "quality" !in rc.allowed) return emptyList()
        val mods = if (recipe.inAny(RECYCLING_CATEGORIES)) qp.recyclerModules else qp.modules
        return mods.take(rc.slots).filter { m -> (moduleEffects(data, m).speed ?: 0.0) == 0.0 || ("speed" in rc.allowed && recipe.allowedEffects.speed) }
    }
    val b = config.beacons ?: return emptyList()
    val rc = receiver(data, machine)?.takeIf { it.modules && it.slots > 0 } ?: return emptyList()
    return b.machineModules.take(rc.slots).mapNotNull { m ->
        when {
            fits(data, recipe, rc, m) -> m
            b.fallbackModule != null && fits(data, recipe, rc, b.fallbackModule) -> b.fallbackModule
            else -> null
        }
    }
}

/** Beacons a machine crafting [recipe] is planned with: per_machine when it takes some effect they give, else 0. */
fun plannedBeacons(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): Int {
    val b = config.beacons ?: return 0
    if (b.perMachine == 0 || (b.recipes != null && recipe.name !in b.recipes)) return 0
    val rc = receiver(data, machine)?.takeIf { it.beacons } ?: return 0
    val useful = b.modules.any { m -> effectKeys(moduleEffects(data, m)).any { it in rc.allowed && recipeAllows(recipe, it) && it == "speed" } }
    return if (useful) b.perMachine else 0
}

/** What a module of the job's module quality does more than a normal one: its bonuses times this. */
private fun moduleMultiplier(data: RootData, config: RunConfiguration): Double =
    GameConstants.moduleBonusMultiplier(data.quality[config.moduleQuality()]?.level ?: 0)

/** [key]'s effect of [modules], bonuses (more speed or productivity, less consumption) times [mult], penalties as they are. */
private fun sumEffect(data: RootData, modules: List<String>, key: String, recipe: Recipe, rc: Receiver, mult: Double = 1.0): Double =
    if (key !in rc.allowed || !recipeAllows(recipe, key)) 0.0 else modules.sumOf { m ->
        val fx = moduleEffects(data, m)
        val v = when (key) { "speed" -> fx.speed; "productivity" -> fx.productivity; else -> fx.consumption } ?: 0.0
        val bonus = if (key == "consumption") v < 0 else v > 0
        if (bonus) v * mult else v
    }

/** Crafting speed of [machine] on [recipe] with its modules and [beacons] beacons in range. */
fun moduleSpeed(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String, beacons: Int): Double {
    val base = data.craftingSpeed(machine, config.quality)
    val rc = receiver(data, machine) ?: return base
    val mult = moduleMultiplier(data, config)
    var bonus = sumEffect(data, machineModules(data, config, recipe, machine), "speed", recipe, rc, mult)
    if (beacons > 0 && rc.beacons) {
        val spec = beaconSpec(data, config) ?: return base * maxOf(0.2, 1 + bonus)
        bonus += spec.effectivity * sqrt(beacons.toDouble()) * sumEffect(data, spec.modules, "speed", recipe, rc, mult)
    }
    // A farm's -100% base speed is made up by its modules (each animal or plant +100%: Py's farm_speed).
    val baseFx = baseSpeedEffect(data, machine)
    return if (baseFx == 0.0) base * maxOf(0.2, 1 + bonus) else base * maxOf(1e-4, 1 + baseFx + bonus)
}

/** Productivity the machine's own modules add. */
fun moduleProductivity(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): Double {
    val rc = receiver(data, machine) ?: return 0.0
    return sumEffect(data, machineModules(data, config, recipe, machine), "productivity", recipe, rc, moduleMultiplier(data, config))
}

/** Fewest beacons, of at most [MachineInstance.beacons], that let [m] run its planned crafts. */
fun beaconNeed(crafts: Double, beaconCrafts: List<Double>): Int {
    if (beaconCrafts.isEmpty()) return 0
    val i = beaconCrafts.indexOfFirst { it >= crafts * (1 - 1e-9) }
    return if (i < 0) beaconCrafts.size - 1 else i
}

/** Is a [mw] x [mh] machine at (mx, my) in the supply area of a beacon at (bx, by)? Strict overlap. */
fun inBeaconRange(spec: BeaconSpec, bx: Int, by: Int, mx: Int, my: Int, mw: Int, mh: Int): Boolean =
    mx < bx + spec.size + spec.supply && mx + mw > bx - spec.supply && my < by + spec.size + spec.supply && my + mh > by - spec.supply
