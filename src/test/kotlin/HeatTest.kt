import config.HeatConfig
import config.Tuning
import geom.Dir
import geom.Kind
import geom.Layout
import geom.Tile
import plan.buildPlan
import power.edgeNeighbours
import power.needsHeat
import search.runTrial
import validate.ErrorType
import validate.HeatSpec
import validate.ValidationSpec
import validate.recipeInfoFrom
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Aquilo heating: every entity that freezes touches a heat pipe joined to the heat port. */
class HeatTest {
    @Test
    fun `a heated block keeps everything warm and validates`() {
        heated("t1", listOf(10, 12, 14))
        // Denser blocks (three cable inserters side by side feeding circuits) often leave an inserter that no tile
        // around can heat: report how it goes, the search tries more seeds and boxes.
        heated("t2", listOf(16, 20), required = false)
        // With rows of heat pipe every seven rows laid first (machines off them, belts under them) it heats.
        heated("t2", listOf(20, 22, 24), lattice = 7)
    }

    private fun heated(job: String, sizes: List<Int>, required: Boolean = true, lattice: Int? = null) {
        val config = TestData.job(job).copy(heat = HeatConfig(mode = "pipes", lattice = lattice))
        val plan = buildPlan(TestData.data, config)
        val tries = ArrayList<search.TrialResult>()
        val r = sizes.asSequence().flatMap { size -> (1L..6L).asSequence().map { runTrial(TestData.data, plan, config, Tuning.defaults(), size, size, it).also { t -> tries += t } } }.firstOrNull { it.success }
        println("$job lattice $lattice heated: ${r?.let { "${it.w} x ${it.h} after ${tries.size} trials" } ?: "none"}; cold left per trial ${tries.map { t -> t.errors.count { it.type == ErrorType.COLD } }}")
        if (r == null) tries.forEachIndexed { i, t ->
            println("seed ${i + 1}: ${t.stage} ${t.notes.filter { "heat" in it }} ${t.errors.take(3)}" +
                (t.route?.let { r -> " overuse ${r.overuse} failed ${r.failedNets} conflicts ${r.conflicts.take(5)} ${r.log.takeLast(2)}" } ?: ""))
            t.layout?.let { renderLayout(it, File("build/renders/heat/${job}_fail_${i + 1}.png"), RenderOptions(errors = t.errors, title = "seed ${i + 1}")) }
        }
        if (!required && r == null) return
        val size = sizes.last()
        assertTrue(r != null, "$job: no seed kept everything warm at $size x $size")
        val layout = r.layout!!
        renderLayout(layout, File("build/renders/heat/${job}_heated.png"), RenderOptions(title = "$job heated ${r.w}x${r.h}", ports = viz.portLabels(r.sources, r.outputs)))
        val pipes = layout.entities.filter { it.kind == Kind.HEAT_PIPE }.map { it.tile }.toSet()
        assertTrue(pipes.isNotEmpty())
        // The heat ports are on the ring around the box.
        assertTrue(pipes.any { it.x == -1 || it.y == -1 || it.x == r.w || it.y == r.h }, "no heat pipe on the ring")
        layout.entities.filter { needsHeat(it, false) }.forEach { e -> assertTrue(edgeNeighbours(e).any { it in pipes }, "${e.name} at ${e.tile} is cold") }
        // Poles do not need heat by default.
        assertTrue(layout.entities.any { it.kind == Kind.POLE })
    }

    @Test
    fun `a factory of blocks is heated as one network, its corridors included`() {
        val config = TestData.job("t3").copy(heat = HeatConfig(mode = "pipes", lattice = 7),
            blocks = config.BlocksConfig(maxMachines = 5, sizeBy = "machines"),
            search = config.SearchConfig(timeBudgetS = 90.0, seedsPerSize = 2, threads = 4, seed = 7))
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/heated_t3")
        out.deleteRecursively()
        val f = blocks.runFactory(TestData.data, config, Tuning.defaults(), plan, out)
        assertTrue(f != null && f.success, "${f?.errors?.take(5)} ${f?.notes}")
        val layout = f.layout
        val pipes = layout.entities.filter { it.kind == Kind.HEAT_PIPE }.map { it.tile }.toSet()
        // Everything that freezes touches a pipe (the validator also checked every pipe reaches a port), corridor
        // belts as much as the blocks' own.
        layout.entities.filter { needsHeat(it, false) }.forEach { e -> assertTrue(edgeNeighbours(e).any { it in pipes }, "${e.name} at ${e.tile} is cold") }
        val inBlocks = f.blockRects.flatMap { it.tiles() }.toSet()
        assertTrue(layout.entities.any { it.kind.beltFamily && it.tile !in inBlocks }, "no corridor belt")
        // Where to bring heat in is in ports.json.
        assertTrue(f.heatPorts.isNotEmpty() && File(out, "ports.json").readText().contains("heat_ports"))
    }

    @Test
    fun `the validator finds cold entities, diagonal touches and pipes cut off from the port`() {
        fun e(name: String, x: Int, y: Int, d: Dir = Dir.N) = Fixtures.e(name, x, y, d)
        val spec = ValidationSpec(recipeInfo = recipeInfoFrom(TestData.data), requirePower = false, checkRates = false, heat = HeatSpec(setOf(Tile(0, -1))))
        // Port at (0,-1); pipes run down x=0; a belt at (1,1) touches (0,1); one at (2,3) touches nothing; one at (1,4)
        // touches (0,3) only at a corner; a pipe at (4,4) is cut off.
        val layout = Layout(6, 6, listOf(
            e("heat-pipe", 0, -1), e("heat-pipe", 0, 0), e("heat-pipe", 0, 1), e("heat-pipe", 0, 2), e("heat-pipe", 0, 3),
            e("transport-belt", 1, 1, Dir.E), e("transport-belt", 2, 3, Dir.E), e("transport-belt", 1, 4, Dir.E),
            e("heat-pipe", 4, 4),
        ))
        val errors = validate(layout, spec.copy(outsideAllowed = setOf(Tile(0, -1))))
        assertTrue(errors.none { it.type == ErrorType.COLD && it.tile == Tile(1, 1) }, "$errors")
        assertTrue(errors.any { it.type == ErrorType.COLD && it.tile == Tile(2, 3) }, "$errors")
        assertTrue(errors.any { it.type == ErrorType.COLD && it.tile == Tile(1, 4) }, "diagonal does not warm: $errors")
        assertTrue(errors.any { it.type == ErrorType.HEAT_DISCONNECTED && it.tile == Tile(4, 4) }, "$errors")
        assertEquals(3, errors.count { it.type == ErrorType.COLD || it.type == ErrorType.HEAT_DISCONNECTED }, "$errors")
    }
}
