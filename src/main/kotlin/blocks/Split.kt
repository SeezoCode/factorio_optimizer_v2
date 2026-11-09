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
import plan.MachineInstance
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
    /** The link sharing this one's belt, one item per lane (-1: none). */
    val pair: Int = -1,
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
    /** Recipes kept in blocks of their own (machine columns, see Templates.kt), each with the most machines per block. */
    pure: Map<String, Int> = emptyMap(),
    /** How far rebalancing may push a machine toward full speed (planner.max_utilization). */
    maxUtilization: Double = 1.0,
): Split {
    val warnings = ArrayList<String>()
    val part = partitionPlan(input, cfg, beltThroughput, seed, pure)
    // Each candidate split with its machine rates rebalanced (see [rebalance]); the cheapest after that wins.
    val depth = machineDepth(input)
    data class Candidate(val blocks: List<List<Int>>, val blockOf: IntArray, val plan: ProductionPlan, val ports: Int, val cost: Double)
    val chosen = part.candidates.distinct().map { c ->
        val bl = c.map { it.sorted() }.sortedBy { ms -> ms.map { depth[it] }.average() }
        val bo = IntArray(input.machines.size).also { a -> bl.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
        val p = rebalance(input, bl, maxUtilization)
        val model = PartitionModel(p, cfg, beltThroughput, pure)
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
    val proposed = ArrayList<SliverMove>()
    var links = linksFor(plan, cfg, beltThroughput, blocks, geometry, warnings, proposed = proposed)
    var linkPlan = plan
    if (proposed.isNotEmpty()) {
        val moved = sliverPlan(input, plan, blocks, blockOf, proposed, maxUtilization)
        if (moved != null) {
            linkPlan = moved.first
            links = linksFor(linkPlan, cfg, beltThroughput, blocks, geometry, warnings, moves = moved.second)
            warnings += "${moved.second.size} of ${proposed.size} sliver links handed to the consumer's main supplier"
        }
    }
    val (plan2, blocks2) = wholeMachines(linkPlan, blocks, links, warnings)
    linkMismatches(plan2, blocks2, links).forEach { warnings += "SPLIT MISMATCH $it" }
    return Split(blocks2, links, warnings, geometry, plan2, part, tradePorts)
}

/**
 * Where a block's links disagree with what its machines make: per block and item, what leaves on links minus what
 * arrives must equal what the block's machines make minus what they use. Empty when the split is consistent (a block
 * that must send more than it makes can never get a valid layout).
 */
fun linkMismatches(plan: ProductionPlan, blocks: List<List<Int>>, links: List<Link>): List<String> {
    val blockOf = IntArray(plan.machines.size).also { a -> blocks.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
    val bal = balances(plan, blocks, blockOf)
    val out = ArrayList<String>()
    plan.nets.filter { !it.fluid }.forEach { net ->
        blocks.indices.forEach { b ->
            val sent = links.filter { it.item == net.item && !it.fluid && it.from == b }.sumOf { it.rate } -
                links.filter { it.item == net.item && !it.fluid && it.to == b }.sumOf { it.rate }
            val made = bal[net.id][b]
            if (abs(sent - made) > 1e-6 + 1e-6 * abs(made)) out += "block ${b + 1} ${net.item}: links send ${"%.4f".format(sent)}/s, machines make ${"%.4f".format(made)}/s"
        }
    }
    return out
}

/**
 * The plan rebalanced so that [proposed] sliver moves hold (each move's supplier makes its rate more, the first
 * supplier that much less), with the moves that could be kept: all if the rebalancing can meet them together, else
 * those it can meet one at a time (largest first, a bounded number of tries). Null if none can.
 */
private fun sliverPlan(
    input: ProductionPlan, plan: ProductionPlan, blocks: List<List<Int>>, blockOf: IntArray,
    proposed: List<SliverMove>, maxUtilization: Double,
): Pair<ProductionPlan, List<SliverMove>>? {
    val bal = balances(plan, blocks, blockOf)
    fun bounds(ms: List<SliverMove>): List<BalanceBound> {
        val shift = HashMap<Pair<Int, Int>, Double>()
        ms.forEach { m -> shift.merge(m.net to m.via, m.rate, Double::plus); shift.merge(m.net to m.from, -m.rate, Double::plus) }
        return shift.map { (nb, d) ->
            val (n, b) = nb
            if (d > 0) BalanceBound(n, b, min = bal[n][b] + d - 1e-9) else BalanceBound(n, b, max = bal[n][b] + d + 1e-9)
        }
    }
    rebalanceWith(input, blocks, maxUtilization, bounds(proposed))?.let { return it to proposed }
    val kept = ArrayList<SliverMove>()
    var best: ProductionPlan? = null
    proposed.sortedByDescending { it.rate }.take(40).forEach { m ->
        val p = rebalanceWith(input, blocks, maxUtilization, bounds(kept + m)) ?: return@forEach
        kept += m; best = p
    }
    return best?.let { it to kept }
}

/**
 * Machines enough to feed each belt an item leaves a block on. A machine putting its output on two belts divides it
 * by inserter speed (see the validator's split producers), about half each, so a belt gets whole or half machines:
 * 5 circuit assemblers of 8.82/s at most cannot fill belts of 19.36, 19.93 and 3.51/s (5 + 5 + 1 halves, 6 machines).
 * Where a block's machines of an item fall short of that (per belt: its rate over half a machine's most, rounded up;
 * plus what the block itself uses, where the belts' spare cannot cover it), copies of its producer join the block
 * and the block's machines of that recipe share its work equally: the block makes the same, each machine a little
 * slower. Items made by more than one recipe in a block are left as they are.
 */
fun wholeMachines(plan: ProductionPlan, blocks: List<List<Int>>, links: List<Link>, warnings: MutableList<String>): Pair<ProductionPlan, List<List<Int>>> {
    val machines = plan.machines.toMutableList()
    val terminals = plan.terminals.toMutableList()
    val netTerms = plan.nets.map { it.terminals.toMutableList() }
    val netOf = HashMap<Int, Int>().also { m -> plan.nets.forEach { n -> n.terminals.forEach { t -> m[t] = n.id } } }
    val newBlocks = blocks.map { it.toMutableList() }
    var changed = false
    // A recipe with several products (Py's sodium hydroxide gives limestone too) is sized once per block: sized again
    // for its next product, the copies already added were missed and the block made half again what its links take.
    val sized = HashSet<Pair<Int, String>>()
    blocks.forEachIndexed { b, ms ->
        val items = ms.flatMap { plan.machines[it].itemProducts.keys }.distinct()
        for (item in items) {
            val out = links.filter { it.from == b && it.item == item && !it.fluid }
            if (out.size < 2) continue
            val producers = ms.filter { (plan.machines[it].itemProducts[item] ?: 0.0) > 1e-12 }
            val recipes = producers.map { plan.machines[it].recipe }.distinct()
            if (recipes.size != 1) continue
            val m0 = plan.machines[producers[0]]
            if ((b to m0.recipe) in sized) continue
            // One machine's most of the item: at full speed (as the validator allows), or held to the plan's headroom.
            val per = m0.itemProducts.getValue(item) / m0.crafts
            val top = maxOf(m0.crafts, plan.headroom * m0.maxCrafts) * per
            if (top <= 1e-12) continue
            val inside = plan.terminals.filter { it.role == Role.CONSUMER && it.item == item && it.machine in ms }.sumOf { it.rate }
            // In halves: a machine whose inserters drop on two belts gives each about half (equal inserters).
            val half = top / 2
            val perBelt = out.map { ceil(it.rate / half - 1e-6).toInt() }
            val spare = out.indices.sumOf { perBelt[it] * half - out[it].rate }
            val halves = perBelt.sum() + ceil(maxOf(0.0, inside - spare) / half - 1e-6).toInt()
            val need = (halves + 1) / 2
            val extra = need - producers.size
            if (extra <= 0) continue
            // The block's machines of the recipe (and the copies) share its crafts equally.
            val all = ms.filter { plan.machines[it].recipe == m0.recipe }
            val total = all.sumOf { plan.machines[it].crafts }
            val share = total / (all.size + extra)
            fun scaled(m: MachineInstance, id: Int): MachineInstance {
                val f = share / m.crafts
                return m.copy(id = id, outputRate = m.outputRate * f, demand = m.demand.mapValues { it.value * f },
                    products = m.products.mapValues { it.value * f }, fluidDemand = m.fluidDemand.mapValues { it.value * f }, crafts = share,
                    maxCrafts = maxOf(m.maxCrafts, share))
            }
            all.forEach { id ->
                val f = share / machines[id].crafts
                machines[id] = scaled(machines[id], id)
                terminals.indices.filter { terminals[it].machine == id }.forEach { t -> terminals[t] = terminals[t].copy(rate = terminals[t].rate * f) }
            }
            repeat(extra) {
                val id = machines.size
                machines += scaled(plan.machines[all[0]], id).copy(id = id)
                plan.terminals.filter { it.machine == all[0] }.forEach { t ->
                    val nt = terminals.size
                    terminals += t.copy(id = nt, machine = id, rate = t.rate * share / plan.machines[all[0]].crafts)
                    netOf[t.id]?.let { n -> netTerms[n] += nt }
                }
                newBlocks[b] += id
            }
            changed = true
            sized += b to m0.recipe
            warnings += "block ${b + 1}: $extra more ${m0.machine} (${m0.recipe}) so that its ${out.size} belts of $item can be fed"
        }
    }
    if (!changed) return plan to blocks
    return plan.copy(machines = machines, terminals = terminals, nets = plan.nets.mapIndexed { i, n -> n.copy(terminals = netTerms[i]) }) to newBlocks
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
/**
 * A sliver link ([rate] of net [net] from block [from] to block [to]) handed to [via], the consumer's main supplier:
 * [via] makes that much more and [from] that much less. Only applied to a plan rebalanced with that move built in
 * ([rebalanceWith]), so every block's machines make what its links say.
 */
data class SliverMove(val net: Int, val from: Int, val to: Int, val via: Int, val rate: Double)

fun linksFor(
    plan: ProductionPlan, cfg: BlocksConfig, beltThroughput: Double, blocks: List<List<Int>>,
    g: BlockGeometry, warnings: MutableList<String> = ArrayList(),
    /** Moves the plan was rebalanced for (see [SliverMove]); null: none applied. */
    moves: List<SliverMove>? = null,
    /** Collects the sliver moves worth rebalancing for (when [moves] is null). */
    proposed: MutableList<SliverMove>? = null,
): List<Link> {
    val k = blocks.size
    val blockOf = IntArray(plan.machines.size).also { a -> blocks.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
    val bal = balances(plan, blocks, blockOf)
    // Per net and block: how much more of it the block's producers could make at full speed (for slivers, below).
    val spare = HashMap<Int, HashMap<Int, Double>>()
    plan.nets.forEach { net ->
        val m = HashMap<Int, Double>()
        plan.netTerminals(net).filter { it.role == Role.PRODUCER && it.machine != null }.forEach { t ->
            val mi = plan.machines[t.machine!!]
            m.merge(blockOf[t.machine], t.rate * (maxOf(mi.crafts, plan.headroom * mi.maxCrafts) / mi.crafts - 1), Double::plus)
        }
        spare[net.id] = m
    }
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
        // An overflow (a 0/s output, see plan.overflowItems): what its users leave goes out of the factory.
        val overflow = ts.any { it.role == Role.OUTPUT && it.rate <= 1e-12 }
        val makers = ts.filter { it.role == Role.PRODUCER && it.machine != null }.map { blockOf[it.machine!!] }.distinct()

        if (net.fluid) {
            val members = (0 until k).filter { abs(bal[net.id][it]) > PartitionModel.EPS }
            if (members.isEmpty()) {
                // Made and used inside one block: its overflow leaves that block for the edge.
                if (overflow) makers.forEach { b ->
                    val ds = directSide(b, outDirect)
                    raw += if (ds != null) Link(0, net.item, 0.0, b, EDGE, true, fromSide = ds.name) else Link(0, net.item, 0.0, b, EDGE, true, routed = true)
                }
                return@forEach
            }
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
            if (output > 1e-9 || overflow) raw += Link(0, net.item, output, HUB, EDGE, true, routed = true)
            return@forEach
        }

        fun dist(a: Int, b: Int): Double = when {
            a == EDGE && b == EDGE -> 1e9
            a == EDGE -> edgeDist(b, inAll) - (if (directSide(b, inDirect) != null) 0.5 else 0.0)
            b == EDGE -> edgeDist(a, outAll) - (if (directSide(a, outDirect) != null) 0.5 else 0.0)
            else -> centreDistance(g.boxes[a], g.boxes[b])
        }
        // Suppliers and takers paired nearest first, from each block's balance of the item.
        fun pairUp(row: DoubleArray): LinkedHashMap<Pair<Int, Int>, Double> {
            val give = ArrayList<Pair<Int, Double>>(); val take = ArrayList<Pair<Int, Double>>()
            (0 until k).forEach { b -> val r = row[b]; if (r > PartitionModel.EPS) give += b to r else if (r < -PartitionModel.EPS) take += b to -r }
            if (source > 1e-9) give += EDGE to source
            if (output > 1e-9) take += EDGE to output
            val giveLeft = give.toMap().toMutableMap(); val takeLeft = take.toMap().toMutableMap()
            val pairs = give.flatMap { gv -> take.map { t -> gv.first to t.first } }.filter { (a, b) -> a != b }.sortedBy { (a, b) -> dist(a, b) }
            val flows = LinkedHashMap<Pair<Int, Int>, Double>()
            pairs.forEach { (a, b) ->
                val r = minOf(giveLeft.getValue(a), takeLeft.getValue(b))
                if (r < 1e-9) return@forEach
                giveLeft[a] = giveLeft.getValue(a) - r; takeLeft[b] = takeLeft.getValue(b) - r
                flows.merge(a to b, r, Double::plus)
            }
            return flows
        }
        // Slivers: a flow of a few hundredths of a belt from a supplier whose output goes mostly elsewhere goes instead
        // with the consumer's largest other supplier, if that one can make it. A sliver link gets a belt of its own,
        // and in game a hungry consumer takes from it whatever the machines feeding that belt make (block 19's 0.15/s
        // link to block 29 took half a furnace column, and the block it was planned for starved). Moving a link
        // changes what both suppliers make, so a move is only proposed here; the plan is rebalanced with it built in
        // ([rebalanceWith]) and the links made again with [moves]. Pairing the moved plan's balances with each move
        // undone gives the same flows, and the move then hands the sliver over.
        val netMoves = moves.orEmpty().filter { it.net == net.id }
        var flows = pairUp(bal[net.id])
        if (netMoves.isNotEmpty()) {
            val undone = bal[net.id].copyOf()
            netMoves.forEach { m -> undone[m.via] -= m.rate; undone[m.from] += m.rate }
            val f = pairUp(undone)
            val fits = netMoves.groupBy { it.from to it.to }.all { (ab, ms) -> (f[ab] ?: 0.0) >= ms.sumOf { it.rate } - 1e-9 }
            if (fits) {
                netMoves.forEach { m ->
                    val left = f.getValue(m.from to m.to) - m.rate
                    if (left > 1e-9) f[m.from to m.to] = left else f.remove(m.from to m.to)
                    f.merge(m.via to m.to, m.rate, Double::plus)
                }
                flows = f
            } else warnings += "${net.item}: sliver links kept (the rebalanced plan pairs its blocks differently)"
        } else if (proposed != null) {
            val sliver = minOf(1.0, 0.03 * beltThroughput)
            val spareLeft = HashMap(spare[net.id].orEmpty())
            flows.entries.filter { (ab, r) -> r < sliver && ab.first >= 0 && ab.second >= 0 }.sortedBy { it.value }.forEach { e ->
                val (a, b) = e.key
                val r = e.value
                if (flows.filterKeys { it.first == a && it.second != b }.values.sum() < r) return@forEach   // a's main output is this one
                val s = flows.filterKeys { it.second == b && it.first != a && it.first >= 0 }
                    .filter { (sb, _) -> (spareLeft[sb.first] ?: 0.0) >= r }.maxByOrNull { it.value }?.key?.first ?: return@forEach
                proposed += SliverMove(net.id, a, b, s, r)
                spareLeft[s] = spareLeft.getValue(s) - r
            }
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
        // Overflow: from each block making it whose users are all inside it (a second output beside a link to another
        // block would take half of what goes there).
        // Not into a block that also receives it: there the overflow would drain the supply.
        if (overflow) makers.forEach { b ->
            if (flows.keys.none { it.first == b || it.second == b } && raw.none { it.item == net.item && (it.from == b || it.to == b) }) emit(b, EDGE, 0.0)
        }
        // And from each block that receives it and makes none, after its own users (Py's logistic science: ash went
        // from three blocks to one whose users stood still; with no way out, the burners' ash boxes filled and they
        // stopped). 57 of its 222 overflows had no way out of the factory.
        if (overflow) flows.keys.map { it.second }.filter { it >= 0 && it !in makers }.distinct().forEach { b ->
            if (raw.none { it.item == net.item && it.from == b }) emit(b, EDGE, 0.0)
        }
    }

    // Sides: a port faces what it connects to; sides whose ports leave the factory straight out take nothing else.
    val directSides = Array(k) { HashSet<Dir>() }
    raw.filter { !it.throughCorridor }.forEach { l ->
        if (l.from >= 0) directSides[l.from] += Dir.fromName(l.fromSide!!)
        if (l.to >= 0) directSides[l.to] += Dir.fromName(l.toSide!!)
    }
    // When every side toward the other end leaves the factory straight out (a corner block of Py science 2 sent pitch
    // to its pipe tree from its top side, flush with the factory's edge, where the corridor would start outside),
    // any side that does not.
    fun pick(b: Int, prefs: List<Dir>): String =
        (prefs.firstOrNull { it !in directSides[b] } ?: (prefs + Dir.entries).firstOrNull { it !in directSides[b] } ?: prefs.first()).name
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
    // A corridor link on a side that leaves the factory straight out: that side is flush with the factory's edge, and
    // the corridor would start outside it.
    out.filter { it.throughCorridor }.forEach { l ->
        listOf(l.from to l.fromSide, l.to to l.toSide).forEach { (b, sd) ->
            if (b >= 0 && sd != null && Dir.fromName(sd) in directSides[b]) warnings += "${l.item}: block ${b + 1}'s corridor port is on its side $sd, which also leaves the factory straight out"
        }
    }
    return if (cfg.lanePairs) pairLanes(out, cfg.linkBeltShare * beltThroughput / 2) else out
}

/**
 * Pairs of links between the same two blocks whose items each fit a lane ([laneCap]) share one belt, one item per
 * lane: the consuming block takes both on one input belt, and next to the supplying block the two items' belts
 * side-load onto it from either side. Only items the consumer takes from this one link and does not pass on.
 */
private fun pairLanes(links: List<Link>, laneCap: Double): List<Link> {
    fun single(l: Link) = links.count { it.to == l.to && it.item == l.item } == 1 && links.none { it.from == l.to && it.item == l.item }
    val pairs = HashMap<Int, Int>()
    links.filter { it.inner && !it.fluid && it.rate <= laneCap && single(it) }.groupBy { it.from to it.to }.values.forEach { group ->
        group.sortedBy { it.rate }.chunked(2).filter { it.size == 2 }.forEach { (a, b) -> pairs[a.id] = b.id; pairs[b.id] = a.id }
    }
    return links.map { l ->
        val p = pairs[l.id] ?: return@map l
        // Both arrive on one port: the side of the lower id.
        val first = minOf(l.id, p)
        l.copy(pair = p, toSide = links[first].toSide)
    }
}

/** One block as a plan of its own: local machine ids, a port per link, and which link each port serves. */
class BlockPlan(
    val index: Int, val plan: ProductionPlan, val globalIds: List<Int>,
    /** The link of each port (of a shared output port, its first link). */
    val linkOfPort: Map<Int, Int>,
    /** Every link of each port: more than one where links share an output port ([blockPlan]'s shared). */
    val linksOfPort: Map<Int, List<Int>> = linkOfPort.mapValues { listOf(it.value) },
)

/** The plan of block [b]: its machines, their terminals, and an input or output port per link it is part of. */
fun blockPlan(full: ProductionPlan, split: Split, b: Int, tuning: Tuning, shared: Boolean = false): BlockPlan =
    blockPlan(full, split.blocks[b], split.links, b, tuning, shared)

/**
 * Belt links leaving block [b] that share one output port: those to other blocks of one item on one side, packed into
 * groups of two or three of at most a belt's worth (largest first). Splitters right outside the port give each its
 * own belt.
 */
fun sharedOutputs(full: ProductionPlan, links: List<Link>, b: Int, tuning: Tuning): List<List<Link>> {
    val cap = tuning.net.beltUtilisation * data.GameConstants.beltTier(full.beltTier, full.beltStack).throughput
    return links.filter { it.from == b && it.inner && !it.fluid && it.pair < 0 }.groupBy { it.item to it.fromSide }.values.flatMap { ls ->
        val groups = ArrayList<MutableList<Link>>()
        ls.sortedByDescending { it.rate }.forEach { l ->
            groups.firstOrNull { g -> g.size < 3 && g.sumOf { it.rate } + l.rate <= cap + 1e-9 }?.add(l) ?: groups.add(mutableListOf(l))
        }
        groups
    }.filter { it.size > 1 }
}

fun blockPlan(full: ProductionPlan, ids: List<Int>, links: List<Link>, b: Int, tuning: Tuning, shared: Boolean = false): BlockPlan {
    val local = ids.withIndex().associate { (i, g) -> g to i }
    val machines = ids.map { full.machines[it].copy(id = local.getValue(it)) }
    val terminals = ArrayList<Terminal>()
    full.terminals.filter { it.machine != null && it.machine in local }.forEach { t ->
        terminals += t.copy(id = terminals.size, machine = local.getValue(t.machine!!), port = null)
    }
    val ports = ArrayList<Port>()
    val linkOfPort = HashMap<Int, Int>()
    val linksOfPort = HashMap<Int, List<Int>>()
    val portOfLink = HashMap<Int, Int>()
    val groupOf = (if (shared) sharedOutputs(full, links, b, tuning) else emptyList()).flatMap { g -> g.map { it.id to g } }.toMap()
    links.filter { it.from == b || it.to == b }.forEach { l ->
        if (l.id in portOfLink) return@forEach
        val out = l.from == b
        val side = l.side(b) ?: error("link ${l.id} has no side on block $b")
        val edge = when (side) { "N" -> "north"; "E" -> "east"; "S" -> "south"; else -> "west" }
        val port = Port(ports.size, l.item, listOf(ConfigSource(l.item, edge = edge)), fluid = l.fluid, output = out, single = l.throughCorridor)
        ports += port
        val group = groupOf[l.id] ?: listOf(l)
        linkOfPort[port.id] = l.id
        linksOfPort[port.id] = group.map { it.id }
        group.forEach { portOfLink[it.id] = port.id }
        terminals += Terminal(terminals.size, if (out) Role.OUTPUT else Role.SOURCE, l.item, group.sumOf { it.rate }, 1, port = port.id, fluid = l.fluid)
    }
    // Input ports of a lane pair know each other.
    ports.replaceAll { p ->
        val l = links[linkOfPort.getValue(p.id)]
        if (!p.output && l.pair >= 0) portOfLink[l.pair]?.let { p.copy(pairWith = it) } ?: p else p
    }
    val tier = data.GameConstants.beltTier(full.beltTier, full.beltStack)
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
        emptyMap(), full.beltTier, emptyList(), outputs, beltStack = full.beltStack,
        // Leftovers sharing a mixed belt: the block hands its share on to the factory's edge like any item.
        mixed = full.mixed.filterKeys { item -> machines.any { item in it.itemProducts } },
        utilization = full.utilization.filterKeys { r -> machines.any { it.recipe == r } }, headroom = full.headroom)
    return BlockPlan(b, plan, ids, linkOfPort, linksOfPort)
}
