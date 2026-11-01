package plan

import config.RunConfiguration
import data.AssemblingMachineEntity
import data.BeaconEntity
import data.FurnaceEntity
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
    return BeaconSpec(b.beacon, e.width, e.supplyAreaDistance["normal"] ?: 3, e.distributionEffectivity, e.moduleInventorySize, b.modules)
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

/** The modules in a machine's own slots: [config.BeaconConfig.machineModules], one the recipe refuses replaced by the fallback. */
fun machineModules(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): List<String> {
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

private fun sumEffect(data: RootData, modules: List<String>, key: String, recipe: Recipe, rc: Receiver): Double =
    if (key !in rc.allowed || !recipeAllows(recipe, key)) 0.0 else modules.sumOf { m ->
        val fx = moduleEffects(data, m)
        when (key) { "speed" -> fx.speed; "productivity" -> fx.productivity; else -> fx.consumption } ?: 0.0
    }

/** Crafting speed of [machine] on [recipe] with its modules and [beacons] beacons in range. */
fun moduleSpeed(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String, beacons: Int): Double {
    val base = data.craftingSpeed(machine, config.quality)
    val rc = receiver(data, machine) ?: return base
    var bonus = sumEffect(data, machineModules(data, config, recipe, machine), "speed", recipe, rc)
    if (beacons > 0 && rc.beacons) {
        val spec = beaconSpec(data, config) ?: return base * maxOf(0.2, 1 + bonus)
        bonus += spec.effectivity * sqrt(beacons.toDouble()) * sumEffect(data, spec.modules, "speed", recipe, rc)
    }
    return base * maxOf(0.2, 1 + bonus)
}

/** Productivity the machine's own modules add. */
fun moduleProductivity(data: RootData, config: RunConfiguration, recipe: Recipe, machine: String): Double {
    val rc = receiver(data, machine) ?: return 0.0
    return sumEffect(data, machineModules(data, config, recipe, machine), "productivity", recipe, rc)
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
