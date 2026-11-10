import config.RunConfiguration
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import plan.Port
import plan.ProductionPlan
import route.walledPort
import route.withPortEnds
import validate.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortEndsTest {
    // A 6 x 6 block: gears leave east at (5,2) on to the corridors; plates go into a chest from a ring port at (6,3),
    // its belt heading north, so the chest's inserter would go straight out onto (6,2), the gears' way out.
    private val ports = listOf(
        Port(0, "iron-gear-wheel", listOf(config.ConfigSource("iron-gear-wheel", edge = "east")), output = true, single = true),
        Port(1, "iron-plate", emptyList(), output = true, end = "chest"),
    )
    private val plan = ProductionPlan(emptyList(), ports, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), "transport-belt", emptyList())
    private val belts = listOf(
        PlacedEntity("transport-belt", Kind.BELT, 5, 2, Dir.E),
        PlacedEntity("transport-belt", Kind.BELT, 6, 3, Dir.N),
    )
    private val outputs = listOf(
        OutputSpec(Tile(5, 2), "iron-gear-wheel", 1.0, port = 0),
        OutputSpec(Tile(6, 3), "iron-plate", 0.1, port = 1),
    )

    @Test
    fun `an end keeps off the way out of a corridor port`() {
        val ends = withPortEnds(belts, emptyList(), outputs, plan, RunConfiguration(targets = emptyList()), 6, 6)
        assertTrue(ends.missing.isEmpty(), "${ends.missing}")
        val added = ends.entities.drop(belts.size)
        assertEquals(setOf(Tile(7, 3), Tile(8, 3)), added.map { it.tile }.toSet(), "the chest's inserter turned east, off the gears' way out")
        assertTrue(ends.outputs.single { it.port == 1 }.local)
        assertNull(walledPort(ends.entities, emptyList(), ends.outputs, plan, 6, 6))
    }

    @Test
    fun `a saved layout with an end on a port's way out is found`() {
        val walled = belts + PlacedEntity("fast-inserter", Kind.INSERTER, 6, 2, Dir.S) + PlacedEntity("steel-chest", Kind.CHEST, 6, 1)
        val why = assertNotNull(walledPort(walled, emptyList(), outputs.map { if (it.port == 1) it.copy(local = true) else it }, plan, 6, 6))
        assertTrue("iron-gear-wheel" in why, why)
    }

    @Test
    fun `beside a pipe's way out only what a pipe joins walls it in`() {
        // Water leaves east at (5,2) by pipe; beside its way out, row 1 east of the box.
        val ports = listOf(Port(0, "water", listOf(config.ConfigSource("water", edge = "east")), fluid = true, output = true, single = true))
        val plan = ProductionPlan(emptyList(), ports, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), "transport-belt", emptyList())
        val pipe = PlacedEntity("pipe", Kind.PIPE, 5, 2, fluid = "water")
        val out = listOf(OutputSpec(Tile(5, 2), "water", 10.0, fluid = true, port = 0))
        assertNull(walledPort(listOf(pipe, PlacedEntity("fast-inserter", Kind.INSERTER, 8, 1, Dir.N), PlacedEntity("steel-chest", Kind.CHEST, 8, 0)), emptyList(), out, plan, 6, 6))
        assertNotNull(walledPort(listOf(pipe, PlacedEntity("py-overflow-valve", Kind.VALVE, 8, 1, Dir.W, fluid = "steam")), emptyList(), out, plan, 6, 6))
        assertNotNull(walledPort(listOf(pipe, PlacedEntity("steel-chest", Kind.CHEST, 9, 2)), emptyList(), out, plan, 6, 6), "four tiles straight out are kept")
    }
}
