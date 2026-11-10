import blocks.routeCorridors
import blocks.splitByGuides
import config.ConfigSource
import config.RunConfiguration
import config.Tuning
import geom.Dir
import geom.Tile
import place.Placement
import plan.Net
import plan.Port
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import route.RouteProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelRouteTest {
    /**
     * Corridor links as the stitch makes them: 32 belts from a block's east side (x 0..9) across to another's west side
     * (x 110..119), each ending some rows away from where it started, so they cross; each with a coarse route east
     * along its start row's cells, then north or south at the far end.
     */
    private fun problem(): RouteProblem {
        val ports = ArrayList<Port>(); val terminals = ArrayList<Terminal>(); val nets = ArrayList<Net>()
        val guides = HashMap<Int, List<Tile>>()
        val items = listOf("iron-plate", "copper-plate", "iron-gear-wheel", "copper-cable", "steel-plate", "stone-brick", "coal", "stone")
        repeat(32) { i ->
            val item = items[i % items.size]
            val y0 = 2 + 2 * i; val y1 = 2 + 2 * ((i * 7) % 32)
            val a = Port(ports.size, item, listOf(ConfigSource(item, x = 10, y = y0, dir = Dir.W.name)), single = true).also { ports += it }
            val b = Port(ports.size, item, listOf(ConfigSource(item, x = 109, y = y1, dir = Dir.E.name)), output = true, single = true).also { ports += it }
            val ta = Terminal(terminals.size, Role.SOURCE, item, 1.0, 1, port = a.id).also { terminals += it }
            val tb = Terminal(terminals.size, Role.OUTPUT, item, 1.0, 1, port = b.id).also { terminals += it }
            nets += Net(nets.size, item, 1.0, listOf(ta.id, tb.id), 1)
            guides[nets.last().id] = (10 / 4..109 / 4).map { Tile(it, y0 / 4) } +
                (if (y1 >= y0) (y0 / 4..y1 / 4) else (y1 / 4..y0 / 4).reversed()).map { Tile(109 / 4, it) }
        }
        val plan = ProductionPlan(emptyList(), ports, terminals, nets, emptyMap(), emptyMap(), emptyMap(), "express", emptyList())
        val blocked = (0 until 70).flatMap { y -> (0 until 10).map { Tile(it, y) } + (110 until 120).map { Tile(it, y) } }.toSet()
        val config = RunConfiguration(targets = emptyList(), beltTier = "express").let { it.copy(laneSharing = false) }
        return RouteProblem(plan, config, Tuning.defaults().route, Placement(120, 70, emptyList()), obstacles = blocked,
            noTunnel = blocked, netGuides = guides)
    }

    @Test
    fun `links are split in groups by where their global routes run`() {
        val guides = mapOf(0 to listOf(Tile(0, 0)), 1 to listOf(Tile(10, 0)), 2 to listOf(Tile(0, 1)), 3 to listOf(Tile(10, 1)))
        val groups = splitByGuides(listOf(0, 1, 2, 3), guides, 2)
        assertEquals(setOf(setOf(0, 2), setOf(1, 3)), groups.map { it.toSet() }.toSet())
    }

    @Test
    fun `corridors routed in groups side by side join up into one valid routing`() {
        val p = problem()
        val log = ArrayList<String>()
        val r = routeCorridors(p, 4) { log += it }
        assertTrue(log.any { "groups side by side" in it }, "$log")
        assertTrue(r.success, "failed ${r.failedNets}, ${r.conflicts.take(5)}; $log; ${r.log.takeLast(10)}")
        // Every link has belts, no two entities share a tile, nothing on the blocks.
        assertEquals((0 until 32).toSet(), r.entities.mapNotNull { it.net }.toSet())
        val tiles = r.entities.flatMap { it.rect.tiles() }
        assertEquals(tiles.size, tiles.toSet().size, "entities overlap")
        assertTrue(tiles.none { it in p.obstacles })
        // Each link's belts reach both of its ports' tiles.
        p.plan.nets.forEach { n ->
            val own = r.entities.filter { it.net == n.id }.flatMap { it.rect.tiles() }.toSet()
            n.terminals.map { p.plan.ports[p.plan.terminals[it].port!!].specs[0] }.forEach { s -> assertTrue(Tile(s.x!!, s.y!!) in own, "net ${n.id} misses (${s.x},${s.y})") }
        }
    }
}
