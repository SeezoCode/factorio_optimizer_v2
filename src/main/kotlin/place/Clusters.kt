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
fun diClusters(plan: ProductionPlan, longHanded: Boolean = false): List<Cluster> {
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
    return cells(plan, out, used, if (longHanded) 3 else 1)
}

/**
 * Cells, a level up: two machines of a recipe further up the chain between two clusters whose outer machines make
 * two of its ingredients, each right below an outer machine of the upper cluster and right above one of the lower
 * (chemical science between the engine and the advanced circuit clusters: engines from above, circuits from below,
 * only sulfur and the packs on belts). Both cluster shapes keep their outer machines at the same two columns, so any
 * two line up. An outer machine must make all the machine beside it needs; members must not overlap. With long-handed
 * inserters the row stands three tiles from each cluster ([gap]): a long inserter hands items across, and the two
 * tiles beside it carry belts through the cell to the machines inside it (machine, belt, long inserter, belt, machine).
 */
private fun cells(plan: ProductionPlan, clusters: List<Cluster>, used: BooleanArray, gap: Int): List<Cluster> {
    val ms = plan.machines
    val pool = clusters.toMutableList()
    val out = ArrayList<Cluster>()
    /** The two outer machines of a cluster with a consumer on each side, at columns 0 and the far one. */
    fun outer(c: Cluster): Pair<Int, Int>? {
        if (c.kind !in setOf("stack", "sandwich") || c.members.size < 3) return null
        val a = 0; val b = c.members.size - 1
        if (ms[c.members[a]].recipe != ms[c.members[b]].recipe || c.dy[a] != c.dy[b] || c.dx[b] == 0) return null
        return a to b
    }
    fun output(m: Int, item: String) = ms[m].products[item] ?: 0.0
    var changed = true
    while (changed) {
        changed = false
        val tops = ms.indices.filter { !used[it] && ms[it].fluidDemand.isEmpty() && ms[it].fluidIn.isEmpty() && ms[it].fluidOut.isEmpty() && ms[it].w == ms[it].h }
            .groupBy { ms[it].recipe }.values.filter { it.size >= 2 }
        search@ for (row in tops) {
            val s = ms[row[0]].w
            for (a in pool) for (b in pool) {
                if (a === b) continue
                val (a0, a1) = outer(a) ?: continue
                val (b0, b1) = outer(b) ?: continue
                if (a.dx[a1] != b.dx[b1] || ms[a.members[a0]].w != s || ms[b.members[b0]].w != s) continue
                val itemA = ms[a.members[a0]].product; val itemB = ms[b.members[b0]].product
                if (itemA == itemB) continue
                val r = row.take(2)
                // Each outer machine feeds the row machine beside it on its own.
                if (r.any { m -> (ms[m].demand[itemA] ?: 0.0) <= 0 || (ms[m].demand[itemB] ?: 0.0) <= 0 }) continue
                if (listOf(a.members[a0], a.members[a1]).any { output(it, itemA) + 1e-9 < ms[r[0]].demand.getValue(itemA) }) continue
                if (listOf(b.members[b0], b.members[b1]).any { output(it, itemB) + 1e-9 < ms[r[0]].demand.getValue(itemB) }) continue
                // Upper cluster as it is; the row one tile below its outer machines; the lower cluster one tile below the row.
                val rowY = a.dy[a0] + s + gap
                val bY = rowY + s + gap - b.dy[b0]
                val members = ArrayList<Int>(); val dx = ArrayList<Int>(); val dy = ArrayList<Int>()
                a.members.indices.forEach { members += a.members[it]; dx += a.dx[it]; dy += a.dy[it] }
                members += r[0]; dx += 0; dy += rowY
                members += r[1]; dx += a.dx[a1]; dy += rowY
                b.members.indices.forEach { members += b.members[it]; dx += b.dx[it]; dy += bY + b.dy[it] }
                val y0 = dy.min()
                val rects = members.indices.map { geom.Rect(dx[it], dy[it] - y0, ms[members[it]].w, ms[members[it]].h) }
                if (rects.indices.any { i -> (i + 1 until rects.size).any { j -> rects[i].overlaps(rects[j]) } }) continue
                pool.remove(a); pool.remove(b); r.forEach { used[it] = true }
                out += Cluster(members.toIntArray(), dx.toIntArray(), dy.map { it - y0 }.toIntArray(), "cell")
                changed = true
                break@search
            }
        }
    }
    return out + pool
}
