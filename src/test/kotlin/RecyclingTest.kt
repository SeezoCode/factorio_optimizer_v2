import config.ConfigSource
import config.ConfigTarget
import config.RecyclingConfig
import config.RunConfiguration
import config.Tuning
import emit.toBlueprint
import emit.toLayout
import geom.Kind
import geom.Rect
import plan.buildPlan
import search.runTrial
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recyclers: recycling recipes only when the job asks, destroying only the items it lists. */
class RecyclingTest {
    private val data get() = TestData.data

    /** Iron from lava on Vulcanus: each foundry craft leaves 10 stone. */
    private fun lava(recycling: RecyclingConfig = RecyclingConfig()) = RunConfiguration(
        targets = listOf(ConfigTarget("iron-plate", 6.0)),
        machines = listOf("foundry"),
        rawResources = listOf("lava", "calcite"),
        sources = listOf(ConfigSource("lava", edge = "west"), ConfigSource("calcite", edge = "west")),
        outputs = listOf(ConfigSource("iron-plate", edge = "east"), ConfigSource("stone", edge = "east")),
        recycling = recycling,
    )

    @Test
    fun `without recycling nothing is recycled and the surplus leaves`() {
        val plan = buildPlan(data, lava())
        assertTrue(plan.machines.none { it.machine == "recycler" })
        assertTrue((plan.outputs["stone"] ?: 0.0) > 1.0, "${plan.outputs}")
    }

    @Test
    fun `void items are destroyed by recyclers fed their own output, and only they`() {
        // Listed: stone does not leave; recyclers destroy it a quarter at a time.
        val plan = buildPlan(data, lava(RecyclingConfig("void", listOf("stone"))))
        val recyclers = plan.machines.filter { it.machine == "recycler" }
        assertTrue(recyclers.isNotEmpty())
        assertTrue(recyclers.all { it.recipe == "stone-recycling" }, "${recyclers.map { it.recipe }.toSet()}")
        assertEquals(0.0, plan.outputs["stone"] ?: 0.0, 1e-9)
        // Each craft takes one and gives back a quarter.
        recyclers.forEach { m -> assertEquals(0.25 * m.demand.getValue("stone"), m.products.getValue("stone"), 1e-9) }
        // Void mode lists the items: with none listed, nothing changes.
        val none = buildPlan(data, lava(RecyclingConfig("void", emptyList())))
        assertTrue(none.machines.none { it.machine == "recycler" })
    }

    @Test
    fun `full recycling takes scrap apart on Fulgora`() {
        val job = RunConfiguration(
            targets = listOf(ConfigTarget("iron-gear-wheel", 1.0)),
            rawResources = emptyList(),
            sources = listOf(ConfigSource("scrap", edge = "west")),
            recycling = RecyclingConfig("full"),
        )
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.any { it.recipe == "scrap-recycling" }, "${plan.machines.map { it.recipe }.toSet()}")
        // A fifth of scrap comes back as gears: at most 5 scrap/s for 1 gear/s (less where other products, recycled
        // and smelted, make more gears), and the rest leaves (nothing is listed to destroy).
        assertTrue(plan.rawRates.getValue("scrap") <= 5.0 + 1e-6, "${plan.rawRates}")
        // Left over: solid fuel leaves as an item. (The planner also turns holmium ore and stone into holmium solution
        // and ice into water, since a fluid leaving costs it less than items: it takes those costs literally.)
        assertTrue("solid-fuel" in plan.outputs, "${plan.outputs}")
    }

    @Test
    fun `scrap leftovers leave together on one mixed belt`() {
        val leftovers = listOf("solid-fuel", "concrete", "ice", "steel-plate", "battery", "stone", "advanced-circuit", "copper-cable",
            "processing-unit", "low-density-structure", "holmium-ore", "iron-plate", "copper-plate", "iron-gear-wheel")
        val job = RunConfiguration(
            targets = listOf(ConfigTarget("iron-gear-wheel", 0.5)),
            machines = listOf("recycler"),
            rawResources = emptyList(),
            sources = listOf(ConfigSource("scrap", edge = "west")),
            outputs = listOf(ConfigSource("iron-gear-wheel", edge = "east")),
            recycling = RecyclingConfig("full"),
            mixedOutputs = listOf(config.MixedOutput(leftovers, edge = "south")),
        )
        val plan = buildPlan(data, job)
        // Gears are the target: they keep their own belt; the rest share one.
        assertTrue("iron-gear-wheel" !in plan.mixed, "${plan.mixed}")
        assertTrue(plan.mixed.keys.containsAll(listOf("solid-fuel", "holmium-ore", "stone")), "${plan.mixed}")
        assertEquals(1, plan.ports.count { it.item == "mixed-1" && it.output })
        assertTrue(plan.ports.none { it.output && it.item in plan.mixed })
        val r = (1L..6L).asSequence().map { runTrial(data, plan, job, Tuning.defaults(), 16, 16, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/recycling/scrap_mixed.png"), RenderOptions(title = "scrap mixed ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        // A scrap recycler: a gear inserter (whitelist) and one taking everything else onto the mixed belt (a blacklist
        // of gears and of whatever else the plan takes further apart).
        val ins = layout.entities.filter { it.kind == Kind.INSERTER }
        assertTrue(ins.any { it.item == "iron-gear-wheel" })
        val mixedOut = ins.filter { it.blacklist?.contains("iron-gear-wheel") == true }
        assertTrue(mixedOut.isNotEmpty(), "${ins.map { it.item to it.blacklist }}")
        // The blacklist survives a blueprint round trip.
        val back = emit.decodeBlueprint(emit.encodeBlueprint(layout.toBlueprint()))
        assertTrue(back.toLayout(layout.w, layout.h).entities.any { it.blacklist == mixedOut[0].blacklist })
    }

    @Test
    fun `a voiding block routes and validates, recycler inputs filtered and nothing before their output end`() {
        val config = lava(RecyclingConfig("void", listOf("stone")))
        val plan = buildPlan(data, config)
        val r = (1L..6L).asSequence().map { runTrial(data, plan, config, Tuning.defaults(), 36, 36, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/recycling/stone_void.png"), RenderOptions(title = "stone void ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        val recyclers = layout.entities.filter { it.kind == Kind.MACHINE && it.name == "recycler" }
        assertTrue(recyclers.isNotEmpty())
        val byTile = layout.tileIndex()
        recyclers.forEach { m ->
            // Its input inserters are filtered to uranium-238.
            val feeders = layout.entities.filter { it.kind == Kind.INSERTER && geom.Rect(m.x, m.y, m.w, m.h).contains(it.x + it.dir.opposite.dx * GameConstants.inserter(it.name).reach, it.y + it.dir.opposite.dy * GameConstants.inserter(it.name).reach) }
            assertTrue(feeders.isNotEmpty() && feeders.all { it.item == "stone" }, "$feeders")
            GameConstants.recyclerOutputTiles(Rect(m.x, m.y, m.w, m.h), m.dir).forEach { t ->
                byTile[t]?.let { assertTrue(!layout.entities[it].kind.beltFamily, "a belt in front of a recycler at $t") }
            }
        }
    }
}

private typealias GameConstants = data.GameConstants
