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
}
