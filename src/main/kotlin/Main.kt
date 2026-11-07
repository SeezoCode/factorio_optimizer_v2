import config.RunConfiguration
import config.Tuning
import data.loadRootData
import emit.toLayout
import plan.buildPlan
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Entry point. Flags:
 *   --config=<resource or path>   job config (default: config.json resource)
 *   --tuning=<resource or path>   tuning knobs (default: tuning.json resource, else built-in defaults); the
 *                                 job's own `tuning` section is merged over them
 *   --print-tuning                print every tuning knob with its value (after the job's overrides) and stop
 *   --out=<dir>                   output directory (default: src/main/resources/solves/<time> - sa)
 *   --calibrate=<cfg>,<cfg>,...   refit q(k) and the area factor from short searches of these jobs and
 *                                 write src/main/resources/tuning.json (with --budget=<s> per job, default 60)
 *   --verify-kit=<dir>            write the in-game verification blueprints (see docs/VERIFY.md)
 *   --render=<dir>                redraw <dir>/best.png from its best_output_string.txt and ports.json (with ports)
 *   --resume=<dir>                continue a factory-of-blocks run in <dir>: its saved split and finished blocks are
 *                                 kept (and refined), missing blocks are searched, then it floorplans and stitches
 *                                 again (the job is the one saved there unless --config is given); a bare --resume
 *                                 continues the run in --out
 *   --compact=<s>                 seconds to compact a factory of blocks (blocks.compact_s), e.g. with --resume
 *   --until-stopped               factory of blocks: search blocks until stopped (q in the live view, or
 *                                 `echo stop > <out>/control`) instead of until the time budget
 *   --plain                       factory of blocks: line-by-line output instead of the live view (the default
 *                                 when not in a terminal); <out>/status.json and <out>/control work either way
 *   --ui [--port=<n>]             the browser UI at http://localhost:8765 (see ui/Server.kt): edit jobs, start and
 *                                 watch runs, look at layouts and blocks
 */
fun main(args: Array<String>) {
    val flags = args.filter { it.startsWith("--") }.associate {
        val eq = it.indexOf('=')
        if (eq < 0) it.removePrefix("--") to "true" else it.substring(2, eq) to it.substring(eq + 1)
    }
    val known = setOf("config", "tuning", "engine", "out", "calibrate", "budget", "verify-kit", "print-tuning", "render", "resume", "compact", "plain", "until-stopped", "ui", "port")
    (flags.keys - known).takeIf { it.isNotEmpty() }?.let { error("Unknown flags: $it (known: $known)") }

    flags["render"]?.let { dir -> renderOutput(java.io.File(dir)); return }

    if (flags.containsKey("ui")) { ui.Server(java.io.File("."), flags["port"]?.toInt() ?: 8765).start(); return }

    flags["verify-kit"]?.let { dir ->
        emit.VerifyKit.write(java.io.File(dir))
        println("wrote ${emit.VerifyKit.cases.size} verification blueprints to $dir")
        return
    }

    flags["calibrate"]?.let { list ->
        val data = loadRootData()
        val base = flags["tuning"]?.let { Tuning.load(it) } ?: Tuning()
        val jobs = list.split(",").map { it.substringAfterLast('/').removeSuffix(".json") to RunConfiguration.load(it) }
        val res = search.calibrate(data, jobs, base, flags["budget"]?.toDouble() ?: 60.0, java.io.File(flags["out"] ?: "src/main/resources/solves/calibration"))
        res.report.forEach { println(it) }
        search.writeTuning(res.tuning, java.io.File("src/main/resources/tuning.json"))
        println("wrote src/main/resources/tuning.json from ${res.samples} routed chains")
        return
    }

    // A bare --resume continues the run in --out.
    val resume = flags["resume"]?.let { r ->
        if (r != "true") r.also { d -> flags["out"]?.takeIf { java.io.File(it).canonicalPath != java.io.File(d).canonicalPath }?.let { o ->
            println("(--resume=$d wins over --out=$o: the run continues in $d)") } }
        else flags["out"] ?: fail("--resume needs the run's folder: --resume=<dir>, or --resume with --out=<dir>")
    }
    val loaded = flags["config"]?.let { RunConfiguration.load(it) }
        ?: resume?.let { blocks.savedJob(java.io.File(it)) ?: fail("--resume: no saved run in $it (no resume/job.json there)") }
        ?: RunConfiguration.load("config.json")
    val config0 = (flags["engine"]?.let { loaded.copy(engine = it).also { c -> c.validate() } } ?: loaded).let { c ->
        flags["compact"]?.let { s -> c.copy(blocks = c.blocks?.copy(compactS = s.toDouble())) } ?: c
    }
    // tuning.json on the classpath holds the measured defaults; the job's tuning section overrides them.
    val tuning = (flags["tuning"]?.let { Tuning.load(it) } ?: Tuning.defaults()).withOverrides(config0.tuning)
    if (flags.containsKey("print-tuning")) {
        println(tuning.toJson())
        return
    }
    // Quality production: items and recipes of every quality the targets need (see plan/Quality.kt).
    val (data, config) = plan.withQualityProduction(loadRootData(config0.data ?: "recipes.json"), config0)

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val out = resume ?: flags["out"] ?: "src/main/resources/solves/$stamp - sa"
    val plan = buildPlan(data, config, tuning)
    if (resume != null) {
        val cfgB = config.blocks
        if (cfgB == null || plan.machines.size <= cfgB.maxMachines)
            fail("--resume continues a factory of blocks only; this job ${if (cfgB == null) "has no blocks section" else "fits in one block (${plan.machines.size} machines)"}")
        blocks.resumeProblem(java.io.File(out), plan)?.let { fail("--resume: $it") }
    }
    plan.write("$out/plan.json")
    java.io.File("$out/tuning.json").writeText(tuning.toJson())   // what this run used, for reproducing it
    println("Stage 1: ${plan.machines.size} machines, ${plan.nets.size} nets, ${plan.ports.size} ports -> $out/plan.json")
    val cfg = config.blocks
    if (cfg != null && plan.machines.size > cfg.maxMachines) {
        // The live view in a terminal (unless --plain); --until-stopped searches blocks until a stop (key or control file).
        val view = if (!flags.containsKey("plain") && viz.LiveView.available()) viz.LiveView(java.io.File(out).name) else null
        if (view == null && !flags.containsKey("plain")) println(
            "(live view off: no terminal here. `./gradlew run` runs the program inside Gradle without one; start it with " +
                "`./optimize --config=...` for the live view and keys. Steer it meanwhile with `echo stop > $out/control` " +
                "(see the readme); --plain hides this note.)")
        val f = blocks.runFactory(data, config, tuning, plan, java.io.File(out), resume = resume != null, view = view,
            untilStopped = flags.containsKey("until-stopped"))
        println(if (f == null) "factory: no result" else "factory: ${f.layout.w}x${f.layout.h} score ${"%.1f".format(f.score)}, " +
            if (f.success) "valid" else "${f.errors.size} validation errors")
    } else search.runSearch(data, config, tuning, plan, out)
}

/** Stops with a message, without a stack trace. */
private fun fail(msg: String): Nothing {
    System.err.println("error: $msg")
    kotlin.system.exitProcess(2)
}

/** Redraws [dir]/best.png from the blueprint and ports.json a search or factory run left there, ports marked. */
private fun renderOutput(dir: java.io.File) {
    val ports = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(dir, "ports.json").readText()).let { it as kotlinx.serialization.json.JsonObject }
    fun num(o: kotlinx.serialization.json.JsonObject, k: String) = (o.getValue(k) as kotlinx.serialization.json.JsonPrimitive).content
    val box = ports.getValue("box") as kotlinx.serialization.json.JsonObject
    val w = num(box, "w").toInt(); val h = num(box, "h").toInt()
    val layout = emit.decodeBlueprint(java.io.File(dir, "best_output_string.txt").readText().trim()).toLayout(w, h)
    fun labels(key: String, input: Boolean) = (ports[key] as? kotlinx.serialization.json.JsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())).map { e ->
        val o = e as kotlinx.serialization.json.JsonObject
        val lane = when ((o["lane"] as? kotlinx.serialization.json.JsonPrimitive)?.content) { "left" -> 0; "right" -> 1; else -> -1 }
        viz.PortLabel(geom.Tile(num(o, "x").toInt(), num(o, "y").toInt()), num(o, "item"), num(o, "rate").toDouble(), input, lane, num(o, "fluid") == "true")
    }
    viz.renderLayout(layout, java.io.File(dir, "best.png"), viz.RenderOptions(title = "${w}x$h", ports = labels("inputs", true) + labels("outputs", false)))
    println("rendered ${java.io.File(dir, "best.png")}")
}
