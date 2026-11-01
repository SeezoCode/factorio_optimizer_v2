package power

import data.GameConstants
import geom.Kind
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import kotlin.math.hypot

data class PowerResult(
    val ok: Boolean,
    val poles: List<PlacedEntity>,
    /** Copper wires as indices into [poles]. */
    val wires: List<Pair<Int, Int>>,
    /** Entities no candidate pole could cover. */
    val uncovered: List<PlacedEntity>,
    val disconnected: Boolean,
)

/** Does a pole standing at [pole] (top-left) supply [r]? Overlap must be strict: touching edges do not count. */
fun supplies(spec: GameConstants.PoleSpec, pole: Tile, r: Rect): Boolean {
    val cx = pole.x + spec.size / 2.0
    val cy = pole.y + spec.size / 2.0
    val s = spec.supplyHalf
    return r.x < cx + s && r.x + r.w > cx - s && r.y < cy + s && r.y + r.h > cy - s
}

private fun center(spec: GameConstants.PoleSpec, t: Tile) = (t.x + spec.size / 2.0) to (t.y + spec.size / 2.0)

/**
 * P1: after routing, cover every machine and inserter with poles placed greedily on free tiles (most
 * newly covered entities first, ties to the candidate nearest an existing pole), then wire them with a
 * minimum spanning tree, adding bridge poles along the line between components when wires cannot reach.
 */
fun placePoles(
    entities: List<PlacedEntity>,
    candidates: List<Tile>,
    poleName: String,
    fixedPoles: List<Tile> = emptyList(),
): PowerResult {
    val spec = GameConstants.pole(poleName)
    val occupied = HashSet<Tile>()
    entities.forEach { e -> e.rect.tiles().forEach { occupied += it } }
    fun fits(t: Tile) = (0 until spec.size).all { dy -> (0 until spec.size).all { dx -> Tile(t.x + dx, t.y + dy) !in occupied } }
    val cands = candidates.filter { fits(it) }.distinct()
    val needs = entities.filter { it.kind == Kind.MACHINE || it.kind == Kind.INSERTER || it.kind == Kind.BEACON }

    val poles = ArrayList<Tile>()
    fixedPoles.forEach { poles += it; markOccupied(it, spec, occupied) }
    val covered = BooleanArray(needs.size) { i -> poles.any { supplies(spec, it, needs[i].rect) } }
    val coverLists = cands.associateWith { c -> needs.indices.filter { supplies(spec, c, needs[it].rect) } }

    while (covered.any { !it }) {
        var best: Tile? = null
        var bestGain = 0
        var bestDist = Double.MAX_VALUE
        for (c in cands) {
            if (!fits(c)) continue
            val gain = coverLists.getValue(c).count { !covered[it] }
            if (gain == 0 || gain < bestGain) continue
            val (cx, cy) = center(spec, c)
            val d = poles.minOfOrNull { val (px, py) = center(spec, it); hypot(cx - px, cy - py) } ?: 0.0
            if (gain > bestGain || d < bestDist) { best = c; bestGain = gain; bestDist = d }
        }
        if (best == null) break
        poles += best
        markOccupied(best, spec, occupied)
        coverLists.getValue(best).forEach { covered[it] = true }
    }
    val uncovered = needs.indices.filter { !covered[it] }.map { needs[it] }

    // Connect: add bridge poles until one component, then take the MST.
    var guard = 0
    while (guard++ < 200) {
        val comps = components(poles, spec)
        if ((comps.maxOrNull() ?: 0) == 0) break
        // Nearest pair of poles in different components.
        var bi = -1; var bj = -1; var bd = Double.MAX_VALUE
        for (i in poles.indices) for (j in poles.indices) {
            if (comps[i] == comps[j] || j <= i) continue
            val (ax, ay) = center(spec, poles[i]); val (bx, by) = center(spec, poles[j])
            val d = hypot(ax - bx, ay - by)
            if (d < bd) { bd = d; bi = i; bj = j }
        }
        val bridge = bridgePole(poles[bi], poles[bj], spec, cands.filter { fits(it) }) ?: break
        poles += bridge
        markOccupied(bridge, spec, occupied)
    }
    val disconnected = (components(poles, spec).maxOrNull() ?: 0) > 0
    val wires = mst(poles, spec)
    val placed = poles.map { PlacedEntity(poleName, Kind.POLE, it.x, it.y, w = spec.size, h = spec.size) }
    return PowerResult(uncovered.isEmpty() && !disconnected, placed, wires, uncovered, disconnected)
}

private fun markOccupied(t: Tile, spec: GameConstants.PoleSpec, occ: MutableSet<Tile>) {
    for (dy in 0 until spec.size) for (dx in 0 until spec.size) occ += Tile(t.x + dx, t.y + dy)
}

private fun reach(spec: GameConstants.PoleSpec, a: Tile, b: Tile): Boolean {
    val (ax, ay) = center(spec, a); val (bx, by) = center(spec, b)
    return hypot(ax - bx, ay - by) <= spec.wireReach + 1e-9
}

/** Component label per pole under wire reach. */
private fun components(poles: List<Tile>, spec: GameConstants.PoleSpec): IntArray {
    val comp = IntArray(poles.size) { -1 }
    var c = 0
    for (s in poles.indices) {
        if (comp[s] >= 0) continue
        val stack = ArrayDeque(listOf(s))
        comp[s] = c
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            for (j in poles.indices) if (comp[j] < 0 && reach(spec, poles[i], poles[j])) { comp[j] = c; stack += j }
        }
        c++
    }
    return comp
}

/** The free candidate nearest the point one wire reach from [a] towards [b]. */
private fun bridgePole(a: Tile, b: Tile, spec: GameConstants.PoleSpec, cands: List<Tile>): Tile? {
    val (ax, ay) = center(spec, a); val (bx, by) = center(spec, b)
    val d = hypot(bx - ax, by - ay)
    val step = minOf(d / 2, spec.wireReach * 0.9)
    val tx = ax + (bx - ax) / d * step
    val ty = ay + (by - ay) / d * step
    return cands.filter { reach(spec, a, it) }
        .minByOrNull { val (cx, cy) = center(spec, it); hypot(cx - tx, cy - ty) + 0.5 * hypot(cx - bx, cy - by) }
}

/** Prim's minimum spanning tree over poles within wire reach. */
private fun mst(poles: List<Tile>, spec: GameConstants.PoleSpec): List<Pair<Int, Int>> {
    if (poles.size < 2) return emptyList()
    val inTree = BooleanArray(poles.size)
    val best = DoubleArray(poles.size) { Double.MAX_VALUE }
    val from = IntArray(poles.size) { -1 }
    val edges = ArrayList<Pair<Int, Int>>()
    // Run Prim from every unreached pole so disconnected parts still get their own trees.
    for (root in poles.indices) {
        if (inTree[root]) continue
        best[root] = 0.0
        while (true) {
            var u = -1
            for (i in poles.indices) if (!inTree[i] && best[i] < Double.MAX_VALUE && (u < 0 || best[i] < best[u])) u = i
            if (u < 0) break
            inTree[u] = true
            if (from[u] >= 0) edges += from[u] to u
            for (v in poles.indices) {
                if (inTree[v] || !reach(spec, poles[u], poles[v])) continue
                val (ux, uy) = center(spec, poles[u]); val (vx, vy) = center(spec, poles[v])
                val d = hypot(ux - vx, uy - vy)
                if (d < best[v]) { best[v] = d; from[v] = u }
            }
        }
    }
    return edges
}

/**
 * P2: pole positions on a fixed lattice, reserved as obstacles before placement. Medium poles every 7
 * tiles (7x7 supply), substations every 18; the lattice is offset so supply areas tile the box.
 */
fun poleLattice(w: Int, h: Int, poleName: String): List<Tile> {
    val spec = GameConstants.pole(poleName)
    val pitch = (2 * spec.supplyHalf).toInt().coerceAtMost(spec.wireReach.toInt())
    val off = (pitch - spec.size) / 2
    val out = ArrayList<Tile>()
    var y = off
    while (y < h + pitch / 2) {
        var x = off
        while (x < w + pitch / 2) {
            out += Tile(minOf(x, w - spec.size), minOf(y, h - spec.size))
            x += pitch
        }
        y += pitch
    }
    return out.distinct()
}

/** Free candidate tiles for poles: the box, plus a 1-tile ring around it when allowed, plus [extra] tiles. */
fun poleCandidates(w: Int, h: Int, ring: Boolean, extra: Collection<Tile> = emptyList()): List<Tile> {
    val r = if (ring) 1 else 0
    val out = ArrayList<Tile>()
    for (y in -r until h + r) for (x in -r until w + r) out += Tile(x, y)
    out += extra
    return out
}
