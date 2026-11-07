import Fixtures.e
import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.Tuning
import geom.Dir
import geom.Kind
import geom.Layout
import geom.Tile
import geom.dropTile
import plan.buildPlan
import search.runTrial
import validate.ErrorType
import validate.OutputSpec
import validate.SourceSpec
import validate.ValidationSpec
import validate.recipeInfoFrom
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Space Age belt stacking: a stack takes one slot of a lane, an item dropped by any other inserter a slot of its own. */
class StackingTest {
    private val base = ValidationSpec(recipeInfo = recipeInfoFrom(TestData.data), requirePower = false, checkRates = true)

    @Test
    fun `a yellow lane takes four times its items in stacks`() {
        val belts = (0..5).map { e("transport-belt", it, 0, Dir.E).copy(net = 1) }
        val layout = Layout(6, 1, belts)
        // 20/s, 10/s a lane: over a yellow lane's 7.5/s, within 30/s in stacks of four.
        val spec = base.copy(sources = listOf(SourceSpec(Tile(0, 0), "iron-plate", 20.0)), outputs = listOf(OutputSpec(Tile(5, 0), "iron-plate", 20.0)))
        assertTrue(validate(layout, spec).any { it.type == ErrorType.BELT_OVER_CAPACITY })
        assertEquals(emptyList(), validate(layout, spec.copy(beltStack = 4)))
    }

    @Test
    fun `only stack inserters stack what they drop`() {
        fun belts() = (0..6).map { e("transport-belt", it, 2, Dir.E).copy(net = 1) }
        fun feed(x: Int, ins: String) = listOf(e("infinity-chest", x, 0, item = "iron-plate"), e(ins, x, 1, Dir.N))
        // Four fast inserters drop 9.2/s on one lane, one item a slot: over 7.5/s, stacking or not.
        val fast = Layout(8, 5, belts() + (1..4).flatMap { feed(it, "fast-inserter") })
        val spec = base.copy(beltStack = 4)
        val errs = validate(fast, spec)
        renderLayout(fast, File("build/renders/stacking/fast.png"), RenderOptions(errors = errs, title = "fast inserters, stacking"))
        assertTrue(errs.any { it.type == ErrorType.BELT_OVER_CAPACITY }, "$errs")
        // Four stack inserters drop twice that in stacks of four: a quarter of the slots.
        val stacked = Layout(8, 5, belts() + (1..4).flatMap { feed(it, "stack-inserter") })
        assertTrue(validate(stacked, spec).none { it.type == ErrorType.BELT_OVER_CAPACITY }, "${validate(stacked, spec)}")
        assertTrue(validate(stacked, base).any { it.type == ErrorType.BELT_OVER_CAPACITY })
    }

    @Test
    fun `with stacking a block drops onto its belts through stack inserters only`() {
        val config = RunConfiguration(
            targets = listOf(ConfigTarget("iron-gear-wheel", 10.0)),
            sources = listOf(ConfigSource("iron-plate", edge = "west")),
            outputs = listOf(ConfigSource("iron-gear-wheel", edge = "east")),
            beltTier = "basic",
            beltStackSize = 4,
        )
        val plan = buildPlan(TestData.data, config)
        // 20 plates/s fit one yellow belt (15/s) only in stacks.
        assertTrue(plan.nets.all { it.beltCount == 1 }, "${plan.nets}")
        assertTrue(plan.nets.any { it.item == "iron-plate" && it.flow > 15.0 })
        val r = (1L..6L).asSequence().map { runTrial(TestData.data, plan, config, Tuning.defaults(), 18, 18, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/stacking/gears.png"), RenderOptions(title = "gears, belt stacking ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        val byTile = layout.tileIndex()
        val ontoBelts = layout.entities.filter { it.kind == Kind.INSERTER && byTile[it.dropTile]?.let { i -> layout.entities[i].kind.beltFamily } == true }
        assertTrue(ontoBelts.isNotEmpty())
        assertTrue(ontoBelts.all { it.name == "stack-inserter" }, "${ontoBelts.map { it.name }}")
    }
}
