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
            val ts = Templates.layouts(TestData.data, config, plan) { notes += it }
            println("${b.name}: ${plan.machines.groupingBy { it.recipe }.eachCount()} ports ${plan.ports.map { "${if (it.output) "out" else "in"} ${it.item} ${it.specs.map { s -> s.edge }}" }} -> ${ts.map { "${it.w}x${it.h}" }} $notes")
        }
    }
}
