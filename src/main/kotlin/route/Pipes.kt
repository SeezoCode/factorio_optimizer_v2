package route

import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import place.portCandidates
import plan.Role
import validate.OutputSpec
import validate.SourceSpec
import kotlin.math.max

/**
 * Pipe routing for fluid nets, inside PathFinder's negotiation (shared tile occupancy, history and present
 * cost). Each fluid net is a tree grown from its source (a port, or a producer's output connection) to every
 * machine connection that uses the fluid: A* from all tiles of the tree to the nearest unconnected
 * terminal. Pipes join every adjacent pipe, so a pipe next to another fluid's pipe is a priced conflict;
 * pipe-to-ground pairs join only along their axis and pass under anything.
 */
internal class PipeRouter(private val pf: PathFinder) {
    private val N = pf.N
    private val dims = pf.dims
    private val tun = pf.tun

    /** A fluid net: terminal tile groups (reaching any tile of a group connects it) and optional port tiles. */
    private class FNet(val idx: Int, val planId: Int, val fluid: String, val groups: List<IntArray>, val portTiles: IntArray, val portOut: IntArray, val hpwl: Int, val sourceRate: Double,
        /** Output port tiles (surplus leaves the block here), reached last; empty without an output. */
        val outTiles: IntArray = IntArray(0), val outRate: Double = 0.0,
        /** Plan ports of the source and the output (-1: none). */
        val srcPort: Int = -1, val outPort: Int = -1)
    private val nets = ArrayList<FNet>()

    private class PRouted {
        val pipes = ArrayList<Int>()
        val ptgs = ArrayList<Int>()        // tile * 4 + open-side dir
        val chans = ArrayList<Int>()
        var sourceTile = -1
        var outTile = -1
        var ok = false
    }
    private lateinit var routed: Array<PRouted?>

    /** -1: any fluid; -2: no pipe; n >= 0: only fluid net n (its machine connection tile or fixed port). */
    private val connNet = IntArray(N) { -1 }
    private val pipeCount = IntArray(N)
    private val pipeOwner = IntArray(N) { -1 }
    /** Joinable pipe ends of other nets next to each tile. */
    private val pipeNbr = IntArray(N)
    /** Open side of a pipe-to-ground end on the tile, or -1 for a plain pipe. */
    private val ptgOpen = IntArray(N) { -1 }
    private val occPChan = IntArray(2 * N)
    private val histPChan = FloatArray(2 * N)
    private val ownStamp = IntArray(N)
    private var stamp = 0
    private val maxJ = GameConstants.PIPE_TO_GROUND_GAP + 1

    val count get() = nets.size

    init {
        val plan = pf.plan
        val placement = pf.p.placement
        val fluidNets = plan.nets.filter { it.fluid }
        val netOfFluid = HashMap<String, Int>()
        fluidNets.forEachIndexed { i, n -> netOfFluid[n.item] = i }
        // Connection tiles: the right fluid only; unused ones and fluids nobody routes take no pipe at all.
        placement.machines.forEach { m ->
            placement.connections(plan, m).forEach { c ->
                if (!dims.inFull(c.tile.x, c.tile.y)) return@forEach
                val i = dims.idx(c.tile)
                connNet[i] = c.fluid?.let { netOfFluid[it] } ?: -2
            }
        }
        fluidNets.forEachIndexed { idx, net ->
            val ts = plan.netTerminals(net)
            val groups = ts.filter { it.machine != null }.map { t ->
                val pm = placement.machines.first { it.id == t.machine }
                placement.connections(plan, pm).filter { it.fluid == net.item }.map { dims.idx(it.tile) }.toIntArray()
            }.filter { it.isNotEmpty() }.toMutableList()
            // Several ports (a fluid's pipe tree between the blocks of a factory): the first source is the root, an
            // output on an edge is reached last, and every other port is a terminal the tree must reach like a
            // machine connection.
            val srcTs = ts.filter { it.role == Role.SOURCE }
            val outTs = ts.filter { it.role == Role.OUTPUT }
            val source = srcTs.firstOrNull()
            val output = outTs.firstOrNull { t -> plan.ports[t.port!!].specs.any { it.edge != null } } ?: outTs.firstOrNull()
            (srcTs.drop(1) + outTs.filter { it !== output }).forEach { t ->
                val tiles = portCandidates(plan.ports[t.port!!].specs, pf.W, pf.H).map { it.first }.filter { dims.inFull(it.x, it.y) }.map { dims.idx(it) }
                tiles.forEach { connNet[it] = idx }
                if (tiles.isNotEmpty()) groups += tiles.toIntArray()
            }
            var portTiles = IntArray(0); var portOut = IntArray(0)
            if (source != null) {
                val cands = portCandidates(plan.ports[source.port!!].specs, pf.W, pf.H)
                    .filter { (t, _) -> dims.inFull(t.x, t.y) && (dims.inBox(t.x, t.y) || (t.x >= -1 && t.y >= -1 && t.x <= pf.W && t.y <= pf.H)) }
                portTiles = cands.map { dims.idx(it.first) }.toIntArray()
                portOut = cands.map { it.second?.ordinal ?: -1 }.toIntArray()
                cands.forEach { (t, out) -> if (out == null) connNet[dims.idx(t)] = idx }
            }
            var outTiles = IntArray(0)
            if (output != null) {
                val cands = portCandidates(plan.ports[output.port!!].specs, pf.W, pf.H).filter { (t, _) -> dims.inBox(t.x, t.y) }
                outTiles = cands.map { dims.idx(it.first) }.toIntArray()
                cands.forEach { (t, out) -> if (out == null) connNet[dims.idx(t)] = idx }
            }
            val pts = groups.map { dims.tile(it[0]) } + portTiles.take(1).map { dims.tile(it) } + outTiles.take(1).map { dims.tile(it) }
            val hpwl = if (pts.isEmpty()) 0 else (pts.maxOf { it.x } - pts.minOf { it.x }) + (pts.maxOf { it.y } - pts.minOf { it.y })
            nets += FNet(idx, net.id, net.item, groups, portTiles, portOut, hpwl, source?.rate ?: 0.0, outTiles, output?.rate ?: 0.0,
                srcPort = source?.port ?: -1, outPort = output?.port ?: -1)
        }
        routed = arrayOfNulls(nets.size)
    }

    // ---- placeability and costs ----
    private fun pipeOk(q: Int, n: Int): Boolean {
        if (q < 0 || q >= N || ownStamp[q] == stamp) return false
        val c = connNet[q]
        if (c == n) return true
        return c == -1 && pf.allowNet[q] == -1
    }

    private fun pipeCost(q: Int, joins: Boolean): Float =
        (tun.beltBase.toFloat() + pf.hist[q]) * (1f + pf.pFac * (pf.occTile[q] + if (joins) pipeNbr[q] else 0)) +
            (if (pf.pinCount[q] > 0) tun.pinReservation.toFloat() else 0f) + pf.bias[q]

    private fun chanCost(c: Int): Float = histPChan[c] + (1f + histPChan[c]) * pf.pFac * occPChan[c]

    // ---- commit / rip-up ----
    private fun addPipe(r: PRouted, t: Int, n: Int) {
        r.pipes += t
        pf.occTile[t]++; pipeCount[t]++; pipeOwner[t] = n; ownStamp[t] = stamp
        for (d in 0..3) { val u = t + pf.dIdx[d]; if (u in 0 until N) pipeNbr[u]++ }
    }

    private fun addPtg(r: PRouted, t: Int, open: Int, n: Int) {
        r.ptgs += t * 4 + open
        pf.occTile[t]++; pipeCount[t]++; pipeOwner[t] = n; ownStamp[t] = stamp; ptgOpen[t] = open
        val u = t + pf.dIdx[open]; if (u in 0 until N) pipeNbr[u]++
    }

    fun ripUp(i: Int) {
        val r = routed[i] ?: return
        r.pipes.forEach { t -> pf.occTile[t]--; pipeCount[t]--; for (d in 0..3) { val u = t + pf.dIdx[d]; if (u in 0 until N) pipeNbr[u]-- } }
        r.ptgs.forEach { e -> val t = e shr 2; pf.occTile[t]--; pipeCount[t]--; ptgOpen[t] = -1; val u = t + pf.dIdx[e and 3]; if (u in 0 until N) pipeNbr[u]-- }
        r.chans.forEach { occPChan[it]-- }
        routed[i] = null
    }

    /** Net [i]'s current route, for putting it back with [restore]. */
    fun saved(i: Int): Any? = routed[i]

    /** Puts a route returned by [saved] back after the net was ripped up. */
    fun restore(i: Int, saved: Any?) {
        val r = saved as PRouted? ?: return
        r.pipes.forEach { t -> pf.occTile[t]++; pipeCount[t]++; pipeOwner[t] = i; for (d in 0..3) { val u = t + pf.dIdx[d]; if (u in 0 until N) pipeNbr[u]++ } }
        r.ptgs.forEach { e -> val t = e shr 2; pf.occTile[t]++; pipeCount[t]++; pipeOwner[t] = i; ptgOpen[t] = e and 3; val u = t + pf.dIdx[e and 3]; if (u in 0 until N) pipeNbr[u]++ }
        r.chans.forEach { occPChan[it]++ }
        routed[i] = r
    }

    /** Port entry tiles just outside the box: a search starting there takes one step inward (direction), else -1. */
    private val entryIn = IntArray(N) { -1 }

    // ---- A* over (tile, k): k = 0 a pipe, k = 1..4 a pipe-to-ground exit whose open side is direction k - 1 ----
    private fun st(t: Int, k: Int) = t * 5 + k

    /** Grows net [n] from [sources] (pipe tiles, cost 0 or given) to the nearest tile in [goal]; returns the end state or -1. */
    private fun search(n: FNet, sources: List<Pair<Int, Float>>, goal: Set<Int>): Int {
        pf.gen++
        pf.heap.clear()
        pf.goalGen++
        goal.forEach { pf.goalStamp[it] = pf.goalGen }
        pf.distanceTransform()
        val hw = pf.hWeight * minOf(1f, (2 * tun.beltBase.toFloat() + tun.ugPenalty.toFloat()) / (maxJ + 1))
        sources.forEach { (t, g) ->
            val s = st(t, 0)
            pf.seenGen[s] = pf.gen; pf.gArr[s] = g; pf.par[s] = -1
            pf.heap.push(g + hw * pf.dist[t], s)
        }
        var budget = tun.maxExpansions
        while (!pf.heap.isEmpty()) {
            val s = pf.heap.pop()
            if (pf.closedGen[s] == pf.gen) continue
            pf.closedGen[s] = pf.gen
            if (--budget < 0) return -1
            val t = s / 5
            val k = s % 5
            val gS = pf.gArr[s]
            if (k == 0 && pf.goalStamp[t] == pf.goalGen && pf.par[s] != -1) return s
            val dirs = if (k != 0) (k - 1)..(k - 1) else if (pf.par[s] == -1 && entryIn[t] >= 0) entryIn[t]..entryIn[t] else 0..3
            for (d in dirs) {
                val q = t + pf.dIdx[d]
                if (!pipeOk(q, n.idx)) continue
                relax(st(q, 0), s, gS + pipeCost(q, true), hw)
                // Pipe-to-ground: entrance on q (open towards t), exit j tiles on (open away), continue past it.
                // Neither end may sit on a machine's connection tile: it joins only through its open side.
                if (!pf.config.trick("B2") || connNet[q] >= 0) continue
                val axis = if (d == 1 || d == 3) 0 else 1
                var chanAcc = chanCost(axis * N + q)
                val qx = dims.x(q); val qy = dims.y(q)
                for (j in 1..maxJ) {
                    if (!dims.inFull(qx + pf.dX[d] * j, qy + pf.dY[d] * j)) break
                    val e = q + pf.dIdx[d] * j
                    chanAcc += chanCost(axis * N + e)
                    if (!pipeOk(e, n.idx) || connNet[e] >= 0) continue
                    val cost = gS + pipeCost(q, false) + pipeCost(e, false) + (tun.ugPenalty + tun.ugPenaltyPerTile * j).toFloat() + chanAcc
                    relax(st(e, d + 1), s, cost, hw)
                }
            }
        }
        return -1
    }

    /** States the current leg may not use (they made an earlier attempt cross itself). */
    private val banned = HashSet<Int>()

    private fun relax(ns: Int, from: Int, g: Float, hw: Float) {
        if (pf.closedGen[ns] == pf.gen || ns in banned) return
        if (pf.seenGen[ns] == pf.gen && g >= pf.gArr[ns]) return
        pf.seenGen[ns] = pf.gen; pf.gArr[ns] = g; pf.par[ns] = from
        pf.heap.push(g + hw * pf.dist[ns / 5], ns)
    }

    private fun pathOf(s: Int): List<Int> {
        val states = ArrayList<Int>()
        var c = s
        while (c != -1) { states += c; c = pf.par[c] }
        states.reverse()
        return states
    }

    /** The first state of the path ending in [s] that lands on a tile the path already uses, or -1. */
    private fun crossing(s: Int): Int {
        val states = pathOf(s)
        val tiles = HashSet<Int>()
        for ((i, st) in states.withIndex()) {
            val t = st / 5
            if (i == 0) { if (ownStamp[t] == stamp) continue }   // starts on the tree (or is the first tile)
            if (i == 0 && entryIn[t] >= 0) { tiles.add(t); continue }   // a port entry outside the box: nothing goes there
            if (st % 5 != 0) { val ent = (states[i - 1] / 5) + pf.dIdx[(st % 5) - 1]; if (!tiles.add(ent)) return st }
            if (!tiles.add(t)) return st
        }
        return -1
    }

    /** A* with lazy repair: a path that crosses itself bans the state where it does and searches again. */
    private fun leg(n: FNet, sources: List<Pair<Int, Float>>, goal: Set<Int>): Int {
        banned.clear()
        repeat(tun.repairRestarts + 1) {
            val s = search(n, sources, goal)
            if (s < 0) return -1
            val bad = crossing(s)
            if (bad < 0) return s
            banned += bad
        }
        return -2
    }

    /** Commits the path ending in [s] (checked by [leg] not to cross itself). */
    private fun commit(n: FNet, r: PRouted, s: Int): Boolean {
        val states = pathOf(s)
        for ((i, st) in states.withIndex()) {
            val t = st / 5
            val k = st % 5
            if (i == 0 && (ownStamp[t] == stamp || entryIn[t] >= 0)) continue
            if (k == 0) addPipe(r, t, n.idx)
            else {
                val d = k - 1
                val ent = (states[i - 1] / 5) + pf.dIdx[d]
                addPtg(r, ent, (d + 2) % 4, n.idx)
                addPtg(r, t, d, n.idx)
                val axis = if (d == 1 || d == 3) 0 else 1
                var x = ent
                while (true) { val ci = axis * N + x; occPChan[ci]++; r.chans += ci; if (x == t) break; x += pf.dIdx[d] }
            }
        }
        return true
    }

    /** Routes one fluid net as a tree; on failure rips it up and returns false. */
    fun route(i: Int): Boolean {
        val n = nets[i]
        stamp++
        val r = PRouted()
        routed[i] = r
        val left = n.groups.toMutableList()
        fun fail(why: String): Boolean { pf.log += "fluid ${n.fluid}: $why"; ripUp(i); return false }
        // First leg: from the port (any candidate tile) or from the first terminal. A net with no machines (a pipe
        // between blocks of a factory) runs from its port straight to its output.
        if (n.portTiles.isNotEmpty()) {
            if (left.isEmpty() && n.outTiles.isEmpty()) return fail("no consumers")
            // An edge port tile is entered from just outside the box, heading in, so it can hold a pipe or a
            // pipe-to-ground open to the outside: that joins nothing beside it, so ports of other fluids may touch.
            val entries = ArrayList<Int>()
            val starts = n.portTiles.withIndex().mapNotNull { (k, t) ->
                val o = n.portOut[k]
                val x = dims.x(t) + pf.dX.getOrElse(o) { 0 }; val y = dims.y(t) + pf.dY.getOrElse(o) { 0 }
                if (o >= 0 && dims.inFull(x, y) && !dims.inBox(x, y)) {
                    val q = dims.idx(x, y)
                    entryIn[q] = (o + 2) % 4; entries += q
                    q to 0f
                } else if (pipeOk(t, n.idx)) t to pipeCost(t, true) else null
            }
            val goal = if (left.isEmpty()) n.outTiles.filter { pipeOk(it, n.idx) }.toSet() else left.flatMap { it.toList() }.toSet()
            val s = leg(n, starts, goal)
            if (s >= 0) {
                commit(n, r, s)
                var c = s; while (pf.par[c] != -1) c = pf.par[c]
                r.sourceTile = (c / 5).let { t0 -> if (entryIn[t0] >= 0) t0 + pf.dIdx[entryIn[t0]] else t0 }
            }
            entries.forEach { entryIn[it] = -1 }
            if (s == -1) return fail("no path from the port")
            if (s < 0) return fail("every path from the port crosses itself")
        } else {
            if (left.size + (if (n.outTiles.isNotEmpty()) 1 else 0) < 2) return fail("needs two connections")
            val first = left.removeAt(0)
            if (!pipeOk(first[0], n.idx)) return fail("first connection blocked")
            addPipe(r, first[0], n.idx)
        }
        while (true) {
            left.removeAll { g -> g.any { ownStamp[it] == stamp } }
            if (left.isEmpty()) break
            val tree = r.pipes.toList()
            val s = leg(n, tree.map { it to 0f }, left.flatMap { it.toList() }.toSet())
            if (s == -1) return fail("no path to ${left.size} more connections")
            if (s < 0) return fail("every path to ${left.size} more connections crosses itself")
            commit(n, r, s)
        }
        r.outTile = n.outTiles.firstOrNull { ownStamp[it] == stamp && it in r.pipes } ?: -1
        if (n.outTiles.isNotEmpty() && r.outTile < 0) {
            val s = leg(n, r.pipes.map { it to 0f }, n.outTiles.filter { pipeOk(it, n.idx) || ownStamp[it] == stamp }.toSet())
            if (s < 0) return fail("no path to the output port")
            commit(n, r, s)
            r.outTile = s / 5
        }
        r.ok = true
        return true
    }

    // ---- negotiation bookkeeping ----
    /** Joins with other fluids at tile t (a pipe end of one net next to a joinable end of another). */
    fun adjacencyOveruse(t: Int): Int {
        if (pipeCount[t] == 0) return 0
        var o = 0
        for (d in 0..3) {
            val u = t + pf.dIdx[d]
            if (u in 0 until N && pipeCount[u] > 0 && pipeOwner[u] != pipeOwner[t] && joins(t, d) && joins(u, (d + 2) % 4)) o++
        }
        return o
    }

    /** Does the pipe end at [t] connect towards direction [d]? (normal pipes: always; pipe-to-ground: open side only) */
    private fun joins(t: Int, d: Int): Boolean = ptgOpen[t] < 0 || ptgOpen[t] == d

    fun channelOveruse(): Int { var o = 0; for (c in 0 until 2 * N) o += max(0, occPChan[c] - 1); return o }

    fun updateHistory() {
        for (c in 0 until 2 * N) { val o = occPChan[c] - 1; if (o > 0) histPChan[c] += (tun.hFac * o).toFloat() }
    }

    fun touchesOveruse(i: Int, overuseAt: (Int) -> Int): Boolean {
        val r = routed[i] ?: return true
        if (!r.ok) return true
        return r.pipes.any { overuseAt(it) > 0 } || r.ptgs.any { overuseAt(it shr 2) > 0 } || r.chans.any { occPChan[it] > 1 }
    }

    fun ok(i: Int) = routed[i]?.ok == true

    /** Does net [i] lie in [region] (or is it unrouted)? */
    fun touchesRegion(i: Int, region: BooleanArray): Boolean {
        val r = routed[i] ?: return true
        if (!r.ok) return true
        return r.pipes.any { region[it] } || r.ptgs.any { region[it shr 2] }
    }

    /** Plan nets of the pipes on [t] and beside it, for naming the fluids in a conflict there. */
    fun planNetsNear(t: Int): Set<Int> = buildSet {
        for (u in listOf(t) + (0..3).map { t + pf.dIdx[it] }) if (u in 0 until N && pipeCount[u] > 0 && pipeOwner[u] >= 0) add(nets[pipeOwner[u]].planId)
    }

    /** Where net [i]'s trouble is: its connection tiles and ports. */
    fun seedTiles(i: Int): List<Int> = nets[i].groups.flatMap { it.toList() } + nets[i].portTiles.take(1) + nets[i].outTiles.take(1)

    /** Tiles of overused pipe-to-ground channels. */
    fun channelConflicts(): List<Int> = (0 until 2 * N).filter { occPChan[it] > 1 }.map { it % N }
    fun hpwl(i: Int) = nets[i].hpwl
    fun failed(): List<Int> = nets.filter { routed[it.idx]?.ok != true }.map { it.planId }

    fun sources(): List<SourceSpec> = nets.mapNotNull { n ->
        routed[n.idx]?.takeIf { it.ok && it.sourceTile >= 0 }?.let { SourceSpec(dims.tile(it.sourceTile), n.fluid, n.sourceRate, fluid = true, port = n.srcPort) }
    }

    fun outputs(): List<OutputSpec> = nets.mapNotNull { n ->
        routed[n.idx]?.takeIf { it.ok && it.outTile >= 0 }?.let { OutputSpec(dims.tile(it.outTile), n.fluid, n.outRate, fluid = true, port = n.outPort) }
    }

    fun entities(): List<PlacedEntity> = nets.flatMap { n ->
        val r = routed[n.idx] ?: return@flatMap emptyList()
        r.pipes.map { t -> dims.tile(t).let { PlacedEntity(GameConstants.PIPE, Kind.PIPE, it.x, it.y, fluid = n.fluid, net = n.planId) } } +
            r.ptgs.map { e -> dims.tile(e shr 2).let { PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, it.x, it.y, Dir.entries[e and 3], fluid = n.fluid, net = n.planId) } }
    }
}
