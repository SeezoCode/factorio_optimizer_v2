import data.GameConstants
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import validate.OutputSpec
import validate.SourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test copy's feeds sit where the port's entity connects, also on the box corners (two outward sides). */
class TestCopyTest {
    private val config = TestData.job("t1")

    @Test
    fun `a corner pipe-to-ground port is fed on its open side`() {
        // As in acid_east: water arrives at the top-right corner through a pipe-to-ground open to the north.
        // East of it the infinity pipe would touch the closed side and the plant would get no water.
        val water = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 8, 0, Dir.N, fluid = "water")
        val acid = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 0, 8, Dir.S, fluid = "sulfuric-acid")
        val layout = Layout(9, 9, listOf(water, acid))
        val tc = power.testCopy(layout, listOf(SourceSpec(water.tile, "water", 100.0, fluid = true)),
            listOf(OutputSpec(acid.tile, "sulfuric-acid", 50.0, fluid = true)), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val pipes = tc.layout.entities.filter { it.kind == Kind.INFINITY_PIPE }
        assertEquals(Tile(8, -1), pipes.single { it.fluid == "water" }.tile)
        assertEquals(Tile(0, 9), pipes.single { it.fluid == "sulfuric-acid" && it.drain }.tile)
    }

    @Test
    fun `a pipe-to-ground whose open side faces the box gets no feed`() {
        val water = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 8, 0, Dir.W, fluid = "water")
        val tc = power.testCopy(Layout(9, 9, listOf(water)), listOf(SourceSpec(water.tile, "water", 100.0, fluid = true)), emptyList(), config)
        assertTrue(tc.layout.entities.none { it.kind == Kind.INFINITY_PIPE })
        assertEquals(1, tc.warnings.size)
    }

    @Test
    fun `feeds of two fluids never touch`() {
        // Oil leaves east at (8,1); water's corner pipe at (8,0) would take east too, next to the oil feed, so it goes north.
        val oil = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 8, 1, Dir.E, fluid = "crude-oil")
        val water = PlacedEntity(GameConstants.PIPE, Kind.PIPE, 8, 0, fluid = "water")
        val tc = power.testCopy(Layout(9, 9, listOf(oil, water)),
            listOf(SourceSpec(oil.tile, "crude-oil", 100.0, fluid = true), SourceSpec(water.tile, "water", 100.0, fluid = true)), emptyList(), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val pipes = tc.layout.entities.filter { it.kind == Kind.INFINITY_PIPE }
        assertEquals(Tile(9, 1), pipes.single { it.fluid == "crude-oil" }.tile)
        assertEquals(Tile(8, -1), pipes.single { it.fluid == "water" }.tile)
    }

    @Test
    fun `a local sink's chest becomes an infinity chest and gets no loader`() {
        val belt = PlacedEntity("fast-transport-belt", Kind.BELT, 3, 1, Dir.E)
        val ins = PlacedEntity("fast-inserter", Kind.INSERTER, 3, 2, Dir.N)
        val chest = PlacedEntity("steel-chest", Kind.CHEST, 3, 3, Dir.N)
        val tc = power.testCopy(Layout(9, 9, listOf(belt, ins, chest)), emptyList(),
            listOf(OutputSpec(belt.tile, "iron-plate", 0.05, local = true)), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        assertTrue(tc.layout.entities.none { it.kind == Kind.LOADER_IN || it.kind == Kind.LOADER_OUT })
        assertEquals(Tile(3, 3), tc.layout.entities.single { it.kind == Kind.INFINITY_CHEST }.tile)
        assertTrue(tc.layout.entities.none { it.kind == Kind.CHEST })
    }

    @Test
    fun `a corner input belt is loaded from behind, not from its side`() {
        // Bottom-right corner, heading north: both east and south are outward; east would side-load one lane only.
        val belt = PlacedEntity("fast-transport-belt", Kind.BELT, 8, 8, Dir.N)
        val tc = power.testCopy(Layout(9, 9, listOf(belt)), listOf(SourceSpec(belt.tile, "iron-plate", 10.0)), emptyList(), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val loader = tc.layout.entities.single { it.kind == Kind.LOADER_OUT }
        assertEquals(Dir.N, loader.dir)
        assertTrue(loader.rect.tiles().any { it + Dir.N == belt.tile }, "${loader.rect.tiles()}")
        assertEquals(Tile(8, 11), tc.layout.entities.single { it.kind == Kind.INFINITY_CHEST }.tile)
    }

    @Test
    fun `a one-lane input's feeder inserters have power`() {
        // Plastic on the left lane only (the right lane takes cable from inside): fed by inserters, not a loader. They
        // stand outside the box, beyond the factory's pole, so they get a pole of their own wired to it.
        val belt = PlacedEntity("fast-transport-belt", Kind.BELT, 0, 4, Dir.E)
        val pole = PlacedEntity("medium-electric-pole", Kind.POLE, 6, 6)
        val tc = power.testCopy(Layout(9, 9, listOf(belt, pole)), listOf(SourceSpec(belt.tile, "plastic-bar", 1.5, lane = 0)), emptyList(), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val es = tc.layout.entities
        val inserters = es.filter { it.kind == Kind.INSERTER }
        assertTrue(inserters.isNotEmpty() && es.none { it.kind == Kind.LOADER_OUT })
        val poles = es.withIndex().filter { it.value.kind == Kind.POLE }
        inserters.forEach { ins ->
            assertTrue(poles.any { (_, p) ->
                val half = GameConstants.pole(p.name).supplyHalf
                kotlin.math.abs(ins.x + 0.5 - (p.x + p.w / 2.0)) <= half && kotlin.math.abs(ins.y + 0.5 - (p.y + p.h / 2.0)) <= half
            }, "no pole reaches the feeder inserter at ${ins.tile}")
        }
        // The new pole is wired to the factory's.
        val added = poles.single { it.value !== pole }.index
        assertTrue(tc.layout.wires.any { (a, b) -> setOf(a, b) == setOf(1, added) }, "${tc.layout.wires}")
    }

    @Test
    fun `two fluid ports side by side are both fed, one through a pipe-to-ground pair`() {
        // As in the 2/s six-science factory: crude oil and water enter on neighbouring tiles of the west edge. Two
        // infinity pipes beside each other would join; the second feed runs underground first.
        val oil = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 0, 4, Dir.W, fluid = "crude-oil")
        val water = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, 0, 5, Dir.W, fluid = "water")
        val tc = power.testCopy(Layout(9, 9, listOf(oil, water)),
            listOf(SourceSpec(oil.tile, "crude-oil", 100.0, fluid = true), SourceSpec(water.tile, "water", 100.0, fluid = true)), emptyList(), config)
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val feeds = tc.layout.entities.filter { it.kind == Kind.INFINITY_PIPE }
        assertEquals(2, feeds.size)
        // No two pipes of different fluids touch where they connect: infinity pipes never side by side.
        assertTrue(feeds[0].tile.manhattan(feeds[1].tile) > 1, "${feeds.map { it.tile }}")
        val pair = tc.layout.entities.filter { it.kind == Kind.PIPE_UG && it.x < 0 }
        assertEquals(2, pair.size)
        assertEquals(setOf(Dir.E, Dir.W), pair.map { it.dir }.toSet())
    }
}
