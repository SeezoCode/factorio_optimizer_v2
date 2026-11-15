import emit.decodeBlueprint
import emit.toBlueprintString
import emit.toLayout
import geom.Layout
import geom.CircuitCondition
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import route.addOverflowLast
import validate.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverflowLastTest {
    // A machine at (0,0)..(2,2). East: an inserter onto two belts running south to an overflow chest's port at (4,1).
    // West: an inserter onto a belt to a provider chest's port at (-2,0).
    private val machine = PlacedEntity("assembling-machine-2", Kind.MACHINE, 0, 0, recipe = "iron-gear-wheel")
    private val toOverflow = PlacedEntity("inserter", Kind.INSERTER, 3, 0, Dir.W)
    private val toProvider = PlacedEntity("inserter", Kind.INSERTER, -1, 0, Dir.E)
    private val base = listOf(
        machine, toOverflow, toProvider,
        PlacedEntity("transport-belt", Kind.BELT, 4, 0, Dir.S), PlacedEntity("transport-belt", Kind.BELT, 4, 1, Dir.S),
        PlacedEntity("inserter", Kind.INSERTER, 4, 2, Dir.N), PlacedEntity("steel-chest", Kind.CHEST, 4, 3),
        PlacedEntity("transport-belt", Kind.BELT, -2, 0, Dir.N),
        PlacedEntity("inserter", Kind.INSERTER, -2, -1, Dir.S), PlacedEntity("passive-provider-chest", Kind.CHEST, -2, -2),
    )
    private val outputs = listOf(
        OutputSpec(Tile(4, 1), "iron-gear-wheel", 0.001, local = true),
        OutputSpec(Tile(-2, 0), "iron-gear-wheel", 0.1, local = true, bot = true),
    )

    @Test
    fun `the inserter toward the overflow chest waits for the stock, the one toward the provider does not`() {
        val r = addOverflowLast(base, outputs, setOf("iron-gear-wheel")) { 200 }
        assertEquals(1, r.gated)
        assertEquals(CircuitCondition("iron-gear-wheel", ">", 200, fluid = false), r.entities.single { it.tile == toOverflow.tile }.logistic)
        assertNull(r.entities.single { it.tile == toProvider.tile }.logistic)
    }

    @Test
    fun `left alone where a user takes from the overflow's belt, the machine has no other way out, or nothing stocks the item`() {
        val user = PlacedEntity("inserter", Kind.INSERTER, 5, 0, Dir.W)
        assertEquals(0, addOverflowLast(base + user, outputs, setOf("iron-gear-wheel")) { 200 }.gated, "a user on the belt")
        assertEquals(0, addOverflowLast(base - toProvider, outputs, setOf("iron-gear-wheel")) { 200 }.gated, "the only way out")
        assertEquals(0, addOverflowLast(base, outputs, emptySet()) { 200 }.gated, "no provider holds it")
    }

    @Test
    fun `the condition survives the blueprint`() {
        val r = addOverflowLast(base, outputs, setOf("iron-gear-wheel")) { 200 }
        // (Moved into a box from the origin: a blueprint keeps positions relative to its own corner.)
        val moved = r.entities.map { it.copy(x = it.x + 2, y = it.y + 2) }
        val back = decodeBlueprint(Layout(8, 8, moved).toBlueprintString()).toLayout(8, 8).entities
        assertEquals(listOf(CircuitCondition("iron-gear-wheel", ">", 200, fluid = false)), back.mapNotNull { it.logistic })
    }
}
