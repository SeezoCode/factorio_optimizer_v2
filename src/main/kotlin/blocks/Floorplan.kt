package blocks

import geom.Dir
import geom.Rect
import geom.Tile
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One way a block can be built: its box, where its ports are (pin id -> tile relative to the box's corner), and how
 * many clockwise quarter turns [rot] it is from the block as searched (its padding and edges turn with it).
 */
class FpShape(val w: Int, val h: Int, val pins: Map<Int, Tile> = emptyMap(), val rot: Int = 0)

/**
 * A rectangle to place: its shapes to choose from, the free tiles it keeps on each side for corridors ([pad],
 * indexed by [Dir.ordinal]), and the factory edges it must touch (ports that leave the factory straight out of the
 * block, a reserved area's corner). Padding and edges are given for the block as searched; a turned shape turns them.
 */
class FpBlock(val shapes: List<FpShape>, val pad: IntArray, val touch: Set<Dir> = emptySet()) {
    init { require(pad.size == 4 && shapes.isNotEmpty()) }
}

/**
 * What connects blocks: pins (block, pin id; a pin id the shape does not list is the block's centre), and the
 * factory edges the net must reach (raw inputs and products; empty for a net between blocks). Its length is the
 * half-perimeter of its pins plus the distance from them to the nearest of those edges.
 */
class FpNet(val pins: List<Pair<Int, Int>>, val edges: Set<Dir> = emptySet(), val weight: Double = 1.0)

class FpParams(
    /** Target width / height of the factory. */
    val aspect: Double = 1.0,
    /** How much a factory off that aspect costs: area x weight x (ratio - 1)^2. */
    val aspectWeight: Double = 0.5,
    /** Price of one tile of net length, in tiles of area. */
    val wireWeight: Double = 2.0,
    val seed: Long = 1L,
    val restarts: Int = 4,
    val movesPerBlock: Int = 3000,
)

/** A sequence pair (two orders of the blocks) and a shape per block: everything that decides a packing. */
class FpState(val plus: IntArray, val minus: IntArray, val shape: IntArray) {
    fun copy() = FpState(plus.clone(), minus.clone(), shape.clone())
}

/**
 * A packing: each block's box (without its padding), the factory size, the cost, and which factory edges each block
 * touches. [violations] counts touch constraints the sequence pair cannot meet.
 */
class FpPlan(
    val state: FpState, val boxes: List<Rect>, val w: Int, val h: Int, val cost: Double, val wire: Double,
    val violations: Int, val touches: List<Set<Dir>>,
) {
    val area get() = w * h
}

/**
 * Floorplanning by simulated annealing over sequence pairs (Murata et al.): any arrangement of rectangles, not just
 * rows. Two orders of the blocks say for every pair which lies left of or above the other (before in both orders:
 * left; before in the first and after in the second: above); the packing pushes every block as far up and left as
 * those relations allow, padding included, then slides blocks that must touch the east or south edge across (a
 * block with nothing to its right can always slide right). The cost is the factory's area, a penalty for straying
 * from the target aspect, the weighted net length, and a large penalty per edge a block must but cannot touch.
 */
class Floorplanner(val blocks: List<FpBlock>, val nets: List<FpNet>, val params: FpParams = FpParams()) {
    private val k = blocks.size
    private val bigPenalty = 10.0 * blocks.sumOf { b -> b.shapes.maxOf { (it.w + b.pad[1] + b.pad[3]).toDouble() * (it.h + b.pad[0] + b.pad[2]) } } + 1000.0

    fun pack(s: FpState, padOverride: List<IntArray>? = null): FpPlan {
        val pp = IntArray(k); val pm = IntArray(k)
        s.plus.forEachIndexed { i, b -> pp[b] = i }
        s.minus.forEachIndexed { i, b -> pm[b] = i }
        val pad0 = padOverride ?: blocks.map { it.pad }
        // Padding on each factory side: the side of the block as searched that a turned shape puts there.
        val pad = (0 until k).map { b -> val r = blocks[b].shapes[s.shape[b]].rot; IntArray(4) { d -> pad0[b][(d - r).mod(4)] } }
        val bw = IntArray(k) { b -> blocks[b].shapes[s.shape[b]].w + pad[b][Dir.E.ordinal] + pad[b][Dir.W.ordinal] }
        val bh = IntArray(k) { b -> blocks[b].shapes[s.shape[b]].h + pad[b][Dir.N.ordinal] + pad[b][Dir.S.ordinal] }
        val x = IntArray(k); val y = IntArray(k)
        for (i in 0 until k) {
            val b = s.plus[i]
            for (j in 0 until i) {
                val a = s.plus[j]
                if (pm[a] < pm[b]) x[b] = max(x[b], x[a] + bw[a]) else y[b] = max(y[b], y[a] + bh[a])
            }
        }
        var w = 0; var h = 0
        for (b in 0 until k) { w = max(w, x[b] + bw[b]); h = max(h, y[b] + bh[b]) }
        // Touches: nothing before it on that side (by the relations, so sizes do not matter); east and south slide.
        val touches = ArrayList<Set<Dir>>(k)
        var violations = 0
        for (b in 0 until k) {
            var left = false; var right = false; var above = false; var below = false
            for (a in 0 until k) {
                if (a == b) continue
                if (pp[a] < pp[b] && pm[a] < pm[b]) left = true
                if (pp[a] > pp[b] && pm[a] > pm[b]) right = true
                if (pp[a] < pp[b] && pm[a] > pm[b]) above = true
                if (pp[a] > pp[b] && pm[a] < pm[b]) below = true
            }
            val t = HashSet<Dir>()
            if (!left) t += Dir.W
            if (!above) t += Dir.N
            if (!right) t += Dir.E
            if (!below) t += Dir.S
            val need = blocks[b].touch.map { it.turned(blocks[b].shapes[s.shape[b]].rot) }
            if (Dir.E in need && !right) x[b] = w - bw[b]
            if (Dir.S in need && !below) y[b] = h - bh[b]
            violations += need.count { it !in t }
            touches += t
        }
        val boxes = (0 until k).map { b ->
            val sh = blocks[b].shapes[s.shape[b]]
            Rect(x[b] + pad[b][Dir.W.ordinal], y[b] + pad[b][Dir.N.ordinal], sh.w, sh.h)
        }
        val wire = wireLength(boxes, s, w, h)
        val r = (w.toDouble() / max(1, h)) / params.aspect
        val dev = max(r, 1 / r) - 1
        val cost = w.toDouble() * h * (1 + params.aspectWeight * dev * dev) + params.wireWeight * wire + bigPenalty * violations
        return FpPlan(s, boxes, w, h, cost, wire, violations, touches)
    }

    /** Where pin [pin] of block [b] lies in the factory (its centre if the shape does not list it). */
    fun pinTile(boxes: List<Rect>, s: FpState, b: Int, pin: Int): Tile {
        val box = boxes[b]
        val p = blocks[b].shapes[s.shape[b]].pins[pin] ?: return Tile(box.x + box.w / 2, box.y + box.h / 2)
        return Tile(box.x + p.x, box.y + p.y)
    }

    private fun wireLength(boxes: List<Rect>, s: FpState, w: Int, h: Int): Double {
        var total = 0.0
        for (net in nets) {
            if (net.pins.isEmpty()) continue
            var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
            for ((b, pin) in net.pins) {
                val t = pinTile(boxes, s, b, pin)
                x0 = min(x0, t.x); x1 = max(x1, t.x); y0 = min(y0, t.y); y1 = max(y1, t.y)
            }
            var len = (x1 - x0 + y1 - y0).toDouble()
            if (net.edges.isNotEmpty()) len += net.edges.minOf { e ->
                when (e) { Dir.N -> y0; Dir.S -> h - 1 - y1; Dir.W -> x0; Dir.E -> w - 1 - x1 }.coerceAtLeast(0)
            }
            total += net.weight * len
        }
        return total
    }

    /** The best packing found from [start] (if given) and from a row, a column and random starts. */
    fun optimise(start: FpState? = null): FpPlan {
        if (k == 0) return FpPlan(FpState(IntArray(0), IntArray(0), IntArray(0)), emptyList(), 0, 0, 0.0, 0.0, 0, emptyList())
        val rnd = SplittableRandom(params.seed)
        val id = IntArray(k) { it }
        val square = IntArray(k) { b -> blocks[b].shapes.indices.minBy { val s = blocks[b].shapes[it]; abs(ln(s.w.toDouble() / s.h)) } }
        val starts = ArrayList<FpState>()
        start?.let { starts += it.copy() }
        starts += gridStart(square)
        starts += FpState(id.clone(), id.clone(), square.clone())                       // one row
        starts += FpState(id.clone(), id.reversedArray(), square.clone())               // one column
        while (starts.size < params.restarts + (if (start != null) 1 else 0)) {
            starts += FpState(shuffled(id, rnd), shuffled(id, rnd), IntArray(k) { rnd.nextInt(blocks[it].shapes.size) })
        }
        var best: FpPlan? = null
        for (s in starts) {
            val p = anneal(s, rnd)
            if (best == null || p.cost < best.cost) best = p
        }
        return best!!
    }

    /** Blocks in about sqrt(k) rows, in the given order (snaking keeps neighbours in order side by side). */
    private fun gridStart(shape: IntArray): FpState {
        val cols = max(1, sqrt(k.toDouble()).roundToInt())
        val rows = (k + cols - 1) / cols
        // Rows top to bottom, columns left to right; as a sequence pair: plus by (row, col), minus by (-row, col).
        val cells = (0 until k).map { i -> val r = i / cols; val c = if (r % 2 == 0) i % cols else cols - 1 - i % cols; Triple(i, r, c) }
        val plus = cells.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }.toIntArray()
        val minus = cells.sortedWith(compareBy({ rows - it.second }, { it.third })).map { it.first }.toIntArray()
        return FpState(plus, minus, shape.clone())
    }

    private fun shuffled(a: IntArray, rnd: SplittableRandom): IntArray {
        val r = a.clone()
        for (i in r.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = r[i]; r[i] = r[j]; r[j] = t }
        return r
    }

    private fun neighbour(s: FpState, rnd: SplittableRandom): FpState {
        val n = s.copy()
        fun swap(a: IntArray) { if (k < 2) return; val i = rnd.nextInt(k); var j = rnd.nextInt(k - 1); if (j >= i) j++; val t = a[i]; a[i] = a[j]; a[j] = t }
        fun shift(a: IntArray) {
            if (k < 2) return
            val i = rnd.nextInt(k); val j = rnd.nextInt(k)
            val v = a[i]
            if (i < j) System.arraycopy(a, i + 1, a, i, j - i) else System.arraycopy(a, j, a, j + 1, i - j)
            a[j] = v
        }
        when (rnd.nextInt(9)) {
            0, 1 -> swap(n.plus)
            2, 3 -> swap(n.minus)
            4 -> {   // swap two blocks in both orders: they trade places
                if (k >= 2) {
                    val a = rnd.nextInt(k); var b = rnd.nextInt(k - 1); if (b >= a) b++
                    val ia = n.plus.indexOf(a); val ib = n.plus.indexOf(b); n.plus[ia] = b; n.plus[ib] = a
                    val ja = n.minus.indexOf(a); val jb = n.minus.indexOf(b); n.minus[ja] = b; n.minus[jb] = a
                }
            }
            5 -> shift(n.plus)
            6 -> shift(n.minus)
            else -> {
                val b = rnd.nextInt(k)
                val m = blocks[b].shapes.size
                if (m > 1) { var c = rnd.nextInt(m - 1); if (c >= n.shape[b]) c++; n.shape[b] = c } else swap(n.plus)
            }
        }
        return n
    }

    private fun anneal(start: FpState, rnd: SplittableRandom): FpPlan {
        var cur = pack(start)
        var best = cur
        if (k < 2 && blocks.all { it.shapes.size < 2 }) return cur
        var up = 0.0; var ups = 0
        repeat(100) { val d = pack(neighbour(cur.state, rnd)).cost - cur.cost; if (d > 0 && d < bigPenalty) { up += d; ups++ } }
        val t0 = if (ups > 0) up / ups else 1.0
        val steps = params.movesPerBlock * k
        val alpha = exp(ln(1e-3) / steps)
        var t = t0
        repeat(steps) {
            val nxt = pack(neighbour(cur.state, rnd))
            val d = nxt.cost - cur.cost
            if (d <= 0 || rnd.nextDouble() < exp(-d / t)) {
                cur = nxt
                if (cur.cost < best.cost) best = cur
            }
            t *= alpha
        }
        return best
    }
}

/** Shapes of about [area] tiles at aspects (width / height) 1:2.5 to 2.5:1, for a block not built yet. */
fun softShapes(area: Double): List<FpShape> =
    listOf(0.4, 0.5, 0.67, 1.0, 1.5, 2.0, 2.5).map { a ->
        val w = max(3, sqrt(area * a).roundToInt()); FpShape(w, max(3, (area / w).roundToInt()))
    }
