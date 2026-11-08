import config.RunConfiguration
import config.Tuning
import config.strictJson
import plan.ProductionPlan
import plan.buildPlan
import plan.withRawByproducts
import search.runTrial
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test

/**
 * Diagnostic, skipped unless DIAG=<job path>,<size>,<seeds> is set (the job's "data" game data is used; DIAG_PLAN=<plan.json>
 * runs a saved plan, such as a factory block's, alone; DIAG_TUNING=<file> overrides tuning.json,
 * DIAG_FIRST=<seed> picks the first seed, DIAG_AT=<x>,<y>,<radius> lists the entities around a tile):
 * runs single trials and prints why routing fails. Use `./gradlew test --tests DiagTest --rerun -i`.
 */
class DiagTest {
    @Test
    fun diag() {
        val spec = System.getenv("DIAG") ?: return
        val (path, size, seeds) = spec.split(",")
        val job = RunConfiguration.load(path)
        // DIAG_PLAN=<plan.json>: a saved plan (e.g. one block of a factory, run alone) instead of planning the job.
        val config = if (System.getenv("DIAG_PLAN") != null) job.copy(blocks = null, testMode = false, reserved = emptyList(), forbiddenTiles = emptyList()) else job
        // The job's own game data (a modded export), else the built-in one.
        val data = withRawByproducts(job.data?.let { data.loadRootData(it) } ?: TestData.data, config)
        val tuning = (System.getenv("DIAG_TUNING")?.takeIf { it.isNotBlank() }?.let { Tuning.load(it) } ?: Tuning.defaults()).withOverrides(config.tuning)
        val plan = System.getenv("DIAG_PLAN")?.let { strictJson.decodeFromString(ProductionPlan.serializer(), File(it).readText()) }
            ?: buildPlan(data, config, tuning)
        println("DIAG machines ${plan.machines.size} nets ${plan.nets.size} terminals ${plan.terminals.size}")
        plan.machines.groupBy { it.recipe }.forEach { (r, ms) -> println("DIAG   $r x${ms.size}") }
        // DIAG_PLAN_ONLY: stop after planning.
        if (System.getenv("DIAG_PLAN_ONLY") != null) return
        // DIAG_ITEM=<item>: the plan's terminals and nets of one item.
        System.getenv("DIAG_ITEM")?.let { item ->
            plan.terminals.filter { it.item == item }.forEach { println("DIAG   terminal $it ${it.machine?.let { m -> plan.machines[m].recipe } ?: ""}") }
            plan.nets.filter { it.item == item }.forEach { println("DIAG   net $it") }
        }
        val first = System.getenv("DIAG_FIRST")?.toLongOrNull() ?: 1L
        for (seed in first until first + seeds.toLong()) {
            val s = size.toInt()
            val r = runTrial(data, plan, config, tuning, s, s, seed)
            println("DIAG seed $seed: ${r.stage} ${r.millis}ms score ${r.score}")
            r.notes.forEach { println("DIAG   note $it") }
            r.route?.log?.filter { "split" in it }?.forEach { println("DIAG   log $it") }
            r.errors.take(8).forEach { println("DIAG   error $it") }
            System.getenv("DIAG_NET")?.toIntOrNull()?.let { net -> r.layout?.entities?.filter { it.net == net }?.forEach { en ->
                println("DIAG   net$net: ${en.name} ${en.kind} (${en.x},${en.y}) ${en.dir}") } }
            // DIAG_AT=<x>,<y>,<radius>: every entity near a tile.
            System.getenv("DIAG_AT")?.split(",")?.map { it.trim().toInt() }?.let { (ax, ay, rad) -> r.layout?.entities?.filter { en ->
                en.rect.tiles().any { t -> kotlin.math.abs(t.x - ax) <= rad && kotlin.math.abs(t.y - ay) <= rad } }?.forEach { en ->
                println("DIAG   at: ${en.name} ${en.kind} (${en.x},${en.y}) ${en.dir} ${en.recipe ?: ""} ${en.item ?: ""} net ${en.net}") } }
            if (System.getenv("DIAG_DUMP") != null) r.layout?.let { l ->
                r.errors.filter { it.type == validate.ErrorType.UNREACHABLE || it.type.name.startsWith("FLUID") }.take(2).forEach { e ->
                    l.entities.filter { en -> en.rect.tiles().any { t -> kotlin.math.abs(t.x - e.tile.x) <= 3 && kotlin.math.abs(t.y - e.tile.y) <= 3 } }
                        .sortedWith(compareBy({ it.y }, { it.x }))
                        .forEach { en -> println("DIAG   near ${e.tile}: ${en.name} ${en.kind} (${en.x},${en.y}) ${en.dir} net ${en.net} ${en.item ?: ""}") }
                }
            }
            r.route?.let { rt ->
                println("DIAG   failed nets ${rt.failedNets.map { plan.nets[it].item }} overuse ${rt.overuse} iters ${rt.iterations} chains ${rt.chains}")
                rt.log.takeLast(8).forEach { println("DIAG   log $it") }
                rt.log.filter { it.startsWith("shared belt") }.forEach { println("DIAG   $it") }
                // With trace_detail: the iteration closest to a solution, and what its conflicts were.
                val iters = rt.log.filter { it.startsWith("iter ") }
                fun total(l: String) = Regex("tile (\\d+).* dead (\\d+) chan (\\d+) unrouted (\\d+)").find(l)
                    ?.groupValues?.drop(1)?.map { it.toInt() }?.let { (t, d, c, u) -> t + d + c + 10 * u } ?: Int.MAX_VALUE
                iters.minByOrNull(::total)?.let { println("DIAG   best $it") }
                iters.lastOrNull()?.let { println("DIAG   last $it") }
                rt.log.filter { it.startsWith("fluid") || it.startsWith("net ") }.map { it.replace(Regex("\\d+ backtracks.*"), "") }
                    .groupingBy { it }.eachCount().forEach { (k, v) -> println("DIAG   count $v x $k") }
            }
            val px = System.getenv("DIAG_PX")?.toIntOrNull() ?: 16
            r.layout?.let { renderLayout(it, File("build/renders/diag/${s}_$seed.png"), RenderOptions(errors = r.errors, px = px, title = "${r.stage} seed $seed")) }
            if (r.layout == null) r.route?.let { rt ->
                val over = rt.log.mapNotNull { Regex("overused \\((-?\\d+),(-?\\d+)\\): (.*)").find(it) }
                    .map { m -> validate.ValidationError(validate.ErrorType.OVERLAP, geom.Tile(m.groupValues[1].toInt(), m.groupValues[2].toInt()), m.groupValues[3].take(60)) }
                renderLayout(geom.Layout(s, s, rt.entities), File("build/renders/diag/${s}_${seed}_fail.png"), RenderOptions(errors = over, px = 24, title = "route seed $seed: ${rt.failedNets.map { plan.nets[it].item }}"))
            }
        }
    }
}

/**
 * Diagnostic, skipped unless RECHECK=<factory run folder> is set: validates every block layout saved in its
 * resume/ folder again with today's validator and prints each block's errors (RECHECK_JOB=<job> when the saved
 * job is not wanted). Use `./gradlew test --tests RecheckTest --rerun -i`.
 */
class RecheckTest {
    @Test
    fun recheck() {
        val dir = File(System.getenv("RECHECK") ?: return)
        val config = System.getenv("RECHECK_JOB")?.let { RunConfiguration.load(it) } ?: blocks.savedJob(dir)!!
        val tuning = Tuning.defaults().withOverrides(config.tuning)
        val split = blocks.loadSplit(dir)!!
        var bad = 0
        val only = System.getenv("RECHECK_BLOCK")?.toInt()
        split.blocks.indices.forEach { b ->
            if (only != null && b + 1 != only) return@forEach
            val bp = blocks.blockPlan(split.plan, split, b, tuning)
            val all = blocks.loadBlock(dir, b)
            if (all.isEmpty()) return@forEach println("RECHECK block ${b + 1}: no layout")
            all.forEachIndexed { v, r ->
            val l = r.layout!!
            val outside = l.entities.flatMap { it.rect.tiles() }.filter { it.x !in 0 until l.w || it.y !in 0 until l.h }.toSet()
            val spec = search.validationSpec(TestData.data, bp.plan, config, r.placement!!, r.sources, requirePower = false,
                outsideAllowed = outside, outputs = r.outputs)
            val errors = validate.validate(l, spec)
            if (errors.isNotEmpty()) bad++
            println("RECHECK block ${b + 1} layout ${v + 1} (${l.w}x${l.h}): ${errors.size} errors ${errors.groupingBy { it.type }.eachCount()}")
            errors.take(6).forEach { println("RECHECK   $it") }
            }
        }
        println("RECHECK $bad block layouts fail")
    }
}

/** Diagnostic, skipped unless SURPLUS=<factory run folder>,<block>: cracking control on one saved block layout. */
class SurplusDiagTest {
    @Test
    fun surplus() {
        val (path, block) = (System.getenv("SURPLUS") ?: return).split(",")
        val dir = File(path)
        val config = blocks.savedJob(dir)!!
        val split = blocks.loadSplit(dir)!!
        val b = block.toInt() - 1
        val bp = blocks.blockPlan(split.plan, split, b, Tuning.defaults().withOverrides(config.tuning))
        val l = blocks.loadBlock(dir, b).first().layout!!
        println("SURPLUS control ${config.fluids.surplusOnly} mode ${config.fluids.mode}")
        println("SURPLUS crackers ${l.entities.count { it.recipe?.contains("cracking") == true }} pipes ${l.entities.groupingBy { "${it.kind}:${it.fluid}" }.eachCount().filterKeys { it.startsWith("PIPE") }}")
        val ctl = route.addSurplusControl(l.entities, if (System.getenv("FULL") != null) split.plan else bp.plan, config, l.w, l.h, listOf(geom.Rect(0, 0, l.w, l.h)))
        println("SURPLUS tanks ${ctl.entities.count { it.kind == geom.Kind.TANK }} wires ${ctl.circuitWires.size} notes ${ctl.notes}")
    }
}
