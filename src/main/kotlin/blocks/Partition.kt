package blocks

import config.BlocksConfig
import plan.ProductionPlan
import plan.Role
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Which machines go into which block. A block trades an item with the rest of the factory when what its machines
 * make and use of it does not balance; every such (block, item) pair is a port on the block's edge and a belt (or
 * pipe) through the corridors, so the split pays for each of them:
 *
 * cost = sum over blocks b and items i with balance B(b, i) != 0 of w(i) * (L(i) + |B(b, i)| / C(i))
 *      + D * (distinct items each block touches) + O * (blocks used)
 *
 * where L is [BlocksConfig.linkCost] for a belt and [BlocksConfig.fluidLinkCost] for a pipe, C a belt's share
 * ([BlocksConfig.linkBeltShare] of the tier) or a pipe's, w is [BlocksConfig.splitAtWeight] for items in
 * [BlocksConfig.splitAt] (cheap places to cut) and 1 otherwise, D a small price on blocks that touch many items
 * (they are harder to route inside), and O [BlocksConfig.blockCost]. Raw inputs and products count too: a block
 * that uses iron plate needs a belt of it whoever makes it.
 *
 * Unlike a split by item flow, this sees that cutting a busy belt costs one link like cutting a quiet one, and that
 * consolidating every consumer of an item into the block that makes it removes the link altogether. Fluids may be
 * cut like items (at a higher price), so oil processing no longer has to stay one block.
 */
class PartitionModel(val plan: ProductionPlan, val cfg: BlocksConfig, beltThroughput: Double) {
    val n = plan.machines.size
    val items: List<String>
    /** Per item: price of a port, and price per unit of rate through it. */
    val linkCost: DoubleArray
    val unitCost: DoubleArray
    /** Per machine: the items it makes or uses, and its net rate of each (made minus used). */
    val mItems: Array<IntArray>
    val mRates: Array<DoubleArray>
    val recipeOf: IntArray
    val recipes: List<String>
    val diversity = 0.15

    init {
        val itemIdx = LinkedHashMap<String, Int>()
        val fluid = HashSet<String>()
        val rates = Array(n) { HashMap<Int, Double>() }
        plan.nets.forEach { net ->
            val i = itemIdx.getOrPut(net.item) { itemIdx.size }
            if (net.fluid) fluid += net.item
            plan.netTerminals(net).forEach { t ->
                val m = t.machine ?: return@forEach
                when (t.role) {
                    Role.PRODUCER -> rates[m].merge(i, t.rate, Double::plus)
                    Role.CONSUMER -> rates[m].merge(i, -t.rate, Double::plus)
                    else -> {}
                }
            }
        }
        items = itemIdx.keys.toList()
        val beltCap = cfg.linkBeltShare * beltThroughput
        linkCost = DoubleArray(items.size) { i ->
            val w = if (items[i] in cfg.splitAt) cfg.splitAtWeight else 1.0
            w * (if (items[i] in fluid) cfg.fluidLinkCost else cfg.linkCost)
        }
        unitCost = DoubleArray(items.size) { i ->
            val w = if (items[i] in cfg.splitAt) cfg.splitAtWeight else 1.0
            w * cfg.linkCost / (if (items[i] in fluid) PIPE_RATE else beltCap)
        }
        mItems = Array(n) { m -> rates[m].keys.sorted().toIntArray() }
        mRates = Array(n) { m -> DoubleArray(mItems[m].size) { rates[m].getValue(mItems[m][it]) } }
        recipes = plan.machines.map { it.recipe }.distinct()
        val rIdx = recipes.withIndex().associate { (i, r) -> r to i }
        recipeOf = IntArray(n) { rIdx.getValue(plan.machines[it].recipe) }
    }

    /** Price of one (block, item) balance [b] with [count] machines of the block touching the item. */
    fun term(i: Int, b: Double, count: Int): Double =
        (if (abs(b) > EPS) linkCost[i] + abs(b) * unitCost[i] else 0.0) + (if (count > 0) diversity else 0.0)

    /** The cost of an assignment of machines to blocks (-1: none), as defined above. */
    fun cost(assign: IntArray, blocks: Int): Double {
        val bal = Array(blocks) { DoubleArray(items.size) }
        val cnt = Array(blocks) { IntArray(items.size) }
        val size = IntArray(blocks)
        for (m in 0 until n) {
            val b = assign[m]; size[b]++
            mItems[m].forEachIndexed { k, i -> bal[b][i] += mRates[m][k]; cnt[b][i]++ }
        }
        var c = 0.0
        for (b in 0 until blocks) {
            if (size[b] > 0) c += cfg.blockCost
            for (i in items.indices) c += term(i, bal[b][i], cnt[b][i])
        }
        return c
    }

    /** Ports the assignment needs: (block, item) pairs that do not balance. */
    fun ports(assign: IntArray, blocks: Int): Int {
        val bal = Array(blocks) { DoubleArray(items.size) }
        for (m in 0 until n) mItems[m].forEachIndexed { k, i -> bal[assign[m]][i] += mRates[m][k] }
        return bal.sumOf { row -> row.count { abs(it) > EPS } }
    }

    companion object {
        const val EPS = 1e-6
        /** Units/s one pipe between blocks is counted to carry, for the rate part of a fluid link's price. */
        const val PIPE_RATE = 1000.0
    }
}

/** A split of the machines into blocks: machine ids per block, and the cost under [PartitionModel]. */
class Partition(
    val blocks: List<List<Int>>, val cost: Double, val ports: Int, val greedyCost: Double, val greedyPorts: Int,
    /** Every split the search ended with (the greedy one first, then each annealing run's best), cheapest first. */
    val candidates: List<List<List<Int>>> = listOf(blocks),
)

/**
 * Splits [plan] into blocks of at most [BlocksConfig.maxMachines] machines, minimising [PartitionModel]'s cost:
 * first greedily, merging groups of same-recipe machines along the largest saving, then by simulated annealing
 * over single machines (move one to another block, swap two) and whole recipe groups (move every machine of a
 * recipe in a block to another block). Several annealing runs; the cheapest split wins.
 */
fun partitionPlan(plan: ProductionPlan, cfg: BlocksConfig, beltThroughput: Double, seed: Long = 1L): Partition {
    val model = PartitionModel(plan, cfg, beltThroughput)
    val n = model.n
    val max = cfg.maxMachines
    val nItems = model.items.size

    // 1. Greedy: same-recipe groups (cut into pieces of at most half a block), merged along the largest saving.
    class Cluster(val ms: MutableList<Int>, val bal: DoubleArray, val cnt: IntArray) {
        fun cost() = cfg.blockCost + (0 until nItems).sumOf { model.term(it, bal[it], cnt[it]) }
    }
    fun clusterOf(ms: List<Int>): Cluster {
        val c = Cluster(ms.toMutableList(), DoubleArray(nItems), IntArray(nItems))
        ms.forEach { m -> model.mItems[m].forEachIndexed { k, i -> c.bal[i] += model.mRates[m][k]; c.cnt[i]++ } }
        return c
    }
    val clusters = ArrayList<Cluster>()
    (0 until n).groupBy { model.recipeOf[it] }.values.forEach { ms ->
        val pieces = (ms.size + maxOf(1, max / 2) - 1) / maxOf(1, max / 2)
        val per = (ms.size + pieces - 1) / pieces
        ms.chunked(per).forEach { clusters += clusterOf(it) }
    }
    fun mergedCost(a: Cluster, b: Cluster): Double =
        cfg.blockCost + (0 until nItems).sumOf { model.term(it, a.bal[it] + b.bal[it], a.cnt[it] + b.cnt[it]) }
    while (true) {
        var best = -1.0; var bi = -1; var bj = -1
        val costs = clusters.map { it.cost() }
        for (i in clusters.indices) for (j in i + 1 until clusters.size) {
            if (clusters[i].ms.size + clusters[j].ms.size > max) continue
            val saving = costs[i] + costs[j] - mergedCost(clusters[i], clusters[j])
            if (saving > best) { best = saving; bi = i; bj = j }
        }
        if (bi < 0) break
        val a = clusters[bi]; val b = clusters.removeAt(bj)
        a.ms += b.ms
        for (i in 0 until nItems) { a.bal[i] += b.bal[i]; a.cnt[i] += b.cnt[i] }
    }
    val k = clusters.size + 1   // one spare block the annealer may open
    val start = IntArray(n).also { a -> clusters.forEachIndexed { b, c -> c.ms.forEach { a[it] = b } } }
    val greedyCost = model.cost(start, k)
    val greedyPorts = model.ports(start, k)

    // 2. Annealing from the greedy split.
    val found = arrayListOf(start.clone() to greedyCost)
    repeat(3) { run -> found += anneal(model, start, k, max, SplittableRandom(seed * 7919 + run)) }
    fun blocksOf(a: IntArray) = (0 until k).map { b -> (0 until n).filter { a[it] == b } }.filter { it.isNotEmpty() }
    val sorted = found.sortedBy { it.second }
    val (bestAssign, bestCost) = sorted.first()
    return Partition(blocksOf(bestAssign), bestCost, model.ports(bestAssign, k), greedyCost, greedyPorts, sorted.map { blocksOf(it.first) })
}

/** Simulated annealing over the assignment; returns the best assignment seen and its cost. */
private fun anneal(model: PartitionModel, start: IntArray, k: Int, max: Int, rnd: SplittableRandom): Pair<IntArray, Double> {
    val n = model.n
    val nItems = model.items.size
    val cfg = model.cfg
    val assign = start.clone()
    val bal = Array(k) { DoubleArray(nItems) }
    val cnt = Array(k) { IntArray(nItems) }
    val size = IntArray(k)
    for (m in 0 until n) {
        val b = assign[m]; size[b]++
        model.mItems[m].forEachIndexed { j, i -> bal[b][i] += model.mRates[m][j]; cnt[b][i]++ }
    }
    var cost = model.cost(assign, k)
    // Blocks holding each item, for picking a target block that already trades in one of the machine's items.
    fun delta(m: Int, to: Int): Double {
        val from = assign[m]
        if (from == to) return 0.0
        var d = 0.0
        if (size[from] == 1) d -= cfg.blockCost
        if (size[to] == 0) d += cfg.blockCost
        val its = model.mItems[m]; val rs = model.mRates[m]
        for (j in its.indices) {
            val i = its[j]; val r = rs[j]
            d += model.term(i, bal[from][i] - r, cnt[from][i] - 1) - model.term(i, bal[from][i], cnt[from][i])
            d += model.term(i, bal[to][i] + r, cnt[to][i] + 1) - model.term(i, bal[to][i], cnt[to][i])
        }
        return d
    }
    fun apply(m: Int, to: Int) {
        val from = assign[m]
        val its = model.mItems[m]; val rs = model.mRates[m]
        for (j in its.indices) {
            val i = its[j]
            bal[from][i] -= rs[j]; cnt[from][i]--
            bal[to][i] += rs[j]; cnt[to][i]++
        }
        size[from]--; size[to]++
        assign[m] = to
    }
    fun target(m: Int): Int {
        if (rnd.nextInt(10) < 3 || model.mItems[m].isEmpty()) return rnd.nextInt(k)
        val i = model.mItems[m][rnd.nextInt(model.mItems[m].size)]
        val holders = (0 until k).filter { it != assign[m] && cnt[it][i] > 0 }
        return if (holders.isEmpty()) rnd.nextInt(k) else holders[rnd.nextInt(holders.size)]
    }
    val byRecipe = (0 until n).groupBy { model.recipeOf[it] }.values.map { it.toIntArray() }

    /** One random move: applied and its cost change returned, with how to undo it; null if not allowed. */
    fun propose(): Pair<Double, () -> Unit>? {
        when (rnd.nextInt(10)) {
            in 0..4 -> {   // move one machine
                val m = rnd.nextInt(n); val to = target(m); val from = assign[m]
                if (to == from || size[to] >= max) return null
                val d = delta(m, to); apply(m, to)
                return d to { apply(m, from) }
            }
            in 5..7 -> {   // swap two machines of different recipes
                val a = rnd.nextInt(n); val ta = target(a)
                if (ta == assign[a] || size[ta] == 0) return null
                val inB = (0 until 8).map { rnd.nextInt(n) }.firstOrNull { assign[it] == ta && model.recipeOf[it] != model.recipeOf[a] } ?: return null
                val fa = assign[a]
                val d1 = delta(a, ta); apply(a, ta)
                val d2 = delta(inB, fa); apply(inB, fa)
                return (d1 + d2) to { apply(inB, ta); apply(a, fa) }
            }
            else -> {   // move a block's machines of one recipe
                val grp = byRecipe[rnd.nextInt(byRecipe.size)]
                val m0 = grp[rnd.nextInt(grp.size)]
                val from = assign[m0]; val to = target(m0)
                if (to == from) return null
                val ms = grp.filter { assign[it] == from }
                if (size[to] + ms.size > max) return null
                var d = 0.0
                ms.forEach { d += delta(it, to); apply(it, to) }
                return d to { ms.forEach { apply(it, from) } }
            }
        }
    }

    // Starting temperature: the mean uphill step of a few random moves.
    var up = 0.0; var ups = 0
    repeat(300) { propose()?.let { (d, undo) -> if (d > 0) { up += d; ups++ }; undo() } }
    val t0 = if (ups > 0) up / ups else 1.0
    val tEnd = t0 * 1e-3
    val steps = (n * 400).coerceIn(30_000, 600_000)
    val alpha = exp(ln(tEnd / t0) / steps)
    var t = t0
    var best = assign.clone(); var bestCost = cost
    repeat(steps) {
        val p = propose()
        if (p != null) {
            val (d, undo) = p
            if (d <= 0 || rnd.nextDouble() < exp(-d / t)) {
                cost += d
                if (cost < bestCost - 1e-9) { bestCost = cost; best = assign.clone() }
            } else undo()
        }
        t *= alpha
    }
    return best to model.cost(best, k)
}
