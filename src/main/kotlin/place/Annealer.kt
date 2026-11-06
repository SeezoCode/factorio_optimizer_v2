package place

import config.PlaceTuning
import plan.ProductionPlan
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class AnnealStats(val initialCost: Double, val finalCost: Double, val t0: Double, val moves: Long, val temperatures: Int)

/**
 * Stage 2: simulated annealing over machine positions (VPR schedule). Every random choice comes from [rnd].
 */
class Annealer(val model: PlaceModel, private val rnd: SplittableRandom, private val tun: PlaceTuning) {
    private val nM = model.nM
    private val ms1 = IntArray(1); private val ms2 = IntArray(2)
    private val nx = IntArray(nM); private val ny = IntArray(nM); private val nr = IntArray(nM)
    private val ox = IntArray(nM); private val oy = IntArray(nM); private val or = IntArray(nM)
    private var moveSize = 0
    /** Only moves that keep columns (set by a column start with [PlaceTuning.columnMovesOnly]). */
    var structured = false
        private set
    /** Machines of a column move (whole columns shift or swap together); empty for single-machine moves. */
    private var msN = NONE
    /** Column of each machine from a column start (-1: none); a swap trades the two machines' columns. */
    private val colOf = IntArray(nM) { -1 }
    private var swapCols = false
    private val fluidMachines = (0 until nM).filter { model.fluid[it] }.toIntArray()
    private val canSwap = (0 until nM).any { a -> (0 until nM).any { b -> model.plan.machines[a].recipe != model.plan.machines[b].recipe } }
    var lastT0 = 0.0
        private set

    // ---------------- direct-insertion clusters (see Clusters.kt) ----------------
    /** Cluster of each machine (-1: none); a clustered machine only moves with its whole cluster. */
    private val groupOf = IntArray(nM) { -1 }
    private var groups: List<Cluster> = emptyList()

    /** Machines of each cluster move and turn as one from now on; the next initial placement places them whole. */
    fun setClusters(cs: List<Cluster>) {
        groups = cs
        groupOf.fill(-1)
        cs.forEachIndexed { g, c -> c.members.forEach { groupOf[it] = g } }
    }

    /** Places cluster [g] with its top-left at ([x], [y]) during an initial placement; false if it does not fit there. */
    private fun placeGroup(g: Int, x: Int, y: Int): Boolean {
        val c = groups[g]
        val xs = IntArray(c.members.size) { x + c.dx[it] }; val ys = IntArray(c.members.size) { y + c.dy[it] }
        if (!model.legal(c.members, xs, ys, IntArray(c.members.size))) return false
        c.members.forEachIndexed { i, m -> model.placeOne(m, xs[i], ys[i], 0) }
        return true
    }

    // ---------------- initial placement ----------------
    /** Random legal positions; false if some machine finds no spot. */
    fun initialRandom(tries: Int = 500): Boolean {
        model.clear()
        structured = false
        colOf.fill(-1)
        // Clusters first, each whole, then the other machines one by one.
        for (g in groups.indices) {
            val c = groups[g]
            val gw = c.members.indices.maxOf { c.dx[it] + model.mw[c.members[it]] }
            val gh = c.members.indices.maxOf { c.dy[it] + model.mh[c.members[it]] }
            var ok = false
            for (t in 0 until tries) {
                if (placeGroup(g, rnd.nextInt(0, max(1, model.w - gw + 1)), rnd.nextInt(0, max(1, model.h - gh + 1)))) { ok = true; break }
            }
            if (!ok) return false
        }
        val order = (0 until nM).filter { groupOf[it] < 0 }.toMutableList().also { shuffle(it) }
        for (m in order) {
            var ok = false
            for (t in 0 until tries) {
                val x = rnd.nextInt(0, max(1, model.w - model.mw[m] + 1))
                val y = rnd.nextInt(0, max(1, model.h - model.mh[m] + 1))
                val r = if (model.fluid[m]) rnd.nextInt(8) else 0
                if (model.legal(intArrayOf(m), intArrayOf(x), intArrayOf(y), intArrayOf(r))) { model.placeOne(m, x, y, r); ok = true; break }
            }
            if (!ok) return false
        }
        model.finishPlacing()
        return true
    }

    /** Row by row in topological order of the recipe graph, one free tile between machines. */
    fun initialRows(plan: ProductionPlan): Boolean {
        model.clear()
        structured = false
        colOf.fill(-1)
        val depth = recipeDepth(plan)
        val order = (0 until nM).sortedWith(compareBy({ depth[plan.machines[it].product] ?: 0 }, { it }))
        var x = 0; var y = 0; var rowH = 0
        for (m in order) {
            var placedHere = false
            while (!placedHere) {
                if (x + model.mw[m] > model.w) { x = 0; y += rowH + 1; rowH = 0 }
                if (y + model.mh[m] > model.h) return false
                for (r in 0..7) {
                    if (!model.fluid[m] && r > 0) break
                    if (model.legal(intArrayOf(m), intArrayOf(x), intArrayOf(y), intArrayOf(r))) {
                        model.placeOne(m, x, y, r); placedHere = true; break
                    }
                }
                if (placedHere) { rowH = max(rowH, model.mh[m] + if (model.fluid[m]) 1 else 0); x += model.mw[m] + 1 } else x++
            }
        }
        model.finishPlacing()
        return true
    }

    /**
     * Columns: machines grouped by recipe (upstream recipes first), stacked from the top of the box with
     * [PlaceTuning.columnSpacing] free tiles between them, a new column when the box height is used up (a recipe
     * never shares a column with one of another machine width).
     * The corridor before a column is wide enough for an inserter on each side and about half the belts of the
     * columns on both sides (each machine draws its items from two sides), and at least
     * [PlaceTuning.columnGap]. A machine that does not fit (fluid stubs, obstacles) slides down. False if the
     * columns run out of width or height.
     */
    fun initialColumns(plan: ProductionPlan): Boolean =
        // Tighter and tighter until it fits: as configured, then without lanes, then narrow corridors with machines
        // of different widths sharing a column.
        tryColumns(plan, tun.columnSpacing, itemCorridors = true, mixWidths = false) ||
            (tun.columnSpacing > 0 && tryColumns(plan, 0, itemCorridors = true, mixWidths = false)) ||
            tryColumns(plan, 0, itemCorridors = false, mixWidths = true)

    private fun tryColumns(plan: ProductionPlan, spacing: Int, itemCorridors: Boolean, mixWidths: Boolean): Boolean {
        model.clear()
        structured = false
        colOf.fill(-1)
        val depth = recipeDepth(plan)
        val groups = (0 until nM).groupBy { plan.machines[it].recipe }.values
            .sortedWith(compareBy({ depth[plan.machines[it[0]].product] ?: 0 }, { -it.size }, { plan.machines[it[0]].recipe }))
        // Items each machine moves by inserter (inputs, fuel and item products).
        val items = IntArray(nM) { m -> plan.machines[m].let { it.demand.size + it.itemProducts.size } }
        // 1. Columns by height (a fluid machine counts a spare tile for its pipe stubs).
        val cols = ArrayList<MutableList<Int>>()
        var used = model.h
        var colW = 0
        for (g in groups) for (m in g) {
            val need = model.mh[m] + spacing + if (model.fluid[m]) 1 else 0
            if (used + need > model.h || (!mixWidths && model.mw[m] != colW)) { cols += mutableListOf<Int>(); used = 0; colW = model.mw[m] }
            cols.last() += m; used += need
        }
        // 2. Corridors and placement.
        val busy = cols.map { c -> c.maxOf { items[it] } }
        fun corridor(i: Int): Int {
            if (!itemCorridors) return tun.columnGap
            val left = if (i > 0) busy[i - 1] else 0
            return max(tun.columnGap, 1 + (left + busy[i] + 1) / 2)
        }
        var x = 0
        for ((i, c) in cols.withIndex()) {
            c.forEach { colOf[it] = i }
            x += corridor(i)
            var y = 0
            for (m in c) {
                var placedHere = false
                while (!placedHere) {
                    if (x + model.mw[m] > model.w || y + model.mh[m] > model.h) { model.clear(); colOf.fill(-1); return false }
                    for (r in 0..7) {
                        if (!model.fluid[m] && r > 0) break
                        if (model.legal(intArrayOf(m), intArrayOf(x), intArrayOf(y), intArrayOf(r))) {
                            model.placeOne(m, x, y, r); placedHere = true; break
                        }
                    }
                    if (placedHere) y += model.mh[m] + spacing else y++
                }
            }
            x += c.maxOf { model.mw[it] }
        }
        model.finishPlacing()
        structured = tun.columnMovesOnly
        return true
    }

    /** Starts from [p] if it is legal for this model; false otherwise. */
    fun initialFrom(p: Placement): Boolean {
        model.clear()
        for (pm in p.machines.sortedBy { it.id }) {
            val code = pm.rot.ordinal + if (pm.mirror) 4 else 0
            if (!model.legal(intArrayOf(pm.id), intArrayOf(pm.x), intArrayOf(pm.y), intArrayOf(code))) { model.clear(); return false }
            model.placeOne(pm.id, pm.x, pm.y, code)
        }
        model.finishPlacing()
        return true
    }

    // ---------------- moves ----------------
    /** Proposes a legal move into the scratch arrays; false if the proposal is illegal. */
    private fun propose(range: Int): Boolean {
        msN = NONE
        swapCols = false
        if (structured) {
            val u = rnd.nextDouble()
            return when {
                u < 0.3 -> shiftColumn(range)
                u < 0.4 -> swapColumns()
                u < 0.7 && canSwap -> if (tun.columnSwapAcross) swap() else swapInColumn()
                u < 0.9 || fluidMachines.isEmpty() -> slide(range)
                else -> rotate()
            }
        }
        if (tun.columnShare > 0 && groups.isEmpty() && rnd.nextDouble() < tun.columnShare) return if (rnd.nextBoolean()) shiftColumn(range) else swapColumns()
        if (tun.diSnapShare > 0 && model.diPairCount > 0 && rnd.nextDouble() < tun.diSnapShare) return snap()
        val u = rnd.nextDouble()
        return when {
            u < tun.shiftShare || (!canSwap && fluidMachines.isEmpty()) -> shift(range)
            u < tun.shiftShare + tun.swapShare && canSwap -> swap()
            fluidMachines.isNotEmpty() -> rotate()
            else -> shift(range)
        }
    }

    private fun shift(range: Int): Boolean {
        val m = rnd.nextInt(nM)
        var dx: Int; var dy: Int
        do { dx = rnd.nextInt(-range, range + 1); dy = rnd.nextInt(-range, range + 1) } while (dx == 0 && dy == 0)
        if (groupOf[m] >= 0) return if (rnd.nextInt(5) == 0) turnGroup(groupOf[m]) else shiftGroup(groupOf[m], dx, dy)
        moveSize = 1; ms1[0] = m
        nx[0] = model.px[m] + dx; ny[0] = model.py[m] + dy; nr[0] = model.rot[m]
        return model.legal(ms1, nx, ny, nr)
    }

    /**
     * One machine of a direct-insertion pair that does not fit yet goes right beside the other: on a random side, a
     * 1-tile gap (or 3 for long-handed inserters), overlapping the partner's edge by at least one tile.
     */
    private fun snap(): Boolean {
        var i = rnd.nextInt(model.diPairCount)
        var guard = 0
        while (model.diFits(i) && guard++ < 8) i = rnd.nextInt(model.diPairCount)
        if (model.diFits(i)) return false
        val moveConsumer = rnd.nextBoolean()
        val m = if (moveConsumer) model.diConsumer(i) else model.diProducer(i)
        val p = if (moveConsumer) model.diProducer(i) else model.diConsumer(i)
        val gap = if (model.diLong && rnd.nextInt(4) == 0) 3 else 1
        val pw = model.mw[p]; val ph = model.mh[p]; val w = model.mw[m]; val h = model.mh[m]
        val x: Int; val y: Int
        when (rnd.nextInt(4)) {
            0 -> { x = model.px[p] - gap - w; y = model.py[p] - h + 1 + rnd.nextInt(ph + h - 1) }
            1 -> { x = model.px[p] + pw + gap; y = model.py[p] - h + 1 + rnd.nextInt(ph + h - 1) }
            2 -> { y = model.py[p] - gap - h; x = model.px[p] - w + 1 + rnd.nextInt(pw + w - 1) }
            else -> { y = model.py[p] + ph + gap; x = model.px[p] - w + 1 + rnd.nextInt(pw + w - 1) }
        }
        if (groupOf[m] >= 0) return shiftGroup(groupOf[m], x - model.px[m], y - model.py[m])
        moveSize = 1; ms1[0] = m
        nx[0] = x; ny[0] = y; nr[0] = model.rot[m]
        return model.legal(ms1, nx, ny, nr)
    }

    /** Cluster [g] moves by ([dx], [dy]). */
    private fun shiftGroup(g: Int, dx: Int, dy: Int): Boolean {
        val ms = groups[g].members
        msN = ms; moveSize = ms.size
        ms.forEachIndexed { i, m -> nx[i] = model.px[m] + dx; ny[i] = model.py[m] + dy; nr[i] = model.rot[m] }
        return model.legal(ms, nx, ny, nr)
    }

    /** Cluster [g] turns a quarter clockwise within its bounding box's top-left corner (its machines are square). */
    private fun turnGroup(g: Int): Boolean {
        val ms = groups[g].members
        val x0 = ms.minOf { model.px[it] }; val y0 = ms.minOf { model.py[it] }
        val gh = ms.maxOf { model.py[it] + model.mh[it] } - y0
        msN = ms; moveSize = ms.size
        ms.forEachIndexed { i, m ->
            val rx = model.px[m] - x0; val ry = model.py[m] - y0
            nx[i] = x0 + gh - ry - model.mh[m]; ny[i] = y0 + rx; nr[i] = model.rot[m]
        }
        return model.legal(ms, nx, ny, nr)
    }

    private fun swap(): Boolean {
        val a = rnd.nextInt(nM)
        var b: Int
        var guard = 0
        do { b = rnd.nextInt(nM); guard++ } while ((b == a || model.plan.machines[a].recipe == model.plan.machines[b].recipe) && guard < 50)
        if (b == a || model.plan.machines[a].recipe == model.plan.machines[b].recipe) return false
        if (groupOf[a] >= 0 || groupOf[b] >= 0) return shift(4)
        moveSize = 2; ms2[0] = a; ms2[1] = b
        swapCols = colOf[a] != colOf[b]
        nx[0] = model.px[b]; ny[0] = model.py[b]; nr[0] = model.rot[a]
        nx[1] = model.px[a]; ny[1] = model.py[a]; nr[1] = model.rot[b]
        return model.legal(ms2, nx, ny, nr)
    }

    /**
     * The column machine [m] stands in: its column from the column start, else the machines with the same left
     * edge and width whose vertical extents chain together with at most one free tile between neighbours.
     */
    private fun columnOf(m: Int): IntArray {
        if (colOf[m] >= 0) return (0 until nM).filter { colOf[it] == colOf[m] }.toIntArray()
        val x = model.px[m]; val wd = model.mw[m]
        val same = (0 until nM).filter { model.px[it] == x && model.mw[it] == wd }.sortedBy { model.py[it] }
        val i = same.indexOf(m)
        var lo = i; var hi = i
        while (lo > 0 && model.py[same[lo]] - (model.py[same[lo - 1]] + model.mh[same[lo - 1]]) <= 1) lo--
        while (hi < same.size - 1 && model.py[same[hi + 1]] - (model.py[same[hi]] + model.mh[same[hi]]) <= 1) hi++
        return same.subList(lo, hi + 1).toIntArray()
    }

    private fun shiftColumn(range: Int): Boolean {
        val col = columnOf(rnd.nextInt(nM))
        var dx: Int; var dy: Int
        do { dx = rnd.nextInt(-range, range + 1); dy = rnd.nextInt(-1, 2) } while (dx == 0 && dy == 0)
        msN = col; moveSize = col.size
        col.forEachIndexed { i, m -> nx[i] = model.px[m] + dx; ny[i] = model.py[m] + dy; nr[i] = model.rot[m] }
        return model.legal(col, nx, ny, nr)
    }

    /** Two columns of equal width trade places (each keeps its own top). */
    private fun swapColumns(): Boolean {
        val a = columnOf(rnd.nextInt(nM))
        val b = columnOf(rnd.nextInt(nM))
        if (a.contentEquals(b) || model.mw[a[0]] != model.mw[b[0]] || a.any { it in b }) return false
        val ax = a.minOf { model.px[it] }; val bx = b.minOf { model.px[it] }
        val ay = a.minOf { model.py[it] }; val by = b.minOf { model.py[it] }
        val both = a + b
        msN = both; moveSize = both.size
        a.forEachIndexed { i, m -> nx[i] = model.px[m] - ax + bx; ny[i] = model.py[m] - ay + by; nr[i] = model.rot[m] }
        b.forEachIndexed { j, m -> val i = a.size + j; nx[i] = model.px[m] - bx + ax; ny[i] = model.py[m] - by + ay; nr[i] = model.rot[m] }
        return model.legal(both, nx, ny, nr)
    }

    /** Two machines of one column trade places (the column keeps its recipes, their order changes). */
    private fun swapInColumn(): Boolean {
        val a = rnd.nextInt(nM)
        if (colOf[a] < 0) return swap()
        val mates = (0 until nM).filter { it != a && colOf[it] == colOf[a] && model.plan.machines[it].recipe != model.plan.machines[a].recipe }
        if (mates.isEmpty()) return false
        val b = mates[rnd.nextInt(mates.size)]
        moveSize = 2; ms2[0] = a; ms2[1] = b
        nx[0] = model.px[b]; ny[0] = model.py[b]; nr[0] = model.rot[a]
        nx[1] = model.px[a]; ny[1] = model.py[a]; nr[1] = model.rot[b]
        return model.legal(ms2, nx, ny, nr)
    }

    /** One machine up or down its column. */
    private fun slide(range: Int): Boolean {
        val m = rnd.nextInt(nM)
        var dy: Int
        do { dy = rnd.nextInt(-range, range + 1) } while (dy == 0)
        moveSize = 1; ms1[0] = m
        nx[0] = model.px[m]; ny[0] = model.py[m] + dy; nr[0] = model.rot[m]
        return model.legal(ms1, nx, ny, nr)
    }

    private fun rotate(): Boolean {
        val m = fluidMachines[rnd.nextInt(fluidMachines.size)]
        moveSize = 1; ms1[0] = m
        nx[0] = model.px[m]; ny[0] = model.py[m]; nr[0] = (model.rot[m] + 1 + rnd.nextInt(7)) % 8
        return model.legal(ms1, nx, ny, nr)
    }

    private fun moving() = when { msN.isNotEmpty() -> msN; moveSize == 1 -> ms1; else -> ms2 }

    private fun apply() {
        val ms = moving()
        for (i in 0 until moveSize) { ox[i] = model.px[ms[i]]; oy[i] = model.py[ms[i]]; or[i] = model.rot[ms[i]] }
        model.move(ms, nx, ny, nr)
        if (swapCols) tradeColumns()
    }

    private fun revert() {
        val ms = moving()
        model.move(ms, ox, oy, or)
        if (swapCols) tradeColumns()
    }

    private fun tradeColumns() { val t = colOf[ms2[0]]; colOf[ms2[0]] = colOf[ms2[1]]; colOf[ms2[1]] = t }

    // ---------------- schedule ----------------
    /**
     * Anneals from the current placement. [t0] null = 20 x std of the cost change over N random moves
     * (accepted unconditionally). Returns stats; the model ends at the best placement seen.
     */
    fun anneal(
        t0: Double? = null, maxMoves: Long = tun.maxMoves, startRange: Int? = null,
        /** Refine the current placement: the sampled starting temperature is scaled by [t0Scale] and the
         *  sampling moves are taken back, so a structured start (columns) is kept. */
        refine: Boolean = false, t0Scale: Double = 1.0,
    ): AnnealStats {
        val initial = model.cost
        var moves = 0L
        val maxR = max(model.w, model.h)
        var range = (startRange ?: maxR).coerceIn(1, maxR)
        var temp = t0 ?: run {
            val deltas = ArrayList<Double>()
            var guard = 0
            while (deltas.size < max(10, nM) && guard++ < 100 * max(10, nM)) {
                if (!propose(if (refine) range else maxR)) continue
                val before = model.cost
                apply(); moves++
                deltas += model.cost - before
                if (refine) revert()
            }
            val mean = deltas.average()
            val sd = sqrt(deltas.sumOf { (it - mean) * (it - mean) } / max(1, deltas.size - 1))
            tun.t0Factor * max(sd, 1e-6) * t0Scale
        }
        lastT0 = temp
        val perTemp = max(50, (tun.movesPerTempFactor * nM.toDouble().pow(4.0 / 3.0)).toInt())
        var best = model.cost
        var bestP = model.placement()
        var bestCols = colOf.copyOf()
        var temps = 0
        val nets = max(1, model.netCount())
        while (moves < maxMoves) {
            var accepted = 0; var tried = 0
            for (i in 0 until perTemp) {
                if (!propose(range)) { tried++; continue }
                tried++
                val before = model.cost
                apply(); moves++
                val d = model.cost - before
                if (d <= 0 || rnd.nextDouble() < exp(-d / temp)) {
                    accepted++
                    if (model.cost < best - 1e-9) { best = model.cost; bestP = model.placement(); bestCols = colOf.copyOf() }
                } else revert()
            }
            temps++
            val a = accepted.toDouble() / max(1, tried)
            temp *= when { a > 0.96 -> 0.5; a > 0.8 -> 0.9; a > 0.15 -> 0.95; else -> 0.8 }
            range = (range * (1 - tun.targetAcceptance + a)).toInt().coerceIn(1, maxR)
            if (temp < tun.stopFactor * model.cost.coerceAtLeast(1.0) / nets) break
        }
        // Greedy quench at T = 0.
        repeat(perTemp) {
            if (!propose(1)) return@repeat
            val before = model.cost
            apply(); moves++
            if (model.cost > before) revert()
            else if (model.cost < best - 1e-9) { best = model.cost; bestP = model.placement(); bestCols = colOf.copyOf() }
        }
        if (model.cost > best + 1e-9) { initialFrom(bestP); bestCols.copyInto(colOf) }
        return AnnealStats(initial, model.cost, lastT0, moves, temps)
    }

    private fun shuffle(list: MutableList<Int>) {
        for (i in list.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = list[i]; list[i] = list[j]; list[j] = t }
    }
}

private val NONE = IntArray(0)

/** Depth of each item in the recipe graph: raw-fed items first. */
fun recipeDepth(plan: ProductionPlan): Map<String, Int> {
    val byProduct = plan.machines.groupBy { it.product }
    val memo = HashMap<String, Int>()
    fun depth(item: String, guard: Int): Int = memo[item] ?: run {
        val ms = byProduct[item] ?: return 0
        if (guard > 50) return 0
        val d = 1 + (ms.first().demand.keys.maxOfOrNull { depth(it, guard + 1) } ?: 0)
        memo[item] = d
        d
    }
    plan.machines.forEach { depth(it.product, 0) }
    return memo
}
