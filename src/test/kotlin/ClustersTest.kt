import config.RunConfiguration
import config.Tuning
import place.Annealer
import place.Cluster
import place.PlaceModel
import place.diClusters
import place.staticObstacles
import plan.buildPlan
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClustersTest {
    private val job = RunConfiguration.load("jobs/chem_science_block.json")
    private val tuning = Tuning.defaults().let { t -> t.copy(planner = t.planner.copy(diConsumersPerProducer = 2), place = t.place.copy(clusters = true)) }
    private val plan = buildPlan(TestData.data, job, tuning)

    private fun recipes(c: Cluster) = c.members.map { plan.machines[it].recipe }

    /** Producer-consumer pairs of [c] that its own layout puts one tile apart, side by side. */
    private fun designedPairs(c: Cluster): List<Pair<Int, Int>> {
        val ms = plan.machines
        val rects = c.members.indices.map { geom.Rect(c.dx[it], c.dy[it], ms[c.members[it]].w, ms[c.members[it]].h) }
        return c.members.indices.flatMap { i -> c.members.indices.map { j -> i to j } }.filter { (i, j) ->
            val p = ms[c.members[i]]; val q = ms[c.members[j]]
            if (i == j || p.product !in q.demand) return@filter false
            val a = rects[i]; val b = rects[j]
            val yo = maxOf(a.y, b.y) <= minOf(a.y2, b.y2); val xo = maxOf(a.x, b.x) <= minOf(a.x2, b.x2)
            val gx = maxOf(b.x - a.x2 - 1, a.x - b.x2 - 1); val gy = maxOf(b.y - a.y2 - 1, a.y - b.y2 - 1)
            (yo && (gx == 1 || gx == 3)) || (xo && (gy == 1 || gy == 3))
        }.map { (i, j) -> c.members[i] to c.members[j] }
    }

    @Test
    fun `chemical science becomes two cells between the engine and circuit clusters`() {
        // Two producers per item: 2 gear, 2 pipe and 2 cable machines for 4 engines and 4 advanced circuits.
        assertEquals(2, plan.machines.count { it.recipe == "copper-cable" })
        val cs = diClusters(plan, longHanded = true)
        assertEquals(listOf("cell", "cell"), cs.map { it.kind })
        cs.forEach { c ->
            // Engines around a gear machine over a pipe machine, a science machine under each engine, the circuit
            // sandwich under them.
            assertEquals(listOf("engine-unit", "iron-gear-wheel", "pipe", "engine-unit", "chemical-science-pack", "chemical-science-pack",
                "advanced-circuit", "copper-cable", "advanced-circuit"), recipes(c))
            val pairs = designedPairs(c).map { (p, q) -> plan.machines[p].recipe to plan.machines[q].recipe }
            assertEquals(2, pairs.count { it == "engine-unit" to "chemical-science-pack" })
            assertEquals(2, pairs.count { it == "advanced-circuit" to "chemical-science-pack" })
            assertEquals(2, pairs.count { it == "copper-cable" to "advanced-circuit" })
            assertEquals(2, pairs.count { it == "iron-gear-wheel" to "engine-unit" })
            assertEquals(2, pairs.count { it == "pipe" to "engine-unit" })
        }
        // The fifth science machine stays outside, fed by belt.
        assertEquals(1, plan.machines.indices.count { m -> plan.machines[m].recipe == "chemical-science-pack" && cs.none { m in it.members } })
    }

    @Test
    fun `annealing keeps every cluster whole, each consumer right beside its producers`() {
        val cs = diClusters(plan, longHanded = true)
        for (seed in 1L..3L) {
            val model = PlaceModel(plan, job, tuning.place, 30, 30, staticObstacles(job, plan, 30, 30))
            val ann = Annealer(model, SplittableRandom(seed), tuning.place)
            ann.setClusters(cs)
            assertTrue(ann.initialRandom(), "seed $seed: no start")
            ann.anneal(maxMoves = 40_000)
            val fit = model.diPairsFitting().toSet()
            cs.forEach { c -> designedPairs(c).forEach { (p, q) ->
                assertTrue((p to q) in fit, "seed $seed: ${plan.machines[p].recipe} not beside ${plan.machines[q].recipe}")
            } }
        }
    }
}
