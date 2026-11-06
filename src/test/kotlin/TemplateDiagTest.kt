import blocks.Templates
import config.RunConfiguration
import config.strictJson
import plan.ProductionPlan
import java.io.File
import kotlin.test.Test

/**
 * Why blocks of a factory run got no template, skipped unless TEMPLATE_DIAG=<factory output dir> is set (with
 * TEMPLATE_JOB=<job file>): prints each block's ports and the template notes.
 */
class TemplateDiagTest {
    @Test
    fun diag() {
        val dir = File(System.getenv("TEMPLATE_DIAG") ?: return)
        val config = RunConfiguration.load(System.getenv("TEMPLATE_JOB")!!).let { it.copy(blocks = null) }
        dir.listFiles { f -> f.name.startsWith("block_") }!!.sortedBy { it.name.removePrefix("block_").toInt() }.forEach { b ->
            val plan = strictJson.decodeFromString(ProductionPlan.serializer(), File(b, "plan.json").readText())
            val notes = ArrayList<String>()
            val t0 = System.currentTimeMillis()
            val ts = Templates.layouts(TestData.data, config, plan) { notes += it }
            println("${b.name} (${System.currentTimeMillis() - t0} ms): ${plan.machines.groupingBy { it.recipe }.eachCount()} ports ${plan.ports.map { "${if (it.output) "out" else "in"} ${it.item} ${it.specs.map { s -> s.edge }}" }} -> ${ts.map { "${it.w}x${it.h}" }} ${ts.firstOrNull()?.notes} $notes")
        }
    }

    /** The recipes a factory run keeps in blocks of their own and their caps, with TEMPLATE_CAPS=<plan.json> (and TEMPLATE_JOB). */
    @Test
    fun caps() {
        val f = File(System.getenv("TEMPLATE_CAPS") ?: return)
        val config = RunConfiguration.load(System.getenv("TEMPLATE_JOB")!!)
        val plan = strictJson.decodeFromString(ProductionPlan.serializer(), f.readText())
        val cfg = config.blocks!!
        val caps = Templates.columnRecipes(plan, config, cfg.templateIngredients, cfg.templateMinMachines, cfg.maxMachines / 2)
        plan.machines.groupBy { it.recipe }.forEach { (r, ms) ->
            println("CAP $r: ${ms.size} x ${ms[0].machine} ${ms[0].w}x${ms[0].h} -> ${caps[r]?.let { if (it == Int.MAX_VALUE) "all" else "$it" } ?: "-"} (capacity ${if (ms.all(Templates::columnMachine)) Templates.columnCapacity(ms, config) else -1})")
        }
    }
}
