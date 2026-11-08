package route

import config.RunConfiguration
import geom.Dir
import geom.Rect
import geom.Tile
import place.Placement
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import plan.beltDropRate
import plan.bestInserterRate
import plan.inserterCount
import plan.inserterRate
import kotlin.math.abs
import kotlin.math.min

/**
 * How one inserter connects a terminal to a belt (or a chest, for sinks). Tiles are box coordinates.
 * [insDir] is the inserter's blueprint direction (its pickup side).
 */
data class AccessOption(
    val terminal: Int,
    val beltTile: Tile,
    val insTile: Tile,
    val insDir: Dir,
    val long: Boolean,
    val trick: String,
    val side: Dir,
)

/** A direct-insertion link: inserters in the gap between two machines (I5, or I6 with long-handed). */
data class DiLink(
    val producer: Int,
    val consumer: Int,
    val item: String,
    val flow: Double,
    val tiles: List<Tile>,
    val long: Boolean,
    /** Pickup side, pointing at the producer. */
    val insDir: Dir,
)

/** Rates and inserter counts per terminal after direct insertion took its share. */
data class EffectiveTerminal(val base: Terminal, val rate: Double, val k: Int)

/**
 * Access options of a machine terminal for a placement (tricks I1, I2, I3; I7 for sinks).
 * [free] says whether a tile may hold an inserter or belt (in the box, not a machine or obstacle).
 */
fun accessOptions(
    t: Terminal, k: Int, rate: Double, rect: Rect, config: RunConfiguration, free: (Tile) -> Boolean,
): List<AccessOption> {
    val out = mutableListOf<AccessOption>()
    val consumer = t.role == Role.CONSUMER
    val sink = t.role == Role.SINK
    // With belt stacking a producer drops through a stack inserter (a long-handed one would not stack).
    val longAllowed = !sink && config.trick("I3") && "long-handed-inserter" in config.inserters.allowed &&
        !(config.beltStackSize > 1 && t.role == Role.PRODUCER) &&
        inserterRate("long-handed-inserter", config, fromBelt = consumer) * k >= rate - 1e-9
    val normalAllowed = config.inserters.allowed.any { it != "long-handed-inserter" }
    for (side in Dir.entries) {
        val d1 = rect.sideTiles(side, 1)
        val d2 = rect.sideTiles(side, 2)
        val d3 = rect.sideTiles(side, 3)
        // A consumer's inserter picks from the belt (outward); a producer's or sink's picks from the machine.
        val insDir = if (consumer) side else side.opposite
        for (i in d1.indices) {
            if (!free(d1[i])) continue
            if (normalAllowed && free(d2[i])) {
                out += AccessOption(t.id, d2[i], d1[i], insDir, false, if (sink) "I7" else if (consumer) "I1" else "I2", side)
            }
            if (longAllowed && free(d3[i])) {
                out += AccessOption(t.id, d3[i], d1[i], insDir, true, "I3", side)
            }
        }
    }
    return out
}

/**
 * Direct insertion (I5, I6), decided per placement: for each producer-consumer pair of the same item whose
 * footprints leave a 1-tile gap (or a 3-tile gap for a long-handed) with at least one tile of overlap,
 * assign flow greedily, largest first, up to the consumer's demand and the producer's spare output.
 */
fun assignDirectInsertion(
    plan: ProductionPlan, placement: Placement, config: RunConfiguration, free: (Tile) -> Boolean,
): List<DiLink> {
    if (!config.trick("I5") && !config.trick("I6")) return emptyList()
    val rectOf = placement.machines.associate { it.id to placement.rect(plan, it) }
    val producerRate = HashMap<Int, Double>()   // terminal id -> spare
    val consumerNeed = HashMap<Int, Double>()
    val spoilDrain = config.spoilage.mode == "drain"
    plan.terminals.forEach { t ->
        if (t.fluid) return@forEach
        // A machine holding something that spoils hands its spoilage out with its products: not into another machine.
        if (spoilDrain && t.machine != null && spoilProne(plan.machines[t.machine])) return@forEach
        when (t.role) {
            Role.PRODUCER -> producerRate[t.id] = t.rate
            Role.CONSUMER -> consumerNeed[t.id] = t.rate
            else -> {}
        }
    }
    val normalRate = config.inserters.allowed.filter { it != "long-handed-inserter" }
        .maxOfOrNull { inserterRate(it, config, fromBelt = false) } ?: 0.0
    val longRate = if ("long-handed-inserter" in config.inserters.allowed) inserterRate("long-handed-inserter", config, false) else 0.0

    data class Cand(val p: Terminal, val c: Terminal, val tiles: List<Tile>, val long: Boolean, val dir: Dir) {
        val cap get() = tiles.size * (if (long) longRate else normalRate)
    }
    val cands = mutableListOf<Cand>()
    val producers = plan.terminals.filter { it.role == Role.PRODUCER && !it.fluid && it.id in producerRate }
    val consumers = plan.terminals.filter { it.role == Role.CONSUMER && it.machine != null && !it.fluid && it.id in consumerNeed }
    for (p in producers) for (c in consumers) {
        if (p.item != c.item || p.machine == c.machine) continue
        val a = rectOf.getValue(p.machine!!)
        val b = rectOf.getValue(c.machine!!)
        for ((gap, long) in listOf(1 to false, 3 to true)) {
            if (long && (!config.trick("I6") || longRate == 0.0)) continue
            if (!long && (!config.trick("I5") || normalRate == 0.0)) continue
            gapTiles(a, b, gap)?.let { (tiles, dir) ->
                val usable = tiles.filter(free)
                if (usable.isNotEmpty()) cands += Cand(p, c, usable, long, dir)
            }
        }
    }
    val used = HashSet<Tile>()
    val links = mutableListOf<DiLink>()
    while (true) {
        val best = cands.maxByOrNull { min(min(producerRate.getValue(it.p.id), consumerNeed.getValue(it.c.id)), it.tiles.count { t -> t !in used } * (if (it.long) longRate else normalRate)) }
            ?: break
        val tiles = best.tiles.filter { it !in used }
        val rate = if (best.long) longRate else normalRate
        val f = min(min(producerRate.getValue(best.p.id), consumerNeed.getValue(best.c.id)), tiles.size * rate)
        cands.remove(best)
        if (f < 1e-9) continue
        val k = inserterCount(f, rate)
        // Prefer tiles near the middle of the overlap.
        val mid = tiles.map { it.x + it.y }.average()
        val chosen = tiles.sortedBy { abs(it.x + it.y - mid) }.take(k)
        used += chosen
        producerRate[best.p.id] = producerRate.getValue(best.p.id) - f
        consumerNeed[best.c.id] = consumerNeed.getValue(best.c.id) - f
        links += DiLink(best.p.machine!!, best.c.machine!!, best.p.item, f, chosen, best.long, best.dir)
    }
    return settleDiFlows(plan, links, config)
}

/**
 * What each link really delivers in game, as the validator models it: a consumer draws an item evenly
 * through all its direct-insertion inserters (each capped by its rate), and a producer that cannot keep up
 * is scaled down to its spare output. The greedy choice above only decides where inserters go.
 */
fun settleDiFlows(plan: ProductionPlan, links: List<DiLink>, config: RunConfiguration): List<DiLink> {
    val normalRate = config.inserters.allowed.filter { it != "long-handed-inserter" }
        .maxOfOrNull { inserterRate(it, config, fromBelt = false) } ?: 0.0
    val longRate = if ("long-handed-inserter" in config.inserters.allowed) inserterRate("long-handed-inserter", config, false) else 0.0
    val nIn = HashMap<Pair<Int, String>, Int>()
    links.forEach { l -> nIn.merge(l.consumer to l.item, l.tiles.size, Int::plus) }
    val want = links.map { l ->
        val need = plan.machines[l.consumer].demand[l.item] ?: 0.0
        val per = minOf(if (l.long) longRate else normalRate, need / nIn.getValue(l.consumer to l.item))
        per * l.tiles.size
    }
    // Per machine and item: a machine with several products has a spare output of each.
    val spare = HashMap<Pair<Int, String>, Double>()
    plan.terminals.forEach { t ->
        if (t.role == Role.PRODUCER && !t.fluid) spare.merge(t.machine!! to t.item, t.rate, Double::plus)
    }
    val total = HashMap<Pair<Int, String>, Double>()
    links.forEachIndexed { i, l -> total.merge(l.producer to l.item, want[i], Double::plus) }
    return links.mapIndexed { i, l ->
        val s = spare[l.producer to l.item] ?: 0.0
        val tot = total.getValue(l.producer to l.item)
        val scale = if (tot > s && tot > 0) s / tot else 1.0
        l.copy(flow = want[i] * scale)
    }
}

/** Gap tiles where an inserter reaches from [a] into [b] across [gap] free tiles, with the pickup direction. */
private fun gapTiles(a: Rect, b: Rect, gap: Int): Pair<List<Tile>, Dir>? {
    val mid = gap / 2 + 1   // tile index into the gap holding the inserter (1 for gap 1, 2 for gap 3)
    val yLo = maxOf(a.y, b.y); val yHi = minOf(a.y2, b.y2)
    val xLo = maxOf(a.x, b.x); val xHi = minOf(a.x2, b.x2)
    return when {
        yLo <= yHi && b.x - a.x2 - 1 == gap -> (yLo..yHi).map { Tile(a.x2 + mid, it) } to Dir.W
        yLo <= yHi && a.x - b.x2 - 1 == gap -> (yLo..yHi).map { Tile(a.x - mid, it) } to Dir.E
        xLo <= xHi && b.y - a.y2 - 1 == gap -> (xLo..xHi).map { Tile(it, a.y2 + mid) } to Dir.N
        xLo <= xHi && a.y - b.y2 - 1 == gap -> (xLo..xHi).map { Tile(it, a.y - mid) } to Dir.S
        else -> null
    }
}

/** Terminal rates after direct insertion; terminals fully served by it are dropped. */
fun effectiveTerminals(plan: ProductionPlan, links: List<DiLink>, config: RunConfiguration): Map<Int, EffectiveTerminal> {
    val drop = beltDropRate(config)
    val pick = bestInserterRate(config, fromBelt = true)
    val out = HashMap<Int, EffectiveTerminal>()
    plan.terminals.forEach { t ->
        if (t.fluid) return@forEach
        val di = when (t.role) {
            Role.PRODUCER -> links.filter { it.producer == t.machine && it.item == t.item }.sumOf { it.flow }
            Role.CONSUMER -> links.filter { it.consumer == t.machine && it.item == t.item }.sumOf { it.flow }
            else -> 0.0
        }
        val rate = t.rate - di
        // An overflow (a 0/s output, plan.overflowItems) stays: it takes what the consumers leave.
        if (rate < 1e-6 && !(t.role == Role.OUTPUT && t.port != null)) return@forEach
        val k = when (t.role) {
            Role.CONSUMER -> inserterCount(rate, pick)
            Role.PRODUCER, Role.SINK -> inserterCount(rate, drop)
            Role.SOURCE, Role.OUTPUT -> 1
        }
        out[t.id] = EffectiveTerminal(t, rate, k)
    }
    return out
}
