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
    /** Where each block's box ended up, its layout as placed there (turned) and its turn. */
    val blockRects: List<Rect> = emptyList(), val blockLayouts: List<TrialResult> = emptyList(), val blockRot: IntArray = IntArray(0),
    /** Aquilo: the heat pipe tiles where heat must be brought in. */
    val heatPorts: List<Tile> = emptyList(),
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
 * factory.txt logs it all), each block to `block_<n>`. With [resume], the split and block layouts an earlier run
 * saved in [out] are used (see Resume.kt): only blocks without a layout are searched.
 */
/** A live view of a factory run (the terminal UI): while [live], progress lines go to the log files only. */
interface FactoryView {
    val live: Boolean
    fun attach(engine: BlockEngine)
    fun detach()
}

fun runFactory(
    data: RootData, config: RunConfiguration, tuning: Tuning, input: ProductionPlan, out: File, resume: Boolean = false,
    view: FactoryView? = null,
    /** Search blocks until stopped (key, control file) instead of until the time budget. */
    untilStopped: Boolean = false,
): Factory? {
    val cfg = config.blocks!!
    require(input.ports.none { p -> p.specs.any { it.edge == null } }) {
        "a factory of blocks takes its inputs and outputs by edge (with from and to), not by x and y"
    }
    out.mkdirs()
    val logFile = File(out, "factory.txt")
    if (!resume || !logFile.exists()) logFile.writeText("")
    val t0 = System.currentTimeMillis()
    var deadline = t0 + (config.search.timeBudgetS * 1000).toLong()
    val sayLock = Any()
    fun say(msg: String) {
        val line = "[%7.1fs] %s".format((System.currentTimeMillis() - t0) / 1000.0, msg)
        synchronized(sayLock) { if (view?.live != true) println(line); logFile.appendText(line + "\n") }
    }
    fun left() = (deadline - System.currentTimeMillis()) / 1000.0
    val tier = GameConstants.beltTier(config.beltTier, config.beltStackSize)
    fun recipes(p: ProductionPlan) = recipeCounts(p)
    val saved = if (resume) loadSplit(out) else null
    if (resume) say(when {
        saved == null -> "resume: nothing saved in $out; starting afresh"
        recipes(saved.plan) != recipes(input) -> "resume: the saved split is of another plan; starting afresh"
        else -> "resume: split of ${saved.blocks.size} blocks from $out/resume"
    })
    var split = saved?.takeIf { recipes(it.plan) == recipes(input) }
        ?: splitPlan(input, cfg, tier.throughput, config.search.seed, config.box.aspect, config.reserved,
            pure = if (cfg.templates) Templates.columnRecipes(input, config, cfg.templateIngredients, cfg.templateMinMachines, cfg.maxMachines / 2, cfg.templateColumns) else emptyMap()).also { saveSplit(out, config, it) }
    // Machine rates rebalanced for the split: blocks, stitching and validation all use this plan.
    val plan = split.plan
    plan.write(File(out, "plan_rebalanced.json").path)
    split.warnings.forEach { say("WARNING: $it") }
    sayPlan(split, plan, ::say)
    var bps = split.blocks.indices.map { blockPlan(plan, split, it, tuning, cfg.sharedOutputs) }
    val later = (if (cfg.repack) cfg.repackShare else 0.0) + (if (cfg.merge) cfg.mergeShare else 0.0)
    fun blockConfig(budget: Double, aspect: Double, startSize: Int? = null) = config.copy(
        blocks = null, testMode = false, reserved = emptyList(), forbiddenTiles = emptyList(),
        // Cracking control is added once for the whole factory (its wires would not survive stitching).
        fluids = config.fluids.copy(surplusOnly = emptyMap()),
        box = config.box.copy(aspect = aspect, startSize = startSize, maxSize = null, fixedHeight = null, fixedWidth = null),
        search = config.search.copy(timeBudgetS = budget),
        // Aquilo: a block takes heat from any side; the factory's heat network joins its pipes (see finishFactory).
        heat = config.heat.copy(edge = "any"),
    )

    val weights = machineWeights(plan, cfg, tier.throughput)
    val blockSize = BlockSize.of(plan, weights, cfg)
    fun weightOf(ms: List<Int>) = ms.sumOf { weights[it] }
    fun effort(b: Int) = weightOf(split.blocks[b]) + 0.5 * split.links.count { it.from == b || it.to == b }

    // The smallest valid factory so far, and how factories are stitched (previews while blocks are searched, then the final one).
    var best: Factory? = null
    var bestState: FpState? = null
    fun area(f: Factory) = f.layout.w * f.layout.h
    var engineRef: BlockEngine? = null
    fun considerLocked(r: Pair<Factory, FpState>?, what: String): Boolean {
        val f = r?.first
        if (f == null || !f.success) { say("$what: no valid factory"); return false }
        val b = best
        if (b != null && area(f) >= area(b)) { say("$what: ${f.layout.w} x ${f.layout.h} is not smaller than ${b.layout.w} x ${b.layout.h}"); return false }
        best = f; bestState = r.second
        writeFactory(f, out, config)
        say("$what: ${f.layout.w} x ${f.layout.h} (${area(f)} tiles), saved")
        engineRef?.factorySaved(f.layout.w, f.layout.h)
        return true
    }
    // Previews (stitched alongside the block search) and the final factory may both offer a factory.
    fun consider(r: Pair<Factory, FpState>?, what: String): Boolean = synchronized(sayLock) { considerLocked(r, what) }
    fun assemble(sp: Split, bpl: List<BlockPlan>, vs: List<List<TrialResult>>, start: FpState?, what: String, squeezeCap: Double? = null, tries: Int? = null) =
        runCatching {
            // Compaction gets blocks.compact_s, or else the time left for stitching (at least half a minute).
            val squeeze0 = cfg.compactS ?: maxOf(30.0, minOf(left(), config.search.timeBudgetS * (1 - cfg.blockTimeShare)))
            val squeeze = squeezeCap?.let { minOf(it, squeeze0) } ?: squeeze0
            assembleFactory(data, config, tuning, plan, sp, bpl, vs, start, ::say, what, System.currentTimeMillis() + (squeeze * 1000).toLong(),
                { consider(it, "$what (compacting)") }, tries)
        }
            .getOrElse { e -> say("$what: failed with $e"); e.printStackTrace(); null }

    // 1. Blocks in turns (see Engine.kt): a first layout each and a preview factory, then smaller boxes, the blocks
    //    that set the factory's size first, until the time for blocks is up or the run is stopped.
    // A saved layout that cannot be stitched (one an older version made, see [unstitchable]) is left out; its slot
    // stays empty so the others keep their shape variants, and an empty one is searched afresh.
    val loaded: List<List<TrialResult?>> = split.blocks.indices.map { b ->
        if (!resume) return@map emptyList()
        loadBlock(out, b).mapIndexed { i, r ->
            val why = unstitchable(bps[b], r) ?: return@mapIndexed r
            say("block ${b + 1}: resumed layout ${i + 1} (${r.w} x ${r.h}) dropped: $why")
            null
        }
    }
    loaded.forEachIndexed { b, l ->
        val first = l.firstOrNull() ?: return@forEachIndexed
        say("block ${b + 1}: ${first.w} x ${first.h}, resumed (${l.count { it != null }} layout(s)); refining it")
    }
    bps.forEach { bp ->
        val b = bp.index
        bp.plan.write(File(out, "block_${b + 1}/plan.json").path)
        say("block ${b + 1}: ${bp.globalIds.size} machines (weight ${"%.1f".format(weightOf(split.blocks[b]))} of ${"%.1f".format(blockSize.target)}), ${bp.plan.ports.size} ports")
    }
    // Time kept for the final factory: its compaction and stitching.
    val finalReserve = (cfg.compactS ?: config.search.timeBudgetS * (1 - cfg.blockTimeShare)) + minOf(60.0, 0.05 * config.search.timeBudgetS)
    // Blocks get the budget's block_time_share (less repack and merge time); the final factory runs after it.
    val blocksUntil = if (untilStopped) Long.MAX_VALUE else t0 + (config.search.timeBudgetS * cfg.blockTimeShare * (1 - later) * 1000).toLong()
    var previewState: FpState? = split.geometry.state
    // The rotation: every block, then each block at its aspect times each of blocks.shape_variants (so the floorplan
    // can pick shapes that pack together; a hole between mismatched blocks is space no squeezing gets back).
    val jobs = bps.map { Job(it.index, 0, 1.0) } +
        bps.flatMap { bp -> cfg.shapeVariants.mapIndexed { i, f -> Job(bp.index, i + 1, f) } }
    fun baseAspect(b: Int) = loaded[b].firstOrNull()?.let { it.w.toDouble() / it.h } ?: split.geometry.boxes[b].let { it.w.toDouble() / it.h }
    // Ready-made layouts for simple blocks (a column of furnaces...), offered next to whatever the search finds.
    // (Blocks in parallel: a wide column's header search takes a second or two.)
    val templatePool = java.util.concurrent.Executors.newFixedThreadPool(config.search.threads.coerceAtLeast(1))
    val templates = try {
        bps.map { bp ->
            templatePool.submit(java.util.concurrent.Callable {
                if (!cfg.templates) emptyList() else runCatching {
                    Templates.layouts(data, blockConfig(1e7, baseAspect(bp.index)), bp.plan)
                }.getOrElse { e -> say("block ${bp.index + 1}: template failed with $e"); emptyList() }
                    .also { ts -> if (ts.isNotEmpty()) say("block ${bp.index + 1}: template ${ts.joinToString(", ") { "${it.w} x ${it.h}" }} (${ts[0].notes.firstOrNull() ?: ""})") }
            })
        }.map { it.get() }
    } finally { templatePool.shutdown() }
    val engine = BlockEngine(
        bps = bps,
        jobs = jobs,
        searchOf = { job, init, printer ->
            val aspect = init?.let { it.w.toDouble() / it.h } ?: (baseAspect(job.block) * job.factor)
            val dir = File(out, "block_${job.block + 1}" + if (job.variant > 0) "/shape_${job.variant}" else "")
            Search(data, blockConfig(1e7, aspect), tuning, bps[job.block].plan, dir, initial = init, printer = printer)
        },
        initial = jobs.map { loaded[it.block].getOrNull(it.variant) },
        weights = split.blocks.map { weightOf(it) },
        threads = config.search.threads.coerceAtLeast(1),
        lanes0 = cfg.lanes ?: maxOf(1, config.search.threads / 2),
        seeds = config.search.seedsPerSize,
        parkAfter = cfg.parkAfter, variantCapS = cfg.shapeVariantS, restitchShare = cfg.restitchShare,
        limit = blocksUntil, out = out, say = ::say,
        blockPrinter = { job, line ->
            if (view?.live != true) synchronized(sayLock) { println("  b${job.block + 1}${if (job.variant > 0) "x" + "%.2f".format(job.factor) else ""} $line") }
        },
        stitch = { layouts ->
            // A preview is quick: one floorplan (from the last preview's) and a short squeeze; the final factory gets the rest.
            val r = assemble(split, bps, layouts, previewState, "preview", squeezeCap = 20.0, tries = 1)
            if (r != null && r.first.success) { previewState = r.second; consider(r, "preview") }
            r?.first?.takeIf { it.success }?.let { f -> Triple(f.blockRects, f.layout.w, f.layout.h) }
        },
        corridor = cfg.corridor,
        extras = templates,
        extraShare = cfg.templateShare,
    )
    engine.onBetter = { b, ls -> saveBlock(out, b, ls) }
    engineRef = engine
    view?.attach(engine)
    try {
    engine.run()
    if (engine.stopNow) { say("stopped; the best factory so far is saved"); return best }
    engine.finalPhase("final factory: stitching and compacting")
    val variants = ArrayList<MutableList<TrialResult>>()
    for (b in split.blocks.indices) {
        val r = engine.best(b) ?: run { say("block ${b + 1}: no layout found; giving up"); return null }
        variants += engine.layouts(b).toMutableList()
        say("block ${b + 1}: ${r.w} x ${r.h}" + if (variants[b].size > 1) "; shapes " + variants[b].drop(1).joinToString(", ") { "${it.w} x ${it.h}" } else "")
    }
    // The final factory gets its time even if the blocks ran over (a stop, or a limit moved while running).
    deadline = maxOf(deadline, System.currentTimeMillis() + ((finalReserve + config.search.timeBudgetS * later) * 1000).toLong())

    // 2. Floorplan the finished blocks and stitch them. Keep the smallest valid factory.
    val first = assemble(split, bps, variants, previewState ?: split.geometry.state, "factory")
    if ((first == null || !first.first.success) && best == null) {
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
            if (r != null) { variants[b] += r; saveBlock(out, b, variants[b]); added = true; say("reshape block ${b + 1}: ${r.w} x ${r.h} (was ${variants[b][0].w} x ${variants[b][0].h})") }
            else say("reshape block ${b + 1}: nothing at aspect ${"%.2f".format(wanted[b])}")
        }
        if (added) consider(assemble(split, bps, variants, bestState, "reshaped factory"), "reshaped factory")
    }

    // 4. Merge neighbours: two blocks next to each other become one, searched from their placements side by side.
    //    A full compaction takes minutes, so each merge is judged after a short one, against the factory as it stands
    //    after the same short one; merges that win are kept and built on, and the result is compacted fully at the
    //    end and kept if it beats the best factory.
    if (cfg.merge) {
        // A merged block weighs at most 2.5 times the target (merge_max_machines machines with size_by machines).
        val maxW = if (cfg.sizeBy == "machines") (cfg.mergeMaxMachines ?: (2 * cfg.maxMachines)).toDouble() else 2.5 * blockSize.target
        // Seconds per unit of effort the blocks got, so a merged block gets about what its two halves had, and more.
        val perEffort = config.search.timeBudgetS * cfg.blockTimeShare * (1 - later) / split.blocks.indices.sumOf(::effort).coerceAtLeast(1.0)
        val quick = 120.0
        val tried = HashSet<Set<Int>>()
        var merges = 0
        var cur = bestState?.let { assemble(split, bps, variants, it, "merge reference", squeezeCap = quick) }?.takeIf { it.first.success }
        if (cur != null) say("merge: the factory is ${cur.first.layout.w} x ${cur.first.layout.h} after a short compaction; a merge must beat that at the same stage")
        // Time kept for the final full compaction.
        val reserve = (cfg.compactS ?: 300.0) + 60.0
        while (cur != null && left() > reserve + 180) {
            val (f, st) = cur
            val boxes = f.blockRects
            val pairs = neighbourPairs(boxes, cfg.corridor * 3)
                .filter { (a, b) -> weightOf(split.blocks[a] + split.blocks[b]) <= maxW + 1e-9 && (split.blocks[a] + split.blocks[b]).toSet() !in tried }
            if (pairs.isEmpty()) { say("merge: no more pairs to try"); break }
            // Most promising first: belts between the two disappear, shared recipes make one simple block (two
            // smelting columns), and lighter merges are faster to search and more likely to route.
            fun recipesOf(b: Int) = split.blocks[b].map { plan.machines[it].recipe }.toSet()
            fun promise(a: Int, b: Int): Double {
                val between = split.links.count { it.inner && ((it.from == a && it.to == b) || (it.from == b && it.to == a)) }
                val shared = (recipesOf(a) intersect recipesOf(b)).size
                return 2.0 * between + 3.0 * shared - weightOf(split.blocks[a] + split.blocks[b]) / blockSize.target
            }
            val (l, r) = pairs.maxBy { (a, b) -> promise(a, b) }
            tried += (split.blocks[l] + split.blocks[r]).toSet()
            // Both blocks as they lie in the factory: turned layouts, and their ports' sides turned with them.
            val turnedSplit = Split(split.blocks, split.links.map { lk ->
                fun t(side: String?, b: Int) = if (side != null && (b == l || b == r)) Dir.fromName(side).turned(f.blockRot[b]).name else side
                lk.copy(fromSide = t(lk.fromSide, lk.from), toSide = t(lk.toSide, lk.to))
            }, split.warnings, split.geometry, split.plan, split.partition, split.ports)
            val merged = mergeBlocks(turnedSplit, l, r)
            val m = min(l, r)
            val bp = blockPlan(plan, merged, m, tuning, cfg.sharedOutputs)
            // The two placements as they lie in the factory, in the box around both.
            val ul = boxes[l]; val ur = boxes[r]
            val ux = min(ul.x, ur.x); val uy = min(ul.y, ur.y)
            val uw = max(ul.x2, ur.x2) - ux + 1; val uh = max(ul.y2, ur.y2) - uy + 1
            val local = bp.globalIds.withIndex().associate { (i, g) -> g to i }
            val warm = ArrayList<PlacedMachine>()
            for (b in listOf(l, r)) {
                val res = f.blockLayouts[b]
                res.placement!!.machines.forEach { pm -> warm += pm.copy(id = local.getValue(bps[b].globalIds[pm.id]), x = pm.x + boxes[b].x - ux, y = pm.y + boxes[b].y - uy) }
            }
            val budget = (1.5 * perEffort * (effort(l) + effort(r))).coerceIn(60.0, 3600.0).coerceAtMost(left() - reserve - 150)
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
            val newBps = merged.blocks.indices.map { i -> if (i == m) bp else blockPlan(plan, merged, i, tuning, cfg.sharedOutputs) }
            val cand = assemble(merged, newBps, newVariants, st.without(gone), "merged ${l + 1}+${r + 1}", squeezeCap = quick)?.takeIf { it.first.success }
            if (cand != null && area(cand.first) < area(f)) {
                say("merge blocks ${l + 1} and ${r + 1}: kept, ${cand.first.layout.w} x ${cand.first.layout.h} against ${f.layout.w} x ${f.layout.h} at the same stage")
                split = merged; bps = newBps; variants.clear(); variants += newVariants
                cur = cand
                merges++
            } else say("merge blocks ${l + 1} and ${r + 1}: dropped" + (cand?.let { ", ${it.first.layout.w} x ${it.first.layout.h} against ${f.layout.w} x ${f.layout.h}" } ?: ", no valid factory"))
        }
        if (merges > 0 && cur != null) {
            // The merged factory compacted fully; kept (and saved for --resume) if it beats the best.
            val before = best
            consider(assemble(split, bps, variants, cur.second, "merged factory ($merges merge(s)), compacted"), "merged factory")
            if (best !== before) saveAll(out, config, split, variants)
        }
    }
    say("done: ${best?.let { "${it.layout.w} x ${it.layout.h} (${area(it)} tiles)" } ?: "no valid factory"}")
    best?.notes?.forEach { say("  note: $it") }
    return best
    } finally { view?.detach() }
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
    // Links are renumbered; lane pairs follow (a pair broken by the merge is dropped).
    val newId = links.withIndex().associate { (i, l) -> l.id to i }
    val renumbered = links.mapIndexed { i, l -> l.copy(id = i, pair = newId[l.pair] ?: -1) }
    return Split(blocks, renumbered.map { l -> if (l.pair >= 0 && renumbered[l.pair].pair != l.id) l.copy(pair = -1) else l }, split.warnings, g, split.plan, split.partition, split.ports)
}

/** For each block, the aspect a floorplan of the real block areas with free shapes would give it. */
private fun reshapeTargets(plan: ProductionPlan, split: Split, variants: List<List<TrialResult>>, cfg: config.BlocksConfig, config: RunConfiguration): List<Double> {
    val input = floorplanInput(plan, split, variants, cfg, config, soft = true)
    val fp = Floorplanner(input.blocks, input.nets, FpParams(aspect = config.box.aspect, aspectWeight = cfg.aspectWeight, wireWeight = cfg.wireWeight, seed = config.search.seed + 17)).optimise()
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

/** A block's layout as the floorplan may place it: turned [rot] clockwise quarter turns from the search's. */
class BlockShape(val layout: TrialResult, val rot: Int)

/** Floorplan blocks and nets for a split, and the layout behind each shape of each block. */
private class FloorplanInput(val blocks: List<FpBlock>, val nets: List<FpNet>, val shapes: List<List<BlockShape>>)

/**
 * Floorplan blocks and nets for [split]: a shape per finished layout of a block and quarter turn of it (with
 * [config.BlocksConfig.rotate]; pins at its ports), or with [soft] free shapes of the first layout's area; a net
 * per link, and one per fluid pipe tree.
 */
private fun floorplanInput(
    plan: ProductionPlan, split: Split, variants: List<List<TrialResult>>, cfg: config.BlocksConfig, config: RunConfiguration,
    soft: Boolean = false, bps: List<BlockPlan>? = null,
): FloorplanInput {
    val k = split.blocks.size
    val blocks = ArrayList<FpBlock>()
    val layouts = ArrayList<List<BlockShape>>()
    fun edges(item: String, output: Boolean): Set<Dir> {
        val specs = plan.ports.firstOrNull { it.item == item && it.output == output }?.specs ?: return emptySet()
        return specs.flatMap { s -> if (s.edge == "any") Dir.entries.toList() else listOfNotNull(s.edge?.let(Dir::fromName)) }.toSet()
    }
    for (b in 0 until k) {
        // A port that leaves the factory straight out of the block turns with it: only turns that keep each such port
        // on an edge the job allows it (the search put it on one; a turned block would put a west input on the north).
        val straight = split.links.filter { !it.throughCorridor && (it.from == b || it.to == b) && (it.from == EDGE || it.to == EDGE) }
        fun turnKeepsEdges(q: Int) = straight.all { l -> l.side(b)?.let { Dir.fromName(it).turned(q) in edges(l.item, l.to == EDGE) } ?: true }
        val turns = (0 until if (cfg.rotate) 4 else 1).filter { turnKeepsEdges(it) }.ifEmpty { listOf(0) }
        val bs = if (soft) emptyList() else variants[b].flatMap { r -> turns.map { q -> BlockShape(turnLayout(r, q), q) } }
        val shapes = if (soft) softShapes(variants[b][0].area.toDouble()) else bs.map { sh ->
            val r = sh.layout
            val pins = HashMap<Int, Tile>()
            val lp = bps!![b].linksOfPort
            r.sources.forEach { s -> lp[s.port]?.forEach { pins[it] = s.tile } }
            r.outputs.forEach { o -> lp[o.port]?.forEach { pins[it] = o.tile } }
            FpShape(r.w, r.h, pins, sh.rot)
        }
        layouts += bs
        blocks += FpBlock(shapes, initialPads(split, b, cfg.corridor, config.testMode), split.touch[b])
    }
    config.reserved.forEachIndexed { i, r -> blocks += reservedBlock(r, i, cfg.corridor) }
    val nets = ArrayList<FpNet>()
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
    return FloorplanInput(blocks, nets, layouts)
}

/**
 * Floorplans the finished blocks ([variants]: every layout each block has, each in four turns; the floorplan picks
 * one) and stitches them, [config.BlocksConfig.floorplanTries] times from different floorplans (the first from
 * [start]). Each try: the corridors start at half a corridor plus room for each side's ports; where routing fails
 * the sides near the trouble get more room; once it routes, padding lines that stayed empty are given back, the
 * blocks are floorplanned again with the measured padding, and then single padding lines are squeezed out while it
 * still routes. The smallest routed factory of all tries is finished (poles, validation; the next smallest if that
 * fails), and comes back with its floorplan state.
 */
fun assembleFactory(
    data: RootData, config: RunConfiguration, tuning: Tuning, plan: ProductionPlan, split: Split, bps: List<BlockPlan>,
    variants: List<List<TrialResult>>, start: FpState?, say: (String) -> Unit, what: String,
    /** When compaction stops (epoch ms). */
    deadline: Long = System.currentTimeMillis() + 30_000,
    /** Called with a finished, valid factory whenever compaction has made one smaller (at most once a minute). */
    onBetter: (Pair<Factory, FpState>) -> Unit = {},
    /** Floorplans to try (default blocks.floorplan_tries). */
    triesOverride: Int? = null,
): Pair<Factory, FpState>? {
    val cfg = config.blocks!!
    val input = floorplanInput(plan, split, variants, cfg, config, bps = bps)
    val tries = (triesOverride ?: cfg.floorplanTries).coerceAtLeast(1)
    val t0 = System.currentTimeMillis()
    // 1. Every floorplan routed, tightened and squeezed, side by side on the job's threads, for a quarter of the time.
    val threads = config.search.threads.coerceAtLeast(1)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(tries, threads))
    val stitchings: List<Stitching> = try {
        (0 until tries).map { t ->
            pool.submit<Stitching?> {
                val label = if (tries > 1) "$what (floorplan ${t + 1}/$tries)" else what
                val params = FpParams(aspect = config.box.aspect, aspectWeight = cfg.aspectWeight, wireWeight = cfg.wireWeight, seed = config.search.seed + 1009L * t)
                val st = if (t == 0) start?.takeIf { it.plus.size == input.blocks.size }?.let { s -> FpState(s.plus.clone(), s.minus.clone(), IntArray(input.blocks.size)) } else null
                val s = Stitching(config, tuning, plan, split, bps, input, params, say, label)
                s.start(st)
                if (!s.routed) null
                else {
                    val n = s.squeeze(t0 + (deadline - t0) / 4)
                    if (n > 0) say("$label: squeezed $n corridor lines out: ${s.fp.w} x ${s.fp.h}")
                    s
                }
            }
        }.mapNotNull { runCatching { it.get() }.getOrElse { e -> say("$what: a floorplan failed with $e"); null } }
    } finally { pool.shutdown() }
    if (stitchings.isEmpty()) return null
    // 2. Compaction, until the time is up, of the smallest floorplans side by side (two when there are threads for
    //    it): squeeze; when that stalls, floorplan again from the current arrangement with a fresh seed and a lighter
    //    price on belt length (longer belts for a tighter packing). Every smaller valid factory is offered on the way.
    val compacting = stitchings.sortedBy { it.area }.take(if (threads >= 2) 2 else 1)
    if (tries > 1) say("$what: compacting the smallest floorplan${if (compacting.size > 1) "s" else ""}, " + compacting.joinToString(" and ") { "${it.fp.w} x ${it.fp.h}" })
    val savedArea = java.util.concurrent.atomic.AtomicInteger(Int.MAX_VALUE)
    fun compact(best: Stitching, index: Int) {
        var lastSave = System.currentTimeMillis()
        fun checkpoint() {
            if (System.currentTimeMillis() - lastSave < 60_000) return
            lastSave = System.currentTimeMillis()
            val (s, st) = synchronized(best.ok) { best.ok.minBy { it.first.w * it.first.h } }
            if (s.w * s.h >= savedArea.get()) return
            val f = finishFactory(data, config, plan, s)
            if (f.success && savedArea.getAndUpdate { a -> minOf(a, s.w * s.h) } > s.w * s.h) onBetter(f to st)
        }
        var wire = cfg.wireWeight
        var seed = config.search.seed + 7919 + 104729L * index
        var stalls = 0
        while (System.currentTimeMillis() < deadline) {
            val before = best.area
            val n = best.squeeze(deadline) { checkpoint() }
            if (n > 0) say("$what: squeezed $n corridor lines out: ${best.fp.w} x ${best.fp.h}")
            if (System.currentTimeMillis() >= deadline) break
            if (best.reshuffle(seed++, wire)) say("$what: floorplanned again (belt length at ${"%.2f".format(wire)}): ${best.fp.w} x ${best.fp.h}")
            else wire = maxOf(cfg.wireWeight * 0.05, wire * 0.6)
            if (best.area < before) stalls = 0 else if (++stalls >= 12) break
            checkpoint()
        }
    }
    if (compacting.size == 1) compact(compacting[0], 0)
    else {
        val cp = java.util.concurrent.Executors.newFixedThreadPool(compacting.size)
        try { compacting.mapIndexed { i, s -> cp.submit { compact(s, i) } }.forEach { runCatching { it.get() }.onFailure { e -> say("$what: compaction failed with $e") } } }
        finally { cp.shutdown() }
    }
    val all = stitchings.flatMap { it.ok }
    val sorted = all.sortedBy { it.first.w * it.first.h }
    say("$what: smallest routed factory ${sorted[0].first.w} x ${sorted[0].first.h} of ${all.size} routed")
    // Finish the smallest; if validation finds anything, the next smallest.
    var last: Pair<Factory, FpState>? = null
    for ((s, st) in sorted.take(8)) {
        val f = finishFactory(data, config, plan, s)
        if (f.success) return f to st
        say("$what: ${f.layout.w} x ${f.layout.h} has ${f.errors.size} validation errors: ${f.errors.take(5)}")
        // FACTORY_INVALID_DIR=<dir>: a render of each factory that failed validation, for finding out why.
        System.getenv("FACTORY_INVALID_DIR")?.let { d ->
            runCatching {
                val name = "${what.replace(' ', '_')}_${f.layout.w}x${f.layout.h}_${System.currentTimeMillis() % 100000}"
                viz.renderLayout(f.layout, File(d, "$name.png"),
                    viz.RenderOptions(px = 12, errors = f.errors, title = "$what ${f.layout.w}x${f.layout.h} blocks ${f.blockRects}"))
                // Each error with the block it lies in (1-based; 0: the corridors).
                File(d, "$name.txt").writeText(f.errors.joinToString("\n") { e ->
                    val b = f.blockRects.indexOfFirst { r -> e.tile.x in r.x until r.x + r.w && e.tile.y in r.y until r.y + r.h } + 1
                    "block $b: $e"
                })
            }
        }
        last = f to st
    }
    return last
}

/**
 * One floorplan of [assembleFactory] and what has been routed on it: [start] routes it (widening the corridors
 * where routing fails, then giving back padding lines nothing uses), [squeeze] takes single padding lines out (one
 * block side at a time, one tile less, kept when the factory shrinks and still routes; a side without ports may go
 * to nothing, validation catches anything that then touches across), and [reshuffle] floorplans again from the
 * current arrangement with the padding as it now is, kept when it is smaller and routes.
 */
private class Stitching(
    val config: RunConfiguration, val tuning: Tuning, val plan: ProductionPlan, val split: Split, val bps: List<BlockPlan>,
    val input: FloorplanInput, val params: FpParams, val say: (String) -> Unit, val what: String,
) {
    val k = split.blocks.size
    val planner = Floorplanner(input.blocks, input.nets, params)
    var fp: FpPlan = planner.optimise(null)
    var pads = input.blocks.map { it.pad.clone() }
    /** Everything routed so far, with its floorplan state, oldest first. */
    val ok = ArrayList<Pair<Stitched, FpState>>()
    val routed get() = ok.isNotEmpty()
    val area get() = fp.area
    private val portSides = (0 until k).map { b -> split.links.filter { it.from == b || it.to == b }.mapNotNull { it.side(b) }.map { Dir.fromName(it).ordinal }.toSet() }

    fun layoutOf(p: FpPlan) = Arrangement(p.boxes.take(k), p.boxes.drop(k), p.w, p.h, IntArray(k) { p.state.shape[it] },
        IntArray(k) { input.blocks[it].shapes[p.state.shape[it]].rot })
    /** Lane pairs whose junction next to the supplier did not route: theirs goes in front of the consumer. */
    val atConsumer = HashSet<Int>()
    private fun route(p: FpPlan) = stitchRoute(config, tuning, plan, split, bps, input.shapes, layoutOf(p), atConsumer)

    fun start(from: FpState?) {
        fp = planner.optimise(from)
        if (fp.violations > 0) say("$what: the floorplan leaves ${fp.violations} block side(s) off the factory edge they need")
        for (attempt in 0 until 8) {
            val arr = layoutOf(fp)
            val r = stitchRoute(config, tuning, plan, split, bps, input.shapes, arr, atConsumer)
            if (r.stitched != null) { ok += r.stitched to fp.state; say("$what: routed at ${fp.w} x ${fp.h}${if (attempt > 0) " (attempt ${attempt + 1})" else ""}"); break }
            if (!atConsumer.containsAll(r.badPairs)) {
                atConsumer += r.badPairs
                say("$what: ${r.badPairs.size} lane junction(s) did not route next to the supplier; moving them in front of the consumer")
                continue
            }
            say("$what: corridors at ${fp.w} x ${fp.h} did not route (${r.why}); widening near ${r.trouble.size} trouble tiles ${r.trouble.take(4)}")
            pads = grow(pads, arr, r.trouble, r.edges, k)
            fp = planner.pack(fp.state, pads)
        }
        if (!routed) return
        for (round in 0 until 3) {
            val tighter = tighten(pads, ok.last().first, k)
            if (tighter.indices.all { tighter[it].contentEquals(pads[it]) }) break
            val p2 = planner.pack(fp.state, tighter)
            if (p2.area >= fp.area) break
            val r = route(p2)
            if (r.stitched == null) { say("$what: tighter corridors (${p2.w} x ${p2.h}) did not route"); break }
            say("$what: tighter corridors: ${p2.w} x ${p2.h}")
            ok += r.stitched to p2.state; pads = tighter; fp = p2
        }
    }

    /** Squeezes until a full pass over the sides gains nothing or [deadline]; the number of lines taken out. */
    fun squeeze(deadline: Long, onBetter: () -> Unit = {}): Int {
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
                val r = route(p2)
                if (r.stitched != null) { ok += r.stitched to p2.state; pads = trial; fp = p2; improved = true; squeezed++; onBetter() }
            }
            // No single side shortens every longest chain: take a line from each block on them at once (the larger of
            // its two sides across), all of them, else half of them by position.
            if (!improved && System.currentTimeMillis() < deadline) improved = batch(deadline, onBetter).also { if (it) squeezed++ }
        }
        return squeezed
    }

    private fun batch(deadline: Long, onBetter: () -> Unit): Boolean {
        val (cx, cy) = planner.critical(fp.state, pads)
        fun rot(b: Int) = input.blocks[b].shapes[fp.state.shape[b]].rot
        fun idx(b: Int, d: Dir) = (d.ordinal - rot(b)).mod(4)
        fun min(b: Int, i: Int) = if (i in portSides[b]) 1 else 0
        for ((blocks, sides) in listOf(cx to listOf(Dir.E, Dir.W), cy to listOf(Dir.N, Dir.S))) {
            val cands = blocks.mapNotNull { b -> sides.map { idx(b, it) }.filter { pads[b][it] > min(b, it) }.maxByOrNull { pads[b][it] }?.let { b to it } }
            if (cands.isEmpty()) continue
            val centre = { b: Int -> if (sides[0] == Dir.E) fp.boxes[b].x else fp.boxes[b].y }
            val sorted = cands.sortedBy { centre(it.first) }
            for (subset in listOf(sorted, sorted.take(sorted.size / 2), sorted.drop(sorted.size / 2)).filter { it.isNotEmpty() }) {
                if (System.currentTimeMillis() >= deadline) return false
                val trial = pads.map { it.clone() }.also { t -> subset.forEach { (b, i) -> t[b][i]-- } }
                val p2 = planner.pack(fp.state, trial)
                if (p2.area >= fp.area) continue
                val r = route(p2)
                if (r.stitched != null) { ok += r.stitched to p2.state; pads = trial; fp = p2; onBetter(); return true }
            }
        }
        return false
    }

    /** Floorplans again from the current arrangement (seed [seed], wire length weighted [wire]); true if kept. */
    fun reshuffle(seed: Long, wire: Double): Boolean {
        val p = FpParams(params.aspect, params.aspectWeight, wire, seed, params.restarts, params.movesPerBlock)
        val re = Floorplanner(input.blocks.mapIndexed { i, b -> FpBlock(b.shapes, pads[i], b.touch) }, input.nets, p).optimise(fp.state)
        if (re.area >= fp.area || re.violations > 0) return false
        val p2 = planner.pack(re.state, pads)
        val r = route(p2)
        if (r.stitched == null) return false
        ok += r.stitched to p2.state; fp = p2
        return true
    }
}

/** Where everything goes: block boxes, reserved boxes, the factory size, which shape of each block and its turn. */
class Arrangement(val boxes: List<Rect>, val reserved: List<Rect>, val w: Int, val h: Int, val shape: IntArray, val rot: IntArray)

/** Everything placed and routed, before poles and validation. */
class Stitched(
    val entities: List<PlacedEntity>, val machines: List<PlacedMachine>, val sources: List<SourceSpec>, val outputs: List<OutputSpec>,
    val w: Int, val h: Int, val blockRects: List<Rect>, val reservedRects: List<Rect>,
    /** Each block's layout as placed (turned), and its turn. */
    val layouts: List<TrialResult> = emptyList(), val rot: IntArray = IntArray(0),
)

/** A stitch that routed, or why not: tiles near the trouble, and factory edges a corridor link could not reach. */
class StitchAttempt(
    val stitched: Stitched?, val trouble: List<Tile>, val why: String, val edges: Set<Dir> = emptySet(),
    /** Lane pairs (lower link id) whose belts did not route at their junction. */
    val badPairs: Set<Int> = emptySet(),
)

/**
 * More padding on the block sides near [trouble] (or everywhere, by one, if nothing is near), and between the
 * blocks along factory edges in [edges] (a link to that edge found no free edge tile).
 */
private fun grow(pads: List<IntArray>, arr: Arrangement, trouble: List<Tile>, edges: Set<Dir>, k: Int): List<IntArray> {
    val out = pads.map { it.clone() }
    var any = false
    // Padding is kept per side of the block as searched; factory side [d] of a turned block is its side d - turn.
    fun i(b: Int, d: Dir) = (d.ordinal - arr.rot[b]).mod(4)
    for (b in 0 until k) for (e in edges) {
        val box = arr.boxes[b]
        val dist = when (e) { Dir.N -> box.y; Dir.S -> arr.h - 1 - box.y2; Dir.W -> box.x; Dir.E -> arr.w - 1 - box.x2 }
        if (dist > pads[b][i(b, e)] + 1) continue
        listOf(e.cw, e.ccw).forEach { d -> if (pads[b][i(b, d)] > 0) { out[b][i(b, d)] += 2; any = true } }
    }
    for (b in 0 until k) {
        val box = arr.boxes[b]
        for (d in Dir.entries) {
            val p = pads[b][i(b, d)]
            if (p == 0) continue
            val near = trouble.any { t ->
                val along = if (d.axis == 0) t.y in box.y - 2..box.y2 + 2 else t.x in box.x - 2..box.x2 + 2
                val dist = when (d) { Dir.N -> box.y - t.y; Dir.S -> t.y - box.y2; Dir.W -> box.x - t.x; Dir.E -> t.x - box.x2 }
                along && dist in 0..p + 3
            }
            if (near) { out[b][i(b, d)] = max(out[b][i(b, d)], p + 2); any = true }
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
            val pi = (d.ordinal - s.rot[b]).mod(4)
            val p = pads[b][pi]
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
            out[b][pi] = max(1, p - empty)
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
    bps: List<BlockPlan>, shapes: List<List<BlockShape>>, arr: Arrangement,
    /** Lane pairs (by their lower link id) whose junction goes right in front of the consumer instead. */
    atConsumer: Set<Int> = emptySet(),
): StitchAttempt {
    val k = bps.size
    val results = (0 until k).map { shapes[it][arr.shape[it]].layout }
    // A port's side in the factory: its side as searched, turned with the block.
    fun side(name: String?, b: Int) = name?.let { Dir.fromName(it).turned(arr.rot[b]) }
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
    /** Groups of links leaving by one shared output port. */
    val sharedStarts = ArrayList<List<Int>>()
    /** Edge links whose port did not end up on its edge: they run through the corridors to it. */
    val detoured = HashSet<Int>()
    val sources = ArrayList<SourceSpec>()
    val outputs = ArrayList<OutputSpec>()
    // (On the ring, or on the box's own edge row: fluid ports start with a pipe-to-ground there.)
    fun outsideOnSide(t: Tile, side: String?) = when (side) { "N" -> t.y <= 0; "S" -> t.y >= H - 1; "W" -> t.x <= 0; "E" -> t.x >= W - 1; else -> false }
    for (b in 0 until k) {
        results[b].outputs.forEach { o ->
            val group = bps[b].linksOfPort[o.port] ?: return@forEach
            val l = split.links[group[0]]
            val t = Tile(o.tile.x + ox[b], o.tile.y + oy[b])
            if (!l.throughCorridor) {
                if (outsideOnSide(t, side(l.fromSide, b)?.name)) { outputs += o.copy(tile = t, port = -1); return@forEach }
                // Not on its edge (a block that must touch two opposite edges is narrower than the factory, so it
                // slid off one): its belt runs through the corridors to that edge instead.
                if (o.fluid) return StitchAttempt(null, listOf(t), "block ${b + 1}'s ${l.item} output is not on the factory's edge")
                detoured += l.id
            }
            // A pipe port joins on the side the block was searched with it (a corner tile alone does not say which).
            val d = if (o.fluid) side(l.fromSide, b)!! else byTile[t]?.dir ?: return StitchAttempt(null, listOf(t), "no belt at a port")
            // Links sharing the port all start there (one belt, branching in the corridors).
            group.forEach { starts[it] = (t + d) to d }
            if (group.size > 1) sharedStarts += group
        }
        results[b].sources.forEach { s ->
            val l = split.links[bps[b].linkOfPort[s.port] ?: return@forEach]
            val t = Tile(s.tile.x + ox[b], s.tile.y + oy[b])
            if (!l.throughCorridor) {
                if (outsideOnSide(t, side(l.toSide, b)?.name)) { sources += s.copy(tile = t, port = -1); return@forEach }
                if (s.fluid) return StitchAttempt(null, listOf(t), "block ${b + 1}'s ${l.item} input is not on the factory's edge")
                detoured += l.id
            }
            val out = side(l.toSide, b) ?: sideOf(s.tile, results[b].w, results[b].h)
            ends[l.id] = (t + out) to out.opposite
        }
    }
    val corridorLinks = split.links.filter { it.throughCorridor || it.id in detoured }
    corridorLinks.filter { (it.from >= 0 && it.id !in starts) || (it.to >= 0 && it.id !in ends) }.takeIf { it.isNotEmpty() }?.let { miss ->
        return StitchAttempt(null, emptyList(), "a block port is missing: " + miss.take(3).joinToString { l ->
            val b = if (l.from >= 0 && l.id !in starts) l.from else l.to
            "block ${b + 1}'s ${l.item} ${if (b == l.from) "output" else "input"} (link ${l.id}, its layout: ${results[b].notes.firstOrNull() ?: "searched"})"
        })
    }

    // A pipe link whose first tile outside its port touches another fluid's pipe (ports of two fluids side by side)
    // or a machine starts with a pipe-to-ground pair out of the port instead: it joins only through its open sides.
    val blockTiles = HashSet<Tile>()
    for (b in 0 until k) for (y in oy[b] until oy[b] + results[b].h) for (x in ox[b] until ox[b] + results[b].w) blockTiles += Tile(x, y)
    arr.reserved.forEach { r -> blockTiles += r.tiles() }
    // Nothing tunnels under a block, nor under an underground belt or pipe-to-ground of one outside its box (a ring
    // port): ours would pair with it.
    val noTunnel = HashSet(blockTiles)
    entities.filter { it.kind == Kind.PIPE_UG || it.kind == Kind.UG_IN || it.kind == Kind.UG_OUT }.forEach { noTunnel += it.rect.tiles() }
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
    // A corridor belt or pipe starts and ends inside the factory: a port side squeezed to its ring alone on the
    // factory's border leaves no tile for it.
    (starts.values + ends.values).map { it.first }.filter { it.x !in 0 until W || it.y !in 0 until H }.takeIf { it.isNotEmpty() }?.let {
        return StitchAttempt(null, it, "${it.size} link end(s) outside the factory")
    }
    for (l in corridorLinks.filter { it.fluid }) {
        starts[l.id]?.let { (t, d) -> starts[l.id] = (dive(l, t, d) ?: return StitchAttempt(null, listOf(t), "${l.item} cannot leave its port")) to d }
        ends[l.id]?.let { (t, d) -> ends[l.id] = (dive(l, t, d.opposite) ?: return StitchAttempt(null, listOf(t), "${l.item} cannot reach its port")) to d }
    }
    // A shared output port: splitters near it give each of its links a belt of its own (two or three), reached by a
    // short belt from the port (the nearest spot with room, found by a small search).
    val otherEnds = (starts.filterKeys { k -> sharedStarts.none { k in it } }.values + ends.values).map { it.first }.toSet()
    // Tiles another group needs: the first tile outside each shared port, and what was handed out so far.
    val claimed = sharedStarts.map { starts.getValue(it[0]).first }.toMutableSet()
    val tier = GameConstants.beltTier(config.beltTier)
    for (g in sharedStarts) {
        val (s0, d0) = starts.getValue(g[0])
        val item = split.links[g[0]].item
        val net = netOfItem[item]
        fun open(t: Tile) = free(t) && t !in otherEnds && (t == s0 || t !in claimed)
        // Nothing may push into [t] but [from] (the belt or splitter meant to feed it).
        fun quiet(t: Tile, from: Tile?) = Dir.entries.none { dd -> val u = t + dd; u != from && byTile[u]?.let { e -> e.kind.beltFamily && (e.outputTile == t || (e.kind == Kind.SPLITTER && e.rect.tiles().any { it + e.dir == t })) } == true }
        fun splitter(a: Tile, b: Tile, h: Dir) = PlacedEntity(tier.splitter, Kind.SPLITTER, minOf(a.x, b.x), minOf(a.y, b.y), h, net = net)
        /** Splitters with their trunk half on [p] heading [h] (fed from [from]), and the start tiles they hand out. */
        fun tree(p: Tile, h: Dir, from: Tile?, used: Set<Tile>): Pair<List<PlacedEntity>, List<Tile>>? {
            fun ok(t: Tile) = open(t) && t !in used
            for (side in listOf(h.cw, h.ccw)) {
                val half = p + side
                if (!ok(p) || !ok(half) || !quiet(half, null) || !quiet(p, from)) continue
                val a = p + h; val b = half + h
                if (g.size == 2) { if (ok(a) && ok(b)) return listOf(splitter(p, half, h)) to listOf(a, b) else continue }
                val half2 = a + side.opposite
                val a2 = a + h; val b2 = half2 + h
                if (listOf(a, half2, b, a2, b2).all { ok(it) } && quiet(half2, null)) return listOf(splitter(p, half, h), splitter(a, half2, h)) to listOf(a2, b2, b)
            }
            return null
        }
        // Breadth first over short belt paths from the port: each step a belt heading on, straight or turning.
        data class St(val t: Tile, val h: Dir)
        val prev = HashMap<St, St?>()
        val queue = ArrayDeque(listOf(St(s0, d0))); prev[St(s0, d0)] = null
        var found: Triple<St, List<PlacedEntity>, List<Tile>>? = null
        while (queue.isNotEmpty() && found == null) {
            val cur = queue.removeFirst()
            val path = generateSequence(cur) { prev[it] }.toList()
            if (path.size > 10) continue
            val pathTiles = path.map { it.t }.toSet()
            val from = prev[cur]?.t ?: (s0 + d0.opposite)
            tree(cur.t, cur.h, from, pathTiles - cur.t)?.let { (es, ts) -> found = Triple(cur, es, ts) }
            if (found != null) break
            for (nh in listOf(cur.h, cur.h.cw, cur.h.ccw)) {
                val n = St(cur.t + nh, nh)
                // The belt on cur heads nh into n; only it may feed n.
                if (n in prev || n.t in pathTiles || !open(n.t) || !quiet(n.t, cur.t)) continue
                prev[n] = cur; queue += n
            }
        }
        val (end, es, ts) = found ?: return StitchAttempt(null, listOf(s0), "no room for the splitters of a shared $item output")
        // The belts on the way: each heads to the next tile (the last into the splitter).
        val path = generateSequence(end) { prev[it] }.toList().reversed()
        for (i in 0 until path.size - 1) {
            val t = path[i].t
            val e = PlacedEntity(tier.belt, Kind.BELT, t.x, t.y, path[i + 1].h, net = net)
            entities += e; byTile[t] = e; claimed += t
        }
        es.forEach { e -> entities += e; e.rect.tiles().forEach { byTile[it] = e; claimed += it } }
        g.forEachIndexed { i, id -> starts[id] = ts[i] to end.h }
        claimed += ts
    }

    // The belts and pipes through the corridors.
    val ports = ArrayList<Port>(); val terminals = ArrayList<Terminal>(); val nets = ArrayList<Net>()
    val edgePorts = HashSet<Int>()
    val linksOfNet = HashMap<Int, List<Link>>()
    fun edgeSpecs(item: String, output: Boolean): List<ConfigSource> {
        // Leftovers (a mixed belt) that cannot leave their block straight out leave at whichever edge is nearest.
        if (output && item in plan.mixed.values) return listOf(ConfigSource(item, edge = "any"))
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
    // Lane pairs the consuming block took on one belt: next to the supplier the two items' belts side-load onto a
    // junction belt from either side (a belt fed from both sides and not from behind stays straight and takes one
    // item per lane), and one belt runs on from there.
    val paired = HashSet<Int>()
    val junctionKeepFree = HashSet<Tile>()
    val laneOf = HashMap<Int, Int>()
    for (b in 0 until k) results[b].sources.forEach { s -> bps[b].linkOfPort[s.port]?.let { if (s.lane >= 0) laneOf[it] = s.lane } }
    val endTiles = (starts.values + ends.values).map { it.first }.toMutableSet()
    for (l in corridorLinks) {
        if (l.pair <= l.id || !l.inner) continue
        val m = split.links[l.pair]
        if (ends[l.id]?.first == null || ends[l.id]?.first != ends[m.id]?.first || laneOf[l.id] == null || laneOf[m.id] == null) continue
        val (left, right) = if (laneOf[l.id] == 0) l to m else m to l
        val sl = starts.getValue(left.id).first; val sr = starts.getValue(right.id).first
        val end = ends.getValue(l.id).first
        if (l.id in atConsumer) {
            // The fallback: the junction belt is the tile in front of the consumer's port, fed from both sides.
            val h = ends.getValue(l.id).second
            if (listOf(end + h.ccw, end + h.cw).any { !free(it) || it in endTiles } || (end + h.opposite) in byTile)
                return StitchAttempt(null, listOf(end), "no room for the junction of ${left.item} and ${right.item} at the consumer", badPairs = setOf(l.id))
            val e = PlacedEntity(GameConstants.beltTier(config.beltTier).belt, Kind.BELT, end.x, end.y, h)
            entities += e; byTile[end] = e
            junctionKeepFree += listOf(end + h.opposite)
            endTiles += listOf(end + h.ccw, end + h.cw)
            for ((lk, pEnd) in listOf(left to fixedPort(left, end + h.ccw, h.cw, true), right to fixedPort(right, end + h.cw, h.ccw, true))) {
                val p1 = starts.getValue(lk.id).let { (st, sd) -> fixedPort(lk, st, sd.opposite, false) }
                val t1 = terminal(Role.SOURCE, lk, lk.rate, p1); val t2 = terminal(Role.OUTPUT, lk, lk.rate, pEnd)
                linksOfNet[nets.size] = listOf(lk)
                nets += Net(nets.size, lk.item, lk.rate, listOf(t1.id, t2.id), 1)
            }
            paired += left.id; paired += right.id
            continue
        }
        val mid = Tile((sl.x + sr.x) / 2, (sl.y + sr.y) / 2)
        // Junction tiles keep a tile's distance from every link end, so the belts into and out of it have room.
        fun ok(t: Tile) = free(t) && t !in endTiles && t !in junctionKeepFree && Dir.entries.none { t + it in endTiles }
        val cands = ArrayList<Pair<Double, Pair<Tile, Dir>>>()
        for (dy in -14..14) for (dx in -14..14) for (h in Dir.entries) {
            val j = Tile(mid.x + dx, mid.y + dy)
            if (j.manhattan(sl) < 3 || j.manhattan(sr) < 3) continue
            val behind = j + h.opposite
            if (!ok(j) || !ok(j + h.ccw) || !ok(j + h.cw) || !ok(j + h) || behind in byTile || behind in endTiles) continue
            // Room to turn: the corners around the junction are free too.
            if (listOf(-1, 1).any { a -> listOf(-1, 1).any { b -> !free(Tile(j.x + a, j.y + b)) } }) continue
            // Each item best comes from its own side, so neither belt crosses the shared one; facing the consumer
            // matters most (an item from the wrong side only has to cross the shared belt, which an underground does).
            val lv = h.ccw; val sideL = (sl.x - j.x) * lv.dx + (sl.y - j.y) * lv.dy; val sideR = (sr.x - j.x) * lv.dx + (sr.y - j.y) * lv.dy
            val toward = (end.x - j.x) * h.dx + (end.y - j.y) * h.dy
            cands += (j.manhattan(mid) + 0.2 * j.manhattan(end) + (if (sideL > 0) 0 else 3) + (if (sideR < 0) 0 else 3) + (if (toward > 0) 0 else 12)) to (j to h)
        }
        /** Can a belt get from [from] to [to] over free tiles, avoiding [avoid]? (Directions aside: a quick check.) */
        fun reaches(from: Tile, to: Tile, avoid: Set<Tile>): Boolean {
            if (from == to) return true
            val seen = hashSetOf(from); val q = ArrayDeque(listOf(from))
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                for (d in Dir.entries) {
                    val n = c + d
                    if (n == to) return true
                    if (n in seen || n.manhattan(from) > 60 || !free(n) || n in avoid || n in endTiles || n in junctionKeepFree) continue
                    seen += n; q += n
                }
            }
            return false
        }
        var best: Pair<Tile, Dir>? = null
        for ((_, c) in cands.sortedBy { it.first }.take(40)) {
            val (j, h) = c
            val mine = setOf(j, j + h.opposite, j + h.ccw, j + h.cw, j + h)
            // Each item reaches the tile it arrives from (beyond its side of the junction), the shared belt the consumer.
            // (A link's first belt runs straight on out of its port, so the search starts past it.)
            val dl = starts.getValue(left.id).second; val dr = starts.getValue(right.id).second
            if (reaches(sl + dl, j + h.ccw + h.ccw, mine + sl) && reaches(sr + dr, j + h.cw + h.cw, mine + sr) && reaches(j + h + h, end, mine)) { best = c; break }
        }
        val (j, h) = best ?: return StitchAttempt(null, listOf(sl, sr), "no room for the junction of ${left.item} and ${right.item}", badPairs = setOf(l.id))
        // No net: the junction belongs to both items (the validator's shape check is for belts of one item each).
        val e = PlacedEntity(GameConstants.beltTier(config.beltTier).belt, Kind.BELT, j.x, j.y, h)
        entities += e; byTile[j] = e
        junctionKeepFree += listOf(j, j + h.opposite)
        endTiles += listOf(j + h.ccw, j + h.cw, j + h)
        // The two items to the junction's sides, heading into it; the shared belt from the tile after it.
        val pl = fixedPort(left, j + h.ccw, h.cw, true); val pr = fixedPort(right, j + h.cw, h.ccw, true)
        for ((lk, pEnd) in listOf(left to pl, right to pr)) {
            val p1 = starts.getValue(lk.id).let { (st, sd) -> fixedPort(lk, st, sd.opposite, false) }
            val t1 = terminal(Role.SOURCE, lk, lk.rate, p1); val t2 = terminal(Role.OUTPUT, lk, lk.rate, pEnd)
            linksOfNet[nets.size] = listOf(lk)
            nets += Net(nets.size, lk.item, lk.rate, listOf(t1.id, t2.id), 1)
        }
        val rate = left.rate + right.rate
        val ps = fixedPort(left, j + h, h.opposite, false)
        val pe = ends.getValue(left.id).let { (en, ed) -> fixedPort(left, en, ed, true) }
        val t1 = terminal(Role.SOURCE, left, rate, ps); val t2 = terminal(Role.OUTPUT, left, rate, pe)
        linksOfNet[nets.size] = listOf(left, right)
        nets += Net(nets.size, left.item, rate, listOf(t1.id, t2.id), 1)
        paired += left.id; paired += right.id
    }
    corridorLinks.filter { !it.hub && it.id !in paired }.forEach { l ->
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
    val pointedAt = (entities.filter { it.kind.beltFamily }.mapNotNull { it.outputTile } +
        entities.filter { it.kind == Kind.SPLITTER }.flatMap { sp -> sp.rect.tiles().map { it + sp.dir } })
        .filter { t -> t !in blockTiles && t !in startTiles && t !in endTiles }
    val reached = entities.filter { it.kind == Kind.INSERTER }.flatMap { listOf(it.pickupTile, it.dropTile) }.filter { it !in blockTiles }
    // (Heat stays on, so corridor belts keep a side free for a heat pipe, but the blocks' lattice is theirs alone.)
    val stitchConfig = config.copy(blocks = null, laneSharing = false, heat = config.heat.copy(lattice = null))
    // Everything the blocks put outside their boxes (ring ports) is in the way too.
    val entityTiles = entities.flatMap { it.rect.tiles() }
    // A corridor pipe beside a block's pipe or machine would join it: keep those tiles free, except where a link ends.
    val besidePipes = if (corridorLinks.none { it.fluid }) emptyList() else {
        val linkTiles = (starts.values + ends.values).map { it.first }.toSet()
        entities.filter { it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG || it.kind == Kind.MACHINE }.flatMap { it.rect.tiles() }
            .flatMap { t -> Dir.entries.map { t + it } }.filter { it !in blockTiles && it !in linkTiles }
    }
    val routed = route(RouteProblem(stitchPlan, stitchConfig, tuning.route, Placement(W, H, emptyList()),
        obstacles = blockTiles + entityTiles + pointedAt + reached + besidePipes + junctionKeepFree, noTunnel = noTunnel))
    if (!routed.success) {
        // Trouble: conflict tiles, and the ports of every link that did not route.
        val trouble = routed.conflicts + routed.failedNets.flatMap { n ->
            (linksOfNet[n] ?: emptyList()).flatMap { l -> listOfNotNull(starts[l.id]?.first, ends[l.id]?.first) }
        }
        val edges = routed.failedNets.flatMap { n -> (linksOfNet[n] ?: emptyList()).filter { it.from == EDGE || it.to == EDGE } }
            .flatMap { l -> plan.ports.first { it.item == l.item && it.output == (l.to == EDGE) }.specs }
            .flatMap { s -> if (s.edge == "any") Dir.entries.toList() else listOfNotNull(s.edge?.let(Dir::fromName)) }.toSet()
        val unrouted = routed.failedNets.mapNotNull { stitchPlan.nets.getOrNull(it)?.item }
        val badPairs = routed.failedNets.flatMap { n -> linksOfNet[n] ?: emptyList() }.filter { it.pair >= 0 }.map { minOf(it.id, it.pair) }.toSet()
        // STITCH_DEBUG_DIR=<dir>: a render of each failed stitch, the trouble marked, for finding out why.
        System.getenv("STITCH_DEBUG_DIR")?.let { d ->
            runCatching {
                val l = geom.Layout(W, H, entities + routed.entities)
                viz.renderLayout(l, File(d, "stitch_${W}x${H}_${System.nanoTime() % 100000}.png"), viz.RenderOptions(px = 14,
                    errors = trouble.map { validate.ValidationError(validate.ErrorType.UNSUPPORTED, it, "trouble") }, title = "stitch ${W}x$H: ${routed.failedNets.mapNotNull { stitchPlan.nets.getOrNull(it)?.item }}"))
            }
        }
        return StitchAttempt(null, trouble, "${routed.conflicts.size} conflicts, ${routed.failedNets.size} unrouted" +
            (if (unrouted.isNotEmpty()) " $unrouted; ${routed.log.filter { l -> unrouted.any { it in l } }.takeLast(2)}" else ""), edges, badPairs)
    }
    entities += routed.entities.map { it.copy(net = global(it, stitchPlan)) }
    sources += routed.sources.filter { it.port in edgePorts }.map { it.copy(port = -1) }
    outputs += routed.outputs.filter { it.port in edgePorts }.map { it.copy(port = -1) }
    val blockRects = (0 until k).map { b -> Rect(ox[b], oy[b], results[b].w, results[b].h) }
    return StitchAttempt(Stitched(entities, machines, sources, outputs, W, H, blockRects, arr.reserved, results, arr.rot), emptyList(), "ok")
}

/** Test harness on the factory's ports, compaction, poles over everything, block borders, and validation. */
fun finishFactory(data: RootData, config: RunConfiguration, plan: ProductionPlan, s: Stitched): Factory {
    fun finish(
        ents: List<PlacedEntity>, mach: List<PlacedMachine>, srcs: List<SourceSpec>, outs: List<OutputSpec>,
        w: Int, h: Int, rects: List<Rect>, reserved: List<Rect>, notes: MutableList<String>,
    ): Factory {
        // Crackers work only on what other machines leave of their oil: a tank per block and red wires.
        val ctl = route.addSurplusControl(ents, plan, config, w, h, rects)
        notes += ctl.notes
        val harness = if (config.testMode) buildHarness(srcs, ctl.entities, w, h, config, outs) else null
        var all: List<PlacedEntity> = ArrayList(ctl.entities).also { it += harness?.entities ?: emptyList() }
        harness?.warnings?.let { notes += it }
        // Aquilo: one heat network over the whole factory, joining every block's own pipes and heating the corridors
        // (before the poles, unless heat.poles: then after them, so they are heated too).
        fun heatOf(es: List<PlacedEntity>) = power.placeHeatPipes(es, w, h, search.heatPortTiles(config.heat.edge, w, h), config.heat.poles, maxPorts = config.heat.ports)
            .also { r -> if (!r.ok) notes += "heat: ${r.cold.size} entities stay cold"; notes += "heat ports at ${r.ports}" }
        var heat: power.HeatResult? = null
        if (config.heat.mode != "off" && !config.heat.poles) { heat = heatOf(all); all = heat.entities + heat.pipes }
        var poles = emptyList<PlacedEntity>()
        var wires = emptyList<Pair<Int, Int>>()
        val keepFree = reserved.flatMap { it.tiles() }.toSet()
        if (config.power.mode != "none") {
            val extra = (harness?.tiles ?: emptySet()).flatMap { t -> (-2..2).flatMap { dy -> (-2..2).map { dx -> Tile(t.x + dx, t.y + dy) } } }
            val ps = placePoles(all, poleCandidates(w, h, config.power.allowOutsideRing, extra).filter { it !in keepFree }, config.power.pole)
            if (!ps.ok) notes += "power: ${ps.uncovered.size} entities not covered"
            poles = ps.poles; wires = ps.wires
        }
        if (config.heat.mode != "off" && config.heat.poles) {
            // Heat pipes go after the poles here; crossings keep every index, so the pole wires hold.
            val withPoles = all + poles
            heat = heatOf(withPoles)
            all = heat.entities.take(all.size); poles = heat.entities.drop(all.size) + heat.pipes
        }
        val offset = all.size
        // Block borders as floor tiles, so each block shows in the game.
        val marks = if (config.markerTile == "none") emptyList() else (rects + reserved).flatMap { geom.borderTiles(it, config.markerTile) }
        val layout = Layout(w, h, all + poles, wires.map { (a, b) -> a + offset to b + offset }, "factorio-optimizer factory ${w}x$h", marks, ctl.circuitWires)
        val outside = (harness?.tiles ?: emptySet()) + poles.flatMap { it.rect.tiles() }.filter { it.x !in 0 until w || it.y !in 0 until h } +
            srcs.map { it.tile } +   // ring ports (in test mode the harness feeds them instead of listing them)
            (heat?.let { r -> r.ports + (r.entities + r.pipes).filter { it.kind == Kind.HEAT_PIPE }.map { it.tile }.filter { it.x !in 0 until w || it.y !in 0 until h } } ?: emptyList())
        val spec = validationSpec(data, plan, config, Placement(w, h, mach), if (config.testMode) emptyList() else srcs,
            requirePower = config.power.mode != "none", outsideAllowed = outside.toSet(), outputs = outs)
            .copy(heat = heat?.let { validate.HeatSpec(it.ports.toSet(), config.heat.poles) })
        val errors = validate(layout, spec)
        return Factory(layout, errors, score(layout, config), srcs, outs, notes, rects, s.layouts, s.rot, heat?.ports ?: emptyList())
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
    File(out, "best_output_string.txt").writeText(l.toBlueprintString(config.buildQuality()))
    File(out, "best_blueprint.json").writeText(l.toBlueprint(config.buildQuality()).toJson())
    if (!config.testMode) power.testCopy(l, f.sources, f.outputs, config).let { tc ->
        File(out, "test_output_string.txt").writeText(tc.layout.toBlueprintString(config.buildQuality()))
        File(out, "test_feeds.txt").writeText(if (tc.warnings.isEmpty()) "every port fed\n" else tc.warnings.joinToString("\n", postfix = "\n"))
    }
    renderLayout(l, File(out, "best.png"), RenderOptions(errors = f.errors, ports = viz.portLabels(f.sources, f.outputs), title = "factory ${l.w}x${l.h} score ${"%.1f".format(f.score)}"))
    File(out, "ports.json").writeText(strictJson.encodeToString(JsonObject.serializer(), factoryManifest(f)))
    // What stitching could not do as asked (crackers without a tank, poles short, ...).
    File(out, "factory_notes.txt").writeText(f.notes.joinToString("\n", postfix = if (f.notes.isEmpty()) "" else "\n"))
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
        // Aquilo: where heat must be brought in (heat pipe tiles on the ring).
        if (f.heatPorts.isNotEmpty()) putJsonArray("heat_ports") { f.heatPorts.forEach { p -> addJsonObject { put("x", p.x); put("y", p.y); put("edge", edge(p.x, p.y)) } } }
    }
}
