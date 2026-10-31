package search

import config.RunConfiguration
import config.Tuning
import config.strictJson
import data.RootData
import plan.buildPlan
import route.ChainStat
import java.io.File

data class CalibrationResult(val tuning: Tuning, val samples: Int, val areaRatios: Map<String, Double>, val report: List<String>)

/**
 * Refits the placer's q(k) table and the s_lo area factor from routed results: runs a short search per
 * job, collects every routed chain (terminals, HPWL, belt tiles) from all successful trials, and takes
 * the smallest feasible box area over machine area per job.
 */
fun calibrate(data: RootData, jobs: List<Pair<String, RunConfiguration>>, base: Tuning, budgetS: Double, out: File): CalibrationResult {
    val stats = ArrayList<ChainStat>()
    val ratios = LinkedHashMap<String, Double>()
    val report = ArrayList<String>()
    jobs.forEach { (name, cfg0) ->
        val cfg = cfg0.copy(search = cfg0.search.copy(timeBudgetS = budgetS))
        val plan = buildPlan(data, cfg, base)
        val search = Search(data, cfg, base, plan, File(out, name), onTrial = { t -> if (t.success) synchronized(stats) { stats += t.route!!.chainStats } })
        val res = search.run()
        res.best?.let { ratios[name] = it.area.toDouble() / plan.totalMachineArea() }
        report += "$name: best ${res.best?.let { "${it.w}x${it.h}" } ?: "none"}, area ratio ${ratios[name]?.let { "%.2f".format(it) } ?: "-"}, ${res.trials} trials"
    }

    // q(k): median tiles / HPWL per terminal count, made non-decreasing in k.
    val byK = stats.filter { it.hpwl > 0 }.groupBy { it.terminals }.toSortedMap()
    val points = ArrayList<List<Double>>()
    var last = 0.0
    byK.forEach { (k, list) ->
        if (list.size < 3) return@forEach
        val sorted = list.map { it.tiles.toDouble() / it.hpwl }.sorted()
        val med = sorted[sorted.size / 2]
        last = maxOf(last, med)
        points += listOf(k.toDouble(), last)
        report += "q($k) = ${"%.3f".format(last)} from ${list.size} chains"
    }
    val qTable = if (points.size >= 2) {
        // Extend beyond the largest observed k with the old table's slope.
        val tail = base.place.qTable.filter { it[0] > points.last()[0] }.map { listOf(it[0], it[1] - base.place.q(points.last()[0].toInt()) + points.last()[1]) }
        listOf(listOf(1.0, points.first()[1])) + points.filter { it[0] > 1.0 } + tail
    } else base.place.qTable
    // Area factor: s_lo must stay a lower bound, so use the tightest job (with a small margin).
    val factor = ratios.values.minOrNull()?.let { it * 0.95 } ?: base.search.areaFactor
    report += "area factor ${"%.3f".format(factor)} (min ratio over ${ratios.size} jobs), ${stats.size} routed chains"
    val tuned = base.copy(place = base.place.copy(qTable = qTable), search = base.search.copy(areaFactor = factor))
    return CalibrationResult(tuned, stats.size, ratios, report)
}

fun writeTuning(t: Tuning, file: File) {
    file.parentFile?.mkdirs()
    file.writeText(strictJson.encodeToString(Tuning.serializer(), t))
}
