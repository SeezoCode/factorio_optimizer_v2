package blocks

import config.ConfigSource
import config.RunConfiguration
import config.Tuning
import config.strictJson
import data.GameConstants
import data.RootData
import emit.toBlueprint
import emit.toBlueprintString
import emit.toJson
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import geom.dropTile
import geom.outputTile
import geom.pickupTile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import place.PlacedMachine
import place.Placement
import plan.Net
import plan.Port
import plan.ProductionPlan
import plan.Role
import plan.Terminal
import power.buildHarness
import power.placePoles
import power.poleCandidates
import route.RouteProblem
import route.route
import search.Search
import search.TrialResult
import search.score
import search.validationSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.ValidationError
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A stitched factory: the combined layout, what validation found, and where its inputs and outputs are. */
class Factory(
    val layout: Layout, val errors: List<ValidationError>, val score: Double,
    val sources: List<SourceSpec>, val outputs: List<OutputSpec>, val notes: List<String>,
    /** Where each block's box ended up. */
    val blockRects: List<Rect> = emptyList(),
) {
    val success: Boolean get() = errors.isEmpty()
}

/**
 * Runs a job as several blocks: the plan is split (see [partitionPlan]) and floorplanned (see [Floorplanner]) so
 * each port faces what it trades with, each block is searched on its own (its share of the time budget) at the
 * shape the floorplan planned for it, then the finished blocks are floorplanned again with their real sizes and
 * ports, the belts and pipes between them are routed through the corridors (which widen where routing fails and
 * narrow where they stay empty), rows and columns that only straight belts cross are taken out, poles cover the
 * whole factory, and the result is validated against the full plan. With [config.BlocksConfig.repack] blocks are
 * then searched again at the shapes a floorplan of their real areas asks for, and with [config.BlocksConfig.merge]
 * neighbouring blocks are merged and searched again from their placements until the budget runs out; either is kept
 * only if the factory gets smaller. Every better factory is written to [out] (blueprint, render, ports.json;
 * factory.txt logs it all), each block to `block_<n>`.
 */
fun runFactory(data: RootData, config: RunConfiguration, tuning: Tuning, input: ProductionPlan, out: File): Factory? {
    val cfg = config.blocks!!
    require(input.ports.none { p -> p.specs.any { it.edge == null } }) {
        "a factory of blocks takes its inputs and outputs by edge (with from and to), not by x and y"
    }
    out.mkdirs()
    val logFile = File(out, "factory.txt")
    logFile.writeText("")
    val t0 = System.currentTimeMillis()
    val deadline = t0 + (config.search.timeBudgetS * 1000).toLong()
    fun say(msg: String) {
        val line = "[%7.1fs] %s".format((System.currentTimeMillis() - t0) / 1000.0, msg)
        println(line); logFile.appendText(line + "\n")
    }
    fun left() = (deadline - System.currentTimeMillis()) / 1000.0
    val tier = GameConstants.beltTier(config.beltTier)
    var split = splitPlan(input, cfg, tier.throughput, config.search.seed, config.box.aspect, config.reserved)
    // Machine rates rebalanced for the split: blocks, stitching and validation all use this plan.
    val plan = split.plan
    plan.write(File(out, "plan_rebalanced.json").path)
    split.warnings.forEach { say("WARNING: $it") }
    sayPlan(split, plan, ::say)
    var bps = split.blocks.indices.map { blockPlan(plan, split, it, tuning) }
    val later = (if (cfg.repack) cfg.repackShare else 0.0) + (if (cfg.merge) cfg.mergeShare else 0.0)
    fun blockConfig(budget: Double, aspect: Double, startSize: Int? = null) = config.copy(
        blocks = null, testMode = false, reserved = emptyList(), forbiddenTiles = emptyList(),
        box = config.box.copy(aspect = aspect, startSize = startSize, maxSize = null, fixedHeight = null, fixedWidth = null),
        search = config.search.copy(timeBudgetS = budget),
    )

    // 1. Blocks, one after another, each with all threads and its share of the budget (machines and ports).
    fun effort(b: Int) = split.blocks[b].size + 0.5 * split.links.count { it.from == b || it.to == b }
    val totalEffort = split.blocks.indices.sumOf(::effort)
    val variants = ArrayList<MutableList<TrialResult>>()
    for (bp in bps) {
        val b = bp.index
        val budget = minOf(left(), config.search.timeBudgetS * cfg.blockTimeShare * (1 - later) * effort(b) / totalEffort)
        val box = split.geometry.boxes[b]
        val aspect = box.w.toDouble() / box.h
        val dir = File(out, "block_${b + 1}")
        bp.plan.write(File(dir, "plan.json").path)
        say("block ${b + 1}: ${bp.globalIds.size} machines, ${bp.plan.ports.size} ports, aspect ${"%.2f".format(aspect)}, ${"%.0f".format(budget)} s")
        val best = Search(data, blockConfig(budget, aspect), tuning, bp.plan, dir).run().best
        if (best == null) { say("block ${b + 1}: no layout found; giving up"); return null }
        say("block ${b + 1}: ${best.w} x ${best.h}")
        variants += mutableListOf(best)
    }

    // 2. Floorplan the finished blocks and stitch them. Keep the smallest valid factory.
    var best: Factory? = null
    var bestState: FpState? = null
    fun area(f: Factory) = f.layout.w * f.layout.h
    fun consider(r: Pair<Factory, FpState>?, what: String): Boolean {
        val f = r?.first
        if (f == null || !f.success) { say("$what: no valid factory"); return false }
        val b = best
        if (b != null && area(f) >= area(b)) { say("$what: ${f.layout.w} x ${f.layout.h} is not smaller than ${b.layout.w} x ${b.layout.h}"); return false }
        best = f; bestState = r.second
        writeFactory(f, out, config)
        say("$what: ${f.layout.w} x ${f.layout.h} (${area(f)} tiles), saved")
        return true
    }
    fun assemble(sp: Split, bpl: List<BlockPlan>, vs: List<List<TrialResult>>, start: FpState?, what: String) =
        runCatching {
            // Squeezing the corridors gets the time left for stitching, at least half a minute.
            val squeeze = maxOf(30.0, minOf(left(), config.search.timeBudgetS * (1 - cfg.blockTimeShare)))
            assembleFactory(data, config, tuning, plan, sp, bpl, vs, start, ::say, what, System.currentTimeMillis() + (squeeze * 1000).toLong())
        }
            .getOrElse { e -> say("$what: failed with $e"); e.printStackTrace(); null }
    val first = assemble(split, bps, variants, split.geometry.state, "factory")
    if (first == null || !first.first.success) {
        first?.let { writeFactory(it.first, out, config) }
        say("done: no valid factory")
        return first?.first
    }
    consider(first, "factory")

    // 3. Reshape: a floorplan of the blocks' real areas with free shapes says which shape each block should have;
    //    blocks far from theirs are searched again at it, and the floorplan then picks among every shape a block has.
    if (cfg.repack && left() > 60) {
        val wanted = reshapeTargets(plan, split, variants, cfg, config)
        val todo = split.blocks.indices.filter { b -> val r = variants[b][0]; abs(ln(wanted[b] / (r.w.toDouble() / r.h))) > ln(1.3) }
        say("reshape: blocks ${todo.map { it + 1 }} to aspects ${todo.map { "%.2f".format(wanted[it]) }}")
        val budget = config.search.timeBudgetS * cfg.repackShare
        val effortTodo = todo.sumOf(::effort).coerceAtLeast(1.0)
        var added = false
        for (b in todo) {
            if (left() < 30) break
            val area0 = variants[b][0].area
            val r = runCatching {
                Search(data, blockConfig(minOf(left(), budget * effort(b) / effortTodo), wanted[b], ceil(sqrt(area0 * 1.15)).toInt()),
                    tuning, bps[b].plan, File(out, "reshape/block_${b + 1}")).run().best
            }.getOrElse { e -> say("reshape block ${b + 1}: failed with $e"); null }
            if (r != null) { variants[b] += r; added = true; say("reshape block ${b + 1}: ${r.w} x ${r.h} (was ${variants[b][0].w} x ${variants[b][0].h})") }
            else say("reshape block ${b + 1}: nothing at aspect ${"%.2f".format(wanted[b])}")
        }
        if (added) consider(assemble(split, bps, variants, bestState, "reshaped factory"), "reshaped factory")
    }

    // 4. Merge neighbours: two blocks next to each other become one, searched from their placements side by side.
    if (cfg.merge) {
        val maxM = cfg.mergeMaxMachines ?: (2 * cfg.maxMachines)
        val tried = HashSet<Set<Int>>()
        while (left() > 120) {
            val f = best ?: break
            val st = bestState ?: break
            val boxes = f.blockRects
            val pairs = neighbourPairs(boxes, cfg.corridor * 3)
                .filter { (a, b) -> split.blocks[a].size + split.blocks[b].size <= maxM && (split.blocks[a] + split.blocks[b]).toSet() !in tried }
            if (pairs.isEmpty()) { say("merge: no more pairs to try"); break }
            val (l, r) = pairs.minBy { (a, b) -> split.blocks[a].size + split.blocks[b].size }
            tried += (split.blocks[l] + split.blocks[r]).toSet()
            val merged = mergeBlocks(split, l, r)
            val m = min(l, r)
            val bp = blockPlan(plan, merged, m, tuning)
            // The two placements as they lie in the factory, in the box around both.
            val ul = boxes[l]; val ur = boxes[r]
            val ux = min(ul.x, ur.x); val uy = min(ul.y, ur.y)
            val uw = max(ul.x2, ur.x2) - ux + 1; val uh = max(ul.y2, ur.y2) - uy + 1
            val local = bp.globalIds.withIndex().associate { (i, g) -> g to i }
            val warm = ArrayList<PlacedMachine>()
            for (b in listOf(l, r)) {
                val res = variants[b].firstOrNull { it.w == boxes[b].w && it.h == boxes[b].h } ?: variants[b][0]
                res.placement!!.machines.forEach { pm -> warm += pm.copy(id = local.getValue(bps[b].globalIds[pm.id]), x = pm.x + boxes[b].x - ux, y = pm.y + boxes[b].y - uy) }
            }
            val budget = (left() / pairs.size).coerceIn(600.0, 3600.0).coerceAtMost(left() - 60)
            say("merge blocks ${l + 1} and ${r + 1} (${bp.globalIds.size} machines) from $uw x $uh, ${"%.0f".format(budget)} s")
            val dir = File(out, "merge/${tried.size}_blocks_${l + 1}_${r + 1}")
            bp.plan.write(File(dir, "plan.json").path)
            val res = runCatching {
                Search(data, blockConfig(budget, uw.toDouble() / uh, ceil(sqrt(uw.toDouble() * uh)).toInt()), tuning, bp.plan, dir,
                    startFrom = Placement(uw, uh, warm.sortedBy { it.id })).run().best
            }.getOrElse { e -> say("merge blocks ${l + 1} and ${r + 1}: failed with $e"); null }
            if (res == null) { say("merge blocks ${l + 1} and ${r + 1}: no layout"); continue }
            say("merge blocks ${l + 1} and ${r + 1}: ${res.w} x ${res.h} instead of $uw x $uh")
            val gone = max(l, r)
            val newVariants = variants.filterIndexed { i, _ -> i != gone }.map { it.toMutableList() }.toMutableList()
            newVariants[m] = mutableListOf(res)
            val newBps = merged.blocks.indices.map { i -> if (i == m) bp else blockPlan(plan, merged, i, tuning) }
            val start = st.without(gone)
            if (consider(assemble(merged, newBps, newVariants, start, "merged ${l + 1}+${r + 1}"), "merged factory")) {
                split = merged; bps = newBps; variants.clear(); variants += newVariants
            }
        }
    }
    say("done: ${best?.let { "${it.layout.w} x ${it.layout.h} (${area(it)} tiles)" } ?: "no valid factory"}")
    return best
}

/** The split, its links and the preliminary floorplan, in a few lines of the log. */
private fun sayPlan(split: Split, plan: ProductionPlan, say: (String) -> Unit) {
    val p = split.partition
    say("${plan.machines.size} machines in ${split.blocks.size} blocks: " + split.blocks.mapIndexed { b, ms -> "block ${b + 1}: ${ms.size}" }.joinToString(", ") +
        (p?.let { "; ${it.ports} ports between blocks and edges (greedy split: ${it.greedyPorts}; ${split.ports} with machine rates rebalanced), cost ${"%.1f".format(it.cost)} (greedy ${"%.1f".format(it.greedyCost)})" } ?: ""))
    split.blocks.forEachIndexed { b, ms ->
        val box = split.geometry.boxes[b]
        say("  block ${b + 1}: " + ms.groupingBy { plan.machines[it].recipe }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value}x ${it.key}" } +
            "; planned at (${box.x},${box.y}) ${box.w} x ${box.h}")
    }
    split.links.filter { it.inner }.groupBy { it.from to it.to }.forEach { (ab, ls) ->
        say("  block ${ab.first + 1} -> block ${ab.second + 1}: " + ls.groupBy { it.item }.map { (i, l) -> "$i ${"%.2f".format(l.sumOf { it.rate })}/s (${l.size} belt(s))" }.joinToString(", "))
    }
    split.links.filter { it.hub }.groupBy { it.item }.forEach { (item, ls) ->
        say("  $item pipes: " + ls.joinToString(", ") { l ->
            when { l.from >= 0 -> "block ${l.from + 1} gives ${"%.1f".format(l.rate)}/s"; l.to >= 0 -> "block ${l.to + 1} takes ${"%.1f".format(l.rate)}/s"
                l.from == EDGE -> "edge gives ${"%.1f".format(l.rate)}/s"; else -> "edge takes ${"%.1f".format(l.rate)}/s" }
        })
    }
    val edge = split.links.filter { (it.from == EDGE || it.to == EDGE) && !it.hub }
    say("  ${edge.count { !it.routed }} inputs and outputs leave their block straight out of the factory, ${edge.count { it.routed }} run through the corridors to its edge")
}

/** [this] state with block [gone] removed (indices above it, reserved areas included, move down). */
private fun FpState.without(gone: Int): FpState {
    fun fix(a: IntArray) = a.filter { it != gone }.map { if (it > gone) it - 1 else it }.toIntArray()
    return FpState(fix(plus), fix(minus), shape.filterIndexed { i, _ -> i != gone }.map { 0 }.toIntArray())
}

/** Pairs of blocks whose boxes face each other across at most [gap] tiles along a good share of a side. */
private fun neighbourPairs(boxes: List<Rect>, gap: Int): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (a in boxes.indices) for (b in a + 1 until boxes.size) {
        val p = boxes[a]; val q = boxes[b]
        val ox = min(p.x2, q.x2) - max(p.x, q.x) + 1
        val oy = min(p.y2, q.y2) - max(p.y, q.y) + 1
        val gx = max(q.x - p.x2 - 1, p.x - q.x2 - 1)
        val gy = max(q.y - p.y2 - 1, p.y - q.y2 - 1)
        if (gx in 0..gap && oy >= min(p.h, q.h) / 3) out += a to b
        else if (gy in 0..gap && ox >= min(p.w, q.w) / 3) out += a to b
    }
    return out
}

/**
 * [split] with blocks [a] and [b] made one block in the lower index's place: links between them disappear (their
 * items now flow inside the block), the merged block's other links keep their sides, and its links into one fluid's
 * pipe tree become one (a block has one port per fluid).
 */
fun mergeBlocks(split: Split, a: Int, b: Int): Split {
    val keep = min(a, b); val gone = max(a, b)
    fun idx(i: Int) = when { i < 0 -> i; i == gone -> keep; i > gone -> i - 1; else -> i }
    val blocks = split.blocks.indices.filter { it != gone }.map { i -> if (i == keep) (split.blocks[a] + split.blocks[b]).sorted() else split.blocks[i] }
    val remapped = split.links.map { l -> l.copy(from = idx(l.from), to = idx(l.to)) }.filter { !(it.inner && it.from == it.to) }
    // One port per fluid on the merged block: its tree links and straight-out links of a fluid, netted.
    val (fluidMine, rest) = remapped.partition { it.fluid && (it.from == keep || it.to == keep) }
    val links = ArrayList(rest)
    fluidMine.groupBy { it.item to it.throughCorridor }.forEach { (key, ls) ->
        val net = ls.sumOf { if (it.from == keep) it.rate else -it.rate }
        if (abs(net) < 1e-9) return@forEach
        val l0 = ls.first()
        val other = if (l0.from == keep) l0.to else l0.from
        val side = l0.side(keep)
        links += if (net > 0) l0.copy(rate = net, from = keep, to = other, fromSide = side, toSide = null)
            else l0.copy(rate = -net, from = other, to = keep, toSide = side, fromSide = null)
    }
    val boxes = split.geometry.boxes
    val union = Rect(min(boxes[a].x, boxes[b].x), min(boxes[a].y, boxes[b].y),
        max(boxes[a].x2, boxes[b].x2) - min(boxes[a].x, boxes[b].x) + 1, max(boxes[a].y2, boxes[b].y2) - min(boxes[a].y, boxes[b].y) + 1)
    val g = BlockGeometry(split.geometry.boxes.indices.filter { it != gone }.map { if (it == keep) union else boxes[it] }, split.geometry.w, split.geometry.h,
        split.geometry.touches.indices.filter { it != gone }.map { if (it == keep) split.geometry.touches[a] + split.geometry.touches[b] else split.geometry.touches[it] })
    return Split(blocks, links.mapIndexed { i, l -> l.copy(id = i) }, split.warnings, g, split.plan, split.partition, split.ports)
}

/** For each block, the aspect a floorplan of the real block areas with free shapes would give it. */
private fun reshapeTargets(plan: ProductionPlan, split: Split, variants: List<List<TrialResult>>, cfg: config.BlocksConfig, config: RunConfiguration): List<Double> {
    val (blocks, nets) = floorplanInput(plan, split, variants, cfg, config, soft = true)
    val fp = Floorplanner(blocks, nets, FpParams(aspect = config.box.aspect, aspectWeight = cfg.aspectWeight, wireWeight = cfg.wireWeight, seed = config.search.seed + 17)).optimise()
    return split.blocks.indices.map { b -> fp.boxes[b].w.toDouble() / fp.boxes[b].h }
}

/** Free tiles to start with on each side of block [b] for the corridors: half a corridor and room for its ports. */
private fun initialPads(split: Split, b: Int, corridor: Int, testMode: Boolean): IntArray {
    val touch = split.touch[b]
    val pads = IntArray(4)
    for (d in Dir.entries) {
        val ports = split.links.count { (it.from == b || it.to == b) && it.throughCorridor && it.side(b) == d.name }
        val edgePorts = split.links.count { (it.from == b || it.to == b) && it.routed && it.side(b) == d.name }
        pads[d.ordinal] = if (d in touch) 0 else (corridor + 1) / 2 + (ports + 1) / 2 + (if (testMode) 2 * edgePorts else 0)
    }
    return pads
}

/**
 * Floorplan blocks and nets for [split]: one shape per finished layout of a block (pins at its ports), or with
 * [soft] free shapes of the first layout's area; a net per link, and one per fluid pipe tree.
 */
private fun floorplanInput(
    plan: ProductionPlan, split: Split, variants: List<List<TrialResult>>, cfg: config.BlocksConfig, config: RunConfiguration,
    soft: Boolean = false, bps: List<BlockPlan>? = null,
): Pair<List<FpBlock>, List<FpNet>> {
    val k = split.blocks.size
    val blocks = ArrayList<FpBlock>()
    for (b in 0 until k) {
        val shapes = if (soft) softShapes(variants[b][0].area.toDouble()) else variants[b].map { r ->
            val pins = HashMap<Int, Tile>()
            val lp = bps!![b].linkOfPort
            r.sources.forEach { s -> lp[s.port]?.let { pins[it] = s.tile } }
            r.outputs.forEach { o -> lp[o.port]?.let { pins[it] = o.tile } }
            FpShape(r.w, r.h, pins)
        }
        blocks += FpBlock(shapes, initialPads(split, b, cfg.corridor, config.testMode), split.touch[b])
    }
    config.reserved.forEachIndexed { i, r -> blocks += reservedBlock(r, i, cfg.corridor) }
    val nets = ArrayList<FpNet>()
    fun edges(item: String, output: Boolean): Set<Dir> {
        val specs = plan.ports.firstOrNull { it.item == item && it.output == output }?.specs ?: return emptySet()
        return specs.flatMap { s -> if (s.edge == "any") Dir.entries.toList() else listOfNotNull(s.edge?.let(Dir::fromName)) }.toSet()
    }
    split.links.filter { !it.hub }.forEach { l ->
        nets += when {
            l.inner -> FpNet(listOf(l.from to l.id, l.to to l.id))
            l.from == EDGE -> FpNet(listOf(l.to to l.id), edges(l.item, false))
            else -> FpNet(listOf(l.from to l.id), edges(l.item, true))
        }
    }
    split.links.filter { it.hub }.groupBy { it.item }.forEach { (item, ls) ->
        val pins = ls.mapNotNull { l -> if (l.from >= 0) l.from to l.id else if (l.to >= 0) l.to to l.id else null }
        val es = ls.flatMap { l -> when { l.from == EDGE -> edges(item, false); l.to == EDGE -> edges(item, true); else -> emptySet() } }.toSet()
        nets += FpNet(pins, es, 2.0)
    }
    return blocks to nets
}

/**
 * Floorplans the finished blocks ([variants]: every layout each block has; the floorplan picks one) and stitches
 * them: the corridors start at half a corridor plus room for each side's ports; where routing fails the sides
 * near the trouble get more room, and once it routes, padding lines that stayed empty are given back and it
 * routes again. The smallest routed factory is finished (poles, validation); the floorplan state comes back too.
 */
fun assembleFactory(
    data: RootData, config: RunConfiguration, tuning: Tuning, plan: ProductionPlan, split: Split, bps: List<BlockPlan>,
    variants: List<List<TrialResult>>, start: FpState?, say: (String) -> Unit, what: String,
    /** When squeezing the corridors stops (epoch ms). */
    deadline: Long = System.currentTimeMillis() + 30_000,
): Pair<Factory, FpState>? {
    val cfg = config.blocks!!
    val k = split.blocks.size
    val (fpBlocks, fpNets) = floorplanInput(plan, split, variants, cfg, config, bps = bps)
    val params = FpParams(aspect = config.box.aspect, aspectWeight = cfg.aspectWeight, wireWeight = cfg.wireWeight, seed = config.search.seed)
    val start0 = start?.takeIf { it.plus.size == fpBlocks.size }?.let { s -> FpState(s.plus.clone(), s.minus.clone(), IntArray(fpBlocks.size) { 0 }) }
    val planner = Floorplanner(fpBlocks, fpNets, params)
    var fp = planner.optimise(start0)
    if (fp.violations > 0) say("$what: the floorplan leaves ${fp.violations} block side(s) off the factory edge they need")
    var pads = fpBlocks.map { it.pad.clone() }
    fun layoutOf(p: FpPlan) = Arrangement(p.boxes.take(k), p.boxes.drop(k), p.w, p.h, IntArray(k) { p.state.shape[it] })
    val ok = ArrayList<Stitched>()
    var routedOnce = false
    for (attempt in 0 until 6) {
        val arr = layoutOf(fp)
        val r = stitchRoute(config, tuning, plan, split, bps, variants, arr)
        if (r.stitched != null) { ok += r.stitched; routedOnce = true; say("$what: routed at ${fp.w} x ${fp.h}${if (attempt > 0) " (attempt ${attempt + 1})" else ""}"); break }
        say("$what: corridors at ${fp.w} x ${fp.h} did not route (${r.why}); widening near ${r.trouble.size} trouble tiles")
        pads = grow(pads, arr, r.trouble, r.edges, k)
        fp = planner.pack(fp.state, pads)
    }
    if (!routedOnce) return null
    // Tighten: give back padding lines nothing uses; re-floorplan with the measured padding; keep what routes.
    for (round in 0 until 3) {
        val last = ok.last()
        val tighter = tighten(pads, last, k)
        if (tighter.indices.all { tighter[it].contentEquals(pads[it]) }) break
        val p2 = planner.pack(fp.state, tighter)
        if (p2.area >= fp.area) break
        val r = stitchRoute(config, tuning, plan, split, bps, variants, layoutOf(p2))
        if (r.stitched == null) { say("$what: tighter corridors (${p2.w} x ${p2.h}) did not route"); break }
        say("$what: tighter corridors: ${p2.w} x ${p2.h}")
        ok += r.stitched; pads = tighter; fp = p2
    }
    run {
        val re = Floorplanner(fpBlocks.mapIndexed { i, b -> FpBlock(b.shapes, pads[i], b.touch) }, fpNets, params).optimise(fp.state)
        if (re.area < fp.area && re.violations == 0) {
            val r = stitchRoute(config, tuning, plan, split, bps, variants, layoutOf(re))
            if (r.stitched != null) { say("$what: re-floorplanned with the measured corridors: ${re.w} x ${re.h}"); ok += r.stitched; fp = re }
        }
    }
    // Squeeze: one block side at a time, one tile less, kept when the factory gets smaller and still routes. A side
    // without ports may go to nothing; validation below catches anything that then touches across.
    val portSides = (0 until k).map { b -> split.links.filter { it.from == b || it.to == b }.mapNotNull { it.side(b) }.map { Dir.fromName(it).ordinal }.toSet() }
    var squeezed = 0
    var improved = true
    while (improved && System.currentTimeMillis() < deadline) {
        improved = false
        val order = (0 until k).flatMap { b -> (0..3).map { b to it } }
            .filter { (b, d) -> pads[b][d] > (if (d in portSides[b]) 1 else 0) }.sortedByDescending { (b, d) -> pads[b][d] }
        for ((b, d) in order) {
            if (System.currentTimeMillis() >= deadline) break
            val trial = pads.map { it.clone() }.also { it[b][d]-- }
            val p2 = planner.pack(fp.state, trial)
            if (p2.area >= fp.area) continue
            val r = stitchRoute(config, tuning, plan, split, bps, variants, layoutOf(p2))
            if (r.stitched != null) { ok += r.stitched; pads = trial; fp = p2; improved = true; squeezed++ }
        }
    }
    if (squeezed > 0) say("$what: squeezed $squeezed corridor lines out: ${fp.w} x ${fp.h}")
    // Finish the smallest; if validation finds anything, the next smallest.
    var last: Factory? = null
    for (s in ok.sortedBy { it.w * it.h }.take(8)) {
        val f = finishFactory(data, config, plan, s)
        if (f.success) return f to fp.state
        say("$what: ${f.layout.w} x ${f.layout.h} has ${f.errors.size} validation errors: ${f.errors.take(5)}")
        last = f
    }
    return last!! to fp.state
}

/** Where everything goes: block boxes, reserved boxes, the factory size and which layout of each block. */
class Arrangement(val boxes: List<Rect>, val reserved: List<Rect>, val w: Int, val h: Int, val shape: IntArray)

/** Everything placed and routed, before poles and validation. */
class Stitched(
    val entities: List<PlacedEntity>, val machines: List<PlacedMachine>, val sources: List<SourceSpec>, val outputs: List<OutputSpec>,
    val w: Int, val h: Int, val blockRects: List<Rect>, val reservedRects: List<Rect>,
)

/** A stitch that routed, or why not: tiles near the trouble, and factory edges a corridor link could not reach. */
class StitchAttempt(val stitched: Stitched?, val trouble: List<Tile>, val why: String, val edges: Set<Dir> = emptySet())

/**
 * More padding on the block sides near [trouble] (or everywhere, by one, if nothing is near), and between the
 * blocks along factory edges in [edges] (a link to that edge found no free edge tile).
 */
private fun grow(pads: List<IntArray>, arr: Arrangement, trouble: List<Tile>, edges: Set<Dir>, k: Int): List<IntArray> {
    val out = pads.map { it.clone() }
    var any = false
    for (b in 0 until k) for (e in edges) {
        val box = arr.boxes[b]
        val dist = when (e) { Dir.N -> box.y; Dir.S -> arr.h - 1 - box.y2; Dir.W -> box.x; Dir.E -> arr.w - 1 - box.x2 }
        if (dist > pads[b][e.ordinal] + 1) continue
        listOf(e.cw, e.ccw).forEach { d -> if (pads[b][d.ordinal] > 0) { out[b][d.ordinal] += 2; any = true } }
    }
    for (b in 0 until k) {
        val box = arr.boxes[b]
        for (d in Dir.entries) {
            val p = pads[b][d.ordinal]
            if (p == 0) continue
            val near = trouble.any { t ->
                val along = if (d.axis == 0) t.y in box.y - 2..box.y2 + 2 else t.x in box.x - 2..box.x2 + 2
                val dist = when (d) { Dir.N -> box.y - t.y; Dir.S -> t.y - box.y2; Dir.W -> box.x - t.x; Dir.E -> t.x - box.x2 }
                along && dist in 0..p + 3
            }
            if (near) { out[b][d.ordinal] = max(out[b][d.ordinal], p + 2); any = true }
        }
    }
    if (!any) for (b in 0 until k) for (d in 0..3) if (out[b][d] > 0) out[b][d]++
    return out
}

/** Padding with the lines nothing uses taken out: per block side, empty lines counted from the outside in. */
private fun tighten(pads: List<IntArray>, s: Stitched, k: Int): List<IntArray> {
    val used = HashSet<Tile>()
    s.entities.forEach { e -> e.rect.tiles().forEach { used += it } }
    val out = pads.map { it.clone() }
    for (b in 0 until k) {
        val box = s.blockRects[b]
        for (d in Dir.entries) {
            val p = pads[b][d.ordinal]
            if (p <= 1) continue
            var empty = 0
            for (dist in p downTo 2) {   // line [dist] tiles out from the box; the ring (1) stays
                val line = when (d) {
                    Dir.N -> (box.x..box.x2).map { Tile(it, box.y - dist) }
                    Dir.S -> (box.x..box.x2).map { Tile(it, box.y2 + dist) }
                    Dir.W -> (box.y..box.y2).map { Tile(box.x - dist, it) }
                    Dir.E -> (box.y..box.y2).map { Tile(box.x2 + dist, it) }
                }
                if (line.any { it in used }) break
                empty++
            }
            out[b][d.ordinal] = max(1, p - empty)
        }
    }
    return out
}

/**
 * Places the finished blocks as [arr] says and routes the belts and pipes between them: a belt per link between
 * two blocks or between a block and the factory edge, and one pipe tree per fluid traded between blocks.
 */
fun stitchRoute(
    config: RunConfiguration, tuning: Tuning, plan: ProductionPlan, split: Split,
    bps: List<BlockPlan>, variants: List<List<TrialResult>>, arr: Arrangement,
): StitchAttempt {
    val k = bps.size
    val results = (0 until k).map { variants[it][arr.shape[it]] }
    val W = arr.w; val H = arr.h
    val ox = IntArray(k) { arr.boxes[it].x }; val oy = IntArray(k) { arr.boxes[it].y }
    val entities = ArrayList<PlacedEntity>()
    val machines = ArrayList<PlacedMachine>()
    // Entities carry the full plan's net of their item (blocks number their nets on their own), so a corridor
    // belt handing over to a block's belt counts as the same net.
    val netOfItem = plan.nets.associate { it.item to it.id }
    fun global(e: PlacedEntity, local: ProductionPlan) = e.net?.let { n -> local.nets.getOrNull(n)?.item?.let { netOfItem[it] } }
    for (b in 0 until k) {
        results[b].layout!!.entities.filter { it.kind != Kind.POLE }.forEach { entities += it.copy(x = it.x + ox[b], y = it.y + oy[b], net = global(it, bps[b].plan)) }
        results[b].placement!!.machines.forEach { pm -> machines += pm.copy(id = bps[b].globalIds[pm.id], x = pm.x + ox[b], y = pm.y + oy[b]) }
    }
    val byTile = HashMap<Tile, PlacedEntity>().also { m -> entities.forEach { e -> e.rect.tiles().forEach { m[it] = e } } }

    // Link ends: the tile just outside a block's port (and the way a belt points there). Ports that leave the
    // factory straight out must lie on its ring, on the side the job allows.
    val starts = HashMap<Int, Pair<Tile, Dir>>()
    val ends = HashMap<Int, Pair<Tile, Dir>>()
    val sources = ArrayList<SourceSpec>()
    val outputs = ArrayList<OutputSpec>()
    // (On the ring, or on the box's own edge row: fluid ports start with a pipe-to-ground there.)
    fun outsideOnSide(t: Tile, side: String?) = when (side) { "N" -> t.y <= 0; "S" -> t.y >= H - 1; "W" -> t.x <= 0; "E" -> t.x >= W - 1; else -> false }
    for (b in 0 until k) {
        results[b].outputs.forEach { o ->
            val l = split.links[bps[b].linkOfPort[o.port] ?: return@forEach]
            val t = Tile(o.tile.x + ox[b], o.tile.y + oy[b])
            if (!l.throughCorridor) {
                if (!outsideOnSide(t, l.fromSide)) return StitchAttempt(null, listOf(t), "block ${b + 1}'s ${l.item} output is not on the factory's edge")
                outputs += o.copy(tile = t, port = -1); return@forEach
            }
            // A pipe port joins on the side the block was searched with it (a corner tile alone does not say which).
            val d = if (o.fluid) Dir.fromName(l.fromSide!!) else byTile[t]?.dir ?: return StitchAttempt(null, listOf(t), "no belt at a port")
            starts[l.id] = (t + d) to d
        }
        results[b].sources.forEach { s ->
            val l = split.links[bps[b].linkOfPort[s.port] ?: return@forEach]
            val t = Tile(s.tile.x + ox[b], s.tile.y + oy[b])
            if (!l.throughCorridor) {
                if (!outsideOnSide(t, l.toSide)) return StitchAttempt(null, listOf(t), "block ${b + 1}'s ${l.item} input is not on the factory's edge")
                sources += s.copy(tile = t, port = -1); return@forEach
            }
            val out = l.toSide?.let(Dir::fromName) ?: sideOf(s.tile, results[b].w, results[b].h)
            ends[l.id] = (t + out) to out.opposite
        }
    }
    val corridorLinks = split.links.filter { it.throughCorridor }
    if (corridorLinks.any { (it.from >= 0 && it.id !in starts) || (it.to >= 0 && it.id !in ends) }) return StitchAttempt(null, emptyList(), "a block port is missing")

    // A pipe link whose first tile outside its port touches another fluid's pipe (ports of two fluids side by side)
    // or a machine starts with a pipe-to-ground pair out of the port instead: it joins only through its open sides.
    val blockTiles = HashSet<Tile>()
    for (b in 0 until k) for (y in oy[b] until oy[b] + results[b].h) for (x in ox[b] until ox[b] + results[b].w) blockTiles += Tile(x, y)
    arr.reserved.forEach { r -> blockTiles += r.tiles() }
    val noTunnel = HashSet(blockTiles)
    fun foreign(t: Tile, fluid: String, port: Tile) = Dir.entries.any { d ->
        val u = t + d
        u != port && byTile[u]?.let { e -> (e.kind == Kind.PIPE && e.fluid != fluid) || (e.kind == Kind.PIPE_UG && e.fluid != fluid && e.dir == d.opposite) || e.kind == Kind.MACHINE } == true
    }
    fun free(t: Tile) = t.x in 0 until W && t.y in 0 until H && t !in blockTiles && t !in byTile
    /** The tile after a pipe-to-ground pair from [t] going [out] (port behind it), or null if no pair fits. */
    fun dive(l: Link, t: Tile, out: Dir): Tile? {
        val port = t + out.opposite
        if (!foreign(t, l.item, port)) return t
        for (j in 1..GameConstants.PIPE_TO_GROUND_GAP + 1) {
            val exit = t.step(out, j); val next = exit + out
            if ((0..j).any { !free(t.step(out, it)) || t.step(out, it) in noTunnel } || !free(next) || foreign(next, l.item, exit)) continue
            listOf(t to out.opposite, exit to out).forEach { (p, open) ->
                val e = PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, p.x, p.y, open, fluid = l.item, net = netOfItem[l.item])
                entities += e; byTile[p] = e
            }
            (0..j).forEach { noTunnel += t.step(out, it) }
            return next
        }
        return null
    }
    for (l in corridorLinks.filter { it.fluid }) {
        starts[l.id]?.let { (t, d) -> starts[l.id] = (dive(l, t, d) ?: return StitchAttempt(null, listOf(t), "${l.item} cannot leave its port")) to d }
        ends[l.id]?.let { (t, d) -> ends[l.id] = (dive(l, t, d.opposite) ?: return StitchAttempt(null, listOf(t), "${l.item} cannot reach its port")) to d }
    }

    // The belts and pipes through the corridors.
    val ports = ArrayList<Port>(); val terminals = ArrayList<Terminal>(); val nets = ArrayList<Net>()
    val edgePorts = HashSet<Int>()
    val linksOfNet = HashMap<Int, List<Link>>()
    fun edgeSpecs(item: String, output: Boolean): List<ConfigSource> {
        val specs = plan.ports.first { it.item == item && it.output == output }.specs
        if (!config.testMode) return specs
        // Test mode: every fifth tile of the edge only, so each port's harness (a feeder with inserters and chests
        // on both sides) has room next to its neighbours.
        return place.portCandidates(specs, W, H).filter { (t, d) -> (if (d == Dir.N || d == Dir.S) t.x else t.y) % 5 == 2 }
            .map { (t, d) -> ConfigSource(item, x = t.x, y = t.y, dir = d?.name) }
    }
    fun fixedPort(l: Link, tile: Tile, dir: Dir, output: Boolean) =
        Port(ports.size, l.item, listOf(ConfigSource(l.item, x = tile.x, y = tile.y, dir = dir.name)), fluid = l.fluid, output = output, single = true).also { ports += it }
    fun edgePort(l: Link, output: Boolean) = Port(ports.size, l.item, edgeSpecs(l.item, output), fluid = l.fluid, output = output, single = true).also { ports += it; edgePorts += it.id }
    fun terminal(role: Role, l: Link, rate: Double, p: Port) = Terminal(terminals.size, role, l.item, rate, 1, port = p.id, fluid = l.fluid).also { terminals += it }
    corridorLinks.filter { !it.hub }.forEach { l ->
        val p1 = if (l.from < 0) edgePort(l, false) else starts.getValue(l.id).let { (s, sd) -> fixedPort(l, s, sd.opposite, false) }
        val p2 = if (l.to < 0) edgePort(l, true) else ends.getValue(l.id).let { (e, ed) -> fixedPort(l, e, ed, true) }
        val t1 = terminal(Role.SOURCE, l, l.rate, p1); val t2 = terminal(Role.OUTPUT, l, l.rate, p2)
        linksOfNet[nets.size] = listOf(l)
        nets += Net(nets.size, l.item, l.rate, listOf(t1.id, t2.id), 1, fluid = l.fluid)
    }
    // A fluid's tree: rooted at the edge's supply if there is one, else at a block that gives the fluid.
    corridorLinks.filter { it.hub }.groupBy { it.item }.forEach { (_, ls) ->
        val ordered = ls.sortedBy { l -> when { l.from == EDGE -> 0; l.from >= 0 -> 1; l.to >= 0 -> 2; else -> 3 } }
        val ts = ordered.map { l ->
            when {
                l.from == EDGE -> terminal(Role.SOURCE, l, l.rate, edgePort(l, false))
                l.to == EDGE -> terminal(Role.OUTPUT, l, l.rate, edgePort(l, true))
                l.from >= 0 -> starts.getValue(l.id).let { (s, sd) -> terminal(Role.SOURCE, l, l.rate, fixedPort(l, s, sd.opposite, false)) }
                else -> ends.getValue(l.id).let { (e, ed) -> terminal(Role.OUTPUT, l, l.rate, fixedPort(l, e, ed, true)) }
            }
        }
        linksOfNet[nets.size] = ordered
        nets += Net(nets.size, ls[0].item, ordered.filter { it.to != HUB }.sumOf { it.rate }, ts.map { it.id }, 1, fluid = true)
    }
    val stitchPlan = ProductionPlan(emptyList(), ports, terminals, nets, emptyMap(), emptyMap(), emptyMap(), plan.beltTier, emptyList())
    // Tiles a block belt points into from inside (chain ends at the block edge) must not get a corridor belt, nor
    // tiles a block's inserter reaches out to.
    val startTiles = starts.values.map { it.first }.toSet()
    val pointedAt = entities.filter { it.kind.beltFamily }.mapNotNull { it.outputTile }.filter { t -> t !in blockTiles && t !in startTiles }
    val reached = entities.filter { it.kind == Kind.INSERTER }.flatMap { listOf(it.pickupTile, it.dropTile) }.filter { it !in blockTiles }
    val stitchConfig = config.copy(blocks = null, laneSharing = false)
    // Everything the blocks put outside their boxes (ring ports) is in the way too.
    val entityTiles = entities.flatMap { it.rect.tiles() }
    // A corridor pipe beside a block's pipe or machine would join it: keep those tiles free, except where a link ends.
    val besidePipes = if (corridorLinks.none { it.fluid }) emptyList() else {
        val linkTiles = (starts.values + ends.values).map { it.first }.toSet()
        entities.filter { it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG || it.kind == Kind.MACHINE }.flatMap { it.rect.tiles() }
            .flatMap { t -> Dir.entries.map { t + it } }.filter { it !in blockTiles && it !in linkTiles }
    }
    val routed = route(RouteProblem(stitchPlan, stitchConfig, tuning.route, Placement(W, H, emptyList()),
        obstacles = blockTiles + entityTiles + pointedAt + reached + besidePipes, noTunnel = noTunnel))
    if (!routed.success) {
        // Trouble: conflict tiles, and the ports of every link that did not route.
        val trouble = routed.conflicts + routed.failedNets.flatMap { n ->
            (linksOfNet[n] ?: emptyList()).flatMap { l -> listOfNotNull(starts[l.id]?.first, ends[l.id]?.first) }
        }
        val edges = routed.failedNets.flatMap { n -> (linksOfNet[n] ?: emptyList()).filter { it.from == EDGE || it.to == EDGE } }
            .flatMap { l -> plan.ports.first { it.item == l.item && it.output == (l.to == EDGE) }.specs }
            .flatMap { s -> if (s.edge == "any") Dir.entries.toList() else listOfNotNull(s.edge?.let(Dir::fromName)) }.toSet()
        return StitchAttempt(null, trouble, "${routed.conflicts.size} conflicts, ${routed.failedNets.size} unrouted", edges)
    }
    entities += routed.entities.map { it.copy(net = global(it, stitchPlan)) }
    sources += routed.sources.filter { it.port in edgePorts }.map { it.copy(port = -1) }
    outputs += routed.outputs.filter { it.port in edgePorts }.map { it.copy(port = -1) }
    val blockRects = (0 until k).map { b -> Rect(ox[b], oy[b], results[b].w, results[b].h) }
    return StitchAttempt(Stitched(entities, machines, sources, outputs, W, H, blockRects, arr.reserved), emptyList(), "ok")
}

/** Test harness on the factory's ports, compaction, poles over everything, block borders, and validation. */
fun finishFactory(data: RootData, config: RunConfiguration, plan: ProductionPlan, s: Stitched): Factory {
    fun finish(
        ents: List<PlacedEntity>, mach: List<PlacedMachine>, srcs: List<SourceSpec>, outs: List<OutputSpec>,
        w: Int, h: Int, rects: List<Rect>, reserved: List<Rect>, notes: MutableList<String>,
    ): Factory {
        val harness = if (config.testMode) buildHarness(srcs, ents, w, h, config, outs) else null
        val all = ArrayList(ents).also { it += harness?.entities ?: emptyList() }
        harness?.warnings?.let { notes += it }
        var poles = emptyList<PlacedEntity>()
        var wires = emptyList<Pair<Int, Int>>()
        val keepFree = reserved.flatMap { it.tiles() }.toSet()
        if (config.power.mode != "none") {
            val extra = (harness?.tiles ?: emptySet()).flatMap { t -> (-2..2).flatMap { dy -> (-2..2).map { dx -> Tile(t.x + dx, t.y + dy) } } }
            val ps = placePoles(all, poleCandidates(w, h, config.power.allowOutsideRing, extra).filter { it !in keepFree }, config.power.pole)
            if (!ps.ok) notes += "power: ${ps.uncovered.size} entities not covered"
            poles = ps.poles; wires = ps.wires
        }
        val offset = all.size
        // Block borders as floor tiles, so each block shows in the game.
        val marks = if (config.markerTile == "none") emptyList() else (rects + reserved).flatMap { geom.borderTiles(it, config.markerTile) }
        val layout = Layout(w, h, all + poles, wires.map { (a, b) -> a + offset to b + offset }, "factorio-optimizer factory ${w}x$h", marks)
        val outside = (harness?.tiles ?: emptySet()) + poles.flatMap { it.rect.tiles() }.filter { it.x !in 0 until w || it.y !in 0 until h } +
            srcs.map { it.tile }   // ring ports (in test mode the harness feeds them instead of listing them)
        val spec = validationSpec(data, plan, config, Placement(w, h, mach), if (config.testMode) emptyList() else srcs,
            requirePower = config.power.mode != "none", outsideAllowed = outside.toSet(), outputs = outs)
        val errors = validate(layout, spec)
        return Factory(layout, errors, score(layout, config), srcs, outs, notes, rects)
    }

    // Rows and columns that only straight belts and pipes cross go first; if that breaks anything (it should
    // not), the factory as stitched.
    if (config.blocks?.compact != false) {
        val c = runCatching { compactLayout(s.entities, emptyList(), s.w, s.h) }.getOrNull()
        if (c != null && c.rowsRemoved + c.colsRemoved > 0) {
            fun rect(r: Rect) = Rect(c.x(r.x), c.y(r.y), c.x(r.x2 + 1) - c.x(r.x), c.y(r.y2 + 1) - c.y(r.y))
            val notes = mutableListOf("compacted: ${c.rowsRemoved} rows and ${c.colsRemoved} columns removed from ${s.w} x ${s.h}")
            val f = finish(c.entities, s.machines.map { it.copy(x = c.x(it.x), y = c.y(it.y)) }, s.sources.map { it.copy(tile = c.tile(it.tile)) },
                s.outputs.map { it.copy(tile = c.tile(it.tile)) }, c.w, c.h, s.blockRects.map(::rect), s.reservedRects.map(::rect), notes)
            if (f.success) return f
            println("compaction broke the factory (${f.errors.take(3)}); keeping it as stitched")
        }
    }
    return finish(s.entities, s.machines, s.sources, s.outputs, s.w, s.h, s.blockRects, s.reservedRects, mutableListOf())
}

/** The box side a port tile of a [w] x [h] block is on (on the ring around it, or on its edge). */
private fun sideOf(t: Tile, w: Int, h: Int): Dir = when {
    t.x < 0 -> Dir.W; t.x >= w -> Dir.E; t.y < 0 -> Dir.N; t.y >= h -> Dir.S
    t.x == 0 -> Dir.W; t.x == w - 1 -> Dir.E; t.y == 0 -> Dir.N; else -> Dir.S
}

private fun writeFactory(f: Factory, out: File, config: RunConfiguration) {
    val l = f.layout
    File(out, "best_output_string.txt").writeText(l.toBlueprintString(config.quality))
    File(out, "best_blueprint.json").writeText(l.toBlueprint(config.quality).toJson())
    renderLayout(l, File(out, "best.png"), RenderOptions(errors = f.errors, ports = viz.portLabels(f.sources, f.outputs), title = "factory ${l.w}x${l.h} score ${"%.1f".format(f.score)}"))
    File(out, "ports.json").writeText(strictJson.encodeToString(JsonObject.serializer(), factoryManifest(f)))
}

/** Where the factory's inputs and outputs are (box-relative tiles, edge, item, rate, lane for shared belts). */
fun factoryManifest(f: Factory): JsonObject {
    val w = f.layout.w; val h = f.layout.h
    fun edge(x: Int, y: Int) = when { y <= 0 -> "north"; y >= h - 1 -> "south"; x <= 0 -> "west"; x >= w - 1 -> "east"; else -> "inside" }
    return buildJsonObject {
        putJsonObject("box") { put("w", w); put("h", h) }
        putJsonArray("inputs") {
            f.sources.forEach { s ->
                addJsonObject {
                    put("item", s.item); put("rate", s.rate); put("fluid", s.fluid); put("x", s.tile.x); put("y", s.tile.y); put("edge", edge(s.tile.x, s.tile.y))
                    if (s.lane >= 0) put("lane", if (s.lane == 0) "left" else "right")
                }
            }
        }
        putJsonArray("outputs") {
            f.outputs.forEach { o -> addJsonObject { put("item", o.item); put("rate", o.rate); put("fluid", o.fluid); put("x", o.tile.x); put("y", o.tile.y); put("edge", edge(o.tile.x, o.tile.y)) } }
        }
    }
}
