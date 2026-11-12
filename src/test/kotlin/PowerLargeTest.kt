import geom.Kind
import geom.PlacedEntity
import geom.Tile
import power.placePoles
import kotlin.test.Test
import kotlin.test.assertTrue

/** The pole greedy on a whole factory's worth of candidate tiles (power.placePolesLarge). */
class PowerLargeTest {
    @Test
    fun `a large factory gets every machine covered and one connected network quickly`() {
        val ms = ArrayList<PlacedEntity>()
        for (y in 0 until 50) for (x in 0 until 50) {
            ms += PlacedEntity("assembling-machine-2", Kind.MACHINE, 4 + x * 11, 4 + y * 11)
            ms += PlacedEntity("inserter", Kind.INSERTER, 7 + x * 11, 4 + y * 11)
        }
        val w = 560; val h = 560
        val cands = (0 until h).flatMap { y -> (0 until w).map { x -> Tile(x, y) } }
        val t0 = System.currentTimeMillis()
        val r = placePoles(ms, cands, "medium-electric-pole")
        val took = System.currentTimeMillis() - t0
        // Components under wire reach (9 for a medium pole), for the message.
        val ps = r.poles.map { it.x + 0.5 to it.y + 0.5 }
        val comp = IntArray(ps.size) { -1 }; var c = 0
        for (s0 in ps.indices) { if (comp[s0] >= 0) continue; val st = ArrayDeque(listOf(s0)); comp[s0] = c
            while (st.isNotEmpty()) { val i = st.removeLast(); for (j in ps.indices) if (comp[j] < 0 && Math.hypot(ps[i].first - ps[j].first, ps[i].second - ps[j].second) <= 9.0) { comp[j] = c; st += j } }
            c++ }
        println("PowerLargeTest: ${r.poles.size} poles, ${r.wires.size} wires, $c components in $took ms")
        assertTrue(r.uncovered.isEmpty(), "uncovered ${r.uncovered.size}")
        assertTrue(!r.disconnected, "disconnected")
        assertTrue(took < 60_000, "took $took ms")
        println("PowerLargeTest: ${r.poles.size} poles, ${r.wires.size} wires in $took ms")
    }
}
