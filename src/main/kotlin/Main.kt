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
 *                                 kept, missing blocks are searched, then it floorplans and stitches again (the job
 *                                 is the one saved there unless --config is given)
 */
fun main(args: Array<String>) {
    val flags = args.filter { it.startsWith("--") }.associate {
        val eq = it.indexOf('=')
        if (eq < 0) it.removePrefix("--") to "true" else it.substring(2, eq) to it.substring(eq + 1)
    }
    val known = setOf("config", "tuning", "engine", "out", "calibrate", "budget", "verify-kit", "print-tuning", "render", "resume")
    (flags.keys - known).takeIf { it.isNotEmpty() }?.let { error("Unknown flags: $it (known: $known)") }

    flags["render"]?.let { dir -> renderOutput(java.io.File(dir)); return }

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

    val resume = flags["resume"]
    val loaded = flags["config"]?.let { RunConfiguration.load(it) }
        ?: resume?.let { blocks.savedJob(java.io.File(it)) ?: error("--resume: no saved job in $it (give --config)") }
        ?: RunConfiguration.load("config.json")
    val config = flags["engine"]?.let { loaded.copy(engine = it).also { c -> c.validate() } } ?: loaded
    // tuning.json on the classpath holds the measured defaults; the job's tuning section overrides them.
    val tuning = (flags["tuning"]?.let { Tuning.load(it) } ?: Tuning.defaults()).withOverrides(config.tuning)
    if (flags.containsKey("print-tuning")) {
        println(tuning.toJson())
        return
    }
    val data = loadRootData()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val out = resume ?: flags["out"] ?: "src/main/resources/solves/$stamp - sa"
    val plan = buildPlan(data, config, tuning)
    plan.write("$out/plan.json")
    java.io.File("$out/tuning.json").writeText(tuning.toJson())   // what this run used, for reproducing it
    println("Stage 1: ${plan.machines.size} machines, ${plan.nets.size} nets, ${plan.ports.size} ports -> $out/plan.json")
    val cfg = config.blocks
    if (cfg != null && plan.machines.size > cfg.maxMachines) {
        val f = blocks.runFactory(data, config, tuning, plan, java.io.File(out), resume = resume != null)
        println(if (f == null) "factory: no result" else "factory: ${f.layout.w}x${f.layout.h} score ${"%.1f".format(f.score)}, " +
            if (f.success) "valid" else "${f.errors.size} validation errors")
    } else search.runSearch(data, config, tuning, plan, out)
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
