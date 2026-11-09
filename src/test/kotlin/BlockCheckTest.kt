import blocks.blockPlan
import blocks.loadBlock
import blocks.loadSplit
import config.RunConfiguration
import config.Tuning
import search.validationSpec
import validate.validate
import java.io.File
import kotlin.test.Test

/**
 * Diagnostic, skipped unless BLOCKCHECK=<job path>,<run folder>,<block (1-based)> is set: validates each layout saved
 * for that block of a factory run against its block plan, as the block search does, and prints the errors.
 * Use `./gradlew test --tests BlockCheckTest --rerun -i`.
 */
class BlockCheckTest {
    @Test
    fun check() {
        val spec = System.getenv("BLOCKCHECK") ?: return
        val (job, out, block) = spec.split(",")
        val config0 = RunConfiguration.load(job)
        // The job's own game data, made as the program makes it (quality, raw by-products, launch products).
        val (data1, config) = plan.withQualityProduction(data.loadRootData(config0.data ?: "recipes.json"), config0)
        val gameData = plan.withLaunchProducts(plan.withRawByproducts(data1, config))
        val tuning = Tuning.defaults().withOverrides(config.tuning)
        val split = loadSplit(File(out)) ?: error("no split in $out")
        val b = block.trim().toInt() - 1
        val bp = blockPlan(split.plan, split, b, tuning, config.blocks?.sharedOutputs ?: false)
        val bconf = config.copy(blocks = null, testMode = false, reserved = emptyList(), forbiddenTiles = emptyList())
        loadBlock(File(out), b).forEachIndexed { k, r ->
            val l = r.layout ?: return@forEachIndexed
            val outside = l.entities.flatMap { it.rect.tiles() }.filter { it.x !in 0 until l.w || it.y !in 0 until l.h }.toSet()
            val s = validationSpec(gameData, bp.plan, bconf, r.placement!!, r.sources, requirePower = false, outsideAllowed = outside, outputs = r.outputs)
            val errs = validate(l, s)
            println("BLOCKCHECK layout $k ${l.w}x${l.h}: ${errs.size} errors")
            errs.groupBy { it.type }.forEach { (t, es) -> println("BLOCKCHECK   $t x${es.size}: ${es.first()}") }
            // As the stitched factory sees it: the full plan (global machine ids) and the factory's own config.
            val gp = r.placement.copy(machines = r.placement.machines.map { it.copy(id = bp.globalIds[it.id]) })
            val g = validationSpec(gameData, split.plan, config, gp, r.sources, requirePower = false, outsideAllowed = outside, outputs = r.outputs)
            val gerrs = validate(l, g)
            println("BLOCKCHECK   with the factory's plan and config: ${gerrs.size} errors")
            gerrs.groupBy { it.type }.forEach { (t, es) -> println("BLOCKCHECK     $t x${es.size}: ${es.first()}") }
            val diff = s.machines.entries.firstOrNull { (t, m) -> g.machines[t] != m }
            if (diff != null) println("BLOCKCHECK   machine spec differs at ${diff.key}:\n  block   ${diff.value}\n  factory ${g.machines[diff.key]}")
        }
    }
}
