import data.GameConstants
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
import route.addProviderLevel
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
        val stoneOnly = base.map { if (it.tile == toOverflow.tile) it.copy(item = "stone") else if (it.tile == toProvider.tile) it.copy(item = "iron-gear-wheel") else it }
        val stone = listOf(OutputSpec(Tile(4, 1), "stone", 0.001, local = true), outputs[1])
        assertEquals(0, addOverflowLast(stoneOnly, stone, setOf("stone", "iron-gear-wheel")) { 200 }.gated, "a by-product's only way out")
        val gearsOnly = base.map { if (it.tile == toProvider.tile) it.copy(item = "iron-stick") else it }
        assertEquals(0, addOverflowLast(gearsOnly, outputs, setOf("iron-gear-wheel")) { 200 }.gated, "the other exit carries something else")
    }

    @Test
    fun `the condition survives the blueprint`() {
        val r = addOverflowLast(base, outputs, setOf("iron-gear-wheel")) { 200 }
        // (Moved into a box from the origin: a blueprint keeps positions relative to its own corner.)
        val moved = r.entities.map { it.copy(x = it.x + 2, y = it.y + 2) }
        val back = decodeBlueprint(Layout(8, 8, moved).toBlueprintString()).toLayout(8, 8).entities
        assertEquals(listOf(CircuitCondition("iron-gear-wheel", ">", 200, fluid = false)), back.mapNotNull { it.logistic })
    }

    @Test
    fun `an overflow chest behind an underground longer than the base game's is still found`() {
        // As Py does: express undergrounds reaching 34. The overflow chest's port lies 20 tiles under ground from the maker.
        val root = TestData.data
        val lg = root.logistics!!
        val modded = root.copy(logistics = lg.copy(undergrounds = lg.undergrounds!!.map { if (it.name == "express-underground-belt") it.copy(maxDistance = 34) else it }))
        val far = listOf(
            machine, toOverflow, toProvider,
            PlacedEntity("express-transport-belt", Kind.BELT, 4, 0, Dir.S), PlacedEntity("express-underground-belt", Kind.UG_IN, 4, 1, Dir.S),
            PlacedEntity("express-underground-belt", Kind.UG_OUT, 4, 21, Dir.S), PlacedEntity("express-transport-belt", Kind.BELT, 4, 22, Dir.S),
            PlacedEntity("inserter", Kind.INSERTER, 4, 23, Dir.N), PlacedEntity("steel-chest", Kind.CHEST, 4, 24),
            PlacedEntity("transport-belt", Kind.BELT, -2, 0, Dir.N),
            PlacedEntity("inserter", Kind.INSERTER, -2, -1, Dir.S), PlacedEntity("passive-provider-chest", Kind.CHEST, -2, -2),
        )
        val outs = listOf(OutputSpec(Tile(4, 22), "iron-gear-wheel", 0.001, local = true), outputs[1])
        try {
            GameConstants.register(modded)
            val r = addOverflowLast(far, outs, setOf("iron-gear-wheel")) { 200 }
            assertEquals(1, r.gated)
            assertEquals(CircuitCondition("iron-gear-wheel", ">", 200, fluid = false), r.entities.single { it.tile == toOverflow.tile }.logistic)
            // (And the far overflow chest is no user: the provider exit keeps no limit of its own.)
            assertEquals(0, addProviderLevel(far, outs, { 200 }, gears).gated)
        } finally {
            GameConstants.register(root)
        }
    }

    // South of the maker an inserter straight into a machine that uses the gears.
    private val toUser = PlacedEntity("inserter", Kind.INSERTER, 0, 3, Dir.N)
    private val user = PlacedEntity("assembling-machine-2", Kind.MACHINE, 0, 4, recipe = "transport-belt")
    private val gears = { _: String -> listOf("iron-gear-wheel") }

    @Test
    fun `with a user of its own the maker's inserter toward the provider chest stops at the stock`() {
        val r = addProviderLevel(base + toUser + user, outputs, { 200 }, gears)
        assertEquals(1, r.gated)
        assertEquals(CircuitCondition("iron-gear-wheel", "<", 201, fluid = false), r.entities.single { it.tile == toProvider.tile }.logistic)
        assertNull(r.entities.single { it.tile == toOverflow.tile }.logistic)
        assertNull(r.entities.single { it.tile == toUser.tile }.logistic)
        // (And with the overflow rule after it both hold: at most 200 to the provider, more than 200 to the overflow.)
        val both = addOverflowLast(r.entities, outputs, setOf("iron-gear-wheel")) { 200 }
        assertEquals(2, both.entities.count { it.logistic != null })
    }

    @Test
    fun `the provider exit is left alone without a user beside the maker, or where it carries more than the one item`() {
        assertEquals(0, addProviderLevel(base, outputs, { 200 }, gears).gated, "only chests behind the other exits")
        assertEquals(0, addProviderLevel(base + toUser + user, outputs, { 200 }) { listOf("iron-gear-wheel", "iron-stick") }.gated, "two products, no filter")
        val other = toUser.copy(item = "iron-stick")
        assertEquals(0, addProviderLevel(base + other + user, outputs, { 200 }, gears).gated, "the other exit carries something else")
    }

    // No provider chest for the gears: the maker hands them to the machine south of it, and to its overflow chest.
    private val handed = base - toProvider + toUser + user
    private val sinkOnly = listOf(outputs[0])
    private val perCraft = { recipe: String, item: String -> if (recipe == "transport-belt" && item == "iron-gear-wheel") 1 else null }

    @Test
    fun `an item no provider holds - the overflow exit waits for a craft's worth in the machine it is handed to`() {
        val r = route.addOverflowAfterUser(handed, emptyList(), sinkOnly, emptySet(), perCraft)
        assertEquals(1, r.gated)
        assertEquals(CircuitCondition("iron-gear-wheel", ">", 0, fluid = false), r.entities.single { it.tile == toOverflow.tile }.circuit)
        assertEquals(listOf(user.tile), r.entities.filter { it.readContents }.map { it.tile })
        val i = r.entities.indexOfFirst { it.tile == toOverflow.tile }; val u = r.entities.indexOfFirst { it.tile == user.tile }
        assertEquals(listOf(Triple(minOf(i, u), maxOf(i, u), GameConstants.WIRE_RED)), r.circuitWires)
        assertNull(r.entities.single { it.tile == toUser.tile }.circuit)
        // (The machine's reading survives the blueprint.)
        val moved = r.entities.map { it.copy(x = it.x + 4, y = it.y + 4) }
        val back = decodeBlueprint(Layout(12, 12, moved, circuitWires = r.circuitWires).toBlueprintString()).toLayout(12, 12)
        assertEquals(1, back.entities.count { it.readContents })
    }

    @Test
    fun `the wired rule leaves alone what the stock rule guards, a neighbour that does not use the item, and what has a condition`() {
        assertEquals(0, route.addOverflowAfterUser(handed, emptyList(), sinkOnly, setOf("iron-gear-wheel"), perCraft).gated, "a provider holds it")
        assertEquals(0, route.addOverflowAfterUser(handed, emptyList(), sinkOnly, emptySet()) { _, _ -> null }.gated, "the neighbour takes something else")
        assertEquals(0, route.addOverflowAfterUser(handed - toUser, emptyList(), sinkOnly, emptySet(), perCraft).gated, "no way to the neighbour")
        val set = handed.map { if (it.tile == toOverflow.tile) it.copy(logistic = CircuitCondition("iron-gear-wheel", ">", 200, fluid = false)) else it }
        assertEquals(0, route.addOverflowAfterUser(set, emptyList(), sinkOnly, emptySet(), perCraft).gated, "a condition already")
        val busy = handed.map { if (it.tile == user.tile) it.copy(circuit = CircuitCondition("signal-A", ">", 0, fluid = false)) else it }
        assertEquals(0, route.addOverflowAfterUser(busy, emptyList(), sinkOnly, emptySet(), perCraft).gated, "the neighbour works by a condition of its own")
    }

    @Test
    fun `an exit out of wire reach of the neighbour is wired through an inserter between them, or left open with a note`() {
        // A maker twelve tiles wide: the overflow inserter at its north-east corner, the neighbour under its south-west one.
        val big = machine.copy(w = 12, h = 12)
        val east = PlacedEntity("inserter", Kind.INSERTER, 12, 0, Dir.W)
        val south = PlacedEntity("inserter", Kind.INSERTER, 0, 12, Dir.N)
        val wide = listOf(
            big, east, south, user.copy(y = 13),
            PlacedEntity("transport-belt", Kind.BELT, 13, 0, Dir.S), PlacedEntity("transport-belt", Kind.BELT, 13, 1, Dir.S),
            PlacedEntity("inserter", Kind.INSERTER, 13, 2, Dir.N), PlacedEntity("steel-chest", Kind.CHEST, 13, 3),
        )
        val sink = listOf(OutputSpec(Tile(13, 1), "iron-gear-wheel", 0.001, local = true))
        val open = route.addOverflowAfterUser(wide, emptyList(), sink, emptySet(), perCraft)
        assertEquals(0, open.gated)
        assertEquals(1, open.notes.size)
        // (Two inserters bringing something into the maker, on its east and its south side, carry the wire.)
        val relays = listOf(PlacedEntity("inserter", Kind.INSERTER, 12, 7, Dir.E), PlacedEntity("inserter", Kind.INSERTER, 7, 12, Dir.S))
        val r = route.addOverflowAfterUser(wide + relays, emptyList(), sink, emptySet(), perCraft)
        assertEquals(1, r.gated)
        assertEquals(3, r.circuitWires.size)
        relays.forEach { relay -> assertNull(r.entities.single { it.tile == relay.tile }.circuit) }
    }

    @Test
    fun `a provider chest without its slot limit gets it back, other chests and a limit already there are left`() {
        val r = route.withProviderBars(base, outputs, 2)
        assertEquals(2, r.single { it.name == "passive-provider-chest" }.bar)
        assertNull(r.single { it.name == "steel-chest" }.bar)
        val had = base.map { if (it.name == "passive-provider-chest") it.copy(bar = 5) else it }
        assertEquals(5, route.withProviderBars(had, outputs, 2).single { it.name == "passive-provider-chest" }.bar)
    }
}
