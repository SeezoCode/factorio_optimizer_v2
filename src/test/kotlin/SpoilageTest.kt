import Fixtures.e
import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.SpoilageConfig
import config.Tuning
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import geom.dropTile
import geom.pickupTile
import plan.buildPlan
import search.runTrial
import validate.ErrorType
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

/** Gleba spoilage: drains at belt ends, machines that let their spoilage out. */
class SpoilageTest {
    private val data get() = TestData.data
    private val base = ValidationSpec(recipeInfo = recipeInfoFrom(TestData.data), requirePower = false, checkRates = false, spoilage = true)

    @Test
    fun `a belt of something that spoils needs a drain at its end`() {
        val belts = (0..4).map { e("transport-belt", it, 0, Dir.E).copy(net = 1) }
        val spec = base.copy(sources = listOf(SourceSpec(Tile(0, 0), "yumako-mash", 1.0)))
        val bare = Layout(8, 3, belts)
        assertTrue(validate(bare, spec).any { it.type == ErrorType.SPOILAGE_STUCK && it.tile == Tile(4, 0) }, "${validate(bare, spec)}")
        // Without spoilage modelled, nothing to say.
        assertTrue(validate(bare, spec.copy(spoilage = false)).none { it.type == ErrorType.SPOILAGE_STUCK })
        // A filter inserter at the end, into a chest.
        val drained = Layout(8, 3, belts + e("fast-inserter", 4, 1, Dir.N, item = "spoilage") + e("steel-chest", 4, 2))
        assertEquals(emptyList(), validate(drained, spec))
        // Filtered to something else, it does not count.
        val wrong = Layout(8, 3, belts + e("fast-inserter", 4, 1, Dir.N, item = "yumako-mash") + e("steel-chest", 4, 2))
        assertTrue(validate(wrong, spec).any { it.type == ErrorType.SPOILAGE_STUCK })
    }

    @Test
    fun `a machine holding something that spoils needs an inserter that takes the spoilage out`() {
        // Yumako processing makes mash and seeds: an inserter filtered to mash alone leaves spoilage in.
        fun layout(out: PlacedEntity) = Layout(9, 5, listOf(
            e("biochamber", 0, 0, recipe = "yumako-processing"),
            out, *(0..4).map { e("transport-belt", 4, it, Dir.S) }.toTypedArray(),
            e("fast-inserter", 2, 4, Dir.S, item = "spoilage"), e("steel-chest", 2, 5).copy(y = 4, x = 1),
        ))
        val whitelist = e("fast-inserter", 3, 1, Dir.W, item = "yumako-mash")
        val errs = validate(layout(whitelist), base)
        assertTrue(errs.any { it.type == ErrorType.SPOILAGE_CLOGS }, "$errs")
        val blacklist = PlacedEntity("fast-inserter", Kind.INSERTER, 3, 1, Dir.W, blacklist = listOf("yumako-seed"))
        assertTrue(validate(layout(blacklist), base).none { it.type == ErrorType.SPOILAGE_CLOGS }, "${validate(layout(blacklist), base)}")
    }

    @Test
    fun `a Gleba block drains its belts and lets its biochambers' spoilage out`() {
        val job = RunConfiguration(
            targets = listOf(ConfigTarget("nutrients", 2.0)),
            machines = listOf("biochamber"),
            sources = listOf(ConfigSource("yumako", edge = "west")),
            outputs = listOf(ConfigSource("nutrients", edge = "east"), ConfigSource("yumako-seed", edge = "east")),
            recipeOverrides = mapOf("nutrients" to "nutrients-from-yumako-mash"),
            spoilage = SpoilageConfig("drain"),
        )
        val plan = buildPlan(data, job)
        assertTrue(plan.machines.any { it.recipe == "yumako-processing" } && plan.machines.any { it.recipe == "nutrients-from-yumako-mash" })
        val tries = ArrayList<search.TrialResult>()
        val r = listOf(16, 20, 24).asSequence().flatMap { s -> (1L..4L).asSequence().map { runTrial(data, plan, job, Tuning.defaults(), s, s, it).also { t -> tries += t } } }
            .firstOrNull { it.success }
        assertTrue(r != null, "no trial: ${tries.map { t -> "${t.stage} ${t.notes.filter { "spoil" in it }} ${t.errors.take(2)}" }}")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/spoilage/nutrients.png"), RenderOptions(title = "nutrients, spoilage drained ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        val byTile = layout.tileIndex()
        fun at(t: Tile) = byTile[t]?.let { layout.entities[it] }
        val drains = layout.entities.filter { it.kind == Kind.INSERTER && at(it.pickupTile)?.kind?.beltFamily == true && at(it.dropTile)?.kind == Kind.CHEST }
        assertTrue(drains.isNotEmpty(), "no drain")
        // The processing biochambers (two products) let their spoilage out through a blacklist.
        val procs = layout.entities.filter { it.kind == Kind.MACHINE && it.recipe == "yumako-processing" }
        procs.forEach { m ->
            val outs = layout.entities.filter { it.kind == Kind.INSERTER && at(it.pickupTile) == m }
            assertTrue(outs.isNotEmpty() && outs.all { it.item == null }, "${outs.map { it.item to it.blacklist }}")
        }
    }
}
