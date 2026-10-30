package blocks

import config.BlocksConfig
import config.ConfigSource
import config.RESERVED_CORNERS
import config.ReservedArea
import config.Tuning
import geom.Dir
import geom.Rect
import plan.Net
import plan.Port
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import kotlin.math.abs
import kotlin.math.ceil

/** Link end that is the factory's outer edge (raw inputs, products). */
const val EDGE = -1
/** Link end that is a fluid's pipe tree through the corridors, which joins every block trading that fluid. */
const val HUB = -2

/**
 * [rate] of [item] going from block [from] to block [to] ([EDGE]: the factory's edge, [HUB]: the fluid's pipe tree
 * between blocks). [routed]: an edge link that runs through the corridors to the factory's edge instead of leaving
 * its block straight out of the factory. [fromSide], [toSide]: the side of each block its port is on.
 */
data class Link(
    val id: Int, val item: String, val rate: Double, val from: Int, val to: Int, val fluid: Boolean,
    val routed: Boolean = false, val fromSide: String? = null, val toSide: String? = null,
) {
    /** Between two blocks: one belt, routed through the corridors. */
    val inner: Boolean get() = from >= 0 && to >= 0
    /** Part of a fluid's pipe tree between blocks. */
    val hub: Boolean get() = from == HUB || to == HUB
    /** One belt (or pipe) through the corridors; the other links leave their block straight out of the factory. */
    val throughCorridor: Boolean get() = inner || routed || hub
    fun side(b: Int): String? = if (from == b) fromSide else if (to == b) toSide else null
}

/**
 * The plan split into blocks (machine ids of the full plan, upstream first) and the links between them, with the
 * preliminary floorplan the links' sides were chosen on ([geometry]: each block's planned box, whose aspect it is
 * searched at). [plan] is the full plan with machine rates rebalanced for this split (see [rebalance]); [ports]
 * counts the (block, item) pairs that trade after that. [touch]: the factory edges each block must stay on (its
 * ports that leave the factory straight out).
 */
class Split(
    val blocks: List<List<Int>>, val links: List<Link>, val warnings: List<String>,
    val geometry: BlockGeometry, val plan: ProductionPlan, val partition: Partition? = null, val ports: Int = 0,
) {
    val touch: List<Set<Dir>> get() = blocks.indices.map { b -> links.filter { !it.throughCorridor && (it.from == b || it.to == b) }.mapNotNull { it.side(b) }.map(Dir::fromName).toSet() }
}

/**
 * Where blocks are (or are planned to be) in a [w] x [h] factory, which factory edges each touches, and the
 * floorplan's sequence pair if it came from one (the final floorplan starts from it).
 */
class BlockGeometry(val boxes: List<Rect>, val w: Int, val h: Int, val touches: List<Set<Dir>>, val state: FpState? = null)

/** Estimated box area of a block of these machines (blocks get sparser as they grow). */
fun estimatedArea(plan: ProductionPlan, ms: List<Int>): Double =
    ms.sumOf { plan.machines[it].w * plan.machines[it].h }.toDouble() * (2.5 + ms.size / 20.0).coerceAtMost(7.0)

/** Production depth of each machine: the longest chain from raw inputs to it (capped for loops). */
fun machineDepth(plan: ProductionPlan): DoubleArray {
    val depth = DoubleArray(plan.machines.size)
    repeat(40) {
        val itemDepth = HashMap<String, Double>()
        plan.machines.forEach { m -> m.products.keys.forEach { p -> itemDepth.merge(p, depth[m.id], ::maxOf) } }
        plan.machines.forEach { m ->
            val ins = m.demand.keys + m.fluidDemand.keys
            depth[m.id] = 1 + (ins.maxOfOrNull { itemDepth[it] ?: 0.0 } ?: 0.0).coerceAtMost(60.0)
        }
    }
    return depth
}

/** The factory edges an input ([output] false) or output of [item] may use, and those a block can use directly. */
private fun edgesOf(plan: ProductionPlan, item: String, output: Boolean): Pair<Set<Dir>, Set<Dir>> {
    val specs = plan.ports.firstOrNull { it.item == item && it.output == output }?.specs ?: return emptySet<Dir>() to emptySet()
    val all = HashSet<Dir>(); val direct = HashSet<Dir>()
    specs.forEach { s ->
        val e = s.edge ?: return@forEach
        val ds = if (e == "any") Dir.entries.toList() else listOf(Dir.fromName(e))
        all += ds
        if (e == "any" || (s.from == null && s.to == null)) direct += ds
    }
    return all to direct
}

/** Distance from [box] to factory edge [d] of a [w] x [h] factory. */
private fun edgeDistance(box: Rect, d: Dir, w: Int, h: Int): Int = when (d) {
    Dir.N -> box.y; Dir.S -> h - 1 - box.y2; Dir.W -> box.x; Dir.E -> w - 1 - box.x2
}

/** Sides of [a] in order of preference for a port towards [b]: the axis they are further apart on first. */
private fun sidesToward(a: Rect, b: Rect): List<Dir> {
    val dx = (b.x + b.w / 2.0) - (a.x + a.w / 2.0)
    val dy = (b.y + b.h / 2.0) - (a.y + a.h / 2.0)
    val sx = abs(dx) - (a.w + b.w) / 2.0
    val sy = abs(dy) - (a.h + b.h) / 2.0
    val hx = if (dx >= 0) Dir.E else Dir.W
    val hy = if (dy >= 0) Dir.S else Dir.N
    return if (sx >= sy) listOf(hx, hy, hy.opposite, hx.opposite) else listOf(hy, hx, hx.opposite, hy.opposite)
}

private fun centreDistance(a: Rect, b: Rect): Double =
    abs((a.x + a.w / 2.0) - (b.x + b.w / 2.0)) + abs((a.y + a.h / 2.0) - (b.y + b.h / 2.0))

/**
 * Splits [plan] into blocks (see [partitionPlan]), orders them upstream first, plans where they go (see
 * [Floorplanner]; shapes estimated from machine area) and derives the links between them on that floorplan (see
 * [linksFor]).
 */
fun splitPlan(
    input: ProductionPlan, cfg: BlocksConfig, beltThroughput: Double, seed: Long = 1L,
    aspect: Double = 1.0, reserved: List<ReservedArea> = emptyList(),
): Split {
    val warnings = ArrayList<String>()
    val part = partitionPlan(input, cfg, beltThroughput, seed)
    // Each candidate split with its machine rates rebalanced (see [rebalance]); the cheapest after that wins.
    val depth = machineDepth(input)
    data class Candidate(val blocks: List<List<Int>>, val blockOf: IntArray, val plan: ProductionPlan, val ports: Int, val cost: Double)
    val chosen = part.candidates.distinct().map { c ->
        val bl = c.map { it.sorted() }.sortedBy { ms -> ms.map { depth[it] }.average() }
        val bo = IntArray(input.machines.size).also { a -> bl.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
        val p = rebalance(input, bl)
        val model = PartitionModel(p, cfg, beltThroughput)
        Candidate(bl, bo, p, model.ports(bo, bl.size), model.cost(bo, bl.size))
    }.minBy { it.cost }
    val blocks = chosen.blocks
    val k = blocks.size
    val blockOf = chosen.blockOf
    val plan = chosen.plan
    val tradePorts = chosen.ports

    // Preliminary floorplan: soft shapes of the estimated area; one net per item between the blocks trading it,
    // and one per block and raw input or product towards the edges it may use.
    val bal = balances(plan, blocks, blockOf)
    val fpNets = ArrayList<FpNet>()
    plan.nets.forEach { net ->
        val inner = (0 until k).filter { abs(bal[net.id][it]) > PartitionModel.EPS }
        val w = if (net.fluid) 2.0 else 1.0
        val (inEdges, _) = edgesOf(plan, net.item, false)
        val (outEdges, _) = edgesOf(plan, net.item, true)
        val external = plan.netTerminals(net).any { it.role == Role.SOURCE || it.role == Role.OUTPUT }
        if (inner.size >= 2) fpNets += FpNet(inner.map { it to -1 }, weight = w)
        if (external) inner.forEach { b -> fpNets += FpNet(listOf(b to -1), inEdges + outEdges, w) }
    }
    val ports = IntArray(k) { b -> plan.nets.count { abs(bal[it.id][b]) > PartitionModel.EPS } }
    val fpBlocks = ArrayList<FpBlock>()
    blocks.forEachIndexed { b, ms ->
        val p = (cfg.corridor + 1) / 2 + ports[b] / 8
        fpBlocks += FpBlock(softShapes(estimatedArea(plan, ms)), IntArray(4) { p })
    }
    reserved.forEachIndexed { i, r -> fpBlocks += reservedBlock(r, i, cfg.corridor) }
    val fp = Floorplanner(fpBlocks, fpNets, FpParams(aspect = aspect, aspectWeight = cfg.aspectWeight, wireWeight = cfg.wireWeight, seed = seed)).optimise()
    val geometry = BlockGeometry(fp.boxes.take(k), fp.w, fp.h, fp.touches.take(k), fp.state)
    val links = linksFor(plan, cfg, beltThroughput, blocks, geometry, warnings)
    return Split(blocks, links, warnings, geometry, plan, part, tradePorts)
}

/** A reserved area as a floorplan block: fixed size, on its corner (by default the corners in turn). */
fun reservedBlock(r: ReservedArea, i: Int, corridor: Int): FpBlock {
    val corner = r.corner ?: RESERVED_CORNERS[i % 4]
    val touch = setOf(if (corner.startsWith("north")) Dir.N else Dir.S, if (corner.endsWith("west")) Dir.W else Dir.E)
    val pad = IntArray(4) { d -> if (Dir.entries[d] in touch) 0 else (corridor + 1) / 2 }
    return FpBlock(listOf(FpShape(r.w, r.h)), pad, touch)
}

/** Per net (by id) and block: what the block's machines make of it minus what they use. */
fun balances(plan: ProductionPlan, blocks: List<List<Int>>, blockOf: IntArray): Array<DoubleArray> {
    val bal = Array(plan.nets.size) { DoubleArray(blocks.size) }
    plan.nets.forEach { net ->
        plan.netTerminals(net).forEach { t ->
            val m = t.machine ?: return@forEach
            when (t.role) {
                Role.PRODUCER -> bal[net.id][blockOf[m]] += t.rate
                Role.CONSUMER -> bal[net.id][blockOf[m]] -= t.rate
                else -> {}
            }
        }
    }
    return bal
}

/**
 * The links of a split on a floorplan. Per item, the blocks with a surplus (and the edge for raw inputs) supply the
 * blocks with a deficit (and the edge for products), nearest first; links between blocks are cut into belts of at
 * most [BlocksConfig.linkBeltShare] of a belt. A raw input or product goes straight out of a block that touches an
 * edge the job allows for it, else through the corridors. Every fluid traded between blocks gets one pipe tree
 * through the corridors that joins one port on each block trading it (and the factory edge, if it enters or leaves
 * there), so any number of blocks can make and use the same fluid. Each port faces what it connects to.
 */
fun linksFor(
    plan: ProductionPlan, cfg: BlocksConfig, beltThroughput: Double, blocks: List<List<Int>>,
    g: BlockGeometry, warnings: MutableList<String> = ArrayList(),
): List<Link> {
    val k = blocks.size
    val blockOf = IntArray(plan.machines.size).also { a -> blocks.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
    val bal = balances(plan, blocks, blockOf)
    val raw = ArrayList<Link>()
    fun pieces(r: Double) = maxOf(1, ceil(r / (cfg.linkBeltShare * beltThroughput) - 1e-9).toInt())

    plan.nets.forEach { net ->
        val ts = plan.netTerminals(net)
        val source = ts.filter { it.role == Role.SOURCE }.sumOf { it.rate }
        val output = ts.filter { it.role == Role.OUTPUT }.sumOf { it.rate }
        val (inAll, inDirect) = edgesOf(plan, net.item, false)
        val (outAll, outDirect) = edgesOf(plan, net.item, true)
        fun edgeDist(b: Int, edges: Set<Dir>) = edges.minOfOrNull { edgeDistance(g.boxes[b], it, g.w, g.h) }?.toDouble() ?: 1e6
        fun directSide(b: Int, direct: Set<Dir>) = direct.firstOrNull { it in g.touches[b] }

        if (net.fluid) {
            val members = (0 until k).filter { abs(bal[net.id][it]) > PartitionModel.EPS }
            if (members.isEmpty()) return@forEach
            val b0 = members[0]
            // One block and the edge, and the block touches it: straight out, as for a single block.
            if (members.size == 1 && (source > 1e-9) != (output > 1e-9)) {
                val ds = directSide(b0, if (source > 1e-9) inDirect else outDirect)
                if (ds != null) {
                    raw += if (source > 1e-9) Link(0, net.item, source, EDGE, b0, true, toSide = ds.name)
                        else Link(0, net.item, output, b0, EDGE, true, fromSide = ds.name)
                    return@forEach
                }
            }
            members.forEach { b ->
                val r = bal[net.id][b]
                raw += if (r > 0) Link(0, net.item, r, b, HUB, true) else Link(0, net.item, -r, HUB, b, true)
            }
            if (source > 1e-9) raw += Link(0, net.item, source, EDGE, HUB, true, routed = true)
            if (output > 1e-9) raw += Link(0, net.item, output, HUB, EDGE, true, routed = true)
            return@forEach
        }

        val give = ArrayList<Pair<Int, Double>>(); val take = ArrayList<Pair<Int, Double>>()
        (0 until k).forEach { b -> val r = bal[net.id][b]; if (r > PartitionModel.EPS) give += b to r else if (r < -PartitionModel.EPS) take += b to -r }
        if (source > 1e-9) give += EDGE to source
        if (output > 1e-9) take += EDGE to output
        fun dist(a: Int, b: Int): Double = when {
            a == EDGE && b == EDGE -> 1e9
            a == EDGE -> edgeDist(b, inAll) - (if (directSide(b, inDirect) != null) 0.5 else 0.0)
            b == EDGE -> edgeDist(a, outAll) - (if (directSide(a, outDirect) != null) 0.5 else 0.0)
            else -> centreDistance(g.boxes[a], g.boxes[b])
        }
        val giveLeft = give.toMap().toMutableMap(); val takeLeft = take.toMap().toMutableMap()
        val pairs = give.flatMap { gv -> take.map { t -> gv.first to t.first } }.filter { (a, b) -> a != b }.sortedBy { (a, b) -> dist(a, b) }
        val flows = LinkedHashMap<Pair<Int, Int>, Double>()
        pairs.forEach { (a, b) ->
            val r = minOf(giveLeft.getValue(a), takeLeft.getValue(b))
            if (r < 1e-9) return@forEach
            giveLeft[a] = giveLeft.getValue(a) - r; takeLeft[b] = takeLeft.getValue(b) - r
            flows.merge(a to b, r, Double::plus)
        }
        fun emit(a: Int, b: Int, r: Double) {
            when {
                a == EDGE -> {
                    val ds = directSide(b, inDirect)
                    if (ds != null) raw += Link(0, net.item, r, EDGE, b, false, toSide = ds.name)
                    else { val n = pieces(r); repeat(n) { raw += Link(0, net.item, r / n, EDGE, b, false, routed = true) } }
                }
                b == EDGE -> {
                    val ds = directSide(a, outDirect)
                    if (ds != null) raw += Link(0, net.item, r, a, EDGE, false, fromSide = ds.name)
                    else { val n = pieces(r); repeat(n) { raw += Link(0, net.item, r / n, a, EDGE, false, routed = true) } }
                }
                else -> { val n = pieces(r); repeat(n) { raw += Link(0, net.item, r / n, a, b, false) } }
            }
        }
        // Pass-through chains: what one supplier sends to several blocks goes to the nearest, which keeps its share and
        // passes the rest on to the next block, and so on, as long as the chain fits one belt and each next block is
        // nearer the chain's end than the supplier. Hand-built factories run a bus through their blocks the same way.
        val cap = cfg.linkBeltShare * beltThroughput
        flows.entries.groupBy { it.key.first }.forEach { (src, es) ->
            val left = es.filter { cfg.passThrough && it.key.second >= 0 && it.value <= cap }.map { it.key.second to it.value }.toMutableList()
            es.filter { (it.key.second to it.value) !in left }.forEach { emit(src, it.key.second, it.value) }
            while (left.isNotEmpty()) {
                val first = left.minBy { dist(src, it.first) }
                left.remove(first)
                val chain = arrayListOf(first)
                var total = first.second
                while (true) {
                    val end = chain.last().first
                    val next = left.filter { total + it.second <= cap && centreDistance(g.boxes[end], g.boxes[it.first]) < dist(src, it.first) }
                        .minByOrNull { centreDistance(g.boxes[end], g.boxes[it.first]) } ?: break
                    left.remove(next); chain += next; total += next.second
                }
                var from = src; var rest = total
                chain.forEach { (b, r) -> emit(from, b, rest); rest -= r; from = b }
            }
        }
    }

    // Sides: a port faces what it connects to; sides whose ports leave the factory straight out take nothing else.
    val directSides = Array(k) { HashSet<Dir>() }
    raw.filter { !it.throughCorridor }.forEach { l ->
        if (l.from >= 0) directSides[l.from] += Dir.fromName(l.fromSide!!)
        if (l.to >= 0) directSides[l.to] += Dir.fromName(l.toSide!!)
    }
    fun pick(b: Int, prefs: List<Dir>): String = (prefs.firstOrNull { it !in directSides[b] } ?: prefs.first()).name
    fun towardEdges(b: Int, edges: Set<Dir>): List<Dir> {
        val es = edges.ifEmpty { Dir.entries.toSet() }
        return Dir.entries.sortedBy { d -> if (d in es) edgeDistance(g.boxes[b], d, g.w, g.h) else 1_000_000 + edgeDistance(g.boxes[b], d, g.w, g.h) }
    }
    val hubMembers = raw.filter { it.hub }.groupBy { it.item }
    val out = raw.mapIndexed { id, l ->
        fun sideFor(b: Int, other: Int): String {
            if (l.side(b) != null) return l.side(b)!!
            return when {
                other >= 0 -> pick(b, sidesToward(g.boxes[b], g.boxes[other]))
                other == EDGE -> pick(b, towardEdges(b, if (l.from == b) edgesOf(plan, l.item, true).first else edgesOf(plan, l.item, false).first))
                else -> {   // the fluid's tree: towards the nearest other block in it, or the edge if that is nearer
                    val others = hubMembers.getValue(l.item).flatMap { listOf(it.from, it.to) }.filter { it >= 0 && it != b }.distinct()
                    val edgeLinks = hubMembers.getValue(l.item).filter { it.from == EDGE || it.to == EDGE }
                    val edges = edgeLinks.flatMap { e -> edgesOf(plan, l.item, e.to == EDGE).first }.toSet()
                    val nearest = others.minByOrNull { centreDistance(g.boxes[b], g.boxes[it]) }
                    val edgeD = edges.minOfOrNull { edgeDistance(g.boxes[b], it, g.w, g.h) }?.toDouble()
                    if (nearest != null && (edgeD == null || centreDistance(g.boxes[b], g.boxes[nearest]) / 2 <= edgeD)) pick(b, sidesToward(g.boxes[b], g.boxes[nearest]))
                    else pick(b, towardEdges(b, edges))
                }
            }
        }
        l.copy(id = id,
            fromSide = if (l.from >= 0) sideFor(l.from, l.to) else null,
            toSide = if (l.to >= 0) sideFor(l.to, l.from) else null)
    }
    return out
}

/** One block as a plan of its own: local machine ids, a port per link, and which link each port serves. */
class BlockPlan(val index: Int, val plan: ProductionPlan, val globalIds: List<Int>, val linkOfPort: Map<Int, Int>)

/** The plan of block [b]: its machines, their terminals, and an input or output port per link it is part of. */
fun blockPlan(full: ProductionPlan, split: Split, b: Int, tuning: Tuning): BlockPlan = blockPlan(full, split.blocks[b], split.links, b, tuning)

fun blockPlan(full: ProductionPlan, ids: List<Int>, links: List<Link>, b: Int, tuning: Tuning): BlockPlan {
    val local = ids.withIndex().associate { (i, g) -> g to i }
    val machines = ids.map { full.machines[it].copy(id = local.getValue(it)) }
    val terminals = ArrayList<Terminal>()
    full.terminals.filter { it.machine != null && it.machine in local }.forEach { t ->
        terminals += t.copy(id = terminals.size, machine = local.getValue(t.machine!!), port = null)
    }
    val ports = ArrayList<Port>()
    val linkOfPort = HashMap<Int, Int>()
    links.filter { it.from == b || it.to == b }.forEach { l ->
        val out = l.from == b
        val side = l.side(b) ?: error("link ${l.id} has no side on block $b")
        val edge = when (side) { "N" -> "north"; "E" -> "east"; "S" -> "south"; else -> "west" }
        val port = Port(ports.size, l.item, listOf(ConfigSource(l.item, edge = edge)), fluid = l.fluid, output = out, single = l.throughCorridor)
        ports += port
        linkOfPort[port.id] = l.id
        terminals += Terminal(terminals.size, if (out) Role.OUTPUT else Role.SOURCE, l.item, l.rate, 1, port = port.id, fluid = l.fluid)
    }
    val tier = data.GameConstants.beltTier(full.beltTier)
    val nets = ArrayList<Net>()
    terminals.filter { it.role != Role.SINK }.groupBy { it.item }.forEach { (item, ts) ->
        val takers = ts.filter { it.role == Role.CONSUMER || it.role == Role.OUTPUT }
        if (ts.size < 2 || takers.isEmpty()) return@forEach
        val fluid = ts.first().fluid
        val flow = takers.sumOf { it.rate }
        val belts = if (fluid) 1 else maxOf(1, ceil(flow / (tuning.net.beltUtilisation * tier.throughput) - 1e-9).toInt())
        nets += Net(nets.size, item, flow, ts.map { it.id }, belts, fluid = fluid)
    }
    val mine = links.filter { it.from == b || it.to == b }
    val inputs = mine.filter { it.to == b }.groupBy { it.item }.mapValues { (_, ls) -> ls.sumOf { it.rate } }
    val outputs = mine.filter { it.from == b }.groupBy { it.item }.mapValues { (_, ls) -> ls.sumOf { it.rate } }
    val plan = ProductionPlan(machines, ports, terminals, nets, inputs, inputs.filterKeys { k -> mine.any { it.item == k && it.fluid } },
        emptyMap(), full.beltTier, emptyList(), outputs)
    return BlockPlan(b, plan, ids, linkOfPort)
}
