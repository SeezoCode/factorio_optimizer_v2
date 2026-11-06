package place

import plan.ProductionPlan
import plan.Role

/**
 * A direct-insertion cluster: machines at fixed offsets from the cluster's top-left (machines facing north), one tile
 * apart where an inserter hands items straight from a producer to a consumer. The annealer places and moves it whole.
 */
data class Cluster(val members: IntArray, val dx: IntArray, val dy: IntArray, val kind: String) {
    override fun equals(other: Any?) = other is Cluster && members.contentEquals(other.members) && dx.contentEquals(other.dx) && dy.contentEquals(other.dy)
    override fun hashCode() = members.contentHashCode()
}

/**
 * Clusters players build, from the plan: a consumer of one item made inside the block sits on either side of its
 * producer (`sandwich`: consumer, producer, consumer in a row); a consumer of two such items sits beside both
 * producers stacked one over the other (`stack`: one consumer left, one right, each touching both producers).
 * A producer joins only when it makes all its consumers there need; machines with fluids, or of different sizes,
 * are left out (their orientation or fit is not fixed). Consumers of items from the bottom of the chain go first.
 */
fun diClusters(plan: ProductionPlan): List<Cluster> {
    val ms = plan.machines
    val producersOf = HashMap<String, MutableList<Int>>()
    plan.terminals.filter { it.role == Role.PRODUCER && it.machine != null && !it.fluid }.forEach { producersOf.getOrPut(it.item) { ArrayList() } += it.machine!! }
    val used = BooleanArray(ms.size)
    fun plain(m: Int) = ms[m].fluidDemand.isEmpty() && ms[m].fluidIn.isEmpty() && ms[m].fluidOut.isEmpty() && ms[m].w == ms[m].h
    fun output(p: Int, item: String) = ms[p].products[item] ?: 0.0
    fun free(item: String, size: Int) = producersOf[item].orEmpty().firstOrNull { !used[it] && plain(it) && ms[it].w == size }
    /** Consumers of the same recipe as [c], free and fitting, to pair with it. */
    fun mates(c: Int) = ms.indices.filter { it != c && !used[it] && ms[it].recipe == ms[c].recipe && plain(it) }
    val out = ArrayList<Cluster>()
    fun internal(item: String) = producersOf[item].orEmpty().isNotEmpty()
    /** Made by machines fed only from outside the block (gears, pipes, cable): the bottom of the chain. */
    fun leaf(item: String) = producersOf[item].orEmpty().all { p -> ms[p].demand.keys.none { internal(it) } }
    // Bottom up: consumers of leaf items first, so the many small, fast intermediates go machine to machine and the
    // products further up (which need belts to their own consumers anyway) are not taken as producers early.
    val consumers = ms.indices.filter { plain(it) }.map { c -> c to ms[c].demand.keys.filter { i -> producersOf[i].orEmpty().any { it != c } }.sortedByDescending { leaf(it) } }
        .filter { it.second.isNotEmpty() }
        .sortedWith(compareByDescending<Pair<Int, List<String>>> { (_, items) -> items.count { leaf(it) } }.thenByDescending { it.second.size })
    for ((c, items) in consumers) {
        if (used[c]) continue
        val s = ms[c].w
        if (items.size >= 2) {
            // Stack: producers of the first two items one over the other, consumers left and right, overlapping both.
            val (a, b) = items.take(2)
            val pa = free(a, s) ?: continue
            used[pa] = true
            val pb = free(b, s)
            if (pb == null) { used[pa] = false; continue }
            used[pb] = true
            val need = { m: Int -> (ms[m].demand[a] ?: 0.0) to (ms[m].demand[b] ?: 0.0) }
            if (output(pa, a) + 1e-9 < need(c).first || output(pb, b) + 1e-9 < need(c).second) { used[pa] = false; used[pb] = false; continue }
            used[c] = true
            val c2 = mates(c).firstOrNull { m ->
                val (da, db) = need(m); val (ca, cb) = need(c)
                output(pa, a) + 1e-9 >= ca + da && output(pb, b) + 1e-9 >= cb + db
            }
            val px = s + 1
            val members = listOfNotNull(c, pa, pb, c2)
            val dx = listOfNotNull(0, px, px, c2?.let { px + s + 1 })
            val dy = listOfNotNull(s - 1, 0, s + 1, c2?.let { s - 1 })
            c2?.let { used[it] = true }
            out += Cluster(members.toIntArray(), dx.toIntArray(), dy.toIntArray(), "stack")
        } else {
            // Sandwich: the producer between two consumers of its item.
            val item = items[0]
            val p = free(item, s) ?: continue
            if (output(p, item) + 1e-9 < (ms[c].demand[item] ?: 0.0)) continue
            used[p] = true; used[c] = true
            val c2 = mates(c).firstOrNull { m -> output(p, item) + 1e-9 >= (ms[c].demand[item] ?: 0.0) + (ms[m].demand[item] ?: 0.0) }
            c2?.let { used[it] = true }
            val members = listOfNotNull(c, p, c2)
            val dx = listOfNotNull(0, s + 1, c2?.let { 2 * (s + 1) })
            out += Cluster(members.toIntArray(), dx.toIntArray(), IntArray(members.size), "sandwich")
        }
    }
    return out
}
