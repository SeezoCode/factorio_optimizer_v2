package power

import data.GameConstants
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import kotlin.math.abs
import kotlin.math.max

/** Roboports placed, and what could not be done. */
class Roboports(val roboports: List<PlacedEntity>, val notes: List<String>)

/**
 * Roboports over the chests robots serve (blocks.bot_links: provider and requester chests at the ports of small links
 * between blocks), one logistic network. First each chest gets a roboport whose logistic area reaches it, on a free
 * 4 x 4 spot covering as many chests still uncovered as it can; then the networks join: two roboports are one network
 * when their logistic areas meet (centres at most twice the radius apart on both axes), and roboports go on free spots
 * along the way from one network to the nearest other until they meet. [taken0]: tiles to keep free besides the
 * entities'. A chest no spot reaches, or a network nothing joins, is a note (in game its robots would not get there).
 */
fun placeRoboports(entities: List<PlacedEntity>, chests: List<Tile>, w: Int, h: Int, taken0: Set<Tile> = emptySet()): Roboports {
    if (chests.isEmpty()) return Roboports(emptyList(), emptyList())
    val size = GameConstants.ROBOPORT_SIZE
    val r = GameConstants.ROBOPORT_LOGISTIC_RADIUS
    val notes = ArrayList<String>()
    val taken = HashSet<Tile>(taken0)
    entities.forEach { e -> e.rect.tiles().forEach { taken += it } }
    fun fits(x: Int, y: Int) = x >= 0 && y >= 0 && x + size <= w && y + size <= h &&
        (0 until size).all { dx -> (0 until size).all { dy -> Tile(x + dx, y + dy) !in taken } }
    // (Positions are top-left corners; a roboport's centre is 2 tiles in.)
    fun covers(p: Tile, t: Tile) = abs(t.x + 0.5 - (p.x + size / 2.0)) <= r && abs(t.y + 0.5 - (p.y + size / 2.0)) <= r
    fun linked(a: Tile, b: Tile) = abs(a.x - b.x) <= 2 * r && abs(a.y - b.y) <= 2 * r
    /** The free spot nearest [c] (top-left), at most [reach] away on both axes, that [ok] accepts. */
    fun near(c: Tile, reach: Int, ok: (Tile) -> Boolean = { true }): Tile? {
        for (d in 0..reach) for (dy in -d..d) for (dx in -d..d) {
            if (max(abs(dx), abs(dy)) != d) continue
            val p = Tile(c.x + dx, c.y + dy)
            if (fits(p.x, p.y) && ok(p)) return p
        }
        return null
    }
    val placed = ArrayList<Tile>()
    fun place(p: Tile) { placed += p; for (dy in 0 until size) for (dx in 0 until size) taken += Tile(p.x + dx, p.y + dy) }

    // 1. Cover every chest.
    var left = chests.filter { c -> placed.none { covers(it, c) } }
    while (left.isNotEmpty()) {
        val c = left.first()
        var best: Tile? = null
        var most = 0
        val span = r - size
        for (dy in -span..span step 3) for (dx in -span..span step 3) {
            val p = Tile(c.x + dx - size / 2, c.y + dy - size / 2)
            if (!covers(p, c) || !fits(p.x, p.y)) continue
            val n = left.count { covers(p, it) }
            if (n > most) { best = p; most = n }
        }
        val spot = best ?: near(Tile(c.x - size / 2, c.y - size / 2), span) { covers(it, c) }
        if (spot == null) {
            notes += "no room for a roboport within reach of the chest at $c"
            left = left.drop(1)
            continue
        }
        place(spot)
        left = left.filter { t -> !covers(spot, t) }
    }

    // 2. One network. Candidate spots: one free 4 x 4 spot per 8 x 8 cell where one fits. From the largest network, the
    // shortest chain of candidates (each within reach of the last) to any other network gets roboports; again until
    // none is apart, or nothing reaches the rest (a network of its own, a note).
    val cell = 8
    val cands = ArrayList<Tile>()
    for (cy in 0 until h step cell) for (cx in 0 until w step cell) {
        var found: Tile? = null
        for (y in cy until minOf(cy + cell, h)) { for (x in cx until minOf(cx + cell, w)) { if (fits(x, y)) { found = Tile(x, y); break } }; if (found != null) break }
        found?.let { cands += it }
    }
    fun components(): List<List<Tile>> {
        val parent = IntArray(placed.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in placed.indices) for (j in i + 1 until placed.size) if (linked(placed[i], placed[j])) parent[find(i)] = find(j)
        return placed.indices.groupBy { find(it) }.values.map { g -> g.map { placed[it] } }
    }
    val apart = HashSet<Tile>()
    var rounds = 0
    while (rounds++ < 200) {
        val comps = components()
        val main = comps.maxByOrNull { it.size } ?: break
        val goal = comps.filter { it !== main }.flatten().filter { it !in apart }.toSet()
        if (goal.isEmpty()) break
        // BFS over main's roboports, the candidates and the goal roboports, through cells of 2r.
        val nodes = main + cands.filter { c -> fits(c.x, c.y) } + goal
        val bucket = HashMap<Pair<Int, Int>, MutableList<Int>>()
        nodes.forEachIndexed { k, t -> bucket.getOrPut(t.x / (2 * r) to t.y / (2 * r)) { ArrayList() } += k }
        val from = IntArray(nodes.size) { -2 }
        val queue = ArrayDeque<Int>()
        main.indices.forEach { from[it] = -1; queue += it }
        var hit = -1
        while (queue.isNotEmpty() && hit < 0) {
            val u = queue.removeFirst()
            val bx = nodes[u].x / (2 * r); val by = nodes[u].y / (2 * r)
            for (ddy in -1..1) for (ddx in -1..1) for (v in bucket[bx + ddx to by + ddy].orEmpty()) {
                if (from[v] != -2 || !linked(nodes[u], nodes[v])) continue
                from[v] = u
                if (nodes[v] in goal) { hit = v; break }
                queue += v
            }
        }
        if (hit < 0) {
            val stranded = comps.filter { it !== main && it.any { t -> t in goal } }
            stranded.forEach { comp -> notes += "roboports at ${comp.first()} (${comp.size}) could not be joined to the rest: no free spots in between (a network of their own)"; apart += comp }
            continue
        }
        var v = from[hit]
        while (v >= 0 && nodes[v] !in main) { if (fits(nodes[v].x, nodes[v].y)) place(nodes[v]); v = from[v] }
    }
    return Roboports(placed.map { PlacedEntity(GameConstants.ROBOPORT, Kind.ROBOPORT, it.x, it.y) }, notes)
}
