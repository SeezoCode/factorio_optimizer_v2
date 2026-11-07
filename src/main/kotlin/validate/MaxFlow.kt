package validate

import kotlin.math.min

/** Dinic's max flow on an adjacency list with real capacities. */
class MaxFlow(private val n: Int) {
    private val head = IntArray(n) { -1 }
    private val to = ArrayList<Int>()
    private val next = ArrayList<Int>()
    private val cap = ArrayList<Double>()
    private val level = IntArray(n)
    private val iter = IntArray(n)

    /** Adds an edge u -> v and returns its id (for [flowOn]). */
    fun edge(u: Int, v: Int, c: Double): Int {
        val id = to.size
        to += v; next += head[u]; cap += c; head[u] = id
        to += u; next += head[v]; cap += 0.0; head[v] = id + 1
        return id
    }

    /** Adds [extra] capacity to edge [id]; a later [run] pushes more flow over what is there. */
    fun raise(id: Int, extra: Double) { cap[id] = cap[id] + extra }

    /** Flow pushed through edge [id] after [run]. */
    fun flowOn(id: Int): Double = cap[id xor 1]

    fun run(s: Int, t: Int): Double {
        var total = 0.0
        while (bfs(s, t)) {
            for (i in 0 until n) iter[i] = head[i]
            while (true) {
                val f = dfs(s, t, Double.MAX_VALUE)
                if (f <= EPS) break
                total += f
            }
        }
        return total
    }

    private fun bfs(s: Int, t: Int): Boolean {
        level.fill(-1)
        level[s] = 0
        val q = ArrayDeque<Int>().apply { add(s) }
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            var e = head[u]
            while (e >= 0) {
                if (cap[e] > EPS && level[to[e]] < 0) { level[to[e]] = level[u] + 1; q += to[e] }
                e = next[e]
            }
        }
        return level[t] >= 0
    }

    private fun dfs(u: Int, t: Int, f: Double): Double {
        if (u == t) return f
        while (iter[u] >= 0) {
            val e = iter[u]
            val v = to[e]
            if (cap[e] > EPS && level[v] == level[u] + 1) {
                val d = dfs(v, t, min(f, cap[e]))
                if (d > EPS) { cap[e] = cap[e] - d; cap[e xor 1] = cap[e xor 1] + d; return d }
            }
            iter[u] = next[e]
        }
        return 0.0
    }

    private companion object { const val EPS = 1e-12 }
}
