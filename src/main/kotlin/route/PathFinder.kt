package route

import config.RouteTuning
import config.RunConfiguration
import data.GameConstants
import geom.Dir
import geom.GridDims
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import place.Placement
import place.portCandidates
import plan.Net
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import plan.bestInserterRate
import plan.inserterCount
import plan.inserterRate
import validate.OutputSpec
import validate.SourceSpec
import viz.HeatOverlay
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RouteProblem(
    val plan: ProductionPlan,
    val config: RunConfiguration,
    val tuning: RouteTuning,
    val placement: Placement,
    /** Extra fixed obstacles (forbidden tiles, fixed ports, reserved pole lattice, power reservations). */
    val obstacles: Set<Tile> = emptySet(),
    /** Extra per-tile cost on belts, e.g. to keep tiles free for poles. */
    val tileBias: Map<Tile, Double> = emptyMap(),
    /** History from an earlier run on the same placement (warm start). */
    val warmHistory: FloatArray? = null,
    val debugDir: File? = null,
    /** Varies the endgame's shuffles, so repeated attempts on one placement differ. */
    val endgameSeed: Long = 0,
    /** Tiles no underground may pass under (blocks, when stitching a split factory). */
    val noTunnel: Set<Tile> = emptySet(),
)

data class RouteStats(val belts: Int, val undergrounds: Int, val inserters: Int)

/** One routed chain, for refitting the placer's q(k): terminals, half-perimeter, belt tiles used. */
data class ChainStat(val item: String, val terminals: Int, val hpwl: Int, val tiles: Int)

class RouteResult(
    val success: Boolean,
    /** Machines, fluid pipes and everything routed; no poles. Best effort when not successful. */
    val entities: List<PlacedEntity>,
    val iterations: Int,
    val overuse: Int,
    /** PathFinder history cost per box tile, for the placer's feedback term. */
    val history: Map<Tile, Double>,
    val rawHistory: FloatArray,
    val failedNets: List<Int>,
    val sources: List<SourceSpec>,
    /** Where products leave the block (output ports). */
    val outputs: List<OutputSpec> = emptyList(),
    /** Tiles still in conflict when routing ended (empty on success). */
    val conflicts: List<Tile> = emptyList(),
    /** Up to 40 conflict tiles with the plan nets (items, fluids) that meet there, for progress output. */
    val conflictNets: Map<Tile, Set<Int>> = emptyMap(),
    val di: List<DiLink>,
    val stats: RouteStats,
    /** Belt chains after splitting nets. */
    val chains: Int,
    val chainStats: List<ChainStat>,
    val millis: Long,
    val log: List<String>,
)

/**
 * Stage 3: negotiated-congestion belt routing (PathFinder). Every net is a chain routed leg by leg with A*
 * over (tile, heading, belt or underground exit). Overlaps are allowed but priced; prices of contested
 * resources rise each iteration until nothing is overused.
 */
class PathFinder(internal val p: RouteProblem) {
    internal val plan = p.plan
    internal val config = p.config
    internal val tun = p.tuning
    internal val W = p.placement.w
    internal val H = p.placement.h
    val dims = GridDims(W, H, MARGIN)
    internal val N = dims.size
    internal val FW = dims.fullW
    internal val tier = GameConstants.beltTier(config.beltTier)
    private val maxJ = tier.maxUndergroundGap + 1
    private val ugAllowed = config.trick("B2")
    /** Tiles undergrounds may not pass under ([RouteProblem.noTunnel]). */
    private val noTunnel = BooleanArray(dims.fullW * dims.fullH).also { a -> p.noTunnel.forEach { t -> if (dims.inFull(t.x, t.y)) a[dims.idx(t)] = true } }
    /** Weaving tier (B5): its undergrounds have their own channels, so they pass under the main tier's. */
    private val weave = config.weave()
    private val maxJw = (weave?.maxUndergroundGap ?: -1) + 1
    /** Channels: tier * 2N + axis * N + tile (tier 0 main, 1 weaving). */
    private val CH = 4 * N
    /** The net being routed may use the weaving tier (its flow fits that tier's belts). */
    private var weaveOk = false
    internal val log = mutableListOf<String>()
    /** Overuse (+ unrouted penalty) after each iteration. */
    val trace = ArrayList<Int>()

    internal val dIdx = intArrayOf(-FW, 1, FW, -1)
    internal val dX = intArrayOf(0, 1, 0, -1)
    internal val dY = intArrayOf(-1, 0, 1, 0)

    // ---- static tile data ----
    /** -2: never; -1: any net; n >= 0: only net index n (fixed ports). */
    internal val allowNet = IntArray(N) { -2 }
    internal val pinCount = IntArray(N)
    internal val bias = FloatArray(N)

    // ---- terminals and options ----
    private val eff: Map<Int, EffectiveTerminal>
    val di: List<DiLink>

    /**
     * A chain's share of a plan terminal. Nets with many inserter visits (or more flow than one belt) are
     * split into several chains after direct insertion, so one producer may feed two chains.
     */
    private class VT(val id: Int, val base: Terminal, val rate: Double, val k: Int, var net: Int) {
        /** Which of its chain's items this is: 0, or 1 for the second item of a shared belt (its lane). */
        var slot = 0
        val role get() = base.role
        val machine get() = base.machine
    }
    private val vts = ArrayList<VT>()
    private lateinit var termNet: IntArray
    private lateinit var termK: IntArray
    private lateinit var need: IntArray
    private lateinit var termOpts: Array<IntArray>
    private val sinkK = HashMap<Int, Int>()
    /** Terminal visiting order of each chain's last successful route; reroutes follow it (VPR keeps net topology too). */
    private lateinit var netOrder: Array<IntArray?>
    /** The chain's order while routing, and the leg's target terminal (-1: any claimable one). */
    private var order: IntArray? = null
    private var legTarget = -1
    private val options = ArrayList<AccessOption>()
    private lateinit var optBelt: IntArray
    private lateinit var optIns: IntArray
    private lateinit var optTerm: IntArray
    private lateinit var optLong: BooleanArray
    private lateinit var atBeltStart: IntArray
    private lateinit var atBeltList: IntArray
    private val sinkOptions = HashMap<Int, List<AccessOption>>()

    private data class RNet(
        val idx: Int, val planId: Int, val item: String, val terms: IntArray, val portTiles: IntArray, val portOut: IntArray,
        val hpwl: Int, val sourceRate: Double,
        /** Output port: candidate edge tiles, their outward direction (-1: any), and this chain's share of the output. */
        val outTiles: IntArray = IntArray(0), val outOut: IntArray = IntArray(0), val outRate: Double = 0.0,
        /** Second item of a shared belt (right lane; [item] takes the left lane), and its port rate. */
        val item2: String? = null, val sourceRate2: Double = 0.0,
        /** Plan ports this chain starts from and ends at (-1: none), for telling ports of one item apart. */
        val srcPort: Int = -1, val outPort: Int = -1,
        /** Plan port of [item2] on a shared belt (-1: none). */
        val srcPort2: Int = -1,
    )
    private val nets = ArrayList<RNet>()

    // ---- dynamic congestion state ----
    internal val occTile = IntArray(N)
    private val occBelt = IntArray(N)
    /** Chain ends pointing into each tile: total, and per direction they point (tile * 4 + dir). */
    private val deadEndAt = IntArray(N)
    private val deadEndDir = IntArray(N * 4)
    /** Belt-family entities on each tile that take items moving in each direction (tile * 4 + dir). */
    private val beltAccept = IntArray(N * 4)
    private val occChan = IntArray(4 * N)
    internal val hist = FloatArray(N)
    private val histChan = FloatArray(4 * N)
    internal var pFac = tun.pFacFirst.toFloat()

    private class Routed {
        val belts = ArrayList<Int>()      // tile * 4 + dir
        val ugIn = ArrayList<Int>()
        val ugOut = ArrayList<Int>()
        /** Tier of each underground pair (0 main, 1 weaving), parallel to [ugIn] and [ugOut]. */
        val ugTier = ArrayList<Int>()
        val chans = ArrayList<Int>()      // tier * 2N + axis * N + tile
        val claims = ArrayList<Int>()     // option ids
        /** Where the chain's leaves point (the final head, and heads left behind by branches): tile * 4 + dir. */
        val deadEnds = ArrayList<Int>()
        /** Splitters inserted for branches: [trunk tile, branch tile, direction]. */
        val splitters = ArrayList<IntArray>()
        /** Tiles kept free of this net (where a leaf points). */
        val reserved = ArrayList<Int>()
        /** Belts placed once every producer was visited; branches may start from these on. */
        var branchFromBelt = -1
        var sourceTile = -1
        /** Edge tile where the chain leaves the block (output port), or -1. */
        var outTile = -1
        var ok = false
        val tiles = ArrayList<Int>()      // every tile this net occupies
    }
    private lateinit var routed: Array<Routed?>
    private val sinkClaims = ArrayList<Pair<AccessOption, Tile>>()   // (inserter option, chest tile)

    // ---- per-net own markers ----
    private val ownStamp = IntArray(N)
    private val ownBeltStamp = IntArray(N)
    private val ownChanStamp = IntArray(4 * N)
    /**
     * Direction of this net's belt or underground exit on a tile (valid where ownBeltStamp matches); -1 on an
     * underground entrance, which feeds nothing in front of it.
     */
    private val ownDir = IntArray(N)
    /** The leg being searched heads for the output port (every terminal is served). */
    private var outputLeg = false
    private val outDirAt = IntArray(N)
    /** Branch start tile -> trunk tile the splitter goes on, for the current search. */
    private val branchFrom = HashMap<Int, Int>()
    private var stamp = 0
    private var cur = -1

    // ---- A* working arrays ----
    internal val S = N * 8
    internal val gArr = FloatArray(S)
    internal val par = IntArray(S)
    internal val seenGen = IntArray(S)
    internal val closedGen = IntArray(S)
    internal var gen = 0
    internal val heap = MinHeap(4096)
    internal val dist = IntArray(N)
    internal val goalStamp = IntArray(N)
    internal var goalGen = 0
    private val resStamp = IntArray(N)
    private val startStamp = IntArray(N)
    private val pathStamp = IntArray(N)
    private val pathFirst = IntArray(N)
    private val forbidStamp = IntArray(N * 8)
    private var forbidGen = 0
    private val pathChanStamp = IntArray(2 * N)
    private var pathGen = 0
    private val startOpt = IntArray(N) { -1 }
    private val doneState = ArrayList<Int>()
    private val doneOpt = ArrayList<Int>()
    internal val minStep: Float
    internal val hWeight = tun.heuristicWeight.toFloat()
    var expansions = 0L
        private set

    init {
        val placement = p.placement
        // Box tiles are free unless a machine, obstacle or fluid tile sits there.
        for (y in 0 until H) for (x in 0 until W) allowNet[dims.idx(x, y)] = -1
        val machineRects = placement.machines.map { placement.rect(plan, it) }
        machineRects.forEach { r -> r.tiles().forEach { if (dims.inFull(it.x, it.y)) allowNet[dims.idx(it)] = -2 } }
        // Used fluid connection tiles hold a pipe end; belts never go there.
        placement.machines.forEach { m -> placement.connections(plan, m).filter { it.fluid != null }.forEach { c -> if (dims.inFull(c.tile.x, c.tile.y)) allowNet[dims.idx(c.tile)] = -2 } }
        p.obstacles.forEach { if (dims.inFull(it.x, it.y)) allowNet[dims.idx(it)] = -2 }
        p.tileBias.forEach { (t, v) -> if (dims.inFull(t.x, t.y)) bias[dims.idx(t)] = v.toFloat() }
        p.warmHistory?.let { it.copyInto(hist, 0, 0, minOf(it.size, N)) }

        machineRects.forEach { r -> Dir.entries.forEach { side -> r.sideTiles(side, 1).forEach { if (dims.inFull(it.x, it.y)) pinCount[dims.idx(it)]++ } } }

        fun freeTile(t: Tile) = dims.inBox(t.x, t.y) && allowNet[dims.idx(t)] == -1

        // Direct insertion claims gap tiles before anything else.
        di = assignDirectInsertion(plan, placement, config, ::freeTile)
        di.forEach { link -> link.tiles.forEach { allowNet[dims.idx(it)] = -2 } }
        eff = effectiveTerminals(plan, di, config)

        // Nets that still have a producer side and a consumer side, split into chains where needed.
        val rectOf = placement.machines.associate { it.id to placement.rect(plan, it) }
        fun center(t: Terminal): Pair<Double, Double> = rectOf.getValue(t.machine!!).let { (it.x + it.w / 2.0) to (it.y + it.h / 2.0) }
        val dropRate = bestInserterRate(config, fromBelt = false)
        val beltCap = tier.throughput * tun.chainBeltShare
        plan.beltNets.forEach { net ->
            val ts = net.terminals.mapNotNull { eff[it] }
            val consumers = ts.filter { it.base.role == Role.CONSUMER && it.base.machine != null }
            val producers = ts.filter { it.base.role == Role.PRODUCER }
            val source = ts.firstOrNull { it.base.role == Role.SOURCE }
            val output = ts.firstOrNull { it.base.role == Role.OUTPUT }
            if ((consumers.isEmpty() && output == null) || (producers.isEmpty() && source == null)) return@forEach
            val sources = ts.filter { it.base.role == Role.SOURCE }
            val outputs = ts.filter { it.base.role == Role.OUTPUT }
            val single = (sources + outputs).any { plan.ports[it.base.port!!].single }
            if (single || sources.size > 1 || outputs.size > 1 || (sources.isNotEmpty() && producers.isNotEmpty())) {
                buildGeneralChains(net, consumers, producers, sources, outputs, ::center, rectOf, dropRate, bestInserterRate(config, fromBelt = true), beltCap)
                return@forEach
            }
            val visits = consumers.sumOf { it.k } + producers.sumOf { it.k }
            val flow = consumers.sumOf { it.rate } + (output?.rate ?: 0.0)
            // Every chain starts on its own port tile (and ends on one at an output port): a port narrower than
            // the visits would split into gets fewer, longer chains, which branch with splitters instead.
            val room = minOf(source?.let { portTilesOf(it.base.port!!).size } ?: Int.MAX_VALUE,
                output?.let { portTilesOf(it.base.port!!).size } ?: Int.MAX_VALUE)
            val g0 = maxOf(
                minOf(ceilDiv(visits, tun.maxChainVisits), room),
                kotlin.math.ceil(flow / beltCap - 1e-9).toInt(),
                1,
            )
            // Each chain ends at the output port when there is one, so chains need not have consumers.
            val g = if (output != null) g0 else g0.coerceAtMost(consumers.size)
            val groups: List<List<EffectiveTerminal>> = when {
                g == 1 -> listOf(consumers)
                consumers.size >= g -> groupConsumers(consumers, g, ::center)
                else -> consumers.map { listOf(it) } + List(g - consumers.size) { emptyList() }
            }
            val outShare = (output?.rate ?: 0.0) / groups.size
            var outCands: List<Pair<Tile, Dir?>> = emptyList()
            if (output != null) {
                outCands = portCandidates(plan.ports[output.base.port!!].specs, W, H)
                    .filter { (t, _) -> dims.inFull(t.x, t.y) && (dims.inBox(t.x, t.y) || isRing1(t)) }
            }

            val remaining = producers.associateWith { it.rate }.toMutableMap()
            groups.forEach { group ->
                val idx = nets.size
                val demand = group.sumOf { it.rate } + outShare
                val members = ArrayList<VT>()
                group.forEach { c -> members += VT(vts.size + members.size, c.base, c.rate, c.k, idx) }
                if (source == null) {
                    // Nearest producers with spare output supply this chain (around its consumers, else all producers).
                    val around = group.ifEmpty { remaining.filter { it.value > 1e-9 }.keys.toList().ifEmpty { producers } }
                    val gx = around.map { center(it.base).first }.average()
                    val gy = around.map { center(it.base).second }.average()
                    var left = demand
                    while (left > 1e-9) {
                        val pr = remaining.filter { it.value > 1e-9 }.keys
                            .minByOrNull { val (x, y) = center(it.base); abs(x - gx) + abs(y - gy) } ?: break
                        val take = minOf(left, remaining.getValue(pr))
                        remaining[pr] = remaining.getValue(pr) - take
                        left -= take
                        // A producer whose whole spare goes here keeps its k; a share gets its own count.
                        val k = if (abs(take - pr.rate) < 1e-9) pr.k else inserterCount(take, dropRate)
                        members += VT(vts.size + members.size, pr.base, take, k, idx)
                    }
                    if (left > 1e-6) log += "net ${net.id} (${net.item}): chain short of ${"%.2f".format(left)}/s supply"
                }
                vts += members
                var portTiles = IntArray(0)
                var portOut = IntArray(0)
                if (source != null) {
                    val cands = portCandidates(plan.ports[source.base.port!!].specs, W, H)
                        .filter { (t, _) -> dims.inFull(t.x, t.y) && (dims.inBox(t.x, t.y) || isRing1(t)) }
                    portTiles = cands.map { dims.idx(it.first) }.toIntArray()
                    portOut = cands.map { it.second?.ordinal ?: -1 }.toIntArray()
                    cands.forEach { (t, out) ->
                        val i = dims.idx(t)
                        // Fixed ports (no outward edge) belong to this chain alone; edge segments stay shared.
                        if (out == null && (allowNet[i] != -2 || !dims.inBox(t.x, t.y))) allowNet[i] = idx
                    }
                }
                outCands.forEach { (t, out) ->
                    val i = dims.idx(t)
                    if (out == null && (allowNet[i] != -2 || !dims.inBox(t.x, t.y))) allowNet[i] = idx
                }
                val pts = members.mapNotNull { it.machine?.let { m -> rectOf.getValue(m) } }.map { Tile(it.x + it.w / 2, it.y + it.h / 2) } +
                    portTiles.map { dims.tile(it) } + outCands.take(1).map { it.first }
                val hpwl = (pts.maxOf { it.x } - pts.minOf { it.x }) + (pts.maxOf { it.y } - pts.minOf { it.y })
                nets += RNet(idx, net.id, net.item, members.map { it.id }.toIntArray(), portTiles, portOut, hpwl, if (source != null) demand else 0.0,
                    outCands.map { dims.idx(it.first) }.toIntArray(), outCands.map { it.second?.ordinal ?: -1 }.toIntArray(), outShare,
                    srcPort = source?.base?.port ?: -1, outPort = output?.base?.port ?: -1)
            }
        }
        if (config.laneSharing) pairChains()
        routed = arrayOfNulls(nets.size)
        netOrder = arrayOfNulls(nets.size)
        termNet = IntArray(vts.size) { vts[it].net }
        termK = IntArray(vts.size) { vts[it].k }
        need = IntArray(vts.size)
        termOpts = Array(vts.size) { IntArray(0) }

        // Access options for chain terminals, sink options for sinks.
        val opts = ArrayList<AccessOption>()
        val optVt = ArrayList<Int>()
        vts.forEach { v ->
            val pm = placement.machines.first { it.id == v.machine }
            val list = ArrayList<Int>()
            accessOptions(v.base, v.k, v.rate, placement.rect(plan, pm), config, ::freeTile).forEach { o ->
                list += opts.size; opts += o; optVt += v.id
            }
            termOpts[v.id] = list.toIntArray()
        }
        plan.terminals.forEach { t ->
            if (t.role != Role.SINK) return@forEach
            val e = eff[t.id] ?: return@forEach
            sinkK[t.id] = e.k
            val pm = placement.machines.first { it.id == t.machine }
            sinkOptions[t.id] = accessOptions(t, e.k, e.rate, placement.rect(plan, pm), config, ::freeTile)
        }
        options.addAll(opts)
        optBelt = IntArray(opts.size) { dims.idx(opts[it].beltTile) }
        optIns = IntArray(opts.size) { dims.idx(opts[it].insTile) }
        optTerm = IntArray(opts.size) { optVt[it] }
        optLong = BooleanArray(opts.size) { opts[it].long }
        // CSR index belt tile -> options.
        val counts = IntArray(N + 1)
        optBelt.forEach { counts[it + 1]++ }
        for (i in 0 until N) counts[i + 1] += counts[i]
        atBeltStart = counts
        atBeltList = IntArray(opts.size)
        val fill = counts.copyOf()
        optBelt.forEachIndexed { o, b -> atBeltList[fill[b]++] = o }

        val mj = maxOf(maxJ, maxJw)
        val ugStep = ((2 * tun.beltBase + tun.ugPenalty + tun.ugPenaltyPerTile * mj) / (mj + 1)).toFloat()
        minStep = if (ugAllowed) minOf(tun.beltBase.toFloat(), ugStep) else tun.beltBase.toFloat()
    }

    private fun isRing1(t: Tile) = !dims.inBox(t.x, t.y) && t.x >= -1 && t.y >= -1 && t.x <= W && t.y <= H

    // ---- cost functions ----
    private fun placeable(q: Int): Boolean {
        val a = allowNet[q]
        return (a == -1 || a == cur) && ownStamp[q] != stamp
    }

    /** Cost of a belt-family entity on [q] that would take items from [deadTaken] chain ends pointing into it. */
    private fun beltCost(q: Int, deadTaken: Int = deadEndAt[q]): Float =
        (tun.beltBase.toFloat() + hist[q]) * (1f + pFac * (occTile[q] + deadTaken)) +
            (if (pinCount[q] > 0) tun.pinReservation.toFloat() else 0f) + bias[q]

    private fun insCost(q: Int, long: Boolean): Float =
        ((if (long) tun.longInserterBase else tun.inserterBase).toFloat() + hist[q]) * (1f + pFac * occTile[q])

    private fun chestCost(q: Int): Float = (tun.chestBase.toFloat() + hist[q]) * (1f + pFac * occTile[q])

    private fun chanCost(c: Int): Float = histChan[c] + (1f + histChan[c]) * pFac * occChan[c]

    /** Cost of a chain end pointing into [t2] in direction [d]: only belts there that would take its items count. */
    private fun deadEndCost(t2: Int, d: Int): Float {
        if (t2 < 0 || t2 >= N) return 0f
        val a = beltAccept[t2 * 4 + d]
        return if (a == 0) 0f else (1f + hist[t2]) * pFac * a * tun.deadEndConflict.toFloat()
    }

    // ---- which way belt-family entities take items (the validator's rule) ----
    /** Directions of travel a belt (or underground entrance) facing [h] takes items from, as a bit mask. */
    private fun acceptMask(kind: Int, h: Int): Int = when (kind) {
        AK_UG_OUT -> (1 shl ((h + 1) and 3)) or (1 shl ((h + 3) and 3))   // sides only
        AK_SPLITTER -> 1 shl h                                            // from behind only
        else -> 0xF and (1 shl ((h + 2) and 3)).inv()                    // anything but head-on
    }

    private fun accept(t: Int, kind: Int, h: Int, delta: Int) {
        val m = acceptMask(kind, h)
        for (d in 0..3) if ((m shr d) and 1 == 1) beltAccept[t * 4 + d] += delta
    }

    /** Chain ends that an entity of [kind] facing [h] on [q] would take items from. */
    private fun deadEndsTaken(q: Int, kind: Int, h: Int): Int {
        val m = acceptMask(kind, h)
        var c = 0
        for (d in 0..3) if ((m shr d) and 1 == 1) c += deadEndDir[q * 4 + d]
        return c
    }

    private fun addDeadEnd(r: Routed, t2: Int, d: Int) {
        r.deadEnds += t2 * 4 + d; deadEndDir[t2 * 4 + d]++; deadEndAt[t2]++
    }

    private fun removeDeadEnd(e: Int) { deadEndDir[e]--; deadEndAt[e shr 2]-- }

    /** Chain ends pointing into [t] that a belt there would take items from. */
    private fun deadEndConflicts(t: Int): Int {
        var c = 0
        for (d in 0..3) if (beltAccept[t * 4 + d] > 0) c += deadEndDir[t * 4 + d]
        return c
    }

    // ---- state encoding ----
    private fun st(tile: Int, dir: Int, kind: Int) = (((tile shl 2) or dir) shl 1) or kind

    // ---- phases ----
    // ---- chain order: a consumer slot may be claimed once the supply upstream of it covers its share ----
    /** Items/s on the chain at its current head: supply dropped so far minus what consumers took. */
    private val surplus = DoubleArray(2)   // per item slot of the chain
    /** The chain being routed carries two items, one per lane (slot 0 left, slot 1 right). */
    private var paired = false

    private fun share(v: Int) = vts[v].rate / vts[v].k
    private fun claimable(v: Int) = need[v] > 0 && (vts[v].role == Role.PRODUCER || surplus[vts[v].slot] >= share(v) - 1e-9)

    /** Items/s on each lane of the chain at its head (0 left, 1 right of the belt's heading). */
    private val laneLoad = DoubleArray(2)
    private val laneCap get() = tier.throughput / 2 * 0.98

    /**
     * Lane option [o]'s inserter drops onto when the belt at its tile heads [d] and items enter it heading [into]
     * (a curve when they differ), as the validator models it ([GameConstants.dropLane]).
     */
    private fun dropLane(o: Int, d: Int, into: Int = d): Int {
        // Long-handed options sit two tiles from their belt, so compare by direction only.
        val dx = Integer.signum(dims.x(optBelt[o]) - dims.x(optIns[o])); val dy = Integer.signum(dims.y(optBelt[o]) - dims.y(optIns[o]))
        val swing = Dir.entries.first { it.dx == dx && it.dy == dy }
        return GameConstants.dropLane(swing, Dir.entries[into], Dir.entries[d])
    }

    /**
     * A producer option is usable only if its lane still has room, and on a shared belt only if it drops onto
     * its item's lane; consumers take from either lane.
     */
    private fun laneOk(o: Int, d: Int, into: Int = d): Boolean {
        val v = optTerm[o]
        if (vts[v].role != Role.PRODUCER) return true
        val lane = dropLane(o, d, into)
        if (paired && lane != vts[v].slot) return false
        return laneLoad[lane] + share(v) <= laneCap + 1e-9
    }
    private fun remainingVisits(n: RNet) = n.terms.sumOf { maxOf(0, need[it]) }

    // ---- A* ----
    private class Start(val state: Int, val g: Float, val opt: Int)

    /**
     * One leg: from [starts] to any option of a terminal still needing a visit in phase [ph].
     * Returns the index into doneState/doneOpt, or -1.
     */
    private fun search(n: RNet, starts: List<Start>, final: Boolean, leg1: Boolean, banned: Set<Int> = emptySet()): Int {
        forbidGen++
        for (round in 0..tun.repairRestarts) {
            val r = searchOnce(n, starts, final, leg1, banned)
            if (r != REPAIR) return r
        }
        return -1
    }

    /** One A* pass; returns a done index, -1 for no path, or [REPAIR] when it forbade a state and should rerun. */
    private fun searchOnce(n: RNet, starts: List<Start>, final: Boolean, leg1: Boolean, banned: Set<Int>): Int {
        gen++
        heap.clear()
        doneState.clear(); doneOpt.clear()
        var repaired = false
        // Goal tiles and the L1 distance transform for the heuristic.
        goalGen++
        if (leg1) starts.forEach { startStamp[it.state ushr 3] = goalGen }
        var anyGoal = false
        if (outputLeg) n.outTiles.forEachIndexed { i, t -> goalStamp[t] = goalGen; outDirAt[t] = n.outOut[i]; anyGoal = true }
        else n.terms.forEach { t ->
            if (!claimable(t) || (legTarget >= 0 && t != legTarget)) return@forEach
            termOpts[t].forEach { o -> if (goalUsable(o, leg1)) { goalStamp[optBelt[o]] = goalGen; anyGoal = true } }
        }
        if (!anyGoal) return -1
        // Leg 1: a start option whose inserter tile is a goal's belt tile would void itself on the way.
        val usableStarts = if (leg1) starts.filter { it.opt < 0 || goalStamp[optIns[it.opt]] != goalGen }.ifEmpty { starts } else starts
        distanceTransform()
        // Keep off the access tiles of consumers that cannot be served yet, or they are lost for later.
        n.terms.forEach { t ->
            if (need[t] <= 0 || vts[t].role != Role.CONSUMER || claimable(t)) return@forEach
            termOpts[t].forEach { o -> resStamp[optBelt[o]] = goalGen; resStamp[optIns[o]] = goalGen }
        }
        val res = tun.ownConsumerReservation.toFloat()
        usableStarts.forEach { s ->
            val tile = s.state ushr 3
            if (forbidStamp[s.state] == forbidGen) return@forEach
            if (seenGen[s.state] != gen || s.g < gArr[s.state]) {
                seenGen[s.state] = gen
                gArr[s.state] = s.g
                par[s.state] = -1
                heap.push(s.g + hWeight * minStep * dist[tile], s.state)
            }
            if (leg1) startOpt[tile] = s.opt
        }
        var budget = tun.maxExpansions
        while (!heap.isEmpty()) {
            val s = heap.pop()
            if (s >= S) {
                val di = s - S
                val bad = validDone(n, doneState[di], doneOpt[di], final, leg1)
                if (bad == -1) return di
                if (bad >= 0 && forbidStamp[bad] != forbidGen) { forbidStamp[bad] = forbidGen; repaired = true }
                continue
            }
            if (closedGen[s] == gen) continue
            closedGen[s] = gen
            if (--budget < 0) { log += "net ${n.planId}: search budget exhausted"; return -1 }
            expansions++
            val t = s ushr 3
            val d = (s ushr 1) and 3
            val kind = s and 1
            val gS = gArr[s]

            // Output leg: a belt on a port tile facing out of the box.
            if (outputLeg && kind == BELT && goalStamp[t] == goalGen && par[s] != -1 && s !in banned && (outDirAt[t] < 0 || outDirAt[t] == d)) {
                doneState += s; doneOpt += -1
                heap.push(gS + deadEndCost(t + dIdx[d], d), S + doneState.size - 1)
            }
            // Goal: a belt on an option's belt tile (not the very start state).
            if (!outputLeg && kind == BELT && goalStamp[t] == goalGen && par[s] != -1 && s !in banned) {
                val t2 = t + dIdx[d]
                for (k in atBeltStart[t] until atBeltStart[t + 1]) {
                    val o = atBeltList[k]
                    val term = optTerm[o]
                    // Items enter this tile heading the way the previous state heads: a curve when that differs.
                    val into = if (par[s] != -1) (par[s] ushr 1) and 3 else d
                    if (termNet[term] != n.idx || !claimable(term) || (legTarget >= 0 && term != legTarget) || !laneOk(o, d, into)) continue
                    val ins = optIns[o]
                    if (!goalUsable(o, leg1)) continue
                    val extra: Float = if (final) {
                        if (t2 in 0 until N && ownBeltStamp[t2] == stamp) continue
                        deadEndCost(t2, d)
                    } else {
                        if (!placeable(t2) || t2 == ins || !canContinue(t, d, t2)) continue
                        0f
                    }
                    doneState += s; doneOpt += o
                    heap.push(gS + insCost(ins, optLong[o]) + extra, S + doneState.size - 1)
                }
            }

            // Belt step (B1).
            val q = t + dIdx[d]
            if (!placeable(q)) continue
            val resQ = if (resStamp[q] == goalGen) res else 0f
            for (nd in 0..3) {
                if (nd == (d + 2) % 4) continue
                if (kind == UG_EXIT && nd != d && !tun.turnAfterUgExit) continue
                relax(st(q, nd, BELT), s, gS + beltCost(q, deadEndsTaken(q, AK_BELT, nd)) + resQ + (if (nd != d) tun.turnPenalty.toFloat() else 0f))
            }

            // Underground (B2, B3): entrance on q facing d, exit j tiles further.
            if (ugAllowed) {
                val axis = if (d == 1 || d == 3) 0 else 1
                val bc = beltCost(q, deadEndsTaken(q, AK_UG_IN, d)) + resQ
                val qx = dims.x(q); val qy = dims.y(q)
                // Each tier has its own channels; the exit state keeps the cheaper, and the commit picks it again.
                for (ti in 0 until (if (weaveOk) 2 else 1)) {
                    val ch0 = ti * 2 * N + axis * N
                    if (ownChanStamp[ch0 + q] == stamp) continue
                    var chanAcc = chanCost(ch0 + q) + (if (ti == 1) tun.weavePenalty.toFloat() else 0f)
                    for (j in 1..(if (ti == 0) maxJ else maxJw)) {
                        val ex = qx + dX[d] * j; val ey = qy + dY[d] * j
                        if (!dims.inFull(ex, ey)) break
                        val e = q + dIdx[d] * j
                        if (ownChanStamp[ch0 + e] == stamp || noTunnel[e]) break
                        chanAcc += chanCost(ch0 + e)
                        if (!placeable(e)) continue
                        val cost = gS + bc + beltCost(e, deadEndsTaken(e, AK_UG_OUT, d)) + (if (resStamp[e] == goalGen) res else 0f) +
                            (tun.ugPenalty + tun.ugPenaltyPerTile * j).toFloat() + chanAcc
                        relax(st(e, d, UG_EXIT), s, cost)
                    }
                }
            }
        }
        return if (repaired) REPAIR else -1
    }

    /** A goal option is usable if its inserter tile is free of this net and, in leg 1, not a start tile. */
    private fun goalUsable(o: Int, leg1: Boolean): Boolean =
        ownStamp[optIns[o]] != stamp && !(leg1 && startStamp[optIns[o]] == goalGen)

    /** Look-ahead for a leg's end: from [t2] (where the head at [t] points) the next leg must have somewhere to go. */
    private fun canContinue(t: Int, d: Int, t2: Int): Boolean {
        for (dd in 0..3) { val x = t2 + dIdx[dd]; if (x != t && placeable(x)) return true }
        if (!ugAllowed) return false
        val axis = if (d == 1 || d == 3) 0 else 1
        val x0 = dims.x(t2); val y0 = dims.y(t2)
        for (ti in 0 until (if (weaveOk) 2 else 1)) {
            val ch0 = ti * 2 * N + axis * N
            if (ownChanStamp[ch0 + t2] == stamp) continue
            for (j in 1..(if (ti == 0) maxJ else maxJw)) {
                if (!dims.inFull(x0 + dX[d] * j, y0 + dY[d] * j)) break
                val e = t2 + dIdx[d] * j
                if (ownChanStamp[ch0 + e] == stamp || noTunnel[e]) break
                if (placeable(e)) return true
            }
        }
        return false
    }

    private fun relax(ns: Int, from: Int, g: Float) {
        if (closedGen[ns] == gen || forbidStamp[ns] == forbidGen) return
        if (seenGen[ns] == gen && g >= gArr[ns]) return
        seenGen[ns] = gen
        gArr[ns] = g
        par[ns] = from
        heap.push(g + hWeight * minStep * dist[ns ushr 3], ns)
    }

    /** L1 distance from every tile to the nearest goal tile (two-pass chamfer). */
    internal fun distanceTransform() {
        val big = 1 shl 20
        for (i in 0 until N) dist[i] = if (goalStamp[i] == goalGen) 0 else big
        val fh = dims.fullH
        for (y in 0 until fh) for (x in 0 until FW) {
            val i = y * FW + x
            var v = dist[i]
            if (x > 0) v = minOf(v, dist[i - 1] + 1)
            if (y > 0) v = minOf(v, dist[i - FW] + 1)
            dist[i] = v
        }
        for (y in fh - 1 downTo 0) for (x in FW - 1 downTo 0) {
            val i = y * FW + x
            var v = dist[i]
            if (x < FW - 1) v = minOf(v, dist[i + 1] + 1)
            if (y < fh - 1) v = minOf(v, dist[i + FW] + 1)
            dist[i] = v
        }
    }

    /** Path tiles in order, as (state) list from the start to [s]. */
    private fun pathStates(s: Int): List<Int> {
        val out = ArrayList<Int>()
        var c = s
        while (c != -1) { out += c; c = par[c] }
        out.reverse()
        return out
    }

    /** Rejects goals whose path crosses itself, buries the option's inserter tile, or leaves no way on. */
    /**
     * Checks a found leg against what the state graph cannot see: the path may not cross itself, run over
     * the inserter tiles it claims, or end pointing back into itself. Returns -1 if valid, else a path
     * state to forbid before searching again (-2 if there is none to blame).
     */
    private fun validDone(n: RNet, s: Int, o: Int, final: Boolean, leg1: Boolean): Int {
        pathGen++
        val states = pathStates(s)
        // Later legs start on the previous head (already committed) or on a new branch tile (not yet).
        val skipFirst = !leg1 && ownStamp[states[0] ushr 3] == stamp
        for ((i, st) in states.withIndex()) {
            val t = st ushr 3
            val d = (st ushr 1) and 3
            if (i == 0 && skipFirst) continue
            if (pathStamp[t] == pathGen) return pathFirst[t]
            pathStamp[t] = pathGen; pathFirst[t] = st
            if ((st and 1) == UG_EXIT) {
                val ent = (states[i - 1] ushr 3) + dIdx[d]
                if (pathStamp[ent] == pathGen) return st
                pathStamp[ent] = pathGen; pathFirst[ent] = st
                val axis = if (d == 1 || d == 3) 0 else 1
                var c = ent
                while (true) {
                    val ci = axis * N + c
                    if (pathChanStamp[ci] == pathGen) return st
                    pathChanStamp[ci] = pathGen
                    if (c == t) break
                    c += dIdx[d]
                }
            }
        }
        val last0 = states.last()
        if (o < 0) {   // output leg: only the path itself must be clean
            val t2 = (last0 ushr 3) + dIdx[(last0 ushr 1) and 3]
            return if (pathStamp[t2] == pathGen) pathFirst[t2] else -1
        }
        val ins = optIns[o]
        if (pathStamp[ins] == pathGen) return pathFirst[ins]
        if (leg1) {
            val so = startOpt[states[0] ushr 3]
            if (so >= 0 && optIns[so] == ins) return -2
            if (so >= 0 && pathStamp[optIns[so]] == pathGen) return pathFirst[optIns[so]]
        }
        val last = states.last()
        val t2 = (last ushr 3) + dIdx[(last ushr 1) and 3]
        if (pathStamp[t2] == pathGen) return pathFirst[t2]
        if (!final && t2 == ins) return -2
        return -1
    }

    // ---- commit / rip-up ----
    private fun occupy(r: Routed, t: Int, belt: Boolean) {
        occTile[t]++
        if (belt) { occBelt[t]++; ownBeltStamp[t] = stamp }
        ownStamp[t] = stamp
        r.tiles += t
    }

    private fun claim(r: Routed, o: Int, d: Int, into: Int = d) {
        r.claims += o
        occupy(r, optIns[o], false)
        val v = optTerm[o]
        need[v]--
        surplus[vts[v].slot] += if (vts[v].role == Role.PRODUCER) share(v) else -share(v)
        if (vts[v].role == Role.PRODUCER) laneLoad[dropLane(o, d, into)] += share(v)
        else if (paired) laneLoad[vts[v].slot] = maxOf(0.0, laneLoad[vts[v].slot] - share(v))
        else {
            val total = laneLoad[0] + laneLoad[1]
            if (total > 1e-12) { val keep = maxOf(0.0, 1 - share(v) / total); laneLoad[0] *= keep; laneLoad[1] *= keep }
        }
    }

    private fun ripUp(i: Int) {
        val r = routed[i] ?: return
        r.belts.forEach { e -> val t = e shr 2; occTile[t]--; occBelt[t]--; accept(t, AK_BELT, e and 3, -1) }
        r.ugIn.forEach { e -> val t = e shr 2; occTile[t]--; occBelt[t]--; accept(t, AK_UG_IN, e and 3, -1) }
        r.ugOut.forEach { e -> val t = e shr 2; occTile[t]--; occBelt[t]--; accept(t, AK_UG_OUT, e and 3, -1) }
        r.claims.forEach { o -> occTile[optIns[o]]-- }
        r.chans.forEach { occChan[it]-- }
        r.splitters.forEach { sp -> occTile[sp[1]]--; occBelt[sp[1]]--; accept(sp[1], AK_SPLITTER, sp[2], -1) }
        r.deadEnds.forEach { removeDeadEnd(it) }
        routed[i] = null
    }

    /** Puts a ripped-up chain's route back as it was (endgame rollback). */
    private fun restore(i: Int, r: Routed?) {
        if (r == null) return
        r.belts.forEach { e -> val t = e shr 2; occTile[t]++; occBelt[t]++; accept(t, AK_BELT, e and 3, 1) }
        r.ugIn.forEach { e -> val t = e shr 2; occTile[t]++; occBelt[t]++; accept(t, AK_UG_IN, e and 3, 1) }
        r.ugOut.forEach { e -> val t = e shr 2; occTile[t]++; occBelt[t]++; accept(t, AK_UG_OUT, e and 3, 1) }
        r.claims.forEach { o -> occTile[optIns[o]]++ }
        r.chans.forEach { occChan[it]++ }
        r.splitters.forEach { sp -> occTile[sp[1]]++; occBelt[sp[1]]++; accept(sp[1], AK_SPLITTER, sp[2], 1) }
        r.deadEnds.forEach { e -> deadEndDir[e]++; deadEndAt[e shr 2]++ }
        routed[i] = r
    }

    /** Tier for an underground from [ent] to [ex] heading [d]: the one the search priced lower (main on a tie). */
    private fun ugTierFor(ent: Int, ex: Int, d: Int): Int {
        if (!weaveOk) return 0
        val axis = if (d == 1 || d == 3) 0 else 1
        var j = 0; run { var c = ent; while (c != ex) { c += dIdx[d]; j++ } }
        var best = 0; var bestCost = Float.MAX_VALUE
        for (ti in 0..1) {
            if (j > (if (ti == 0) maxJ else maxJw)) continue
            val ch0 = ti * 2 * N + axis * N
            var cost = if (ti == 1) tun.weavePenalty.toFloat() else 0f
            var c = ent
            var ok = true
            while (true) { if (ownChanStamp[ch0 + c] == stamp) { ok = false; break }; cost += chanCost(ch0 + c); if (c == ex) break; c += dIdx[d] }
            if (ok && cost < bestCost) { bestCost = cost; best = ti }
        }
        return best
    }

    /** Commits a found leg: belts, undergrounds, the goal option and options the path passed on the way. */
    private fun commitLeg(n: RNet, r: Routed, s: Int, goalOpt: Int, leg1: Boolean) {
        val states = pathStates(s)
        // 1. Occupy every path tile first, so no claim below can take a tile the belt runs over.
        for ((i, st) in states.withIndex()) {
            val t = st ushr 3
            val d = (st ushr 1) and 3
            if (i == 0 && !leg1) {
                if (ownStamp[t] == stamp) continue
                // A branch: a splitter across the trunk tile and this tile.
                r.splitters += intArrayOf(branchFrom.getValue(t), t, d)
                occupy(r, t, true); ownDir[t] = d; accept(t, AK_SPLITTER, d, 1)
                continue
            }
            if ((st and 1) == UG_EXIT) {
                val ent = (states[i - 1] ushr 3) + dIdx[d]
                val ti = ugTierFor(ent, t, d)
                r.ugIn += ent * 4 + d; occupy(r, ent, true); ownDir[ent] = -1; accept(ent, AK_UG_IN, d, 1)
                r.ugOut += t * 4 + d; occupy(r, t, true); ownDir[t] = d; accept(t, AK_UG_OUT, d, 1)
                r.ugTier += ti
                val ch0 = ti * 2 * N + (if (d == 1 || d == 3) 0 else 1) * N
                var c = ent
                while (true) { val ci = ch0 + c; occChan[ci]++; ownChanStamp[ci] = stamp; r.chans += ci; if (c == t) break; c += dIdx[d] }
            } else {
                r.belts += t * 4 + d; occupy(r, t, true); ownDir[t] = d; accept(t, AK_BELT, d, 1)
            }
        }
        val last = states.last()
        val next = (last ushr 3) + dIdx[(last ushr 1) and 3]   // where the chain continues (or dead-ends)
        // Passing claims skip an inserter tile another net holds when the terminal has a free option further on.
        val lastFree = HashMap<Int, Int>()
        for ((i, st) in states.withIndex()) {
            if (i == 0 || (st and 1) != BELT) continue
            val t = st ushr 3
            for (k in atBeltStart[t] until atBeltStart[t + 1]) {
                val o = atBeltList[k]
                val ins = optIns[o]
                if (termNet[optTerm[o]] == n.idx && occTile[ins] == 0 && allowNet[ins] != -2 && ins != next) lastFree[optTerm[o]] = i
            }
        }
        // 2. Claims in path order: the start option, options passed on the way, then the goal.
        for ((i, st) in states.withIndex()) {
            if ((st and 1) != BELT) continue
            val t = st ushr 3
            val d = (st ushr 1) and 3
            if (i == 0) {
                if (leg1) { val so = startOpt[t]; if (so >= 0) claim(r, so, d) else r.sourceTile = t }
                continue
            }
            val into = (states[i - 1] ushr 1) and 3
            if (goalOpt >= 0 && i == states.size - 1 && claimable(optTerm[goalOpt]) && ownStamp[optIns[goalOpt]] != stamp && laneOk(goalOpt, d, into)) claim(r, goalOpt, d, into)
            for (k in atBeltStart[t] until atBeltStart[t + 1]) {
                val o = atBeltList[k]
                val term = optTerm[o]
                if (termNet[term] != n.idx || !claimable(term) || !laneOk(o, d, into)) continue
                val ins = optIns[o]
                if (ownStamp[ins] == stamp || allowNet[ins] == -2 || ins == next) continue
                if (occTile[ins] > 0 && (lastFree[term] ?: -1) >= i) continue
                claim(r, o, d, into)
            }
        }
    }

    /** Routes one net, trying other start producers (and start options) when the chain gets stuck. */
    private fun routeNet(n: RNet): Boolean {
        netOrder[n.idx]?.takeIf { tun.keepChainOrder }?.let { remembered ->
            order = remembered
            val ok = routeNetFrom(n, if (n.portTiles.isNotEmpty()) -1 else remembered[0], null)
            order = null
            if (ok) return true
        }
        if (n.portTiles.isNotEmpty()) return routeNetFrom(n, -1, null)
        val producers = rankedProducers(n)
        var tries = 0
        for (pt in producers) {
            if (tries >= tun.startAttempts) break
            val opts = termOpts[pt]
            // With k > 1 and no other producer to head for, leg 1 must start on one option so the
            // terminal's other options can be its goals; otherwise start on all of them at once.
            val others = producers.any { it != pt }
            val choices: List<List<Int>?> = if (termK[pt] > 1 && !others) {
                rankOptionsFarFirst(n, opts).take(4).map { listOf(it) }
            } else listOf(null)
            for (c in choices) {
                if (tries++ >= tun.startAttempts + 3) break
                if (routeNetFrom(n, pt, c)) return true
            }
        }
        return false
    }

    /** Routes one net as a chain from producer [startTerm] (-1: from its port), on [startOpts] or all its options. */
    private fun routeNetFrom(n: RNet, startTerm: Int, startOpts: List<Int>?): Boolean {
        stamp++
        cur = n.idx
        // Weaving undergrounds of a slower tier only on chains whose flow fits that tier.
        weaveOk = weave != null && ugAllowed &&
            n.sourceRate + n.terms.filter { vts[it].role == Role.PRODUCER }.sumOf { vts[it].rate } <= weave.throughput * tun.weaveBeltShare
        n.terms.forEach { need[it] = termK[it] }
        paired = n.item2 != null
        surplus[0] = n.sourceRate; surplus[1] = n.sourceRate2
        if (paired) { laneLoad[0] = n.sourceRate; laneLoad[1] = n.sourceRate2 }
        else { laneLoad[0] = n.sourceRate / 2; laneLoad[1] = n.sourceRate / 2 }
        val r = Routed()
        routed[n.idx] = r

        val starts = ArrayList<Start>()
        if (startTerm < 0) {
            n.portTiles.forEachIndexed { i, t ->
                if (!placeable(t)) return@forEachIndexed
                val c = beltCost(t)
                // A shared belt enters straight from the edge, so each item stays on the lane it was fed on.
                // A shared belt, or one whose port moves onto the ring, enters straight from the edge (lanes stay put).
                val straight = paired || (config.portsOnRing && n.portOut[i] >= 0 && dims.inBox(dims.x(t), dims.y(t)))
                for (d in 0..3) if (d != n.portOut[i] && (!straight || d == (n.portOut[i] + 2) % 4)) starts += Start(st(t, d, BELT), c, -1)
            }
        } else {
            val opts = (startOpts ?: termOpts[startTerm].toList()).filter { placeable(optBelt[it]) && placeable(optIns[it]) }
            opts.forEach { o ->
                val c = beltCost(optBelt[o]) + insCost(optIns[o], optLong[o])
                for (d in 0..3) if (!paired || vts[optTerm[o]].role != Role.PRODUCER || dropLane(o, d) == vts[optTerm[o]].slot) starts += Start(st(optBelt[o], d, BELT), c, o)
            }
        }
        if (starts.isEmpty()) return fail(n, "no free start tile")

        // Legs are committed on a stack; when a leg finds no path, the previous one is undone and its goal
        // state banned, so the chain can end that leg somewhere that does not trap it.
        class LegRec(val starts: List<Start>, val leg1: Boolean, val belts: Int, val ugIn: Int, val ugOut: Int,
                     val chans: Int, val claims: Int, val tiles: Int, val sourceTile: Int, val banned: HashSet<Int>, val done: Int,
                     val splitters: Int, val deadEnds: Int, val reserved: Int, val branchFromBelt: Int, val lanes: DoubleArray)
        // A chain fed only by its port may branch anywhere; producers on it must all be passed first.
        if (startTerm < 0 && n.terms.none { vts[it].role == Role.PRODUCER }) r.branchFromBelt = 0
        val legs = ArrayList<LegRec>()
        var leg1 = true
        var current: List<Start> = starts
        var banned = HashSet<Int>()
        var backtracks = 0
        while (true) {
            // The start option counts as visited while searching leg 1; claim() books it on commit.
            if (leg1 && startTerm >= 0) { need[startTerm]--; surplus[vts[startTerm].slot] += share(startTerm) }
            outputLeg = n.outTiles.isNotEmpty() && n.terms.all { need[it] <= 0 }
            // The last terminal ends the chain only when there is no output port to go on to.
            val final = outputLeg || (remainingVisits(n) == 1 && n.outTiles.isEmpty())
            // Following a remembered order: head for the first terminal in it that still needs a visit.
            legTarget = order?.firstOrNull { need[it] > 0 && claimable(it) } ?: -1
            val head = if (leg1) -1 else current[0].state
            val starts = if (leg1) current else current + branchStarts(r, head)
            val found = search(n, starts, final, leg1, banned)
            legTarget = -1
            val wasOutputLeg = outputLeg
            outputLeg = false
            if (leg1 && startTerm >= 0) { need[startTerm]++; surplus[vts[startTerm].slot] -= share(startTerm) }
            if (found < 0) {
                if (legs.isNotEmpty() && backtracks < tun.maxBacktracks) {
                    backtracks++
                    val last = legs.removeAt(legs.size - 1)
                    undoLeg(r, last.belts, last.ugIn, last.ugOut, last.chans, last.claims, last.tiles)
                    while (r.splitters.size > last.splitters) { val sp = r.splitters.removeAt(r.splitters.size - 1); occBelt[sp[1]]--; ownBeltStamp[sp[1]] = 0; accept(sp[1], AK_SPLITTER, sp[2], -1) }
                    while (r.deadEnds.size > last.deadEnds) removeDeadEnd(r.deadEnds.removeAt(r.deadEnds.size - 1))
                    while (r.reserved.size > last.reserved) ownStamp[r.reserved.removeAt(r.reserved.size - 1)] = 0
                    r.branchFromBelt = last.branchFromBelt
                    last.lanes.copyInto(laneLoad)
                    r.sourceTile = last.sourceTile
                    last.banned += last.done
                    current = last.starts; leg1 = last.leg1; banned = last.banned
                    continue
                }
                val detail = n.terms.filter { need[it] > 0 }.joinToString { t ->
                    val free = termOpts[t].count { ownStamp[optIns[it]] != stamp && ownStamp[optBelt[it]] != stamp }
                    "t$t ${vts[t].role.name.take(1)} need ${need[t]} free opts $free/${termOpts[t].size}"
                }
                val reach = if (legs.isEmpty()) {
                    // Diagnostic: belt-step reachability from the start tiles over tiles this net may use.
                    val seen = HashSet<Int>(); val q = ArrayDeque<Int>()
                    current.forEach { st0 -> val t = st0.state ushr 3; if (seen.add(t)) q += t }
                    while (q.isNotEmpty()) { val c = q.removeFirst(); for (dd in 0..3) { val x = c + dIdx[dd]; if (x in 0 until N && placeable(x) && seen.add(x)) q += x } }
                    val g = n.terms.filter { claimable(it) }.sumOf { t -> termOpts[t].count { optBelt[it] in seen } }
                    " starts ${current.size} reach ${seen.size} goals reachable $g"
                } else ""
                return fail(n, "leg ${legs.size + 1} found no path after $backtracks backtracks (surplus ${"%.2f".format(surplus.sum())}, final $final): $detail$reach")
            }
            val s = doneState[found]
            legs += LegRec(current, leg1, r.belts.size, r.ugIn.size, r.ugOut.size, r.chans.size, r.claims.size, r.tiles.size, r.sourceTile, banned, s,
                r.splitters.size, r.deadEnds.size, r.reserved.size, r.branchFromBelt, laneLoad.copyOf())
            // A leg that starts on a branch leaves the old head as a leaf.
            var first = s; while (par[first] != -1) first = par[first]
            val branched = !leg1 && (first ushr 3) != (head ushr 3)
            commitLeg(n, r, s, doneOpt[found], leg1)
            if (branched) {
                val hd = (head ushr 1) and 3
                val t2 = (head ushr 3) + dIdx[hd]
                if (t2 in 0 until N) { addDeadEnd(r, t2, hd); if (ownStamp[t2] != stamp) { ownStamp[t2] = stamp; r.reserved += t2 } }
            }
            if (r.branchFromBelt < 0 && n.terms.none { vts[it].role == Role.PRODUCER && need[it] > 0 }) r.branchFromBelt = r.belts.size
            leg1 = false
            banned = HashSet()
            if (wasOutputLeg) r.outTile = s ushr 3
            if (n.terms.all { need[it] <= 0 } && (n.outTiles.isEmpty() || wasOutputLeg)) {
                val hd = (s ushr 1) and 3
                val t2 = (s ushr 3) + dIdx[hd]
                if (t2 in 0 until N) addDeadEnd(r, t2, hd)
                r.ok = true
                netOrder[n.idx] = r.claims.map { optTerm[it] }.distinct().toIntArray()
                return true
            }
            current = listOf(Start(s, 0f, -1))
        }
    }

    /**
     * Branch starts (B9): a splitter on a straight trunk belt placed after every producer was visited, with
     * its second half beside it; the branch leaves from that half. The leg's search picks a branch only
     * when it beats continuing from the head.
     */
    private fun branchStarts(r: Routed, head: Int): List<Start> {
        branchFrom.clear()
        if (!config.trick("B9") || r.branchFromBelt < 0) return emptyList()
        val out = ArrayList<Start>()
        val headTile = head ushr 3
        val claimedBelts = r.claims.mapTo(HashSet()) { optBelt[it] }
        val splitTiles = r.splitters.flatMapTo(HashSet()) { listOf(it[0], it[1]) }
        for (k in r.branchFromBelt until r.belts.size) {
            val e = r.belts[k]
            val b = e shr 2
            val h = e and 3
            if (b == headTile || b in claimedBelts || b in splitTiles) continue
            val behind = b - dIdx[h]; val front = b + dIdx[h]
            if (behind !in 0 until N || ownBeltStamp[behind] != stamp || ownDir[behind] != h || behind in splitTiles) continue
            if (front !in 0 until N || ownBeltStamp[front] != stamp) continue
            for (side in intArrayOf((h + 1) % 4, (h + 3) % 4)) {
                val sTile = b + dIdx[side]
                if (!placeable(sTile) || !placeable(sTile + dIdx[h])) continue
                val back = sTile - dIdx[h]
                if (back in 0 until N && ownBeltStamp[back] == stamp) continue
                // Anything belt-like behind the second half would feed foreign items into the splitter.
                val foreign = if (back in 0 until N && occBelt[back] > 0) tun.deadEndConflict.toFloat() * 10 else 0f
                out += Start(st(sTile, h, BELT), beltCost(sTile) + tun.splitterCost.toFloat() + foreign, -1)
                branchFrom[sTile] = b
            }
        }
        return out
    }

    /** Takes back everything committed after the given list sizes (one or more legs). */
    private fun undoLeg(r: Routed, belts: Int, ugIn: Int, ugOut: Int, chans: Int, claims: Int, tiles: Int) {
        while (r.claims.size > claims) {
            val o = r.claims.removeAt(r.claims.size - 1)
            val v = optTerm[o]
            need[v]++
            surplus[vts[v].slot] -= if (vts[v].role == Role.PRODUCER) share(v) else -share(v)
        }
        while (r.belts.size > belts) { val e = r.belts.removeAt(r.belts.size - 1); val t = e shr 2; occBelt[t]--; ownBeltStamp[t] = 0; accept(t, AK_BELT, e and 3, -1) }
        while (r.ugIn.size > ugIn) { val e = r.ugIn.removeAt(r.ugIn.size - 1); val t = e shr 2; occBelt[t]--; ownBeltStamp[t] = 0; accept(t, AK_UG_IN, e and 3, -1) }
        while (r.ugOut.size > ugOut) { val e = r.ugOut.removeAt(r.ugOut.size - 1); val t = e shr 2; occBelt[t]--; ownBeltStamp[t] = 0; accept(t, AK_UG_OUT, e and 3, -1) }
        while (r.ugTier.size > ugOut) r.ugTier.removeAt(r.ugTier.size - 1)
        while (r.chans.size > chans) { val c = r.chans.removeAt(r.chans.size - 1); occChan[c]--; ownChanStamp[c] = 0 }
        while (r.tiles.size > tiles) { val t = r.tiles.removeAt(r.tiles.size - 1); occTile[t]--; ownStamp[t] = 0 }
    }

    private var failRenders = 0

    private fun fail(n: RNet, why: String): Boolean {
        log += "net ${n.planId} (${n.item}): $why"
        p.debugDir?.let { dir ->
            if (failRenders++ < 6) {
                val heat = HashMap<Tile, Double>()
                n.terms.filter { need[it] > 0 }.forEach { t -> termOpts[t].forEach { o -> heat[dims.tile(optBelt[o])] = 1.0; heat[dims.tile(optIns[o])] = 0.4 } }
                val head = routed[n.idx]?.belts?.lastOrNull()?.let { dims.tile(it shr 2) }
                renderLayout(Layout(W, H, entities()), File(dir, "fail_%02d_net%d.png".format(failRenders, n.planId)),
                    RenderOptions(px = 28, heat = HeatOverlay(heat, setOfNotNull(head)), title = "net ${n.planId} ${n.item}: $why"))
            }
        }
        ripUp(n.idx)
        return false
    }

    private fun machineCenterOf(m: Int): Tile {
        val pm = p.placement.machines.first { it.id == m }
        val r = p.placement.rect(plan, pm)
        return Tile(r.x + r.w / 2, r.y + r.h / 2)
    }

    private fun machineCenter(term: Int): Tile {
        val m = vts[term].machine!!
        val pm = p.placement.machines.first { it.id == m }
        val r = p.placement.rect(plan, pm)
        return Tile(r.x + r.w / 2, r.y + r.h / 2)
    }

    private fun consumerCentroid(n: RNet): Pair<Double, Double> {
        val cs = n.terms.filter { vts[it].role == Role.CONSUMER }.map { machineCenter(it) }
        return cs.map { it.x.toDouble() }.average() to cs.map { it.y.toDouble() }.average()
    }

    /** Producers by distance from the consumers, farthest first, so the chain flows towards them. */
    private fun rankedProducers(n: RNet): List<Int> {
        val (cx, cy) = consumerCentroid(n)
        return n.terms.filter { vts[it].role == Role.PRODUCER && termOpts[it].isNotEmpty() }
            .sortedByDescending { val c = machineCenter(it); abs(c.x - cx) + abs(c.y - cy) }
    }

    private fun rankOptionsFarFirst(n: RNet, opts: IntArray): List<Int> {
        val (cx, cy) = consumerCentroid(n)
        // Called between attempts: the stamp and current chain still belong to the last one, so only the
        // static allowance counts here (routeNetFrom checks the rest).
        fun open(q: Int) = allowNet[q] == -1 || allowNet[q] == n.idx
        return opts.filter { open(optBelt[it]) && open(optIns[it]) }
            .sortedByDescending { val t = dims.tile(optBelt[it]); abs(t.x - cx) + abs(t.y - cy) }
    }

    // ---- sinks (I7) ----
    private fun solveSinks() {
        sinkClaims.forEach { (o, chest) -> occTile[dims.idx(o.insTile)]--; occTile[dims.idx(chest)]-- }
        sinkClaims.clear()
        sinkOptions.forEach { (term, opts) ->
            val k = sinkK.getValue(term)
            val used = HashSet<Int>()
            val ranked = opts.sortedBy { o ->
                val chest = sinkChestTile(o)
                insCost(dims.idx(o.insTile), false) + chestCost(dims.idx(chest))
            }
            var taken = 0
            for (o in ranked) {
                if (taken == k) break
                val a = dims.idx(o.insTile); val b = dims.idx(sinkChestTile(o))
                if (a in used || b in used) continue
                used += a; used += b
                occTile[a]++; occTile[b]++
                sinkClaims += o to sinkChestTile(o)
                taken++
            }
            if (taken < k) log += "sink terminal $term: only $taken of $k options"
        }
    }

    private fun sinkChestTile(o: AccessOption): Tile = o.beltTile

    // ---- iteration loop ----
    internal fun overuseAt(t: Int): Int =
        max(0, occTile[t] - 1) + deadEndConflicts(t) + (pipes?.adjacencyOveruse(t) ?: 0)

    private fun totalOveruse(): Int {
        var o = 0
        for (t in 0 until N) o += overuseAt(t)
        for (c in 0 until CH) o += max(0, occChan[c] - 1)
        return o + (pipes?.channelOveruse() ?: 0)
    }

    /** Fluid nets routed as pipes ("pipes" mode); created once the static grids exist. */
    private var pipes: PipeRouter? = null

    private fun touchesOveruse(i: Int): Boolean {
        val r = routed[i] ?: return true
        if (r.tiles.any { overuseAt(it) > 0 }) return true
        if (r.chans.any { occChan[it] > 1 }) return true
        if (r.deadEnds.any { beltAccept[it] > 0 }) return true
        return false
    }

    fun run(): RouteResult {
        val t0 = System.currentTimeMillis()
        if (config.fluids.mode == "pipes") pipes = PipeRouter(this)
        val pr = pipes
        // Belt chains (>= 0) and fluid nets (encoded as -1 - i), longest first.
        val order = (nets.map { it.idx to it.hpwl } + (0 until (pr?.count ?: 0)).map { -1 - it to pr!!.hpwl(it) })
            .sortedByDescending { it.second }.map { it.first }
        fun ok(w: Int) = if (w >= 0) routed[w]?.ok == true else pr!!.ok(-1 - w)
        var best = Int.MAX_VALUE
        var sinceBest = 0
        // Routes of the best iteration: negotiation can wander off from it, and the endgame starts there.
        var bestRoutes: Array<Routed?>? = null
        var bestPipes: List<Any?>? = null
        // History as it stood at the best iteration: later detours smear it away from the real hotspots.
        var histBest = hist.copyOf()
        var histChanBest = histChan.copyOf()
        var iter = 0
        var overuse = Int.MAX_VALUE
        var unrouted = nets.map { it.idx }
        var failedFluids = emptyList<Int>()
        while (iter < tun.maxIterations) {
            iter++
            val todo = if (iter <= tun.fullRerouteIterations) order else order.filter { w ->
                !ok(w) || (if (w >= 0) touchesOveruse(w) else pr!!.touchesOveruse(-1 - w, ::overuseAt))
            }
            todo.forEach { w ->
                if (w >= 0) { ripUp(w); routeNet(nets[w]) } else { pr!!.ripUp(-1 - w); pr.route(-1 - w) }
            }
            solveSinks()
            unrouted = nets.filter { routed[it.idx]?.ok != true }.map { it.idx }
            failedFluids = pr?.failed() ?: emptyList()
            overuse = totalOveruse()
            val score = overuse + tun.unroutedPenalty * (unrouted.size + failedFluids.size)
            trace += score
            if (p.debugDir != null || tun.traceDetail) {
                var tileO = 0; var deadO = 0; var chanO = 0
                for (t in 0 until N) { tileO += max(0, occTile[t] - 1); deadO += deadEndConflicts(t) }
                for (c in 0 until CH) chanO += max(0, occChan[c] - 1)
                val beltTiles = nets.sumOf { routed[it.idx]?.let { r -> r.belts.size + r.ugIn.size + r.ugOut.size } ?: 0 }
                // What collides: belt/belt, belt/inserter, inserter/inserter, anything with a sink, pin tiles.
                var bb = 0; var bi = 0; var ii = 0; var sk = 0; var pins = 0
                val sinkTiles = HashSet<Int>().also { set -> sinkClaims.forEach { (o, c) -> set += dims.idx(o.insTile); set += dims.idx(c) } }
                for (t in 0 until N) if (occTile[t] > 1) {
                    val ins = occTile[t] - occBelt[t]
                    when {
                        t in sinkTiles -> sk++
                        occBelt[t] >= 2 && ins == 0 -> bb++
                        occBelt[t] >= 1 && ins >= 1 -> bi++
                        else -> ii++
                    }
                    if (pinCount[t] > 0) pins++
                }
                log += "iter $iter: tile $tileO (belt/belt $bb, belt/ins $bi, ins/ins $ii, sink $sk, on pin tiles $pins) dead $deadO chan $chanO unrouted ${unrouted.size} beltTiles $beltTiles pFac $pFac"
            }
            p.debugDir?.let { dir ->
                val heat = HashMap<Tile, Double>()
                val over = HashSet<Tile>()
                for (t in 0 until N) {
                    if (hist[t] > 0) heat[dims.tile(t)] = hist[t].toDouble()
                    if (overuseAt(t) > 0) over += dims.tile(t)
                }
                renderLayout(Layout(W, H, entities()), File(dir, "route_iter_%03d.png".format(iter)),
                    RenderOptions(heat = HeatOverlay(heat, over), title = "iteration $iter: overuse $overuse, unrouted ${unrouted.size}"))
            }
            if (overuse == 0 && unrouted.isEmpty() && failedFluids.isEmpty()) break
            if (score < best) {
                best = score; sinceBest = 0
                bestRoutes = routed.copyOf()
                bestPipes = pr?.let { (0 until it.count).map { i -> it.saved(i) } }
                hist.copyInto(histBest); histChan.copyInto(histChanBest)
            } else if (++sinceBest >= tun.stagnationStop) break
            // History and present-congestion factor.
            for (t in 0 until N) { val o = overuseAt(t); if (o > 0) hist[t] += (tun.hFac * o).toFloat() }
            for (c in 0 until CH) { val o = occChan[c] - 1; if (o > 0) histChan[c] += (tun.hFac * o).toFloat() }
            pr?.updateHistory()
            pFac = minOf(pFac * tun.pMult.toFloat(), tun.pFacMax.toFloat())
        }
        if (overuse + tun.unroutedPenalty * (unrouted.size + failedFluids.size) > best && bestRoutes != null) {
            nets.indices.forEach { ripUp(it) }
            pr?.let { (0 until it.count).forEach { i -> it.ripUp(i) } }
            nets.indices.forEach { restore(it, bestRoutes!![it]) }
            pr?.let { (0 until it.count).forEach { i -> it.restore(i, bestPipes!![i]) } }
            solveSinks()
            unrouted = nets.filter { routed[it.idx]?.ok != true }.map { it.idx }
            failedFluids = pr?.failed() ?: emptyList()
            overuse = totalOveruse()
            log += "back to the best iteration: overuse $overuse, unrouted ${unrouted.size + failedFluids.size}"
        }
        if ((overuse > 0 || unrouted.isNotEmpty() || failedFluids.isNotEmpty()) &&
            overuse <= maxOf(tun.endgameMaxOveruse, (tun.endgameOverusePerChain * nets.size).toInt()) &&
            unrouted.size + failedFluids.size <= tun.endgameMaxUnrouted) {
            val before = "overuse $overuse, unrouted ${unrouted.size + failedFluids.size}"
            val rounds = endgame(order, ::ok, deadline = System.currentTimeMillis() + ((System.currentTimeMillis() - t0) * tun.endgameTimeFactor).toLong())
            unrouted = nets.filter { routed[it.idx]?.ok != true }.map { it.idx }
            failedFluids = pr?.failed() ?: emptyList()
            overuse = totalOveruse()
            log += "endgame from $before: $rounds rounds, now overuse $overuse, unrouted ${unrouted.size + failedFluids.size}"
        }
        val success = overuse == 0 && unrouted.isEmpty() && failedFluids.isEmpty()
        if (!success) {
            var reported = 0
            for (t in 0 until N) if (overuseAt(t) > 0 && reported++ < 12) {
                val users = nets.filter { routed[it.idx]?.tiles?.contains(t) == true }.map { "net${it.planId}/${it.idx}" } +
                    sinkClaims.filter { (o, c) -> dims.idx(o.insTile) == t || dims.idx(c) == t }.map { "sink${it.first.terminal}" } +
                    nets.filter { routed[it.idx]?.deadEnds?.any { e -> e shr 2 == t } == true }.map { "deadend${it.planId}/${it.idx}" }
                log += "overused ${dims.tile(t)}: occ ${occTile[t]} belt ${occBelt[t]} deadEnd ${deadEndAt[t]} users $users"
            }
            for (c in 0 until CH) if (occChan[c] > 1) log += "overused channel ${if (c >= 2 * N) "weave " else ""}axis ${(c / N) % 2} at ${dims.tile(c % N)}: ${occChan[c]} users " +
                nets.filter { routed[it.idx]?.chans?.contains(c) == true }.map { "net${it.planId}/${it.idx}x${routed[it.idx]!!.chans.count { x -> x == c }}" } + " hist ${histChan[c]}"
        }
        // Congestion map for the placer: history at the best iteration plus the conflicts still left.
        val history = HashMap<Tile, Double>()
        for (y in 0 until H) for (x in 0 until W) {
            val i = dims.idx(x, y)
            var left = overuseAt(i)
            var hc = 0f
            for (k in 0 until 4) { left += max(0, occChan[k * N + i] - 1); hc += histChanBest[k * N + i] }
            val v = histBest[i] + hc + tun.feedbackOveruseWeight * left
            if (v > 0) history[Tile(x, y)] = v
        }
        val ents = entities()
        return RouteResult(
            success = success,
            entities = ents,
            iterations = iter,
            overuse = if (overuse == Int.MAX_VALUE) -1 else overuse,
            history = history,
            rawHistory = hist.copyOf(),
            failedNets = unrouted.map { nets[it].planId } + failedFluids,
            sources = nets.flatMap { n ->
                val r = routed[n.idx]?.takeIf { it.sourceTile >= 0 } ?: return@flatMap emptyList()
                val t = dims.tile(r.sourceTile)
                if (n.item2 == null) listOf(SourceSpec(t, n.item, n.sourceRate, port = n.srcPort))
                else listOfNotNull(SourceSpec(t, n.item, n.sourceRate, lane = 0, port = n.srcPort).takeIf { n.sourceRate > 0 },
                    SourceSpec(t, n.item2, n.sourceRate2, lane = 1, port = n.srcPort2).takeIf { n.sourceRate2 > 0 })
            } + (pr?.sources() ?: emptyList()),
            outputs = nets.mapNotNull { n -> routed[n.idx]?.takeIf { it.outTile >= 0 }?.let { OutputSpec(dims.tile(it.outTile), n.item, n.outRate, port = n.outPort) } } +
                (pr?.outputs() ?: emptyList()),
            conflicts = if (success) emptyList() else {
                val c = HashSet<Int>()
                for (t in 0 until N) if (overuseAt(t) > 0) c += t
                for (ch in 0 until CH) if (occChan[ch] > 1) c += ch % N
                pr?.let { c += it.channelConflicts() }
                nets.filter { routed[it.idx]?.ok != true }.forEach { n ->
                    n.terms.toList().mapNotNull { vts[it].machine }.distinct().forEach { m -> c += dims.idx(machineCenterOf(m)) }
                }
                c.map { dims.tile(it) }
            },
            conflictNets = if (success) emptyMap() else {
                val m = LinkedHashMap<Tile, Set<Int>>()
                for (t in 0 until N) if (overuseAt(t) > 0 && m.size < 40) {
                    val ids = HashSet<Int>()
                    nets.forEach { n -> routed[n.idx]?.let { r -> if (t in r.tiles || r.deadEnds.any { e -> e shr 2 == t }) ids += n.planId } }
                    pr?.let { ids += it.planNetsNear(t) }
                    m[dims.tile(t)] = ids
                }
                for (ch in 0 until CH) if (occChan[ch] > 1 && m.size < 40) {
                    m.getOrPut(dims.tile(ch % N)) { nets.filter { routed[it.idx]?.chans?.contains(ch) == true }.map { it.planId }.toSet() }
                }
                m
            },
            chains = nets.size,
            chainStats = nets.mapNotNull { n ->
                val r = routed[n.idx]?.takeIf { it.ok } ?: return@mapNotNull null
                val pts = n.terms.toList().mapNotNull { vts[it].machine }.distinct().map { m -> machineCenterOf(m) } +
                    listOfNotNull(r.sourceTile.takeIf { it >= 0 }?.let { dims.tile(it) })
                val hp = (pts.maxOf { it.x } - pts.minOf { it.x }) + (pts.maxOf { it.y } - pts.minOf { it.y })
                ChainStat(n.item, n.terms.map { vts[it].machine }.distinct().size + (if (n.portTiles.isNotEmpty()) 1 else 0), hp,
                    r.belts.size + r.ugIn.size + r.ugOut.size)
            },
            di = di,
            stats = RouteStats(
                belts = ents.count { it.kind == Kind.BELT },
                undergrounds = ents.count { it.kind == Kind.UG_IN },
                inserters = ents.count { it.kind == Kind.INSERTER },
            ),
            millis = System.currentTimeMillis() - t0,
            log = log + "trace $trace",
        )
    }

    // ---- endgame ----
    /**
     * LNS endgame. Negotiation that stalls on a few conflicts keeps rerouting each net alone against the
     * rest, and each comes back to the same tiles. Rip up every chain and pipe net near the conflicts,
     * reroute them together in a shuffled order and negotiate among them for a few iterations; the region
     * grows every few rounds that do not clear it. A round that ends worse than it started is undone.
     * Returns the rounds used.
     */
    private fun endgame(order: List<Int>, ok: (Int) -> Boolean, deadline: Long): Int {
        val pr = pipes
        val rnd = java.util.Random(31L * N + nets.size + 1_000_003L * p.endgameSeed)
        fun reroute(w: Int) { if (w >= 0) { ripUp(w); routeNet(nets[w]) } else { pr!!.ripUp(-1 - w); pr.route(-1 - w) } }
        fun overused(w: Int) = !ok(w) || (if (w >= 0) touchesOveruse(w) else pr!!.touchesOveruse(-1 - w, ::overuseAt))
        fun seeds(): List<Int> {
            val s = ArrayList<Int>()
            for (t in 0 until N) if (overuseAt(t) > 0) s += t
            for (c in 0 until CH) if (occChan[c] > 1) s += c % N
            pr?.let { s += it.channelConflicts() }
            nets.filter { routed[it.idx]?.ok != true }.forEach { n ->
                n.terms.toList().mapNotNull { vts[it].machine }.distinct().forEach { m -> s += dims.idx(machineCenterOf(m)) }
                n.portTiles.firstOrNull()?.let { s += it }
            }
            pr?.failed()?.let { failedIds -> (0 until pr.count).filter { !pr.ok(it) }.forEach { s += pr.seedTiles(it) } }
            return s.distinct()
        }
        fun score() = totalOveruse() + tun.unroutedPenalty * (nets.count { routed[it.idx]?.ok != true } + (pr?.failed()?.size ?: 0))
        var round = 0
        while (round < tun.endgameRounds && System.currentTimeMillis() < deadline) {
            val start = score()
            val seedTiles = seeds()
            if (seedTiles.isEmpty()) break
            round++
            val r = tun.endgameRadius + (round - 1) / 4 * 2
            val region = BooleanArray(N)
            seedTiles.forEach { t ->
                val x0 = dims.x(t); val y0 = dims.y(t)
                for (y in y0 - r..y0 + r) for (x in x0 - r..x0 + r) if (dims.inFull(x, y)) region[dims.idx(x, y)] = true
            }
            val victims = order.filter { w ->
                !ok(w) || if (w >= 0) routed[w]!!.let { rr -> rr.tiles.any { region[it] } || rr.deadEnds.any { region[it shr 2] } } else pr!!.touchesRegion(-1 - w, region)
            }.toMutableList()
            val saved = victims.associateWith { w -> if (w >= 0) routed[w] else pr!!.saved(-1 - w) }
            victims.forEach { w -> if (w >= 0) ripUp(w) else pr!!.ripUp(-1 - w) }
            victims.shuffle(rnd)
            victims.forEach { w -> if (w >= 0) routeNet(nets[w]) else pr!!.route(-1 - w) }
            for (k in 0 until tun.endgameIterations) {
                solveSinks()
                val todo = victims.filter { overused(it) }
                if (todo.isEmpty()) break
                for (t in 0 until N) { val o = overuseAt(t); if (o > 0) hist[t] += (tun.hFac * o).toFloat() }
                for (c in 0 until CH) { val o = occChan[c] - 1; if (o > 0) histChan[c] += (tun.hFac * o).toFloat() }
                pr?.updateHistory()
                todo.shuffled(rnd).forEach { reroute(it) }
            }
            solveSinks()
            if (score() > start) {
                victims.forEach { w -> if (w >= 0) ripUp(w) else pr!!.ripUp(-1 - w) }
                victims.forEach { w -> if (w >= 0) restore(w, saved[w] as Routed?) else pr!!.restore(-1 - w, saved[w]) }
                solveSinks()
            }
        }
        return round
    }

    // ---- emission ----
    private fun chooseInserter(share: Double, fromBelt: Boolean, long: Boolean): String {
        if (long) return "long-handed-inserter"
        val allowed = config.inserters.allowed.filter { it != "long-handed-inserter" }
            .sortedBy { GameConstants.inserter(it).tierCost }
        return allowed.firstOrNull { inserterRate(it, config, fromBelt) >= share - 1e-9 }
            ?: allowed.maxByOrNull { inserterRate(it, config, fromBelt) }!!
    }

    private fun entities(): List<PlacedEntity> {
        val out = ArrayList<PlacedEntity>()
        // An inserter taking from a machine with several item products is filtered to the one it carries.
        fun filterFor(machine: Int?, item: String) = item.takeIf { machine != null && plan.machines[machine].itemProducts.size > 1 }
        p.placement.machines.forEach { m ->
            val inst = plan.machines[m.id]
            val oriented = place.orientable(inst)
            out += PlacedEntity(inst.machine, Kind.MACHINE, m.x, m.y, if (oriented) m.rot else Dir.N, inst.recipe,
                mirror = oriented && m.mirror, w = inst.w, h = inst.h)
            if (config.fluids.mode == "infinity-pipe") {
                // Test shortcut: every used connection gets its own infinity pipe (a drain on outputs).
                p.placement.connections(plan, m).filter { it.fluid != null }.forEach { c ->
                    out += PlacedEntity(GameConstants.INFINITY_PIPE, Kind.INFINITY_PIPE, c.tile.x, c.tile.y, fluid = c.fluid,
                        drain = c.flow == GameConstants.Flow.OUTPUT)
                }
            }
        }
        di.forEach { link ->
            val share = link.flow / link.tiles.size
            val name = chooseInserter(share, fromBelt = false, long = link.long)
            link.tiles.forEach { out += PlacedEntity(name, Kind.INSERTER, it.x, it.y, link.insDir, item = filterFor(link.producer, link.item)) }
        }
        nets.forEach { n ->
            val r = routed[n.idx] ?: return@forEach
            fun add(e: Int, kind: Kind, name: String) {
                val t = dims.tile(e shr 2)
                out += PlacedEntity(name, kind, t.x, t.y, Dir.entries[e and 3], net = n.planId)
            }
            val underSplitter = r.splitters.mapTo(HashSet()) { it[0] }
            r.belts.forEach { if ((it shr 2) !in underSplitter) add(it, Kind.BELT, tier.belt) }
            r.splitters.forEach { sp ->
                val a = dims.tile(sp[0]); val b = dims.tile(sp[1])
                out += PlacedEntity(tier.splitter, Kind.SPLITTER, minOf(a.x, b.x), minOf(a.y, b.y), Dir.entries[sp[2]], net = n.planId)
            }
            r.ugIn.forEachIndexed { k, e -> add(e, Kind.UG_IN, if (r.ugTier[k] == 1) weave!!.underground else tier.underground) }
            r.ugOut.forEachIndexed { k, e -> add(e, Kind.UG_OUT, if (r.ugTier[k] == 1) weave!!.underground else tier.underground) }
            r.claims.forEach { o ->
                val opt = options[o]
                val v = vts[optTerm[o]]
                val name = chooseInserter(v.rate / v.k, fromBelt = v.role == Role.CONSUMER, long = opt.long)
                val item = if (v.role == Role.CONSUMER) null else filterFor(v.machine, v.base.item)
                out += PlacedEntity(name, Kind.INSERTER, opt.insTile.x, opt.insTile.y, opt.insDir, item = item, net = n.planId)
            }
        }
        pipes?.let { out += it.entities() }
        sinkClaims.forEach { (o, chest) ->
            val e = eff.getValue(o.terminal)
            out += PlacedEntity(chooseInserter(e.rate / e.k, false, false), Kind.INSERTER, o.insTile.x, o.insTile.y, o.insDir,
                item = filterFor(e.base.machine, e.base.item))
            out += PlacedEntity(config.sinks.chest, Kind.CHEST, chest.x, chest.y)
        }
        return out
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /**
     * Two items per belt: merges pairs of single chains whose consumer machines mostly coincide and whose flow
     * fits one lane each into one chain carrying one item per lane (slot 0 left, slot 1 right). A port-fed item
     * takes slot 0; two port-fed items need an edge tile both ports allow. Chains with fixed-tile ports, output
     * ports or single-belt ports (links between the blocks of a factory) keep a belt of their own.
     */
    private fun pairChains() {
        fun flow(n: RNet) = n.sourceRate + n.terms.filter { vts[it].role == Role.PRODUCER }.sumOf { vts[it].rate }
        fun consumers(n: RNet) = n.terms.filter { vts[it].role == Role.CONSUMER }.mapNotNull { vts[it].machine }.toSet()
        fun edgePorts(n: RNet) = n.portTiles.indices.associate { n.portTiles[it] to n.portOut[it] }
        val chainsOf = nets.groupingBy { it.planId }.eachCount()
        val cand = nets.filter { n ->
            chainsOf[n.planId] == 1 && n.outTiles.isEmpty() && flow(n) <= laneCap && consumers(n).size >= 2 && n.portOut.none { it < 0 } &&
                (n.srcPort < 0 || !plan.ports[n.srcPort].single)
        }
        class Cand(val a: RNet, val b: RNet, val score: Double)
        val found = ArrayList<Cand>()
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val a = cand[i]; val b = cand[j]
            val ca = consumers(a); val cb = consumers(b)
            val shared = ca.intersect(cb).size
            if (shared < 2) continue
            val overlap = shared.toDouble() / ca.union(cb).size
            if (overlap < tun.laneShareOverlap) continue
            if (a.portTiles.isNotEmpty() && b.portTiles.isNotEmpty()) {
                val pb = edgePorts(b)
                if (edgePorts(a).none { (t, o) -> pb[t] == o }) continue
            }
            found += Cand(a, b, overlap + 0.01 * shared)
        }
        found.sortByDescending { it.score }
        val partner = HashMap<Int, RNet>()
        found.forEach { c -> if (c.a.idx !in partner && c.b.idx !in partner) { partner[c.a.idx] = c.b; partner[c.b.idx] = c.a } }
        if (partner.isEmpty()) return
        val old = nets.toList()
        nets.clear()
        val remap = HashMap<Int, Int>()
        for (n in old) {
            if (n.idx in remap) continue
            val idx = nets.size
            val p = partner[n.idx]
            if (p == null) { remap[n.idx] = idx; nets += n.copy(idx = idx); continue }
            val (first, second) = if (n.portTiles.isEmpty() && p.portTiles.isNotEmpty()) p to n else n to p
            remap[first.idx] = idx; remap[second.idx] = idx
            second.terms.forEach { vts[it].slot = 1 }
            var tiles = first.portTiles
            var outs = first.portOut
            if (second.portTiles.isNotEmpty()) {
                val pb = edgePorts(second)
                val keep = first.portTiles.indices.filter { pb[first.portTiles[it]] == first.portOut[it] }
                tiles = keep.map { first.portTiles[it] }.toIntArray(); outs = keep.map { first.portOut[it] }.toIntArray()
            }
            nets += RNet(idx, first.planId, first.item, first.terms + second.terms, tiles, outs, maxOf(first.hpwl, second.hpwl),
                first.sourceRate, item2 = second.item, sourceRate2 = second.sourceRate, srcPort = first.srcPort, srcPort2 = second.srcPort)
            log += "shared belt: ${first.item} (left lane) with ${second.item} (right lane)"
        }
        vts.forEach { it.net = remap.getValue(it.net) }
        for (i in 0 until N) if (allowNet[i] >= 0) allowNet[i] = remap[allowNet[i]] ?: allowNet[i]
    }

    /** Candidate tiles of plan port [port] in this box, each with the direction that points out (or null). */
    private fun portTilesOf(port: Int): List<Pair<Tile, Dir?>> =
        portCandidates(plan.ports[port].specs, W, H).filter { (t, _) -> dims.inFull(t.x, t.y) && (dims.inBox(t.x, t.y) || isRing1(t)) }

    /**
     * Adds one chain of [net]: its machine terminals (each with this chain's share of the rate and its own
     * inserter count), and optionally the port it starts from and the port it ends at.
     */
    private fun addChain(
        net: Net, members: List<EffectiveTerminal>, source: EffectiveTerminal?, sourceRate: Double,
        output: EffectiveTerminal?, outRate: Double, rectOf: Map<Int, Rect>,
    ) {
        val idx = nets.size
        val ids = members.map { t -> VT(vts.size, t.base, t.rate, t.k, idx).also { vts += it }.id }
        fun reserve(cands: List<Pair<Tile, Dir?>>) = cands.forEach { (t, out) ->
            val i = dims.idx(t)
            if (out == null && (allowNet[i] != -2 || !dims.inBox(t.x, t.y))) allowNet[i] = idx
        }
        val src = source?.let { portTilesOf(it.base.port!!) } ?: emptyList()
        val out = output?.let { portTilesOf(it.base.port!!) } ?: emptyList()
        reserve(src); reserve(out)
        val pts = members.mapNotNull { it.base.machine?.let { m -> rectOf.getValue(m) } }.map { Tile(it.x + it.w / 2, it.y + it.h / 2) } +
            src.take(1).map { it.first } + out.take(1).map { it.first }
        val hpwl = if (pts.isEmpty()) 0 else (pts.maxOf { it.x } - pts.minOf { it.x }) + (pts.maxOf { it.y } - pts.minOf { it.y })
        nets += RNet(idx, net.id, net.item, ids.toIntArray(), src.map { dims.idx(it.first) }.toIntArray(), src.map { it.second?.ordinal ?: -1 }.toIntArray(),
            hpwl, sourceRate, out.map { dims.idx(it.first) }.toIntArray(), out.map { it.second?.ordinal ?: -1 }.toIntArray(), outRate,
            srcPort = source?.base?.port ?: -1, outPort = output?.base?.port ?: -1)
    }

    /**
     * Chains of a net with several input or output ports, or with both an input port and producers (a block of
     * a split factory imports part of an item and makes the rest). Every output port gets chains of its own,
     * fed by the producers nearest to it; each input port feeds the consumers nearest to it (splitting one
     * consumer between ports if it has to); producers feed the rest. Chains are split by inserter visits and flow
     * as everywhere else.
     */
    private fun buildGeneralChains(
        net: Net, consumers: List<EffectiveTerminal>, producers: List<EffectiveTerminal>, sources: List<EffectiveTerminal>,
        outputs: List<EffectiveTerminal>, center: (Terminal) -> Pair<Double, Double>, rectOf: Map<Int, Rect>,
        dropRate: Double, pickRate: Double, beltCap: Double,
    ) {
        fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = abs(a.first - b.first) + abs(a.second - b.second)
        fun portCenter(t: EffectiveTerminal): Pair<Double, Double> {
            val c = portTilesOf(t.base.port!!).map { it.first }
            return if (c.isEmpty()) (W / 2.0 to H / 2.0) else c.map { it.x.toDouble() }.average() to c.map { it.y.toDouble() }.average()
        }
        fun part(t: EffectiveTerminal, rate: Double, perInserter: Double) =
            EffectiveTerminal(t.base, rate, if (abs(rate - t.rate) < 1e-9) t.k else inserterCount(rate, perInserter))
        val prodLeft = LinkedHashMap<EffectiveTerminal, Double>().apply { producers.forEach { put(it, it.rate) } }
        val srcLeft = LinkedHashMap<EffectiveTerminal, Double>().apply { sources.forEach { put(it, it.rate) } }
        fun fromProducers(amount: Double, at: Pair<Double, Double>): Pair<List<EffectiveTerminal>, Double> {
            var left = amount
            val parts = ArrayList<EffectiveTerminal>()
            while (left > 1e-9) {
                val pr = prodLeft.filter { it.value > 1e-9 }.keys.minByOrNull { dist(center(it.base), at) } ?: break
                val take = min(left, prodLeft.getValue(pr))
                prodLeft[pr] = prodLeft.getValue(pr) - take
                left -= take
                parts += part(pr, take, dropRate)
            }
            return parts to left
        }
        /** Chains for [parts], at most [room] by visits (one per tile of the port they start from). */
        fun chainsOf(parts: List<EffectiveTerminal>, room: Int = Int.MAX_VALUE): List<List<EffectiveTerminal>> {
            if (parts.isEmpty()) return emptyList()
            val g = maxOf(minOf(ceilDiv(parts.sumOf { it.k }, tun.maxChainVisits), room), kotlin.math.ceil(parts.sumOf { it.rate } / beltCap - 1e-9).toInt(), 1)
                .coerceAtMost(parts.size)
            return if (g == 1) listOf(parts) else groupConsumers(parts, g, center)
        }
        // 1. Output ports.
        outputs.forEach { o ->
            val pieces = if (plan.ports[o.base.port!!].single) 1 else maxOf(1, kotlin.math.ceil(o.rate / beltCap - 1e-9).toInt())
            repeat(pieces) {
                val r = o.rate / pieces
                val (parts, missing) = fromProducers(r, portCenter(o))
                var src: EffectiveTerminal? = null
                var srcRate = 0.0
                if (missing > 1e-6) {
                    src = srcLeft.filter { it.value > 1e-9 }.keys.maxByOrNull { srcLeft.getValue(it) }
                    if (src != null) { srcRate = min(missing, srcLeft.getValue(src)); srcLeft[src] = srcLeft.getValue(src) - srcRate }
                }
                if (missing - srcRate > 1e-6) log += "net ${net.id} (${net.item}): output chain short of ${"%.2f".format(missing - srcRate)}/s"
                addChain(net, parts, src, srcRate, o, r, rectOf)
            }
        }
        // 2. Consumers nearest each input port, fed by it.
        val need = LinkedHashMap<EffectiveTerminal, Double>().apply { consumers.forEach { put(it, it.rate) } }
        sources.sortedByDescending { srcLeft.getValue(it) }.forEach { s ->
            var left = srcLeft.getValue(s)
            val at = portCenter(s)
            val mine = ArrayList<EffectiveTerminal>()
            while (left > 1e-9) {
                val c = need.filter { it.value > 1e-9 }.keys.minByOrNull { dist(center(it.base), at) } ?: break
                val take = min(left, need.getValue(c))
                need[c] = need.getValue(c) - take
                left -= take
                mine += part(c, take, pickRate)
            }
            srcLeft[s] = left
            // A link port is one belt: one chain (branches serve many consumers), so it pairs with its other end.
            val groups = if (plan.ports[s.base.port!!].single) listOf(mine).filter { it.isNotEmpty() } else chainsOf(mine, portTilesOf(s.base.port).size)
            groups.forEach { group -> addChain(net, group, s, group.sumOf { it.rate }, null, 0.0, rectOf) }
        }
        // 3. The rest from producers.
        val rest = need.filter { it.value > 1e-9 }.map { (c, r) -> part(c, r, pickRate) }
        chainsOf(rest).forEach { group ->
            val at = group.map { center(it.base) }.let { ps -> ps.map { it.first }.average() to ps.map { it.second }.average() }
            val (parts, missing) = fromProducers(group.sumOf { it.rate }, at)
            if (missing > 1e-6) log += "net ${net.id} (${net.item}): chain short of ${"%.2f".format(missing)}/s supply"
            addChain(net, group + parts, null, 0.0, null, 0.0, rectOf)
        }
        prodLeft.filter { it.value > 1e-6 }.forEach { (p, r) -> log += "net ${net.id} (${net.item}): ${"%.2f".format(r)}/s of a producer has no chain" }
        srcLeft.filter { it.value > 1e-6 }.forEach { (_, r) -> log += "net ${net.id} (${net.item}): ${"%.2f".format(r)}/s of an input port has no chain" }
    }

    /** Capacity-balanced k-means of consumers into [g] chains, seeded with far-apart consumers. */
    private fun groupConsumers(cs: List<EffectiveTerminal>, g: Int, center: (Terminal) -> Pair<Double, Double>): List<List<EffectiveTerminal>> {
        val pts = cs.map { center(it.base) }
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        fun d(a: Pair<Double, Double>, b: Pair<Double, Double>) = abs(a.first - b.first) + abs(a.second - b.second)
        val seeds = ArrayList<Int>()
        seeds += pts.indices.maxBy { d(pts[it], mx to my) }
        while (seeds.size < g) seeds += pts.indices.filter { it !in seeds }.maxBy { i -> seeds.minOf { d(pts[i], pts[it]) } }
        val centers = seeds.map { pts[it] }.toMutableList()
        val total = cs.sumOf { it.rate }
        val capacity = maxOf(cs.maxOf { it.rate }, total / g * 1.2)
        var assign = IntArray(cs.size)
        repeat(8) {
            val load = DoubleArray(g)
            val next = IntArray(cs.size)
            cs.indices.sortedByDescending { cs[it].rate }.forEach { i ->
                val order = (0 until g).sortedBy { d(pts[i], centers[it]) }
                val pick = order.firstOrNull { load[it] + cs[i].rate <= capacity + 1e-9 } ?: order.first()
                next[i] = pick; load[pick] += cs[i].rate
            }
            assign = next
            for (c in 0 until g) {
                val mine = cs.indices.filter { assign[it] == c }
                if (mine.isNotEmpty()) centers[c] = mine.map { pts[it].first }.average() to mine.map { pts[it].second }.average()
            }
        }
        return (0 until g).map { c -> cs.indices.filter { assign[it] == c }.map { cs[it] } }.filter { it.isNotEmpty() }
    }

    companion object {
        const val MARGIN = 2
        const val BELT = 0
        const val UG_EXIT = 1
        /** Belt-family kinds for [acceptMask]. */
        const val AK_BELT = 0
        const val AK_UG_IN = 1
        const val AK_UG_OUT = 2
        const val AK_SPLITTER = 3
        const val REPAIR = -3
    }
}

fun route(problem: RouteProblem): RouteResult = PathFinder(problem).run()
