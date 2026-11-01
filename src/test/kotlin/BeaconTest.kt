import config.BeaconConfig
import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.Tuning
import emit.decodeBlueprint
import emit.toBlueprintString
import emit.toLayout
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import plan.beaconNeed
import plan.beaconSpec
import plan.buildPlan
import plan.inBeaconRange
import plan.machineModules
import plan.moduleProductivity
import plan.moduleSpeed
import power.placeBeacons
import search.runTrial
import validate.ErrorType
import validate.MachineSpec
import validate.ValidationSpec
import validate.validate
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeaconTest {
    private val prod4 = List(4) { "productivity-module-3" }
    private val job = RunConfiguration(
        targets = listOf(ConfigTarget("automation-science-pack", 1.0), ConfigTarget("logistic-science-pack", 1.0)),
        sources = listOf(ConfigSource("iron-plate", edge = "west"), ConfigSource("copper-plate", edge = "west")),
        beacons = BeaconConfig(perMachine = 2, machineModules = prod4, fallbackModule = "speed-module-3"),
    )
    private val data = TestData.data

    private fun close(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a != $b")   // effects are floats in the data

    @Test
    fun `module and beacon effects follow the 2_0 rules`() {
        val gear = data.recipes.getValue("iron-gear-wheel")
        val belt = data.recipes.getValue("transport-belt")
        // Four productivity modules: speed 1.25 x (1 - 4 x 0.15); one beacon of two speed modules: + 1.5 x 1.0;
        // two: + 1.5 x sqrt 2 x 1.0.
        close(1.25 * 0.4, moduleSpeed(data, job, gear, "assembling-machine-3", 0))
        close(1.25 * 1.9, moduleSpeed(data, job, gear, "assembling-machine-3", 1))
        close(1.25 * (0.4 + 1.5 * sqrt(2.0)), moduleSpeed(data, job, gear, "assembling-machine-3", 2))
        close(0.4, moduleProductivity(data, job, gear, "assembling-machine-3"))
        // Belts take no productivity: the fallback speed modules go in instead.
        assertEquals(List(4) { "speed-module-3" }, machineModules(data, job, belt, "assembling-machine-3"))
        close(0.0, moduleProductivity(data, job, belt, "assembling-machine-3"))
        close(1.25 * 3.0, moduleSpeed(data, job, belt, "assembling-machine-3", 0))
        // Stone furnaces burn fuel: no modules.
        assertEquals(emptyList(), machineModules(data, job, data.recipes.getValue("iron-plate"), "stone-furnace"))
    }

    @Test
    fun `the plan uses fewer machines and knows how many beacons each needs`() {
        val plain = buildPlan(data, job.copy(beacons = null))
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.size < plain.machines.size, "${plan.machines.size} vs ${plain.machines.size}")
        // Productivity: less iron for the same science.
        assertTrue(plan.rawRates.getValue("iron-plate") < plain.rawRates.getValue("iron-plate") * 0.7)
        plan.machines.forEach { m ->
            assertEquals(2, m.beacons)
            assertEquals(3, m.beaconCrafts.size)
            assertTrue(m.crafts <= m.beaconCrafts[2] + 1e-9)
            assertTrue(beaconNeed(m.crafts, m.beaconCrafts) in 0..2)
        }
    }

    @Test
    fun `placing beacons covers what each machine needs and drops the rest`() {
        val spec = beaconSpec(data, job)!!
        val m1 = PlacedEntity("assembling-machine-3", Kind.MACHINE, 2, 2, recipe = "iron-gear-wheel")
        val m2 = PlacedEntity("assembling-machine-3", Kind.MACHINE, 12, 2, recipe = "iron-gear-wheel")
        val needs = mapOf(m1.tile to 2, m2.tile to 1)
        // A lattice spot far from both is dropped; the shortfall is covered on free tiles.
        val r = placeBeacons(listOf(m1, m2), spec, needs, listOf(Tile(2, 14)), 20, 20, emptySet())
        assertTrue(r.ok, "short: ${r.short}")
        assertTrue(r.beacons.none { it.x == 2 && it.y == 14 })
        listOf(m1 to 2, m2 to 1).forEach { (m, n) ->
            assertTrue(r.beacons.count { inBeaconRange(spec, it.x, it.y, m.x, m.y, m.w, m.h) } >= n)
        }
        assertTrue(r.beacons.size <= 3, "${r.beacons}")
        assertTrue(r.beacons.all { it.modules == spec.modules })
    }

    @Test
    fun `the validator counts beacons in range`() {
        val spec = beaconSpec(data, job)!!
        val m = PlacedEntity("assembling-machine-3", Kind.MACHINE, 2, 2, recipe = "iron-gear-wheel")
        val near = PlacedEntity("beacon", Kind.BEACON, 7, 2, w = 3, h = 3)   // two tiles between: in range
        val far = PlacedEntity("beacon", Kind.BEACON, 8, 6, w = 3, h = 3)    // three tiles off: out of range
        val vs = ValidationSpec(machines = mapOf(m.tile to MachineSpec("iron-gear-wheel", setOf("iron-plate"), beaconNeed = 2)),
            requirePower = false, checkRates = false, beacon = spec)
        val errors = validate(Layout(14, 12, listOf(m, near, far)), vs).filter { it.type == ErrorType.BEACON_SHORT }
        assertEquals(1, errors.size, "$errors")
        assertTrue("has 1 beacon" in errors.single().message)
    }

    @Test
    fun `a trial places beacons and module requests that survive the blueprint`() {
        val plan = buildPlan(data, job)
        val r = (1L..8L).asSequence().map { runTrial(data, plan, job, Tuning(), 22, 22, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed made a valid layout")
        val layout = r.layout!!
        viz.renderLayout(layout, java.io.File("build/renders/beacons.png"), viz.RenderOptions(title = "beacons"))
        val beacons = layout.entities.filter { it.kind == Kind.BEACON }
        assertTrue(plan.machines.any { beaconNeed(it.crafts, it.beaconCrafts) > 0 } == beacons.isNotEmpty())
        assertTrue(layout.entities.filter { it.kind == Kind.MACHINE }.all { it.modules?.size == 4 })
        val back = decodeBlueprint(layout.toBlueprintString()).toLayout(layout.w, layout.h)
        assertEquals(layout.entities.map { it.modules }, back.entities.map { it.modules })
        assertEquals(beacons.size, back.entities.count { it.kind == Kind.BEACON })
    }
}
