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
 * few lanes side by side that share the threads. Blocks that set the factory's width or height (on its critical
 * rows and columns) go first; a block that stops improving is parked and runs again only when nothing else is left.
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
    data class Focus(val block: Int?) : Command()
    data class Skip(val block: Int, val on: Boolean) : Command()
    data class Lanes(val n: Int) : Command()

    companion object {
        /** "stop", "stop now", "stitch", "pause", "resume", "limit +600|-300|3600|none", "focus 3|off", "skip 3", "unskip 3", "lanes 2". */
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
                "focus" -> w.getOrNull(1)?.let { if (it == "off" || it == "none") Focus(null) else it.toIntOrNull()?.let { b -> Focus(b - 1) } }
                "skip" -> w.getOrNull(1)?.toIntOrNull()?.let { Skip(it - 1, true) }
                "unskip" -> w.getOrNull(1)?.toIntOrNull()?.let { Skip(it - 1, false) }
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

class BlockEngine(
    private val bps: List<BlockPlan>,
    /** A block's search, refining [initial] when given. */
    private val searchOf: (b: Int, initial: TrialResult?, printer: (String) -> Unit) -> Search,
    private val initial: List<TrialResult?>,
    private val weights: List<Double>,
    private val threads: Int,
    lanes0: Int,
    private val parkAfter: Int,
    private val restitchShare: Double,
    /** When the block search stops (epoch ms; Long.MAX_VALUE: until stopped). Changed by [Command.Limit]. */
    var limit: Long,
    private val out: File,
    private val say: (String) -> Unit,
    /** Block search output (one line per trial and box); the live view keeps it off the screen. */
    private val blockPrinter: (Int, String) -> Unit,
    /** Stitches the factory from one layout per block; its size, block boxes and size, or null if it failed. */
    private val stitch: (List<TrialResult>) -> Triple<List<Rect>, Int, Int>?,
    private val corridor: Int,
) {
    private val n = bps.size
    private val t0 = System.currentTimeMillis()
    private val searches = arrayOfNulls<Search>(n)
    private val running = IntArray(n)            // threads per block right now
    private val spentMs = LongArray(n)
    private val firstSize = arrayOfNulls<Pair<Int, Int>>(n)
    private val betterAt = LongArray(n) { -1 }
    private val skipped = BooleanArray(n)
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

    fun best(b: Int): TrialResult? = searches[b]?.best ?: initial[b]

    private fun event(msg: String) {
        say(msg)
        synchronized(events) { events.addLast("[%6.0fs] %s".format((System.currentTimeMillis() - t0) / 1000.0, msg)); while (events.size > 8) events.removeFirst() }
    }

    fun apply(c: Command) {
        when (c) {
            Command.Stop -> { if (stopping) stopAll(now = true) else { stopping = true; event("stop: finishing the current turns, then the final factory") } }
            Command.StopNow -> stopAll(now = true)
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
            is Command.Focus -> { focus = c.block?.takeIf { it in 0 until n }; event(focus?.let { "focus on block ${it + 1}" } ?: "focus off") }
            is Command.Skip -> if (c.block in 0 until n) { skipped[c.block] = c.on; event("block ${c.block + 1} ${if (c.on) "skipped" else "back in turn"}") }
            is Command.Lanes -> { lanes = c.n.coerceIn(1, max(1, threads)); event("lanes: $lanes") }
        }
    }

    private fun stopAll(now: Boolean) {
        stopping = true
        if (now) { stopNow = true; event("stopping now") }
        searches.forEach { it?.extendTo(System.currentTimeMillis()) }
    }

    private fun timeUp() = stopping || System.currentTimeMillis() >= limit

    private fun stateOf(b: Int): String = when {
        skipped[b] -> "skipped"
        focus == b -> "focus"
        best(b) == null -> "first"
        searches[b]?.done == true -> "done"
        (searches[b]?.stalls ?: 0) >= parkAfter -> "parked"
        else -> "refining"
    }

    fun status(): RunStatus = RunStatus(
        elapsed = (System.currentTimeMillis() - t0) / 1000.0,
        left = if (limit == Long.MAX_VALUE) -1.0 else max(0.0, (limit - System.currentTimeMillis()) / 1000.0),
        paused = paused, stopping = stopping, phase = phase,
        factoryW = factory.first, factoryH = factory.second,
        sinceStitch = if (lastStitch < 0) -1.0 else (System.currentTimeMillis() - lastStitch) / 1000.0,
        stitching = stitchingNow, lanes = lanes, threads = threads,
        blocks = (0 until n).map { b ->
            val r = best(b)
            BlockStatus(b + 1, bps[b].globalIds.size, weights[b], running[b], r?.w ?: 0, r?.h ?: 0,
                firstSize[b]?.first ?: 0, firstSize[b]?.second ?: 0, searches[b]?.steps ?: 0, searches[b]?.stalls ?: 0,
                if (betterAt[b] < 0) -1.0 else (System.currentTimeMillis() - betterAt[b]) / 1000.0, spentMs[b] / 1000.0,
                b in critical, stateOf(b))
        },
        events = synchronized(events) { events.toList() },
    )

    /** Which block gets the next turn, or null: first layouts in order, then critical before others, unparked before parked, fewest turns first. */
    private fun next(): Int? {
        val f = focus
        if (f != null) return f.takeIf { running[it] == 0 && !skipped[it] && searches[it]?.done != true }
        val free = (0 until n).filter { running[it] == 0 && !skipped[it] && searches[it]?.done != true }
        free.firstOrNull { best(it) == null }?.let { return it }
        return free.minWithOrNull(compareBy<Int>({ if (it in critical) 0 else 1 }, { if ((searches[it]?.stalls ?: 0) >= parkAfter) 1 else 0 }, { searches[it]?.steps ?: 0 }))
    }

    private fun totalArea(): Long = (0 until n).sumOf { b -> best(b)?.let { it.w.toLong() * it.h } ?: 0L }

    /** Runs until the limit or a stop; afterwards [best] holds each block's best layout (null if it never found one). */
    fun run() {
        for (b in 0 until n) initial[b]?.let { firstSize[b] = it.w to it.h }
        val pool = Executors.newFixedThreadPool(max(1, threads))
        val stitcher = Executors.newSingleThreadExecutor()
        val active = HashMap<Int, Future<*>>()
        var stitchJob: Future<*>? = null
        val statusFile = File(out, "status.json")
        val controlFile = File(out, "control")
        var lastStatus = 0L
        try {
            while (true) {
                // Finished turns.
                synchronized(lock) {
                    active.entries.removeIf { (b, f) -> if (f.isDone) { running[b] = 0; runCatching { f.get() }.onFailure { e -> event("block ${b + 1}: turn failed with $e") }; true } else false }
                }
                if (stopNow) break
                // Every block gets its first layout, even past the limit (the factory needs one of each).
                val allFirst = (0 until n).all { best(it) != null || skipped[it] }
                if ((stopping || (timeUp() && allFirst)) && active.isEmpty()) break
                if (allFirst && phase == "first layouts") { phase = "refining"; stitchRequested = true; event("every block has a layout; stitching a preview") }
                // A preview when asked, or when the blocks shrank enough (at most every 2 minutes).
                val area = totalArea()
                val due = allFirst && lastStitch >= 0 && area <= stitchedArea * (1 - restitchShare) && System.currentTimeMillis() - lastStitch > 120_000
                if ((stitchRequested || due) && allFirst && stitchJob?.isDone != false && !timeUp()) {
                    stitchRequested = false
                    val layouts = (0 until n).map { best(it)!! }
                    stitchingNow = true
                    stitchJob = stitcher.submit {
                        try {
                            val r = stitch(layouts)
                            lastStitch = System.currentTimeMillis()
                            stitchedArea = layouts.sumOf { it.w.toLong() * it.h }
                            if (r != null) {
                                val (rects, fw, fh) = r
                                factory = fw to fh
                                critical = criticalBlocks(rects, fw, fh, corridor)
                                event("preview: ${fw} x ${fh} (${fw * fh} tiles); blocks setting its size: ${critical.sorted().map { it + 1 }}")
                            } else event("preview: did not stitch")
                        } finally { stitchingNow = false }
                    }
                }
                // New turns on free lanes.
                if (!paused && !stopping && (!timeUp() || !allFirst)) {
                    val laneCount = if (focus != null) 1 else lanes
                    while (active.size < laneCount) {
                        val b = synchronized(lock) { next() }?.takeIf { !timeUp() || best(it) == null } ?: break
                        val t = if (focus != null) threads else max(1, threads / laneCount + if (active.size < threads % laneCount) 1 else 0)
                        running[b] = t
                        active[b] = pool.submit { turn(b, t) }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastStatus > 1000) {
                    lastStatus = now
                    // Commands from the control file, one per line; the file is removed once read.
                    if (controlFile.exists()) {
                        val lines = runCatching { controlFile.readLines() }.getOrDefault(emptyList())
                        controlFile.delete()
                        lines.forEach { l -> Command.parse(l)?.let(::apply) ?: if (l.isNotBlank()) event("control: unknown command '${l.trim()}'") else Unit }
                    }
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

    /** One turn of block [b] on [t] threads: one box size. */
    private fun turn(b: Int, t: Int) {
        val s = searches[b] ?: searchOf(b, initial[b]) { line -> blockPrinter(b, line) }.also { searches[b] = it }
        // A block without a layout yet may run past the limit until it has one.
        s.extendTo(if (s.best == null) max(limit, System.currentTimeMillis() + 120_000) else limit)
        val before = s.best
        val start = System.currentTimeMillis()
        s.step(t)
        spentMs[b] += System.currentTimeMillis() - start
        val now = s.best
        if (now != null && now !== before) {
            betterAt[b] = System.currentTimeMillis()
            if (firstSize[b] == null) firstSize[b] = now.w to now.h
            onBetter(b, now)
            event(if (before == null) "block ${b + 1}: first layout ${now.w} x ${now.h}" else "block ${b + 1}: ${now.w} x ${now.h} (was ${before.w} x ${before.h})")
        }
    }

    /** Called with every new best layout of a block (saved for --resume). */
    var onBetter: (Int, TrialResult) -> Unit = { _, _ -> }
}
