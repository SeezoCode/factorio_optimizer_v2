package blocks

import geom.Rect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import search.Search
import search.TrialResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max

/*
 * Block search in turns. Every block gets one layout first and the factory is stitched from them (a preview, saved
 * like any result); then blocks take turns trying smaller boxes, each turn one box size from its best layout, on a
 * few lanes side by side that share the threads. Search time is shared fairly by weight: twice for blocks that set
 * the factory's width or height (on its critical rows and columns) and their shape variants, a fifth for parked ones.
 * A new preview is stitched whenever the blocks' total area has dropped enough. It runs until the time limit or a
 * stop; the limit, pausing, focus on one block and skipping blocks can be changed while it runs (control file,
 * live view keys). status.json in the output folder always holds what the live view shows.
 */

/** One block in the live view and status.json. */
@Serializable
data class BlockStatus(
    val block: Int, val machines: Int, val weight: Double,
    /** Threads working on it right now (0: waiting). */
    val threads: Int,
    val w: Int, val h: Int, val firstW: Int, val firstH: Int,
    val turns: Int, val stalls: Int,
    /** Seconds since its last smaller or better layout (-1: none yet). */
    val sinceBetter: Double,
    /** Seconds of search it has had. */
    val spent: Double,
    val critical: Boolean,
    /** "first", "refining", "parked", "skipped", "focus", "done". */
    val state: String,
    /** 0 for the block itself, else its shape variant (aspect times [factor]). */
    val variant: Int = 0,
    val factor: Double = 1.0,
)

@Serializable
data class RunStatus(
    val elapsed: Double,
    /** Seconds until the block search stops (-1: until stopped). */
    val left: Double,
    val paused: Boolean,
    val stopping: Boolean,
    val phase: String,
    val factoryW: Int, val factoryH: Int,
    /** Seconds since the last preview was stitched (-1: none yet). */
    val sinceStitch: Double,
    val stitching: Boolean,
    val lanes: Int, val threads: Int,
    val blocks: List<BlockStatus>,
    val events: List<String>,
)

private val statusJson = Json { prettyPrint = true; encodeDefaults = true }

/** Live controls, from the control file or keys. */
sealed class Command {
    object Stop : Command()
    object StopNow : Command()
    object Stitch : Command()
    object Pause : Command()
    object Resume : Command()
    data class Limit(val delta: Double?, val absolute: Double?, val none: Boolean = false) : Command()
    data class Focus(val block: Int?, val variant: Int = 0) : Command()
    data class Skip(val block: Int, val on: Boolean, val variant: Int = 0) : Command()
    data class Lanes(val n: Int) : Command()

    companion object {
        /** "3" is block 3, "3:1" its first shape variant: (block index, variant). */
        private fun row(s: String): Pair<Int, Int>? {
            val parts = s.split(":")
            val b = parts[0].toIntOrNull() ?: return null
            val v = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return (b - 1) to v
        }

        /** "stop", "stop now", "stitch", "pause", "resume", "limit +600|-300|3600|none", "focus 3|3:1|off", "skip 3", "unskip 3", "lanes 2". */
        fun parse(line: String): Command? {
            val w = line.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (w.isEmpty()) return null
            return when (w[0]) {
                "stop" -> if (w.getOrNull(1) == "now") StopNow else Stop
                "quit" -> StopNow
                "stitch" -> Stitch
                "pause" -> Pause
                "resume", "continue" -> Resume
                "limit" -> w.getOrNull(1)?.let { a ->
                    when {
                        a == "none" || a == "off" -> Limit(null, null, none = true)
                        a.startsWith("+") || a.startsWith("-") -> a.toDoubleOrNull()?.let { Limit(it, null) }
                        else -> a.toDoubleOrNull()?.let { Limit(null, it) }
                    }
                }
                "focus" -> w.getOrNull(1)?.let { if (it == "off" || it == "none") Focus(null) else row(it)?.let { (b, v) -> Focus(b, v) } }
                "skip" -> w.getOrNull(1)?.let(::row)?.let { (b, v) -> Skip(b, true, v) }
                "unskip" -> w.getOrNull(1)?.let(::row)?.let { (b, v) -> Skip(b, false, v) }
                "lanes" -> w.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }?.let { Lanes(it) }
                else -> null
            }
        }
    }
}

/**
 * Blocks whose box lies on a chain of blocks across the factory with no room to spare (within [slack] tiles), in
 * either direction: shrinking one of them can shrink the factory; shrinking any other cannot.
 */
fun criticalBlocks(rects: List<Rect>, w: Int, h: Int, gap: Int, slack: Int = 2): Set<Int> {
    fun axis(pos: (Rect) -> Int, len: (Rect) -> Int, opos: (Rect) -> Int, olen: (Rect) -> Int, total: Int): Set<Int> {
        val n = rects.size
        fun overlap(a: Rect, b: Rect) = opos(a) < opos(b) + olen(b) && opos(b) < opos(a) + olen(a)
        val order = rects.indices.sortedBy { pos(rects[it]) }
        // Earliest start with every block pushed against its predecessors (a gap between neighbours), and latest.
        val early = IntArray(n)
        for (i in order) early[i] = rects.indices.filter { j -> pos(rects[j]) + len(rects[j]) <= pos(rects[i]) && overlap(rects[i], rects[j]) }
            .maxOfOrNull { j -> early[j] + len(rects[j]) + gap } ?: 0
        val late = IntArray(n)
        for (i in order.reversed()) late[i] = rects.indices.filter { j -> pos(rects[i]) + len(rects[i]) <= pos(rects[j]) && overlap(rects[i], rects[j]) }
            .minOfOrNull { j -> late[j] - len(rects[i]) - gap } ?: (total - len(rects[i]))
        val longest = rects.indices.maxOfOrNull { early[it] + len(rects[it]) } ?: 0
        val spare = total - longest
        return rects.indices.filter { late[it] - early[it] - spare <= slack }.toSet()
    }
    return axis({ it.x }, { it.w }, { it.y }, { it.h }, w) + axis({ it.y }, { it.h }, { it.x }, { it.w }, h)
}

/** One row of the rotation: a block (variant 0) or one of its shape variants (its aspect times [factor]). */
data class Job(val block: Int, val variant: Int, val factor: Double)

class BlockEngine(
    private val bps: List<BlockPlan>,
    /** The rotation: every block, then its shape variants. */
    private val jobs: List<Job>,
    /** A job's search, refining [initial] when given. */
    private val searchOf: (job: Job, initial: TrialResult?, printer: (String) -> Unit) -> Search,
    /** Per job: a layout to start from (resumed), or null. */
    private val initial: List<TrialResult?>,
    private val weights: List<Double>,
    private val threads: Int,
    lanes0: Int,
    /** Seeds per turn wanted (search.seeds_per_size); a turn runs at least one per thread and at most two per thread. */
    private val seeds: Int,
    private val parkAfter: Int,
    /** Search time a shape variant gets before it is parked like a block that stopped improving (null: no cap). */
    private val variantCapS: Double?,
    private val restitchShare: Double,
    /** When the block search stops (epoch ms; Long.MAX_VALUE: until stopped). Changed by [Command.Limit]. */
    var limit: Long,
    private val out: File,
    private val say: (String) -> Unit,
    /** Search output (one line per trial and box), by job; the live view keeps it off the screen. */
    private val blockPrinter: (Job, String) -> Unit,
    /** Stitches the factory from the layouts each block has (its own, then its shape variants'); block boxes and size, or null. */
    private val stitch: (List<List<TrialResult>>) -> Triple<List<Rect>, Int, Int>?,
    private val corridor: Int,
    /** Per block: ready-made layouts (templates, see Templates.kt), offered next to what the search finds. */
    private val extras: List<List<TrialResult>> = emptyList(),
    /** Search share of a block that has a template (its template already counts as a layout). */
    private val extraShare: Double = 1.0,
    /**
     * Once every block has a layout, stop improving blocks so the stitch has every core (search.stop_after: the first
     * valid factory is what is wanted).
     */
    private val firstOnly: Boolean = false,
    /** With [firstOnly]: how long blocks keep refining after the last first layout before the pause (ms). */
    private val refineMs: Long = 0,
) {
    /** When every block first had a layout (epoch ms), or -1. */
    private var allFirstAt = -1L
    private val n = bps.size
    private val m = jobs.size
    private val t0 = System.currentTimeMillis()
    private val searches = arrayOfNulls<Search>(m)
    private val running = IntArray(m)            // threads per job right now
    private val spentMs = LongArray(m)
    /** When the job's current turn started (0: not running). */
    private val turnStart = LongArray(m)
    /** Search time so far, the turn under way included. */
    private fun spent(j: Int): Long = spentMs[j] + (turnStart[j].takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0L)
    private val firstSize = arrayOfNulls<Pair<Int, Int>>(m)
    private val betterAt = LongArray(m) { -1 }
    private val skipped = BooleanArray(m)
    @Volatile private var focus: Int? = null
    @Volatile var paused = false; private set
    @Volatile var stopping = false; private set
    @Volatile var stopNow = false; private set
    @Volatile private var lanes = lanes0.coerceIn(1, max(1, threads))
    @Volatile private var critical: Set<Int> = emptySet()
    @Volatile private var stitchRequested = false
    @Volatile private var stitchingNow = false
    @Volatile private var lastStitch = -1L
    @Volatile private var stitchedArea = Long.MAX_VALUE
    @Volatile private var factory: Pair<Int, Int> = 0 to 0
    @Volatile private var phase = "first layouts"
    private val events = ArrayDeque<String>()
    private val lock = Any()
    private val statusFile = File(out, "status.json")
    private val controlFile = File(out, "control")

    /** Commands from the control file, one per line; the file is removed once read. */
    private fun pollControl() {
        if (!controlFile.exists()) return
        val lines = runCatching { controlFile.readLines() }.getOrDefault(emptyList())
        controlFile.delete()
        lines.forEach { l -> Command.parse(l)?.let(::apply) ?: if (l.isNotBlank()) event("control: unknown command '${l.trim()}'") else Unit }
    }

    private fun jobBest(j: Int): TrialResult? = searches[j]?.best ?: initial[j]
    private fun extrasOf(b: Int): List<TrialResult> = extras.getOrNull(b) ?: emptyList()
    /** Block [b]'s best layout: its search's, or a template of it when that is smaller (or the search has none yet). */
    fun best(b: Int): TrialResult? = (listOfNotNull(jobBest(b)) + extrasOf(b)).minByOrNull { it.area }
    /** Block [b]'s best layout is a template. */
    private fun templated(b: Int): Boolean = best(b)?.let { r -> extrasOf(b).any { it === r } } == true
    /**
     * Every layout block [b] has: its best, then each shape variant's (the best standing in for a variant without
     * one), then its search's own and its templates when they are not the best already.
     */
    fun layouts(b: Int): List<TrialResult> {
        val own = best(b) ?: return emptyList()
        val vs = jobs.indices.filter { jobs[it].block == b && jobs[it].variant > 0 }.map { jobBest(it) ?: own }
        val more = (listOfNotNull(jobBest(b)) + extrasOf(b)).filter { r -> r !== own && vs.none { it === r } }
        return listOf(own) + vs + more
    }
    private fun jobOf(block: Int, variant: Int) = jobs.indexOfFirst { it.block == block && it.variant == variant }.takeIf { it >= 0 }
    private fun label(j: Int) = jobs[j].let { if (it.variant == 0) "block ${it.block + 1}" else "block ${it.block + 1} x${"%.2f".format(it.factor)}" }

    private fun event(msg: String) {
        say(msg)
        synchronized(events) { events.addLast("[%6.0fs] %s".format((System.currentTimeMillis() - t0) / 1000.0, msg)); while (events.size > 8) events.removeFirst() }
    }

    fun apply(c: Command) {
        when (c) {
            Command.Stop, Command.StopNow -> if (finalStage) {
                event("stopped during the final factory; the best factory so far is saved")
                kotlin.system.exitProcess(0)   // the live view's shutdown hook restores the terminal
            } else if (c == Command.StopNow || stopping) stopAll(now = true)
            else { stopping = true; event("stop: finishing the current turns, then the final factory") }
            Command.Stitch -> { stitchRequested = true; event("stitch requested") }
            Command.Pause -> { paused = true; event("paused: no new turns") }
            Command.Resume -> { paused = false; event("resumed") }
            is Command.Limit -> {
                limit = when {
                    c.none -> Long.MAX_VALUE
                    c.absolute != null -> t0 + (c.absolute * 1000).toLong()
                    limit == Long.MAX_VALUE -> limit
                    else -> max(System.currentTimeMillis(), limit + (c.delta!! * 1000).toLong())
                }
                event(if (limit == Long.MAX_VALUE) "limit: until stopped" else "limit: %.0f s left".format((limit - System.currentTimeMillis()) / 1000.0))
            }
            is Command.Focus -> {
                val j = c.block?.let { jobOf(it, c.variant) }
                focus = j
                if (j == null) { event("focus off"); return }
                // A turn it is in now runs on fewer threads: cut it short (after the trials under way) and start
                // again on every thread.
                if (running[j] in 1 until threads) searches[j]?.extendTo(System.currentTimeMillis())
                event("focus on ${label(j)}: every thread from its next turn" + if (running[j] in 1 until threads) " (its current turn is cut short)" else "")
            }
            is Command.Skip -> jobOf(c.block, c.variant)?.let { j -> skipped[j] = c.on; event("${label(j)} ${if (c.on) "skipped" else "back in turn"}") }
            is Command.Lanes -> { lanes = c.n.coerceIn(1, max(1, threads)); event("lanes: $lanes") }
        }
    }

    /** Stops every block search now (search.stop_after: the factory has its valid layouts). */
    fun stopForGood() = stopAll(true)

    private fun stopAll(now: Boolean) {
        stopping = true
        if (now) { stopNow = true; event("stopping now") }
        searches.forEach { it?.extendTo(System.currentTimeMillis()) }
    }

    private fun timeUp() = stopping || System.currentTimeMillis() >= limit

    private fun stateOf(j: Int): String = when {
        skipped[j] -> "skipped"
        focus == j -> "focus"
        jobs[j].variant == 0 && jobBest(j) == null && templated(jobs[j].block) -> "template"
        jobBest(j) == null -> "first"
        searches[j]?.done == true -> "done"
        parked(j) -> "parked"
        else -> "refining"
    }

    /** Stopped improving for park_after turns, or a shape variant past its shape_variant_s. */
    private fun parked(j: Int): Boolean = (searches[j]?.stalls ?: 0) >= parkAfter ||
        (jobs[j].variant > 0 && variantCapS != null && spent(j) >= variantCapS * 1000)

    fun status(): RunStatus = RunStatus(
        elapsed = (System.currentTimeMillis() - t0) / 1000.0,
        left = if (limit == Long.MAX_VALUE) -1.0 else max(0.0, (limit - System.currentTimeMillis()) / 1000.0),
        paused = paused, stopping = stopping, phase = phase,
        factoryW = factory.first, factoryH = factory.second,
        sinceStitch = if (lastStitch < 0) -1.0 else (System.currentTimeMillis() - lastStitch) / 1000.0,
        stitching = stitchingNow, lanes = lanes, threads = threads,
        blocks = jobs.indices.map { j ->
            val job = jobs[j]
            val r = if (job.variant == 0) best(job.block) else jobBest(j)
            BlockStatus(job.block + 1, bps[job.block].globalIds.size, weights[job.block], running[j], r?.w ?: 0, r?.h ?: 0,
                firstSize[j]?.first ?: 0, firstSize[j]?.second ?: 0, searches[j]?.steps ?: 0, searches[j]?.stalls ?: 0,
                if (betterAt[j] < 0) -1.0 else (System.currentTimeMillis() - betterAt[j]) / 1000.0, spent(j) / 1000.0,
                job.block in critical, stateOf(j), job.variant, job.factor)
        },
        events = synchronized(events) { events.toList() },
    )

    /**
     * Which job gets the next turn, or null: blocks without a layout in order (the preview needs one of each), then
     * shape variants without one, then fair shares: the job with the least search time for its weight goes next,
     * weight 2 for what sets the factory's size (a block and its shape variants alike), 1 for the rest, a fifth of
     * that once parked. Nothing starves; what matters more just gets more.
     */
    private fun next(): Int? {
        val f = focus
        if (f != null && !firstLayoutsOverdue()) return f.takeIf { running[it] == 0 && !skipped[it] && searches[it]?.done != true }
        val free = (0 until m).filter { running[it] == 0 && !skipped[it] && searches[it]?.done != true }
        // A block with a template has a layout already: it goes after blocks that have none.
        free.firstOrNull { jobs[it].variant == 0 && best(jobs[it].block) == null }?.let { return it }
        free.firstOrNull { jobBest(it) == null && extrasOf(jobs[it].block).isEmpty() }?.let { return it }
        fun weight(j: Int) = (if (jobs[j].block in critical) 2.0 else 1.0) * (if (parked(j)) 0.2 else 1.0) *
            (if (extrasOf(jobs[j].block).isNotEmpty()) extraShare else 1.0)
        return free.minWithOrNull(compareBy<Int>({ spent(it) / weight(it) }, { searches[it]?.steps ?: 0 }))
    }

    /** Time is up but some blocks still have no layout: they run whatever focus, pause or skip say (the factory needs them). */
    private fun firstLayoutsOverdue(): Boolean = timeUp() && !stopping && (0 until n).any { best(it) == null }

    private fun totalArea(): Long = (0 until n).sumOf { b -> best(b)?.let { it.w.toLong() * it.h } ?: 0L }

    /** Runs until the limit or a stop; afterwards [best] and [layouts] hold each block's layouts. */
    fun run() {
        for (j in 0 until m) initial[j]?.let { firstSize[j] = it.w to it.h }
        val pool = Executors.newFixedThreadPool(max(1, threads) + 1)
        val stitcher = Executors.newSingleThreadExecutor()
        val active = HashMap<Int, Future<*>>()
        var stitchJob: Future<*>? = null
        var lastStatus = 0L
        try {
            while (true) {
                // Finished turns.
                synchronized(lock) {
                    active.entries.removeIf { (j, f) -> if (f.isDone) { running[j] = 0; runCatching { f.get() }.onFailure { e -> event("${label(j)}: turn failed with $e") }; true } else false }
                }
                if (stopNow) break
                // A block whose search gave up without a layout (no size worked): no factory can be stitched, so the
                // run ends once the turns under way finish. (It waited for that layout for good: Py utility science's
                // blocks 20 and 36 gave up, the rest had layouts, nothing could start, every worker idle.)
                val gaveUp = (0 until n).filter { b -> best(b) == null && jobOf(b, 0)?.let { searches[it]?.done == true } == true }
                if (gaveUp.isNotEmpty() && !stopping) {
                    event("${gaveUp.joinToString(", ") { "block ${it + 1}" }}: no layout at any size; no factory can be stitched, stopping")
                    stopAll(false)
                }
                // Every block gets its first layout, even past the limit (the factory needs one of each).
                val allFirst = (0 until n).all { best(it) != null }
                if ((stopping || (timeUp() && allFirst)) && active.isEmpty()) break
                if (allFirst && phase == "first layouts") {
                    phase = "refining"; allFirstAt = System.currentTimeMillis()
                    if (!firstOnly) { stitchRequested = true; event("every block has a layout; stitching a preview") }
                    else event("every block has a layout; refining them for ${refineMs / 1000} s before the stitch")
                }
                // First factory wanted: after the refining time, the blocks pause and the stitch gets every core
                // (smaller blocks, shorter corridors).
                if (firstOnly && !paused && allFirstAt >= 0 && System.currentTimeMillis() - allFirstAt >= refineMs) {
                    paused = true; stitchRequested = true
                    searches.forEach { it?.extendTo(System.currentTimeMillis()) }
                    event("blocks paused: stitching with every core")
                }
                // A preview when asked, or when the blocks shrank enough (at most every 2 minutes).
                val area = totalArea()
                val due = allFirst && lastStitch >= 0 && area <= stitchedArea * (1 - restitchShare) && System.currentTimeMillis() - lastStitch > 120_000
                if ((stitchRequested || due) && allFirst && stitchJob?.isDone != false && !timeUp()) {
                    stitchRequested = false
                    val layouts = (0 until n).map { layouts(it) }
                    stitchingNow = true
                    event("stitching a preview")
                    stitchJob = stitcher.submit {
                        val began = System.currentTimeMillis()
                        fun took() = "%.0f s".format((System.currentTimeMillis() - began) / 1000.0)
                        try {
                            val r = stitch(layouts)
                            lastStitch = System.currentTimeMillis()
                            stitchedArea = layouts.sumOf { it[0].w.toLong() * it[0].h }
                            if (r != null) {
                                val (rects, fw, fh) = r
                                factory = fw to fh
                                critical = criticalBlocks(rects, fw, fh, corridor)
                                event("preview: ${fw} x ${fh} (${fw * fh} tiles) in ${took()}; blocks setting its size: ${critical.sorted().map { it + 1 }}")
                            } else {
                                event("preview: did not stitch (${took()})")
                                // Blocks paused for the first factory: the final stitch tries again, with more floorplans.
                                if (firstOnly) stopAll(false)
                            }
                        } finally { stitchingNow = false }
                    }
                }
                // New turns on free lanes; a focused job gets every thread.
                val overdue = firstLayoutsOverdue()
                if (overdue && (focus != null || paused)) { focus = null; paused = false; event("time is up: focus and pause end so every block gets its first layout") }
                if (overdue) (0 until n).filter { skipped[it] && best(it) == null }.forEach { skipped[it] = false; event("${label(it)} has no layout yet; it runs after all") }
                if (!paused && !stopping && (!timeUp() || !allFirst)) {
                    val laneCount = if (focus != null) 1 else lanes
                    while (active.size < laneCount || (focus != null && focus !in active.keys && running[focus!!] == 0)) {
                        val j = synchronized(lock) { next() }?.takeIf { !timeUp() || (jobs[it].variant == 0 && best(jobs[it].block) == null) } ?: break
                        val t = if (focus == j) threads else max(1, threads / laneCount + if (active.size < threads % laneCount) 1 else 0)
                        running[j] = t
                        active[j] = pool.submit { turn(j, t) }
                        if (focus == j) break
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastStatus > 1000) {
                    lastStatus = now
                    pollControl()
                    writeStatus(statusFile)
                }
                Thread.sleep(100)
            }
        } finally {
            searches.forEach { it?.extendTo(System.currentTimeMillis()) }
            pool.shutdown(); stitcher.shutdown()
            pool.awaitTermination(if (stopNow) 1 else 10, TimeUnit.MINUTES)
            stitcher.awaitTermination(if (stopNow) 1 else 10, TimeUnit.MINUTES)
            phase = "final factory"
            writeStatus(statusFile)
        }
    }

    /** status.json, replaced in one move so a reader never sees half of it. */
    private fun writeStatus(f: File) = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(statusJson.encodeToString(RunStatus.serializer(), status()))
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** One turn of job [j] on [t] threads: one box size, with one or two seeds per thread. */
    private fun turn(j: Int, t: Int) {
        val job = jobs[j]
        val s = searches[j] ?: searchOf(job, initial[j]) { line -> blockPrinter(job, line) }.also { searches[j] = it }
        // A block without a layout yet may run past the limit until it has one.
        s.extendTo(if (best(job.block) == null && job.variant == 0) max(limit, System.currentTimeMillis() + 120_000) else limit)
        val before = s.best
        val start = System.currentTimeMillis()
        turnStart[j] = start
        try { s.step(t, seeds.coerceIn(t, 2 * t)) } finally {
            spentMs[j] += System.currentTimeMillis() - start
            turnStart[j] = 0
        }
        val now = s.best
        if (now != null && now !== before) {
            betterAt[j] = System.currentTimeMillis()
            if (firstSize[j] == null) firstSize[j] = now.w to now.h
            onBetter(job.block, layouts(job.block))
            event(if (before == null) "${label(j)}: first layout ${now.w} x ${now.h}" else "${label(j)}: ${now.w} x ${now.h} (was ${before.w} x ${before.h})")
        }
    }

    /** The block search is over and the final factory is being built: the live view shows it, a stop exits at once. */
    fun finalPhase(what: String) {
        phase = what; finalStage = true; event(what)
        // The control file and status.json keep working while the final factory is built.
        Thread {
            while (true) { runCatching { pollControl(); writeStatus(statusFile) }; Thread.sleep(1000) }
        }.apply { isDaemon = true; start() }
    }
    @Volatile var finalStage = false; private set

    /** A factory was saved (a preview or the final one, or while compacting). */
    fun factorySaved(w: Int, h: Int) { factory = w to h; lastStitch = System.currentTimeMillis() }

    /** Called with a block's layouts (its own, then its shape variants') whenever one of them improves (saved for --resume). */
    var onBetter: (Int, List<TrialResult>) -> Unit = { _, _ -> }
}
