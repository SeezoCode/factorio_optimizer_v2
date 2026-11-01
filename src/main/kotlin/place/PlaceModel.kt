package place

import config.PlaceTuning
import config.RunConfiguration
import geom.Dir
import geom.Tile
import plan.ProductionPlan
import plan.Role
import plan.bestInserterRate
import plan.beaconNeed as needOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Placement state and the annealing cost, updated incrementally per move:
 *   C = alpha * sum_n w_n q(k_n) HPWL_n + beta * sum_bins max(0, D - S)^2 + gamma * sum_m A_m
 *       - delta * DI + beta2 * sum_bins h * D
 * Every move costs O(touched nets + touched bins), never a routing call.
 */
class PlaceModel(
    val plan: ProductionPlan,
    val config: RunConfiguration,
    val tun: PlaceTuning,
    val w: Int,
    val h: Int,
    obstacles: Set<Tile>,
    /** Fixed beacon spots (top-left; beacon lattice) machines are pulled next to, with their beacon. */
    beacons: List<Tile> = emptyList(),
    beaconSpec: plan.BeaconSpec? = null,
) {
    val nM = plan.machines.size
    val mw = IntArray(nM) { plan.machines[it].w }
    val mh = IntArray(nM) { plan.machines[it].h }
    /** Machines whose orientation matters (they have fluid connections). */
    val fluid = BooleanArray(nM) { orientable(plan.machines[it]) }
    val px = IntArray(nM)
    val py = IntArray(nM)
    /** Orientation code: rotation (quarter turns) + 4 if mirrored. */
    val rot = IntArray(nM)
    var placed = false
        private set
    /** Machines stamped into the grid (all of them once [placed]). */
    val isPlaced = BooleanArray(nM)

    /** 0 free, -1 obstacle, id + 1 for a machine's footprint. */
    val occ = IntArray(w * h)
    /** Used fluid connection tiles: how many machines put a pipe end here, and which fluid (id >= 1). */
    private val connCnt = IntArray(w * h)
    private val connFl = IntArray(w * h)
    /** Connection tiles a machine exposes but its recipe does not use: no pipe may sit there. */
    private val unusedCnt = IntArray(w * h)
    /** The tile straight out from each used connection, kept free of machines so a pipe can always reach it. */
    private val stubCnt = IntArray(w * h)
    private val fluidIds = HashMap<String, Int>()
    /** Per machine and orientation code: connection tiles relative to the top-left, as (dx, dy, fluid id or 0). */
    private val connRel: Array<Array<IntArray>>

    // ---- nets ----
    private class PortGeom(val fixed: List<Tile>, val segments: List<IntArray>)   // segment: x0, y0, x1, y1
    private val nNets: Int
    private val netMachines: Array<IntArray>
    private val netPorts: Array<List<PortGeom>>
    private val netWeight: DoubleArray
    private val machineNets: Array<IntArray>
    private val nb: IntArray            // x0, y0, x1, y1 per net
    private val netHpwl: DoubleArray
    private val netDensity: DoubleArray

    // ---- RUDY bins ----
    private val bin = tun.rudyBin
    private val bw = (w + bin - 1) / bin
    private val bh = (h + bin - 1) / bin
    private val binD = DoubleArray(bw * bh)
    private val binS = IntArray(bw * bh)
    private val binHist = DoubleArray(bw * bh)
    private val binCong = DoubleArray(bw * bh)
    private val binFb = DoubleArray(bw * bh)
    private val dirty = IntArray(bw * bh)
    private val dirtyList = IntArray(bw * bh)
    private var dirtyN = 0
    private var dirtyGen = 0

    // ---- belt-access demand ----
    private val accessNeed = DoubleArray(nM)

    // ---- pins ----
    private val pinNeed = IntArray(nM)
    private val pinPen = DoubleArray(nM)
    private val machineTerms: Array<IntArray>

    // ---- direct insertion pairs (producer, consumer) ----
    private val diA: IntArray
    private val diB: IntArray
    /** Producer and consumer terminal of each pair. */
    private val diPT: IntArray
    private val diCT: IntArray
    private val diFit: BooleanArray
    private val diGain: DoubleArray
    /** Current (possibly partial) credit of each pair: 1 when it fits, less when a tile or two off. */
    private val diScore: DoubleArray
    private val machinePairs: Array<IntArray>

    // ---- beacons: lattice spots in range of each machine size at each position, and each machine's need ----
    private val beaconNeed = IntArray(nM) { m -> if (beaconSpec == null || beacons.isEmpty()) 0 else needOf(plan.machines[m].crafts, plan.machines[m].beaconCrafts) }
    private val beaconCov: Map<Pair<Int, Int>, IntArray> = if (beaconNeed.all { it == 0 }) emptyMap() else
        (0 until nM).filter { beaconNeed[it] > 0 }.map { mw[it] to mh[it] }.distinct().associateWith { (sw, sh) ->
            // Difference array over machine top-left positions in range of each beacon.
            val spec = beaconSpec!!
            val d = IntArray((w + 1) * (h + 1))
            beacons.forEach { b ->
                val x0 = max(0, b.x - spec.supply - sw + 1); val x1 = min(w, b.x + spec.size + spec.supply)
                val y0 = max(0, b.y - spec.supply - sh + 1); val y1 = min(h, b.y + spec.size + spec.supply)
                if (x0 < x1 && y0 < y1) {
                    d[y0 * (w + 1) + x0]++; d[y0 * (w + 1) + x1]--; d[y1 * (w + 1) + x0]--; d[y1 * (w + 1) + x1]++
                }
            }
            val c = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                d[y * (w + 1) + x] += (if (x > 0) d[y * (w + 1) + x - 1] else 0) + (if (y > 0) d[(y - 1) * (w + 1) + x] else 0) -
                    (if (x > 0 && y > 0) d[(y - 1) * (w + 1) + x - 1] else 0)
                c[y * w + x] = d[y * (w + 1) + x]
            }
            c
        }
    private val beaconPen = DoubleArray(nM)

    /** Squared shortfall of beacons in range of machine [m] where it stands. */
    private fun beaconPenalty(m: Int): Double {
        if (beaconNeed[m] == 0) return 0.0
        val c = beaconCov.getValue(mw[m] to mh[m])
        val x = px[m]; val y = py[m]
        val have = if (x in 0 until w && y in 0 until h) c[y * w + x] else 0
        val s = max(0, beaconNeed[m] - have)
        return (s * s).toDouble()
    }

    /** Beacons machine [m] is short of where it stands (lattice spots only). */
    fun beaconShort(m: Int): Int = if (beaconNeed[m] == 0) 0 else kotlin.math.sqrt(beaconPenalty(m)).toInt()

    // ---- totals ----
    var wl = 0.0; private set
    var cong = 0.0; private set
    var pin = 0.0; private set
    /** Belt inserters the fitting direct-insertion pairs save. */
    var di = 0.0; private set
    var fb = 0.0; private set
    var bcn = 0.0; private set
    var beta2 = 0.0

    val cost: Double get() = tun.alpha * wl + tun.beta * cong + tun.gamma * pin - tun.delta * di + beta2 * fb + tun.beaconWeight * bcn

    init {
        obstacles.forEach { if (it.x in 0 until w && it.y in 0 until h) occ[it.y * w + it.x] = -1 }
        connRel = Array(nM) { m ->
            Array(8) { code ->
                val cs = connections(plan.machines[m], 0, 0, code % 4, code >= 4)
                IntArray(cs.size * 3).also { a ->
                    cs.forEachIndexed { i, c ->
                        a[3 * i] = c.tile.x; a[3 * i + 1] = c.tile.y
                        a[3 * i + 2] = c.fluid?.let { f -> fluidIds.getOrPut(f) { fluidIds.size + 1 } } ?: 0
                    }
                }
            }
        }

        val nets = plan.nets
        nNets = nets.size
        netMachines = Array(nNets) { n -> plan.netTerminals(nets[n]).mapNotNull { it.machine }.distinct().toIntArray() }
        netPorts = Array(nNets) { n ->
            plan.netTerminals(nets[n]).mapNotNull { it.port }.map { pid -> portGeom(pid) }
        }
        require(tun.beltCost == "hpwl" || tun.beltCost == "path") { "place.belt_cost must be \"hpwl\" or \"path\"" }
        // A path estimate already grows with the terminal count; q(k) corrects only the bounding box.
        netWeight = DoubleArray(nNets) { n ->
            val k = plan.netTerminals(nets[n]).size
            nets[n].beltCount * if (tun.beltCost == "path") 1.0 else q(k)
        }
        machineNets = Array(nM) { m -> (0 until nNets).filter { m in netMachines[it] }.toIntArray() }
        nb = IntArray(4 * nNets)
        netHpwl = DoubleArray(nNets)
        netDensity = DoubleArray(nNets)

        plan.terminals.forEach { t -> if (t.machine != null && t.role != Role.SOURCE && !t.fluid) pinNeed[t.machine] += t.k }
        plan.terminals.forEach { t ->
            if (t.machine != null && t.role != Role.SOURCE && !t.fluid) accessNeed[t.machine] += tun.accessDemand * (t.k + tun.accessBeltTiles)
        }

        val pairs = ArrayList<Pair<Int, Int>>()   // terminal ids
        if (config.trick("I5") || config.trick("I6")) {
            val producers = plan.terminals.filter { it.role == Role.PRODUCER && !it.fluid }
            val consumers = plan.terminals.filter { it.role == Role.CONSUMER && it.machine != null && !it.fluid }
            producers.forEach { p -> consumers.forEach { c -> if (p.item == c.item && p.machine != c.machine) pairs += p.id to c.id } }
        }
        diPT = IntArray(pairs.size) { pairs[it].first }
        diCT = IntArray(pairs.size) { pairs[it].second }
        diA = IntArray(pairs.size) { plan.terminals[diPT[it]].machine!! }
        diB = IntArray(pairs.size) { plan.terminals[diCT[it]].machine!! }
        diFit = BooleanArray(pairs.size)
        diScore = DoubleArray(pairs.size)
        val pick = bestInserterRate(config, fromBelt = true)
        diGain = DoubleArray(pairs.size) { min(plan.terminals[diPT[it]].rate, plan.terminals[diCT[it]].rate) / pick }
        machinePairs = Array(nM) { m -> pairs.indices.filter { diA[it] == m || diB[it] == m }.toIntArray() }
        machineTerms = Array(nM) { m -> plan.terminals.filter { it.machine == m && it.role != Role.SOURCE && !it.fluid }.map { it.id }.toIntArray() }

        for (by in 0 until bh) for (bx in 0 until bw) {
            var s = 0
            for (y in by * bin until min(h, (by + 1) * bin)) for (x in bx * bin until min(w, (bx + 1) * bin)) if (occ[y * w + x] == 0) s++
            binS[by * bw + bx] = s
        }
    }

    private fun portGeom(pid: Int): PortGeom {
        val fixed = ArrayList<Tile>()
        val segs = ArrayList<IntArray>()
        fun edge(e: String, from: Int?, to: Int?) {
            when (e) {
                "north" -> segs += intArrayOf(max(0, from ?: 0), 0, min(w - 1, to ?: (w - 1)), 0)
                "south" -> segs += intArrayOf(max(0, from ?: 0), h - 1, min(w - 1, to ?: (w - 1)), h - 1)
                "west" -> segs += intArrayOf(0, max(0, from ?: 0), 0, min(h - 1, to ?: (h - 1)))
                "east" -> segs += intArrayOf(w - 1, max(0, from ?: 0), w - 1, min(h - 1, to ?: (h - 1)))
                "any" -> listOf("north", "east", "south", "west").forEach { edge(it, null, null) }
            }
        }
        plan.ports[pid].specs.forEach { s -> if (s.edge != null) edge(s.edge, s.from, s.to) else fixed += Tile(s.x!!, s.y!!) }
        return PortGeom(fixed, segs)
    }

    fun q(k: Int): Double = tun.q(k)

    // ---------------- geometry helpers ----------------
    /** Used fluid connection tiles of machine [m] in orientation [code] at (x, y), with their fluid ids. */
    fun fluidTilesOf(m: Int, x: Int = px[m], y: Int = py[m], code: Int = rot[m]): List<Pair<Tile, Int>> {
        val a = connRel[m][code]
        return (0 until a.size / 3).filter { a[3 * it + 2] != 0 }.map { Tile(x + a[3 * it], y + a[3 * it + 1]) to a[3 * it + 2] }
    }

    private fun inBox(x: Int, y: Int) = x in 0 until w && y in 0 until h

    /** Grid index of the stub of connection [i] of machine [m] at (x, y), or -1 outside the box. */
    private fun stub(m: Int, a: IntArray, i: Int, x: Int, y: Int): Int {
        val dx = a[3 * i]; val dy = a[3 * i + 1]
        val (sx, sy) = when {
            dy < 0 -> dx to dy - 1
            dy >= mh[m] -> dx to dy + 1
            dx < 0 -> dx - 1 to dy
            else -> dx + 1 to dy
        }
        return if (inBox(x + sx, y + sy)) (y + sy) * w + x + sx else -1
    }

    /** Writes or erases a machine in the grids only (no cost bookkeeping). */
    private fun rawStamp(m: Int, x: Int, y: Int, code: Int, add: Boolean) {
        val v = if (add) m + 1 else 0
        for (yy in y until y + mh[m]) for (xx in x until x + mw[m]) occ[yy * w + xx] = v
        val a = connRel[m][code]
        for (i in 0 until a.size / 3) {
            val cx = x + a[3 * i]; val cy = y + a[3 * i + 1]
            if (!inBox(cx, cy)) continue
            val t = cy * w + cx
            val f = a[3 * i + 2]
            if (f == 0) unusedCnt[t] += if (add) 1 else -1
            else {
                connCnt[t] += if (add) 1 else -1; if (add) connFl[t] = f else if (connCnt[t] == 0) connFl[t] = 0
                stub(m, a, i, x, y).let { st -> if (st >= 0) stubCnt[st] += if (add) 1 else -1 }
            }
        }
    }

    /** Can machine [m] stand at (x, y) in orientation [code] against what is in the grids now? */
    private fun fits(m: Int, x: Int, y: Int, code: Int): Boolean {
        for (yy in y until y + mh[m]) for (xx in x until x + mw[m]) {
            if (!inBox(xx, yy)) return false
            val t = yy * w + xx
            if (occ[t] != 0 || connCnt[t] != 0 || stubCnt[t] != 0) return false
        }
        val a = connRel[m][code]
        for (i in 0 until a.size / 3) {
            val cx = x + a[3 * i]; val cy = y + a[3 * i + 1]
            val f = a[3 * i + 2]
            if (f == 0) {
                // An unused connection may not sit on another machine's pipe end.
                if (inBox(cx, cy) && (connCnt[cy * w + cx] != 0 || stubCnt[cy * w + cx] != 0)) return false
                continue
            }
            if (!inBox(cx, cy)) return false
            val t = cy * w + cx
            if (occ[t] != 0 || unusedCnt[t] != 0) return false
            val st = stub(m, a, i, x, y)
            if (st < 0 || occ[st] != 0 || unusedCnt[st] != 0 || stubCnt[st] != 0) return false
            if (connCnt[st] != 0 && connFl[st] != f) return false
            if (connCnt[t] != 0 && connFl[t] != f) return false
            // Pipes of different fluids may not touch.
            for (d in Dir.entries) {
                val nx = cx + d.dx; val ny = cy + d.dy
                if (inBox(nx, ny) && connCnt[ny * w + nx] != 0 && connFl[ny * w + nx] != f) return false
            }
        }
        return true
    }

    /** Can the machines in [ms] stand at the given positions and orientations (others staying put)? */
    fun legal(ms: IntArray, nx: IntArray, ny: IntArray, nr: IntArray): Boolean {
        val moving = ms.indices.filter { isPlaced[ms[it]] }
        moving.forEach { i -> val m = ms[i]; rawStamp(m, px[m], py[m], rot[m], false) }
        var ok = true
        var added = 0
        for (i in ms.indices) {
            if (!fits(ms[i], nx[i], ny[i], nr[i])) { ok = false; break }
            rawStamp(ms[i], nx[i], ny[i], nr[i], true)
            added++
        }
        for (i in 0 until added) rawStamp(ms[i], nx[i], ny[i], nr[i], false)
        moving.forEach { i -> val m = ms[i]; rawStamp(m, px[m], py[m], rot[m], true) }
        return ok
    }

    // ---------------- occupancy ----------------
    private fun isFree(t: Int) = occ[t] == 0 && connCnt[t] == 0

    private fun stamp(m: Int, add: Boolean) {
        val v = if (add) m + 1 else 0
        for (yy in py[m] until py[m] + mh[m]) for (xx in px[m] until px[m] + mw[m]) {
            occ[yy * w + xx] = v
            val b = (yy / bin) * bw + xx / bin
            binS[b] += if (add) -1 else 1
            markDirty(b)
        }
        val a = connRel[m][rot[m]]
        for (i in 0 until a.size / 3) {
            val f = a[3 * i + 2]
            val cx = px[m] + a[3 * i]; val cy = py[m] + a[3 * i + 1]
            if (!inBox(cx, cy)) continue
            val t = cy * w + cx
            if (f == 0) { unusedCnt[t] += if (add) 1 else -1; continue }
            stub(m, a, i, px[m], py[m]).let { st -> if (st >= 0) stubCnt[st] += if (add) 1 else -1 }
            val wasFree = isFree(t)
            connCnt[t] += if (add) 1 else -1
            if (add) connFl[t] = f else if (connCnt[t] == 0) connFl[t] = 0
            if (wasFree != isFree(t)) {
                val b = (cy / bin) * bw + cx / bin
                binS[b] += if (wasFree) -1 else 1
                markDirty(b)
            }
        }
    }

    private fun markDirty(b: Int) { if (dirty[b] != dirtyGen) { dirty[b] = dirtyGen; dirtyList[dirtyN++] = b } }

    // ---------------- nets ----------------
    private fun cx(m: Int) = px[m] + mw[m] / 2
    private fun cy(m: Int) = py[m] + mh[m] / 2

    private val pathCost = tun.beltCost == "path"
    private val pathDone = BooleanArray(nM)

    /**
     * Gaps a greedy path leaves between the inserter rings (footprint plus one tile) of net [n]'s machines,
     * starting at its port point when it has one, else at its first machine: 0 when every next machine shares
     * a corridor with the last, more for every detour a belt would have to make.
     */
    private fun ringGaps(n: Int, portX: Int, portY: Int, hasPort: Boolean): Double {
        val ms = netMachines[n]
        if (ms.isEmpty()) return 0.0
        var ax0: Int; var ay0: Int; var ax1: Int; var ay1: Int
        var left = ms.size
        if (hasPort) { ax0 = portX; ax1 = portX; ay0 = portY; ay1 = portY }
        else {
            val m = ms[0]; pathDone[m] = true; left--
            ax0 = px[m] - 1; ay0 = py[m] - 1; ax1 = px[m] + mw[m]; ay1 = py[m] + mh[m]
        }
        var total = 0
        while (left > 0) {
            var best = -1; var bestD = Int.MAX_VALUE
            for (m in ms) {
                if (pathDone[m]) continue
                val bx0 = px[m] - 1; val by0 = py[m] - 1; val bx1 = px[m] + mw[m]; val by1 = py[m] + mh[m]
                val d = max(0, max(bx0 - ax1, ax0 - bx1)) + max(0, max(by0 - ay1, ay0 - by1))
                if (d < bestD) { bestD = d; best = m }
            }
            pathDone[best] = true; left--
            total += bestD
            ax0 = px[best] - 1; ay0 = py[best] - 1; ax1 = px[best] + mw[best]; ay1 = py[best] + mh[best]
        }
        for (m in ms) pathDone[m] = false
        return total.toDouble()
    }

    private var bestPortX = 0
    private var bestPortY = 0
    private var hasPort = false

    private fun computeNet(n: Int) {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        netMachines[n].forEach { m -> val x = cx(m); val y = cy(m); if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (x0 == Int.MAX_VALUE) { x0 = w / 2; x1 = w / 2; y0 = h / 2; y1 = h / 2 }
        // Each port adds its point closest to the machines' box (the first one also starts the path estimate).
        hasPort = false
        netPorts[n].forEach { pg ->
            var bestX = 0; var bestY = 0; var bestD = Int.MAX_VALUE
            fun consider(x: Int, y: Int) {
                val d = max(0, max(x0 - x, x - x1)) + max(0, max(y0 - y, y - y1))
                if (d < bestD) { bestD = d; bestX = x; bestY = y }
            }
            pg.fixed.forEach { consider(it.x, it.y) }
            pg.segments.forEach { s -> consider(x0.coerceIn(s[0], s[2]), y0.coerceIn(s[1], s[3])); consider(x1.coerceIn(s[0], s[2]), y1.coerceIn(s[1], s[3])) }
            if (bestD != Int.MAX_VALUE) {
                if (!hasPort) { hasPort = true; bestPortX = bestX; bestPortY = bestY }
                x0 = min(x0, bestX); x1 = max(x1, bestX); y0 = min(y0, bestY); y1 = max(y1, bestY)
            }
        }
        nb[4 * n] = x0.coerceIn(0, w - 1); nb[4 * n + 1] = y0.coerceIn(0, h - 1)
        nb[4 * n + 2] = x1.coerceIn(0, w - 1); nb[4 * n + 3] = y1.coerceIn(0, h - 1)
        netHpwl[n] = ((x1 - x0) + (y1 - y0)).toDouble()
        if (pathCost) netHpwl[n] += tun.pathWeight * ringGaps(n, bestPortX, bestPortY, hasPort)
        val area = (nb[4 * n + 2] - nb[4 * n] + 1) * (nb[4 * n + 3] - nb[4 * n + 1] + 1)
        netDensity[n] = netWeight[n] * netHpwl[n] / area
    }

    /** Adds (sign 1) or removes (sign -1) a net's RUDY demand. */
    private fun spread(n: Int, sign: Double) {
        val x0 = nb[4 * n]; val y0 = nb[4 * n + 1]; val x1 = nb[4 * n + 2]; val y1 = nb[4 * n + 3]
        val d = netDensity[n] * sign
        if (d == 0.0) return
        for (by in y0 / bin..y1 / bin) {
            val oy = min(y1, (by + 1) * bin - 1) - max(y0, by * bin) + 1
            for (bx in x0 / bin..x1 / bin) {
                val ox = min(x1, (bx + 1) * bin - 1) - max(x0, bx * bin) + 1
                val b = by * bw + bx
                binD[b] += d * ox * oy
                markDirty(b)
            }
        }
    }

    /**
     * Adds (sign 1) or removes (sign -1) machine [m]'s belt-access demand: spread evenly over the free band
     * [PlaceTuning.accessRange] tiles wide around its footprint, bin by bin.
     */
    private fun spreadAccess(m: Int, sign: Double) {
        if (accessNeed[m] == 0.0) return
        val r = tun.accessRange
        val x0 = max(0, px[m] - r); val y0 = max(0, py[m] - r)
        val x1 = min(w - 1, px[m] + mw[m] - 1 + r); val y1 = min(h - 1, py[m] + mh[m] - 1 + r)
        val fx0 = px[m]; val fy0 = py[m]; val fx1 = px[m] + mw[m] - 1; val fy1 = py[m] + mh[m] - 1
        val band = (x1 - x0 + 1) * (y1 - y0 + 1) - mw[m] * mh[m]
        if (band <= 0) return
        val d = accessNeed[m] / band * sign
        fun overlap(a0: Int, a1: Int, b0: Int, b1: Int) = max(0, min(a1, b1) - max(a0, b0) + 1)
        for (by in y0 / bin..y1 / bin) for (bx in x0 / bin..x1 / bin) {
            val bx0 = bx * bin; val bx1 = bx0 + bin - 1; val by0 = by * bin; val by1 = by0 + bin - 1
            val tiles = overlap(x0, x1, bx0, bx1) * overlap(y0, y1, by0, by1) - overlap(fx0, fx1, bx0, bx1) * overlap(fy0, fy1, by0, by1)
            if (tiles <= 0) continue
            val b = by * bw + bx
            binD[b] += d * tiles
            markDirty(b)
        }
    }

    // ---------------- pins and DI ----------------
    private fun freeSlots(m: Int): Int {
        var f = 0
        val r = geom.Rect(px[m], py[m], mw[m], mh[m])
        for (side in Dir.entries) {
            val d1 = r.sideTiles(side, 1); val d2 = r.sideTiles(side, 2)
            for (i in d1.indices) {
                val a = d1[i]; val b = d2[i]
                if (inBox(a.x, a.y) && isFree(a.y * w + a.x) && inBox(b.x, b.y) && isFree(b.y * w + b.x)) f++
            }
        }
        return f
    }

    /**
     * Belt inserter slots machine [m] needs: each terminal's k, scaled down by the share of its rate that
     * fitting direct-insertion neighbours already cover (their inserters sit in the gap, not on free slots).
     */
    private fun slotsNeeded(m: Int): Int {
        if (machinePairs[m].isEmpty()) return pinNeed[m]
        var need = 0
        for (t in machineTerms[m]) {
            val term = plan.terminals[t]
            var covered = 0.0
            for (i in machinePairs[m]) {
                if (!diFit[i]) continue
                if (diCT[i] == t) covered += plan.terminals[diPT[i]].rate
                if (diPT[i] == t) covered += plan.terminals[diCT[i]].rate
            }
            val left = max(0.0, 1.0 - covered / term.rate)
            need += kotlin.math.ceil(term.k * left - 1e-9).toInt()
        }
        return need
    }

    private fun pinPenalty(m: Int): Double { val s = max(0, slotsNeeded(m) + 1 - freeSlots(m)); return (s * s).toDouble() }

    private fun fits(a: Int, b: Int): Boolean {
        val ra = geom.Rect(px[a], py[a], mw[a], mh[a]); val rb = geom.Rect(px[b], py[b], mw[b], mh[b])
        val yo = max(ra.y, rb.y) <= min(ra.y2, rb.y2)
        val xo = max(ra.x, rb.x) <= min(ra.x2, rb.x2)
        val gx = max(rb.x - ra.x2 - 1, ra.x - rb.x2 - 1)
        val gy = max(rb.y - ra.y2 - 1, ra.y - rb.y2 - 1)
        val ok5 = config.trick("I5"); val ok6 = config.trick("I6")
        return (yo && ((gx == 1 && ok5) || (gx == 3 && ok6))) || (xo && ((gy == 1 && ok5) || (gy == 3 && ok6)))
    }

    /**
     * Credit for a direct-insertion pair: 1 when the gap fits (I5/I6), otherwise a small share that fades
     * over a few tiles, so the annealer feels which way to move. Only exact fits remove inserter slots.
     */
    private fun closeness(a: Int, b: Int): Double {
        if (fits(a, b)) return 1.0
        val ra = geom.Rect(px[a], py[a], mw[a], mh[a]); val rb = geom.Rect(px[b], py[b], mw[b], mh[b])
        val oy = min(ra.y2, rb.y2) - max(ra.y, rb.y) + 1
        val ox = min(ra.x2, rb.x2) - max(ra.x, rb.x) + 1
        val gx = max(rb.x - ra.x2 - 1, ra.x - rb.x2 - 1)
        val gy = max(rb.y - ra.y2 - 1, ra.y - rb.y2 - 1)
        val ok6 = config.trick("I6")
        fun off(g: Int) = if (ok6) min(abs(g - 1), abs(g - 3)) else abs(g - 1)
        // Distance in tiles from a fitting position: gap error plus missing overlap.
        val dh = off(gx) + max(0, 1 - oy)
        val dv = off(gy) + max(0, 1 - ox)
        val d = min(dh, dv)
        return if (d >= 4) 0.0 else 0.35 * (4 - d) / 4.0
    }

    /** Machines whose pin count may change when the rect area around [m] changes. */
    private fun neighbours(m: Int, out: MutableSet<Int>) {
        for (yy in py[m] - 4 until py[m] + mh[m] + 4) for (xx in px[m] - 4 until px[m] + mw[m] + 4) {
            if (!inBox(xx, yy)) continue
            val o = occ[yy * w + xx]
            if (o > 0) out += o - 1
        }
    }

    // ---------------- full and incremental evaluation ----------------
    /** Places every machine from scratch and recomputes all terms. */
    fun setAll(xs: IntArray, ys: IntArray, rs: IntArray) {
        clear()
        for (m in 0 until nM) placeOne(m, xs[m], ys[m], rs[m])
        finishPlacing()
    }

    /** Removes every machine (start of an initial placement). */
    fun clear() {
        dirtyGen++; dirtyN = 0
        for (m in 0 until nM) if (isPlaced[m]) { stamp(m, false); isPlaced[m] = false }
        placed = false
    }

    /** Stamps one machine during initial placement; costs are computed by [finishPlacing]. */
    fun placeOne(m: Int, x: Int, y: Int, r: Int) {
        px[m] = x; py[m] = y; rot[m] = r
        stamp(m, true)
        isPlaced[m] = true
    }

    fun finishPlacing() {
        require(isPlaced.all { it }) { "not every machine is placed" }
        placed = true
        recomputeAll()
    }

    fun recomputeAll() {
        binD.fill(0.0)
        wl = 0.0
        for (n in 0 until nNets) { computeNet(n); wl += netWeight[n] * netHpwl[n]; spread(n, 1.0) }
        for (m in 0 until nM) spreadAccess(m, 1.0)
        cong = 0.0; fb = 0.0
        for (b in binD.indices) { binCong[b] = congOf(b); cong += binCong[b]; binFb[b] = binHist[b] * binD[b]; fb += binFb[b] }
        di = 0.0
        for (i in diA.indices) { diFit[i] = fits(diA[i], diB[i]); diScore[i] = closeness(diA[i], diB[i]); di += diGain[i] * diScore[i] }
        pin = 0.0
        for (m in 0 until nM) { pinPen[m] = pinPenalty(m); pin += pinPen[m] }
        bcn = 0.0
        for (m in 0 until nM) { beaconPen[m] = beaconPenalty(m); bcn += beaconPen[m] }
    }

    private fun congOf(b: Int): Double { val o = binD[b] - binS[b]; return if (o > 0) o * o else 0.0 }

    /** Moves [ms] to the new positions (assumed legal) and updates every term incrementally. */
    fun move(ms: IntArray, nx: IntArray, ny: IntArray, nr: IntArray) {
        dirtyGen++; dirtyN = 0
        val touchedNets = LinkedHashSet<Int>()
        ms.forEach { m -> machineNets[m].forEach { touchedNets += it } }
        val pinMachines = HashSet<Int>()
        ms.forEach { neighbours(it, pinMachines) }

        touchedNets.forEach { n -> wl -= netWeight[n] * netHpwl[n]; spread(n, -1.0) }
        ms.forEach { spreadAccess(it, -1.0) }
        ms.forEach { stamp(it, false) }
        for (i in ms.indices) { px[ms[i]] = nx[i]; py[ms[i]] = ny[i]; rot[ms[i]] = nr[i] }
        ms.forEach { stamp(it, true) }
        ms.forEach { spreadAccess(it, 1.0) }
        touchedNets.forEach { n -> computeNet(n); wl += netWeight[n] * netHpwl[n]; spread(n, 1.0) }

        for (i in 0 until dirtyN) {
            val b = dirtyList[i]
            cong -= binCong[b]; binCong[b] = congOf(b); cong += binCong[b]
            fb -= binFb[b]; binFb[b] = binHist[b] * binD[b]; fb += binFb[b]
        }
        ms.forEach { neighbours(it, pinMachines) }

        val pairs = HashSet<Int>()
        ms.forEach { m -> machinePairs[m].forEach { pairs += it } }
        pairs.forEach { i ->
            val f = fits(diA[i], diB[i])
            if (f != diFit[i]) { diFit[i] = f; pinMachines += diA[i]; pinMachines += diB[i] }
            val c = closeness(diA[i], diB[i])
            if (c != diScore[i]) { di += diGain[i] * (c - diScore[i]); diScore[i] = c }
        }
        pinMachines.forEach { m -> pin -= pinPen[m]; pinPen[m] = pinPenalty(m); pin += pinPen[m] }
        ms.forEach { m -> bcn -= beaconPen[m]; beaconPen[m] = beaconPenalty(m); bcn += beaconPen[m] }
    }

    /** Routing feedback: PathFinder history per tile, summed per bin. */
    fun setHistory(history: Map<Tile, Double>, weight: Double) {
        binHist.fill(0.0)
        history.forEach { (t, v) -> if (inBox(t.x, t.y)) binHist[(t.y / bin) * bw + t.x / bin] += v }
        beta2 = weight
        recomputeAll()
    }

    fun placement(): Placement = Placement(w, h, (0 until nM).map { PlacedMachine(it, px[it], py[it], Dir.entries[rot[it] % 4], rot[it] >= 4) })

    fun diPairsFitting(): List<Pair<Int, Int>> = diA.indices.filter { diFit[it] }.map { diA[it] to diB[it] }

    fun netCount() = nNets
}
