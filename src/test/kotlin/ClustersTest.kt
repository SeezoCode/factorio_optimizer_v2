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
    fun `chemical science becomes two cells and a half cell, each science machine with producers of its own`() {
        // An engine machine cannot feed two science machines, so each gets its own (5 engines and 5 advanced
        // circuits for 5 science machines); gears, pipes and cable one per two consumers.
        assertEquals(5, plan.machines.count { it.recipe == "engine-unit" })
        assertEquals(5, plan.machines.count { it.recipe == "advanced-circuit" })
        assertEquals(3, plan.machines.count { it.recipe == "copper-cable" })
        val cs = diClusters(plan, longHanded = true)
        assertEquals(listOf("cell", "cell", "half cell"), cs.map { it.kind })
        cs.take(2).forEach { c ->
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
        // The fifth science machine between the leftover engine stack and circuit pair.
        val half = cs[2]
        assertEquals(listOf("engine-unit", "iron-gear-wheel", "pipe", "chemical-science-pack", "advanced-circuit", "copper-cable"), recipes(half))
        val pairs = designedPairs(half).map { (p, q) -> plan.machines[p].recipe to plan.machines[q].recipe }
        assertTrue("engine-unit" to "chemical-science-pack" in pairs && "advanced-circuit" to "chemical-science-pack" in pairs, "$pairs")
        assertEquals(0, plan.machines.indices.count { m -> plan.machines[m].recipe == "chemical-science-pack" && cs.none { m in it.members } })
    }

    @Test
    fun `annealing keeps every cluster whole, each consumer right beside its producers`() {
        val cs = diClusters(plan, longHanded = true)
        for (seed in 1L..3L) {
            val model = PlaceModel(plan, job, tuning.place, 36, 36, staticObstacles(job, plan, 36, 36))
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

    @Test
    fun `a circuit assembler sits between two cable assemblers of its own`() {
        val circuits = RunConfiguration.parse("""{"targets": [{"name": "electronic-circuit", "amount": 12}], "machines": ["assembling-machine-2"],
            "sources": [{"name": "iron-plate", "edge": "west"}, {"name": "copper-plate", "edge": "west"}]}""")
        val p = buildPlan(TestData.data, circuits, tuning.copy(planner = tuning.planner.copy(maxUtilization = 1.0)))
        // A circuit assembler 2 needs 1.5 cable assemblers' cable: two each.
        assertEquals(8, p.machines.count { it.recipe == "electronic-circuit" })
        assertEquals(16, p.machines.count { it.recipe == "copper-cable" })
        val cs = diClusters(p)
        assertEquals(List(8) { "duo" }, cs.map { it.kind })
        cs.forEach { c -> assertEquals(listOf("copper-cable", "electronic-circuit", "copper-cable"), c.members.map { p.machines[it].recipe }) }
    }
}
