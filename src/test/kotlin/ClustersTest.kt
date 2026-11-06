import config.RunConfiguration
import config.Tuning
import place.Annealer
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

    @Test
    fun `chemical science clusters from the bottom of the chain up`() {
        // Two producers per item: 2 gear, 2 pipe and 2 cable machines for 4 engines and 4 advanced circuits.
        assertEquals(2, plan.machines.count { it.recipe == "copper-cable" })
        val cs = diClusters(plan)
        fun recipes(c: place.Cluster) = c.members.map { plan.machines[it].recipe }
        // Engines around a gear machine stacked over a pipe machine; advanced circuits on both sides of a cable machine.
        assertEquals(listOf("stack", "stack", "sandwich", "sandwich"), cs.map { it.kind })
        cs.filter { it.kind == "stack" }.forEach { assertEquals(listOf("engine-unit", "iron-gear-wheel", "pipe", "engine-unit"), recipes(it)) }
        cs.filter { it.kind == "sandwich" }.forEach { assertEquals(listOf("advanced-circuit", "copper-cable", "advanced-circuit"), recipes(it)) }
        // The science machines stay free: engines and circuits are not taken as producers of a science cluster.
        assertTrue(cs.none { c -> "chemical-science-pack" in recipes(c) })
    }

    @Test
    fun `annealing keeps every cluster whole, each consumer right beside its producers`() {
        val cs = diClusters(plan)
        for (seed in 1L..3L) {
            val model = PlaceModel(plan, job, tuning.place, 26, 26, staticObstacles(job, plan, 26, 26))
            val ann = Annealer(model, SplittableRandom(seed), tuning.place)
            ann.setClusters(cs)
            assertTrue(ann.initialRandom(), "seed $seed: no start")
            ann.anneal(maxMoves = 40_000)
            val fit = model.diPairsFitting().toSet()
            cs.forEach { c ->
                val ms = c.members.map { it to plan.machines[it] }
                val consumers = ms.filter { (_, m) -> m.recipe == "engine-unit" || m.recipe == "advanced-circuit" }.map { it.first }
                val producers = ms.map { it.first } - consumers.toSet()
                consumers.forEach { cm -> producers.forEach { pm -> assertTrue((pm to cm) in fit, "seed $seed: ${plan.machines[pm].recipe} not beside ${plan.machines[cm].recipe}") } }
            }
        }
    }
}
