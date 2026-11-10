package blocks

import geom.Dir
import geom.Rect
import geom.Tile
import java.util.PriorityQueue
import kotlin.math.max

/**
 * A link for global routing: from a port tile to another port tile, or to the factory's edge on any of [edges].
 */
class CoarseLink(val id: Int, val from: Tile, val to: Tile?, val edges: Set<Dir> = emptySet())

/** What global routing found: where demand exceeds capacity (tile at each overflowing boundary's middle, and by how much). */
class GlobalRoute(val overflow: List<Pair<Tile, Int>>, val rounds: Int, val routed: Int, val unreachable: List<Int>) {
    val worst get() = overflow.maxOfOrNull { it.second } ?: 0
}

/**
 * Global routing of a factory's corridor links on a coarse grid (docs/STITCH_REWRITE.md, step 2): cells of [cell] x
 * [cell] tiles; a cell side's capacity is the number of tile rows across it free on both sides (each carries one belt
 * or pipe along the corridor; an underground lets another cross under it). Every link is routed cell to cell with
 * negotiated congestion (a link pays more where others already are, more each round, and more where demand has
 * exceeded capacity before) until nothing overflows or [rounds] pass. What still overflows says where the corridors
 * are too narrow, before any fine routing is spent on them.
 */
fun globalRoute(w: Int, h: Int, blocked: List<Rect>, links: List<CoarseLink>, cell: Int = 4, rounds: Int = 30): GlobalRoute {
    val cw = (w + cell - 1) / cell; val ch = (h + cell - 1) / cell
    val free = Array(h) { BooleanArray(w) { true } }
    blocked.forEach { r -> for (y in max(0, r.y) until minOf(h, r.y + r.h)) for (x in max(0, r.x) until minOf(w, r.x + r.w)) free[y][x] = false }
    // Ports lie on their block's edge: their tiles are open, so a link leaves its port's cell toward the corridor.
    links.forEach { l -> listOfNotNull(l.from, l.to).forEach { t -> if (t.x in 0 until w && t.y in 0 until h) free[t.y][t.x] = true } }
    // Capacity of the side between cell (cx, cy) and its east neighbour (horizontal moves) and its south one (vertical).
    val capE = Array(ch) { IntArray(cw) }; val capS = Array(ch) { IntArray(cw) }
    for (cy in 0 until ch) for (cx in 0 until cw) {
        val x0 = cx * cell; val y0 = cy * cell
        if (cx + 1 < cw) {
            val xa = minOf(x0 + cell - 1, w - 1); val xb = minOf(x0 + cell, w - 1)
            capE[cy][cx] = (y0 until minOf(y0 + cell, h)).count { y -> free[y][xa] && free[y][xb] }
        }
        if (cy + 1 < ch) {
            val ya = minOf(y0 + cell - 1, h - 1); val yb = minOf(y0 + cell, h - 1)
            capS[cy][cx] = (x0 until minOf(x0 + cell, w)).count { x -> free[ya][x] && free[yb][x] }
        }
    }
    // Usage and history per side.
    val useE = Array(ch) { IntArray(cw) }; val useS = Array(ch) { IntArray(cw) }
    val histE = Array(ch) { DoubleArray(cw) }; val histS = Array(ch) { DoubleArray(cw) }
    fun cellOf(t: Tile) = Tile((t.x.coerceIn(0, w - 1)) / cell, (t.y.coerceIn(0, h - 1)) / cell)
    fun onEdge(c: Tile, d: Dir) = when (d) { Dir.N -> c.y == 0; Dir.S -> c.y == ch - 1; Dir.W -> c.x == 0; Dir.E -> c.x == cw - 1 }
    /** The side between [a] and its neighbour in [d]: (use, cap, hist) accessors. */
    fun side(a: Tile, d: Dir): Triple<Int, Int, Double>? = when (d) {
        Dir.E -> if (a.x + 1 < cw) Triple(useE[a.y][a.x], capE[a.y][a.x], histE[a.y][a.x]) else null
        Dir.W -> if (a.x > 0) Triple(useE[a.y][a.x - 1], capE[a.y][a.x - 1], histE[a.y][a.x - 1]) else null
        Dir.S -> if (a.y + 1 < ch) Triple(useS[a.y][a.x], capS[a.y][a.x], histS[a.y][a.x]) else null
        Dir.N -> if (a.y > 0) Triple(useS[a.y - 1][a.x], capS[a.y - 1][a.x], histS[a.y - 1][a.x]) else null
    }
    fun bump(a: Tile, d: Dir, by: Int) {
        when (d) {
            Dir.E -> useE[a.y][a.x] += by; Dir.W -> useE[a.y][a.x - 1] += by
            Dir.S -> useS[a.y][a.x] += by; Dir.N -> useS[a.y - 1][a.x] += by
        }
    }
    val paths = HashMap<Int, List<Pair<Tile, Dir>>>()
    val unreachable = ArrayList<Int>()
    var pres = 0.5
    var round = 0
    fun route(l: CoarseLink): List<Pair<Tile, Dir>>? {
        val start = cellOf(l.from)
        val goal = l.to?.let(::cellOf)
        val dist = HashMap<Tile, Double>(); val prev = HashMap<Tile, Pair<Tile, Dir>>()
        // A*: cells to the goal (or the nearest edge allowed), each step costing at least 1.
        fun est(c: Tile): Double = if (goal != null) (kotlin.math.abs(c.x - goal.x) + kotlin.math.abs(c.y - goal.y)).toDouble()
            else l.edges.minOfOrNull { d -> when (d) { Dir.N -> c.y; Dir.S -> ch - 1 - c.y; Dir.W -> c.x; Dir.E -> cw - 1 - c.x } }?.toDouble() ?: 0.0
        val pq = PriorityQueue<Pair<Double, Tile>>(compareBy { it.first })
        dist[start] = 0.0; pq += est(start) to start
        var end: Tile? = null
        while (pq.isNotEmpty()) {
            val (f, c) = pq.poll()
            val g = dist[c] ?: continue
            if (f > g + est(c) + 1e-9) continue
            if ((goal != null && c == goal) || (goal == null && l.edges.any { onEdge(c, it) })) { end = c; break }
            for (d in Dir.entries) {
                val (use, cap, hist) = side(c, d) ?: continue
                if (cap == 0) continue
                val over = max(0, use + 1 - cap)
                val cost = (1.0 + hist) * (1.0 + pres * over)
                val n = Tile(c.x + d.dx, c.y + d.dy)
                val ng = g + cost
                if (ng < (dist[n] ?: Double.MAX_VALUE)) { dist[n] = ng; prev[n] = c to d; pq += (ng + est(n)) to n }
            }
        }
        val e = end ?: return null
        val path = ArrayList<Pair<Tile, Dir>>()
        var c = e
        while (c != start) { val p = prev.getValue(c); path += p; c = p.first }
        return path
    }
    while (round < rounds) {
        round++
        unreachable.clear()
        for (l in links) {
            paths[l.id]?.forEach { (a, d) -> bump(a, d, -1) }
            val p = route(l)
            if (p == null) { unreachable += l.id; paths.remove(l.id); continue }
            paths[l.id] = p
            p.forEach { (a, d) -> bump(a, d, 1) }
        }
        var overflowing = false
        for (cy in 0 until ch) for (cx in 0 until cw) {
            if (useE[cy][cx] > capE[cy][cx]) { histE[cy][cx] += (useE[cy][cx] - capE[cy][cx]).toDouble(); overflowing = true }
            if (useS[cy][cx] > capS[cy][cx]) { histS[cy][cx] += (useS[cy][cx] - capS[cy][cx]).toDouble(); overflowing = true }
        }
        if (!overflowing) break
        pres *= 1.6
    }
    val overflow = ArrayList<Pair<Tile, Int>>()
    for (cy in 0 until ch) for (cx in 0 until cw) {
        if (useE[cy][cx] > capE[cy][cx]) overflow += Tile(cx * cell + cell - 1, cy * cell + cell / 2) to useE[cy][cx] - capE[cy][cx]
        if (useS[cy][cx] > capS[cy][cx]) overflow += Tile(cx * cell + cell / 2, cy * cell + cell - 1) to useS[cy][cx] - capS[cy][cx]
    }
    return GlobalRoute(overflow, round, paths.size, unreachable.toList())
}
