import Fixtures.e
import config.BeaconConfig
import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import emit.BuildQuality
import emit.decodeBlueprint
import emit.toBlueprint
import emit.toBlueprintString
import emit.toLayout
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import config.QualityProductionConfig
import config.Tuning
import plan.buildPlan
import plan.moduleProductivity
import plan.moduleSpeed
import plan.withQualityProduction
import search.runTrial
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quality: of the buildings (machines, beacons, modules), and of items and recipes ("name@quality"). */
class QualityTest {
    private val data = TestData.data
    private fun close(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a != $b")
    private val prod4 = List(4) { "productivity-module-3" }
    private fun job(machines: String, beacons: String, modules: String) = RunConfiguration(
        targets = listOf(ConfigTarget("iron-gear-wheel", 1.0)), quality = machines,
        sources = listOf(ConfigSource("iron-plate", edge = "west")),
        beacons = BeaconConfig(perMachine = 2, machineModules = prod4, quality = beacons, moduleQuality = modules),
    )

    @Test
    fun `better modules have larger bonuses, the same penalties, and better beacons pass on more`() {
        val gear = data.recipes.getValue("iron-gear-wheel")
        val normal = job("normal", "normal", "normal")
        // As in BeaconTest: four productivity modules, speed 1.25 x (1 - 4 x 0.15), productivity 4 x 0.1.
        close(1.25 * 0.4, moduleSpeed(data, normal, gear, "assembling-machine-3", 0))
        close(0.4, moduleProductivity(data, normal, gear, "assembling-machine-3"))
        // Legendary modules: productivity x2.5, the speed penalty as it is.
        val legendaryModules = job("normal", "normal", "legendary")
        close(1.0, moduleProductivity(data, legendaryModules, gear, "assembling-machine-3"))
        close(1.25 * 0.4, moduleSpeed(data, legendaryModules, gear, "assembling-machine-3", 0))
        // Two legendary beacons of two legendary speed modules: effectivity 1.5 + 5 x 0.2 = 2.5, each module +125%.
        val all = job("normal", "legendary", "legendary")
        close(1.25 * (0.4 + 2.5 * sqrt(2.0) * 2 * 1.25), moduleSpeed(data, all, gear, "assembling-machine-3", 2))
        // Legendary machines craft 2.5 times as fast (recipes.json).
        close(3.125 * 0.4, moduleSpeed(data, job("legendary", "normal", "normal"), gear, "assembling-machine-3", 0))
    }

    private fun gears(target: String, rate: Double, recycle: Boolean) = RunConfiguration(
        targets = listOf(ConfigTarget(target, rate)), machines = listOf("assembling-machine-3"), rawResources = listOf("iron-plate"),
        sources = listOf(ConfigSource("iron-plate", edge = "west")), outputs = listOf(ConfigSource(target, edge = "east")),
        qualityProduction = QualityProductionConfig(recycle = recycle, leftovers = "south"),
    )

    @Test
    fun `quality modules spread a craft over the qualities above it`() {
        val (d, c) = withQualityProduction(data, gears("iron-gear-wheel@uncommon", 0.1, recycle = false))
        // Four quality modules 3: 4 x 2.5 % = 10 % a tier up, each further tier 10 % of that.
        val r = d.recipes.getValue("iron-gear-wheel")
        val share = r.products.associate { it.name to it.expectedAmount }
        close(0.9, share.getValue("iron-gear-wheel")); close(0.09, share.getValue("iron-gear-wheel@uncommon"))
        close(0.009, share.getValue("iron-gear-wheel@rare")); close(0.0009, share.getValue("iron-gear-wheel@epic"))
        close(0.0001, share.getValue("iron-gear-wheel@legendary"))
        // A rare craft takes rare plates and makes rare gears or better.
        val rare = d.recipes.getValue("iron-gear-wheel@rare")
        assertEquals(listOf("iron-plate@rare"), rare.ingredients.map { it.name })
        close(0.9, rare.products.single { it.name == "iron-gear-wheel@rare" }.expectedAmount)
        // The plan: 0.1 uncommon gears/s from 1.11 crafts/s of normal plates (2 a craft); the rest leaves mixed.
        val plan = buildPlan(d, c)
        close(0.1 / 0.09 * 2, plan.rawRates.getValue("iron-plate"))
        assertTrue(plan.mixed.keys.containsAll(listOf("iron-gear-wheel", "iron-gear-wheel@rare")), "${plan.mixed}")
        assertTrue("iron-gear-wheel@uncommon" !in plan.mixed)
    }

    @Test
    fun `recyclers take what is not wanted back, so rare gears need fewer plates`() {
        fun plates(recycle: Boolean): Double {
            val (d, c) = withQualityProduction(data, gears("iron-gear-wheel@rare", 0.01, recycle))
            val plan = buildPlan(d, c)
            if (recycle) assertTrue(plan.machines.any { it.machine == "recycler" }, "${plan.machines.map { it.recipe }.toSet()}")
            return plan.rawRates.getValue("iron-plate")
        }
        val without = plates(false); val with = plates(true)
        assertTrue(with < without * 0.8, "with recycling $with, without $without")
    }

    @Test
    fun `an uncommon gear block routes, its output filtered to uncommon gears`() {
        val (d, c) = withQualityProduction(data, gears("iron-gear-wheel@uncommon", 0.2, recycle = false))
        val plan = buildPlan(d, c)
        val r = (1L..6L).asSequence().map { runTrial(d, plan, c, Tuning.defaults(), 14, 14, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/quality/uncommon_gears.png"), RenderOptions(title = "uncommon gears ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        val bp = layout.toBlueprint(c.buildQuality())
        // The machines run the normal recipe with quality modules; an inserter takes only uncommon gears to the port.
        assertTrue(bp.entities.filter { it.recipe == "iron-gear-wheel" }.all { it.recipeQuality == "normal" })
        assertTrue(layout.entities.filter { it.kind == Kind.MACHINE }.all { it.modules == List(4) { "quality-module-3" } })
        assertTrue(bp.entities.any { e -> e.filters?.any { it.name == "iron-gear-wheel" && it.quality == "uncommon" } == true })
    }

    @Test
    fun `quality production works in a factory of blocks, each block's leftovers on the mixed belt`() {
        val job = gears("iron-gear-wheel@uncommon", 0.5, recycle = false).copy(
            blocks = config.BlocksConfig(maxMachines = 2, sizeBy = "machines"),
            search = config.SearchConfig(timeBudgetS = 60.0, seedsPerSize = 2, threads = 4, seed = 7))
        val (d, c) = withQualityProduction(data, job)
        val plan = buildPlan(d, c)
        assertTrue(plan.machines.size > 2)
        val out = File("build/factory/quality_uncommon_gears")
        out.deleteRecursively()
        val f = blocks.runFactory(d, c, Tuning.defaults(), plan, out)
        assertTrue(f != null && f.success, "${f?.errors?.take(5)} ${f?.notes}")
        // Every block's leftovers leave on the edge, mixed.
        assertTrue(f.outputs.any { it.item.startsWith("mixed") }, "${f.outputs.map { it.item }}")
    }

    @Test
    fun `a blueprint gives machines, beacons and modules their quality, and recipes and filters theirs`() {
        val layout = Layout(10, 6, listOf(
            e("assembling-machine-3", 0, 0, recipe = "iron-gear-wheel").copy(modules = prod4),
            PlacedEntity("beacon", Kind.BEACON, 4, 0, w = 3, h = 3, modules = listOf("speed-module-3", "speed-module-3")),
            e("assembling-machine-3", 0, 3, recipe = "iron-gear-wheel@rare"),
            e("fast-inserter", 3, 3, Dir.W, item = "iron-gear-wheel@rare"),
            PlacedEntity("fast-inserter", Kind.INSERTER, 3, 4, Dir.W, blacklist = listOf("iron-gear-wheel@epic", "iron-gear-wheel")),
        ))
        val bp = layout.toBlueprint(BuildQuality(machines = "legendary", beacons = "epic", modules = "rare"))
        val m0 = bp.entities[0]; val beacon = bp.entities[1]; val m1 = bp.entities[2]
        assertEquals("legendary", m0.quality); assertEquals("iron-gear-wheel", m0.recipe); assertEquals("normal", m0.recipeQuality)
        assertEquals("epic", beacon.quality)
        assertTrue(m0.items.toString().contains("\"quality\":\"rare\""), "${m0.items}")
        // A quality variant: the plain recipe of that quality.
        assertEquals("iron-gear-wheel", m1.recipe); assertEquals("rare", m1.recipeQuality)
        // Filters name the item and its quality ("=": only that quality); a plain name matches normal.
        val f = bp.entities[3].filters!!.single()
        assertEquals("iron-gear-wheel", f.name); assertEquals("rare", f.quality); assertEquals("=", f.comparator)
        assertEquals(listOf("epic", null), bp.entities[4].filters!!.map { it.quality })
        // Inserters, belts and the like stay normal.
        assertEquals(null, bp.entities[3].quality)
        // Round trip: variants come back as "name@quality".
        val back = decodeBlueprint(layout.toBlueprintString(BuildQuality(machines = "legendary"))).toLayout(10, 6)
        assertEquals("iron-gear-wheel@rare", back.entities[2].recipe)
        assertEquals("iron-gear-wheel@rare", back.entities[3].item)
        assertEquals(listOf("iron-gear-wheel@epic", "iron-gear-wheel"), back.entities[4].blacklist)
        assertEquals("iron-gear-wheel", back.entities[0].recipe)
    }
}
