import config.ConfigSource
import config.ConfigTarget
import config.RouteTuning
import config.RunConfiguration
import geom.Dir
import geom.Rect
import place.PlacedMachine
import place.Placement
import plan.Net
import plan.MachineInstance
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import plan.buildPlan
import route.RouteProblem
import route.route
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertTrue

class RouterFuzzTest {
    private val items = listOf(
        "iron-gear-wheel", "copper-cable", "electronic-circuit", "pipe", "iron-stick", "inserter",
        "transport-belt", "automation-science-pack", "firearm-magazine", "pipe-to-ground", "burner-inserter",
    )

    /** Random legal placement, or null if the machines do not fit after a few tries. */
    private fun randomPlacement(plan: ProductionPlan, w: Int, h: Int, rnd: SplittableRandom, spacing: Int): Placement? {
        val placed = mutableListOf<PlacedMachine>()
        val rects = mutableListOf<Rect>()
        for (m in plan.machines) {
            var ok = false
            repeat(200) {
                if (ok) return@repeat
                val x = rnd.nextInt(0, w - m.w + 1)
                val y = rnd.nextInt(0, h - m.h + 1)
                val r = Rect(x, y, m.w, m.h)
                val grown = Rect(x - spacing, y - spacing, m.w + 2 * spacing, m.h + 2 * spacing)
                if (rects.none { it.overlaps(grown) }) {
                    rects += r; placed += PlacedMachine(m.id, x, y, Dir.entries[rnd.nextInt(4)]); ok = true
                }
            }
            if (!ok) return null
        }
        return Placement(w, h, placed)
    }

    @Test
    fun `random small instances never throw and every success validates`() {
        val rnd = SplittableRandom(4242)
        var instances = 0
        var successes = 0
        var failures = 0
        var woven = 0
        var shared = 0
        var splitters = 0
        val edges = listOf("west", "north", "east", "south", "any")
        while (instances < 200) {
            val targets = (1..rnd.nextInt(1, 4)).map { ConfigTarget(items[rnd.nextInt(items.size)], 0.1 + rnd.nextDouble() * 1.2) }
                .distinctBy { it.name }
            val sources = listOf("iron-plate", "copper-plate").map { ConfigSource(it, edge = edges[rnd.nextInt(edges.size)]) }
            val config = RunConfiguration(targets = targets, sources = sources, laneSharing = rnd.nextBoolean())
            val plan = buildPlan(TestData.data, config)
            if (plan.machines.size !in 2..8) continue
            val size = rnd.nextInt(12, 31)
            val placement = randomPlacement(plan, size, size, rnd, spacing = rnd.nextInt(0, 3)) ?: continue
            instances++
            val r = RouteSupport.routeAndValidate("fuzz_$instances", config, plan, placement, RouteTuning(maxIterations = 30), render = false)
            if (r.result.success) {
                successes++
                woven += r.result.entities.count { it.kind == geom.Kind.UG_IN && it.name == "express-underground-belt" }
                splitters += r.result.entities.count { it.kind == geom.Kind.SPLITTER }
                shared += r.result.log.count { it.startsWith("shared belt") }
                if (r.errors.isNotEmpty()) {
                    RouteSupport.routeAndValidate("fuzz_fail_$instances", config, plan, placement, RouteTuning(maxIterations = 30), render = true)
                    println("fuzz $instances targets ${targets.map { it.name to it.amount }} sources ${sources.map { it.name to it.edge }}")
                    plan.nets.forEach { n -> println("  net ${n.id} ${n.item} flow ${n.flow}: " + plan.netTerminals(n).joinToString { "${it.role}/m${it.machine}/${"%.2f".format(it.rate)}" }) }
                    println("  outputs ${r.result.outputs} sources ${r.result.sources}")
                }
                assertTrue(r.errors.isEmpty(), "instance $instances reported success but fails validation: ${r.errors}")
            } else failures++
        }
        println("fuzz: $successes routed, $failures reported failure out of $instances; $woven woven underground pairs, $shared shared belts, $splitters splitters")
        assertTrue(successes >= instances / 3, "only $successes of $instances routed")
    }

    @Test
    fun `random fluid instances never throw and every success validates`() {
        val rnd = SplittableRandom(777)
        val fluidItems = listOf("plastic-bar", "sulfur", "sulfuric-acid", "lubricant", "solid-fuel", "explosives")
        var instances = 0
        var successes = 0
        while (instances < 40) {
            val targets = (1..rnd.nextInt(1, 3)).map { ConfigTarget(fluidItems[rnd.nextInt(fluidItems.size)], 0.2 + rnd.nextDouble() * 2.0) }
                .distinctBy { it.name }
            val config = RunConfiguration(targets = targets)
            val plan = buildPlan(TestData.data, config)
            if (plan.machines.size !in 2..14) continue
            val size = rnd.nextInt(24, 41)
            val placement = randomPlacement(plan, size, size, rnd, spacing = rnd.nextInt(1, 4)) ?: continue
            instances++
            val r = RouteSupport.routeAndValidate("fluidfuzz_$instances", config, plan, placement, RouteTuning(maxIterations = 30), render = false)
            if (r.result.success) {
                successes++
                if (r.errors.isNotEmpty()) RouteSupport.routeAndValidate("fluidfuzz_fail_$instances", config, plan, placement, RouteTuning(maxIterations = 30), render = true)
                assertTrue(r.errors.isEmpty(), "fluid instance $instances (${targets.map { it.name }}) reported success but fails validation: ${r.errors}")
            }
        }
        println("fluid fuzz: $successes routed out of $instances")
    }

    @Test
    fun `one iteration on 100 x 100 with 60 nets stays under 200 ms`() {
        val rnd = SplittableRandom(7)
        val machines = ArrayList<MachineInstance>()
        val terminals = ArrayList<Terminal>()
        val nets = ArrayList<Net>()
        repeat(60) { n ->
            val ids = (0 until 3).map { machines.size + it }
            ids.forEachIndexed { i, id ->
                machines += MachineInstance(id, "iron-gear-wheel", "assembling-machine-3", 3, 3, null, 1.0, "iron-gear-wheel", emptyMap())
                // One producer at 1.0/s feeding two consumers at 0.5/s each.
                terminals += Terminal(terminals.size, if (i == 0) Role.PRODUCER else Role.CONSUMER, "item-$n", if (i == 0) 1.0 else 0.5, 1, machine = id)
            }
            nets += Net(n, "item-$n", 1.0, terminals.takeLast(3).map { it.id })
        }
        val plan = ProductionPlan(machines, emptyList(), terminals, nets, emptyMap(), emptyMap(), emptyMap(), "fast", emptyList())
        // 14 x 14 lattice of 7-tile cells; machines shuffled into cells.
        val cells = (0 until 196).toMutableList().also { list -> for (i in list.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = list[i]; list[i] = list[j]; list[j] = t } }
        val placement = Placement(100, 100, machines.mapIndexed { i, m -> PlacedMachine(m.id, 1 + (cells[i] % 14) * 7 + 2, 1 + (cells[i] / 14) * 7 + 2) })
        val config = RunConfiguration(targets = emptyList(), tricks = mapOf("I5" to false, "I6" to false))
        val tuning = RouteTuning(maxIterations = 1, endgameRounds = 0)   // one negotiation iteration, no endgame
        repeat(3) { route(RouteProblem(plan, config, tuning, placement)) }   // JIT warm-up
        val runs = (1..5).map { route(RouteProblem(plan, config, tuning, placement)) }
        val times = runs.map { it.millis }
        println("100x100, 60 nets, one iteration: $times ms, ${runs[0].stats}, unrouted ${runs[0].failedNets.size}, overuse ${runs[0].overuse}")
        assertTrue(runs[0].failedNets.isEmpty(), "every net routes in the first pass")
        assertTrue(times.min() < 200, "one iteration took ${times.min()} ms")
    }
}
