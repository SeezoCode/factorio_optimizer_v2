import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.Tuning
import geom.Kind
import geom.Tile
import place.Placement
import plan.Net
import plan.Port
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import route.RouteProblem
import route.route
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Corridor links at 0/s (a block's overflow of an item it only receives) are routed like any other: before, the
 * router built no belt for them, another net's belt ran over their first tile, and the block's port belt fed it
 * from behind or the side (Py science 2 and logistic: SHAPE_CHANGED and two items on one lane, every try).
 */
class StitchIdleLinkTest {
    @Test
    fun `a 0 per second link from a block port to the edge gets its own belt`() {
        val config = RunConfiguration(
            targets = listOf(ConfigTarget("iron-gear-wheel", 1.0)),
            sources = listOf(ConfigSource("iron-plate", edge = "north")),
            outputs = listOf(ConfigSource("iron-gear-wheel", edge = "east")),
        ).copy(blocks = null, laneSharing = false)
        val W = 20; val H = 20
        // A block port at (10,10) whose belt points north into (10,9): the link's first tile.
        val ports = listOf(
            Port(0, "ash", listOf(ConfigSource("ash", x = 10, y = 9, dir = "S")), output = false, single = true),
            Port(1, "ash", listOf(ConfigSource("ash", edge = "north")), output = true, single = true),
        )
        val terminals = listOf(
            Terminal(0, Role.SOURCE, "ash", 0.0, 1, port = 0),
            Terminal(1, Role.OUTPUT, "ash", 0.0, 1, port = 1),
        )
        val nets = listOf(Net(0, "ash", 0.0, listOf(0, 1), 1))
        val plan = ProductionPlan(emptyList(), ports, terminals, nets, emptyMap(), emptyMap(), emptyMap(), "express", emptyList())
        val obstacles = setOf(Tile(10, 10))
        val r = route(RouteProblem(plan, config, Tuning.defaults().route, Placement(W, H, emptyList()), obstacles = obstacles))
        val belts = r.entities.filter { it.kind.beltFamily }
        assertTrue(r.success, "routing failed: ${r.log.takeLast(5)}")
        assertTrue(belts.any { it.tile == Tile(10, 9) }, "no belt on the link's first tile: ${belts.map { "${it.kind} ${it.tile} ${it.dir}" }}; log ${r.log.takeLast(8)}")
    }
}
