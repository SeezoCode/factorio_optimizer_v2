package viz

import blocks.BlockEngine
import blocks.Command
import blocks.FactoryView
import blocks.RunStatus
import java.io.File

/**
 * The live view of a factory run in a terminal: one row per block (threads on it right now, size, first size, turns,
 * time since its last improvement, time it had, state, * when it sets the factory's size), the run's time and limit,
 * the latest preview factory, and the last events, redrawn twice a second. Keys control the run (the same commands
 * as the control file): q stop (q again: stop now), Q stop now, s stitch a preview, + / - limit five minutes more or
 * less, u no limit, p pause or resume, arrows (or j k) select a block, f focus on it, x skip it, l / L more or fewer
 * lanes. Needs a Unix terminal (stty); everything also goes to factory.txt.
 */
class LiveView(private val title: String) : FactoryView {
    @Volatile override var live = false
        private set
    private var engine: BlockEngine? = null
    private var saved: String? = null
    private var renderer: Thread? = null
    @Volatile private var selected = 0
    @Volatile private var top = 0
    private var lastStopKey = 0L
    private val restore = Thread { restoreTerminal() }

    companion object {
        /** A real terminal on a Unix system, not a pipe, a file or a dumb terminal. */
        fun available(): Boolean =
            System.console()?.isTerminal == true && System.getenv("TERM").let { it != null && it != "dumb" } &&
                !System.getProperty("os.name").lowercase().contains("win") && File("/dev/tty").exists()

        private fun sh(cmd: String): String? = runCatching {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0) out else null
        }.getOrNull()
    }

    override fun attach(engine: BlockEngine) {
        this.engine = engine
        saved = sh("stty -g < /dev/tty")
        sh("stty -icanon -echo min 1 < /dev/tty")
        Runtime.getRuntime().addShutdownHook(restore)
        print("\u001b[?1049h\u001b[?25l")   // alternate screen, hidden cursor
        System.out.flush()
        live = true
        renderer = Thread {
            while (live) {
                runCatching { draw() }
                Thread.sleep(500)
            }
        }.apply { isDaemon = true; start() }
        Thread { readKeys() }.apply { isDaemon = true; start() }
    }

    override fun detach() {
        if (!live) return
        live = false
        renderer?.join(2000)
        restoreTerminal()
        runCatching { Runtime.getRuntime().removeShutdownHook(restore) }
        engine?.status()?.let { s ->
            println("blocks after ${clock(s.elapsed)}: " + s.blocks.joinToString(", ") { "${it.block}: ${it.w}x${it.h}" })
            if (s.factoryW > 0) println("factory: ${s.factoryW}x${s.factoryH} (${s.factoryW * s.factoryH} tiles); details in factory.txt")
        }
    }

    private fun restoreTerminal() {
        print("\u001b[?25h\u001b[?1049l")
        System.out.flush()
        saved?.let { sh("stty $it < /dev/tty") } ?: sh("stty sane < /dev/tty")
    }

    private fun readKeys() {
        val input = System.`in`
        while (live) {
            val c = runCatching { input.read() }.getOrDefault(-1)
            if (c < 0) return
            if (!live) return
            val e = engine ?: continue
            val n = e.status().blocks.size
            when (c.toChar()) {
                'q' -> {
                    val now = System.currentTimeMillis()
                    e.apply(if (now - lastStopKey < 3000 || e.stopping) Command.StopNow else Command.Stop)
                    lastStopKey = now
                }
                'Q' -> e.apply(Command.StopNow)
                's' -> e.apply(Command.Stitch)
                '+', '=' -> e.apply(Command.Limit(300.0, null))
                '-', '_' -> e.apply(Command.Limit(-300.0, null))
                'u' -> e.apply(Command.Limit(null, null, none = true))
                'p' -> e.apply(if (e.paused) Command.Resume else Command.Pause)
                'j' -> selected = (selected + 1).coerceAtMost(n - 1)
                'k' -> selected = (selected - 1).coerceAtLeast(0)
                'f' -> e.apply(Command.Focus(if (e.status().blocks.getOrNull(selected)?.state == "focus") null else selected))
                'x' -> e.apply(Command.Skip(selected, e.status().blocks.getOrNull(selected)?.state != "skipped"))
                'l' -> e.apply(Command.Lanes(e.status().lanes + 1))
                'L' -> e.apply(Command.Lanes(e.status().lanes - 1))
                '\u001b' -> {   // arrows: ESC [ A / B
                    if (runCatching { input.read() }.getOrDefault(-1) == '['.code) when (runCatching { input.read() }.getOrDefault(-1)) {
                        'A'.code -> selected = (selected - 1).coerceAtLeast(0)
                        'B'.code -> selected = (selected + 1).coerceAtMost(n - 1)
                    }
                }
                else -> {}
            }
        }
    }

    private fun clock(s: Double): String {
        if (s < 0) return "-"
        val t = s.toLong()
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun size(): Pair<Int, Int> = sh("stty size < /dev/tty")?.split(" ")?.let { (it[0].toIntOrNull() ?: 40) to (it.getOrNull(1)?.toIntOrNull() ?: 120) } ?: (40 to 120)

    private fun draw() {
        val e = engine ?: return
        val s: RunStatus = e.status()
        val (rows, cols) = size()
        val sb = StringBuilder("\u001b[H")
        fun line(text: String, style: String = "") {
            val t = if (text.length > cols) text.substring(0, cols) else text
            sb.append(style).append(t).append(if (style.isNotEmpty()) "\u001b[0m" else "").append("\u001b[K\n")
        }
        val limit = if (s.left < 0) "no limit" else "${clock(s.left)} left"
        line("$title   ${clock(s.elapsed)} elapsed, $limit   ${s.lanes} lane(s) on ${s.threads} thread(s)" +
            (if (s.paused) "   PAUSED" else "") + (if (s.stopping) "   STOPPING" else ""), "\u001b[1m")
        line("factory " + (if (s.factoryW > 0) "${s.factoryW}x${s.factoryH} (${s.factoryW * s.factoryH} tiles), stitched ${clock(s.sinceStitch)} ago" else "not stitched yet") +
            (if (s.stitching) "   stitching a preview..." else "") + "   ${s.phase}")
        line("")
        line(" thr  block  mach  weight   size     first    turns  better ago   spent   state", "\u001b[4m")
        val listRows = (rows - 6 - minOf(5, s.events.size) - 2).coerceAtLeast(3)
        if (selected < top) top = selected
        if (selected >= top + listRows) top = selected - listRows + 1
        s.blocks.drop(top).take(listRows).forEachIndexed { i, b ->
            val idx = top + i
            val thr = if (b.threads > 0) "[${b.threads}]" else "[ ]"
            val sz = if (b.w > 0) "${b.w}x${b.h}" else "-"
            val first = if (b.firstW > 0) "${b.firstW}x${b.firstH}" else "-"
            val text = " %-4s %5d  %4d  %6.1f   %-8s %-8s %5d  %10s  %6s   %s%s".format(thr, b.block, b.machines, b.weight, sz, first, b.turns,
                clock(b.sinceBetter), clock(b.spent), b.state, if (b.critical) " *" else "")
            line(text, when {
                idx == selected -> "\u001b[7m"
                b.threads > 0 -> "\u001b[32m"
                b.state == "skipped" || b.state == "parked" -> "\u001b[2m"
                else -> ""
            })
        }
        if (s.blocks.size > top + listRows) line("  ... ${s.blocks.size - top - listRows} more (arrows to scroll)") else line("")
        line("* sets the factory's size", "\u001b[2m")
        s.events.takeLast(5).forEach { line(it, "\u001b[2m") }
        line(if (e.finalStage) "building the final factory (progress in factory.txt)   q stop now, keeping the best factory so far"
            else "q stop  Q stop now  s stitch  +/- limit 5 min  u no limit  p pause  arrows select  f focus  x skip  l/L lanes", "\u001b[1m")
        sb.append("\u001b[J")
        print(sb)
        System.out.flush()
    }
}
