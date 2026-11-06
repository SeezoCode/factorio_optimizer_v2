import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.Tuning
import emit.toBlueprint
import emit.toLayout
import geom.Kind
import geom.pickupTile
import plan.ProductionPlan
import plan.buildPlan
import search.TrialResult
import search.runTrial
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Space Age machines: their productivity, fuel and fluid connections, and a platform-style job. */
class SpaceAgeTest {
    private val data get() = TestData.data

    private fun total(plan: ProductionPlan, item: String) = plan.machines.sumOf { it.products[item] ?: 0.0 }

    @Test
    fun `a foundry casts with its +50 percent productivity`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("iron-plate", 6.0)), machines = listOf("foundry"),
            sources = listOf(ConfigSource("calcite", edge = "north")))
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.all { it.machine == "foundry" })
        assertEquals(6.0, total(plan, "iron-plate"), 1e-6)
        // 20 molten iron make 2 * 1.5 plates; 50 ore make 500 * 1.5 molten iron.
        assertEquals(6.0 * 20 / 3 * 50 / 750, plan.rawRates.getValue("iron-ore"), 1e-6)
        assertEquals(plan.rawRates.getValue("iron-ore") / 50, plan.rawRates.getValue("calcite"), 1e-6)
        assertTrue(plan.nets.any { it.item == "molten-iron" && it.fluid })
    }

    @Test
    fun `research productivity adds to the machine's own, up to the recipe cap`() {
        val job = RunConfiguration.parse("""{"targets": [{"name": "iron-plate", "amount": 6.0}], "machines": ["foundry"],
            "sources": [{"name": "calcite", "edge": "north"}],
            "research": {"productivity": {"casting-iron": 0.1, "iron-ore-melting": 5.0}}}""")
        val plan = buildPlan(data, job)
        // Casting at 1.6 (2 -> 3.2 plates); melting capped at +300% (500 -> 2000 molten iron).
        assertEquals(6.0 * 20 / 3.2 * 50 / 2000, plan.rawRates.getValue("iron-ore"), 1e-6)
    }

    @Test
    fun `a biochamber burns nutrients`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("nutrients", 1.0)), machines = listOf("biochamber"),
            sources = listOf(ConfigSource("spoilage", edge = "west")), recipeOverrides = mapOf("nutrients" to "nutrients-from-spoilage"))
        val plan = buildPlan(data, job)
        val m = plan.machines.single()
        assertEquals("biochamber", m.machine)
        // 1.5 nutrients a craft (+50%), 0.25 burnt (500 kW for 1 s at 2 MJ each): 1 / 1.25 crafts/s.
        assertEquals(0.8 * 0.25, m.demand.getValue("nutrients"), 1e-6)
        assertEquals(8.0, plan.rawRates.getValue("spoilage"), 1e-6)
        assertEquals(1.0, plan.outputs.getValue("nutrients"), 1e-6)
    }

    /** A space platform: only asteroid chunks come in, crushers make ore, furnaces and assemblers the rest. */
    private val platform = RunConfiguration(targets = listOf(ConfigTarget("iron-gear-wheel", 2.0)),
        machines = listOf("crusher", "electric-furnace", "assembling-machine-3"), rawResources = emptyList(),
        sources = listOf(ConfigSource("metallic-asteroid-chunk", edge = "west")),
        forbiddenRecipes = listOf("advanced-metallic-asteroid-crushing", "metallic-asteroid-reprocessing"))

    @Test
    fun `a platform job takes only its listed sources`() {
        val plan = buildPlan(data, platform)
        assertEquals(setOf("metallic-asteroid-chunk"), plan.rawRates.keys)
        // 4 ore/s from crushing at 20 ore a craft; a craft keeps 0.3 of its chunk (0.2 before Factorio 2.1).
        assertEquals(4.0 / 20 * 0.7,plan.rawRates.getValue("metallic-asteroid-chunk"), 1e-6)
        assertTrue(plan.machines.any { it.machine == "crusher" })
    }

    private fun anySeed(name: String, job: RunConfiguration, size: Int): TrialResult {
        val plan = buildPlan(data, job)
        var last: TrialResult? = null
        for (seed in 1L..8L) {
            val r = runTrial(data, plan, job, Tuning(), size, size, seed)
            println("$name seed $seed: ${r.stage} ${r.errors.take(3)} ${r.notes.take(2)}")
            r.layout?.let { renderLayout(it, File("build/renders/trial/sa_${name}_$seed.png"), RenderOptions(errors = r.errors, title = "$name seed $seed ${r.stage}")) }
            if (r.success) return r
            last = r
        }
        return last!!
    }

    @Test
    fun `foundries cast gears from a molten iron pipe`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("iron-gear-wheel", 4.0)), machines = listOf("foundry"),
            sources = listOf(ConfigSource("molten-iron", edge = "west")))
        val r = anySeed("foundry", job, 16)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val layout = r.layout!!
        assertTrue(layout.entities.any { it.kind == Kind.MACHINE && it.name == "foundry" })
        assertTrue(layout.entities.any { (it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG) && it.fluid == "molten-iron" })
    }

    @Test
    fun `electromagnetic plants (4x4) make circuits and their cable`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("electronic-circuit", 4.0)), machines = listOf("electromagnetic-plant"),
            sources = listOf(ConfigSource("iron-plate", edge = "west"), ConfigSource("copper-plate", edge = "west")))
        val r = anySeed("em", job, 16)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val plants = r.layout!!.entities.filter { it.kind == Kind.MACHINE }
        assertTrue(plants.all { it.name == "electromagnetic-plant" && it.w == 4 && it.h == 4 })
        // Even sizes sit on whole-tile centres and come back where they were.
        val back = r.layout!!.toBlueprint().toLayout(r.layout!!.w, r.layout!!.h)
        assertEquals(plants.map { it.tile }.toSet(), back.entities.filter { it.kind == Kind.MACHINE }.map { it.tile }.toSet())
    }

    @Test
    fun `a crusher's two products leave through filtered inserters`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("iron-ore", 10.0)), machines = listOf("crusher"), rawResources = emptyList(),
            sources = listOf(ConfigSource("metallic-asteroid-chunk", edge = "west")),
            forbiddenRecipes = listOf("advanced-metallic-asteroid-crushing", "metallic-asteroid-reprocessing"))
        val r = anySeed("crusher", job, 12)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val layout = r.layout!!
        val crusher = layout.entities.single { it.kind == Kind.MACHINE }
        assertEquals(2 to 3, crusher.w to crusher.h)
        val out = layout.entities.filter { it.kind == Kind.INSERTER && it.pickupTile in crusher.rect.tiles() }
        assertEquals(setOf("iron-ore", "metallic-asteroid-chunk"), out.map { it.item }.toSet())
        // The filters survive the blueprint.
        val back = layout.toBlueprint().toLayout(layout.w, layout.h)
        assertEquals(out.map { it.item }.sortedBy { it }, back.entities.filter { it.kind == Kind.INSERTER && it.pickupTile in crusher.rect.tiles() }.map { it.item }.sortedBy { it })
    }

    @Test
    fun `direct insertion from a machine with several products counts each product's own output`() {
        // Found by JobFuzzTest: the advanced crusher's copper and chunks counted as spare iron ore, so direct
        // insertion promised its foundry more ore than it makes and the second crusher's ore was never routed.
        val job = RunConfiguration(targets = listOf(ConfigTarget("iron-plate", 0.5), ConfigTarget("electronic-circuit", 0.5)),
            machines = listOf("crusher", "assembling-machine-3", "electric-furnace", "chemical-plant", "foundry"), rawResources = emptyList(),
            sources = listOf(ConfigSource("metallic-asteroid-chunk", edge = "west"), ConfigSource("oxide-asteroid-chunk", edge = "north")),
            forbiddenRecipes = listOf("metallic-asteroid-reprocessing", "oxide-asteroid-reprocessing"))
        val plan = buildPlan(data, job)
        (1L..3L).forEach { seed ->
            val r = runTrial(data, plan, job, Tuning(), 24, 24, seed)
            assertTrue(r.stage != "validate", "seed $seed: ${r.errors.take(3)}")
        }
    }

    @Test
    fun `a fast machine is split when its inserters would not fit around it`() {
        // Found by JobFuzzTest: one electromagnetic plant made all 4 poles/s and needed 17 inserters.
        val job = RunConfiguration(targets = listOf(ConfigTarget("medium-electric-pole", 4.0)), machines = listOf("electromagnetic-plant"),
            sources = listOf(ConfigSource("iron-stick", edge = "west"), ConfigSource("steel-plate", edge = "west"), ConfigSource("copper-plate", edge = "west")))
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.size > 1)
        // A 4 x 4 machine has 16 tiles around it; at most three quarters hold belt inserters.
        plan.machines.forEach { m -> assertTrue(plan.terminals.filter { it.machine == m.id }.sumOf { it.k } <= 12, "machine ${m.id}") }
    }

    @Test
    fun `a target that is also a raw resource is made, not passed through`() {
        val job = RunConfiguration(targets = listOf(ConfigTarget("sulfuric-acid", 10.0)),
            rawResources = listOf("sulfuric-acid", "water", "iron-ore", "sulfur"))
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.any { it.recipe == "sulfuric-acid" })
        assertTrue("sulfuric-acid" !in plan.rawRates, "supplied: ${plan.rawRates}")
    }

    @Test
    fun `the platform job builds`() {
        val r = anySeed("platform", platform, 22)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
    }
}
