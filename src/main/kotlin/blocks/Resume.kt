package blocks

import config.RunConfiguration
import config.strictJson
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import kotlinx.serialization.Serializable
import place.PlacedMachine
import place.Placement
import plan.ProductionPlan
import search.TrialResult
import validate.OutputSpec
import validate.SourceSpec
import java.io.File

/*
 * What a factory run keeps so it can be resumed (`--resume=<dir>`): the job, the split (blocks, links with their
 * port sides, the rebalanced plan, the preliminary floorplan) and every finished layout of each block, all in
 * `<dir>/resume/`. A resumed run searches only the blocks that have no layout yet, then floorplans, stitches and
 * validates as usual, so a factory whose stitching failed, or a run stopped half way, does not search its blocks
 * again.
 */

@Serializable
private data class SEntity(
    val name: String, val kind: String, val x: Int, val y: Int, val dir: String, val recipe: String? = null, val item: String? = null,
    val fluid: String? = null, val net: Int? = null, val mirror: Boolean = false, val drain: Boolean = false, val w: Int, val h: Int,
)

@Serializable
private data class SMachine(val id: Int, val x: Int, val y: Int, val rot: String, val mirror: Boolean = false)

@Serializable
private data class SPort(val x: Int, val y: Int, val item: String, val rate: Double, val fluid: Boolean, val lane: Int = -1, val port: Int)

/** One finished block layout: everything stitching needs. */
@Serializable
private data class SLayout(
    val w: Int, val h: Int, val score: Double, val seed: Long, val entities: List<SEntity>, val machines: List<SMachine>,
    val sources: List<SPort>, val outputs: List<SPort>,
)

@Serializable
private data class SLink(
    val id: Int, val item: String, val rate: Double, val from: Int, val to: Int, val fluid: Boolean,
    val routed: Boolean, val fromSide: String? = null, val toSide: String? = null, val pair: Int = -1,
)

@Serializable
private data class SSplit(
    val machines: Int, val blocks: List<List<Int>>, val links: List<SLink>, val warnings: List<String>,
    val boxes: List<Rect>, val w: Int, val h: Int, val touches: List<List<String>>,
    val plus: List<Int>? = null, val minus: List<Int>? = null, val shape: List<Int>? = null,
    val plan: ProductionPlan, val ports: Int,
    val partitionCost: Double? = null, val partitionPorts: Int? = null, val greedyCost: Double? = null, val greedyPorts: Int? = null,
)

private fun dirOf(out: File) = File(out, "resume").also { it.mkdirs() }

/** Saves the job and the split, and removes block layouts of an earlier split. */
fun saveSplit(out: File, config: RunConfiguration, split: Split) {
    val d = dirOf(out)
    File(d, "job.json").writeText(strictJson.encodeToString(RunConfiguration.serializer(), config))
    d.listFiles { f -> f.name.startsWith("block_") }?.forEach { it.delete() }
    val g = split.geometry
    val p = split.partition
    val s = SSplit(
        split.plan.machines.size, split.blocks, split.links.map { SLink(it.id, it.item, it.rate, it.from, it.to, it.fluid, it.routed, it.fromSide, it.toSide, it.pair) },
        split.warnings, g.boxes, g.w, g.h, g.touches.map { t -> t.map { it.name } },
        g.state?.plus?.toList(), g.state?.minus?.toList(), g.state?.shape?.toList(), split.plan, split.ports,
        p?.cost, p?.ports, p?.greedyCost, p?.greedyPorts,
    )
    File(d, "split.json").writeText(strictJson.encodeToString(SSplit.serializer(), s))
}

/** Saves every layout block [b] has (the first is the one its search found; reshaped ones follow). */
fun saveBlock(out: File, b: Int, layouts: List<TrialResult>) {
    val ls = layouts.map { r ->
        SLayout(r.w, r.h, r.score, r.seed,
            r.layout!!.entities.map { e -> SEntity(e.name, e.kind.name, e.x, e.y, e.dir.name, e.recipe, e.item, e.fluid, e.net, e.mirror, e.drain, e.w, e.h) },
            r.placement!!.machines.map { SMachine(it.id, it.x, it.y, it.rot.name, it.mirror) },
            r.sources.map { SPort(it.tile.x, it.tile.y, it.item, it.rate, it.fluid, it.lane, it.port) },
            r.outputs.map { SPort(it.tile.x, it.tile.y, it.item, it.rate, it.fluid, -1, it.port) })
    }
    File(dirOf(out), "block_${b + 1}.json").writeText(strictJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SLayout.serializer()), ls))
}

/** Saves a whole state: the split and every block's layouts (after a merge renumbers the blocks). */
fun saveAll(out: File, config: RunConfiguration, split: Split, variants: List<List<TrialResult>>) {
    saveSplit(out, config, split)
    variants.forEachIndexed { b, vs -> saveBlock(out, b, vs) }
}

/** The job a run in [out] was started with, or null. */
fun savedJob(out: File): RunConfiguration? =
    File(out, "resume/job.json").takeIf { it.exists() }?.let { RunConfiguration.parse(it.readText()) }

/** The split saved in [out], or null. */
fun loadSplit(out: File): Split? {
    val f = File(out, "resume/split.json").takeIf { it.exists() } ?: return null
    val s = strictJson.decodeFromString(SSplit.serializer(), f.readText())
    val state = if (s.plus != null && s.minus != null && s.shape != null) FpState(s.plus.toIntArray(), s.minus.toIntArray(), s.shape.toIntArray()) else null
    val g = BlockGeometry(s.boxes, s.w, s.h, s.touches.map { t -> t.map(Dir::valueOf).toSet() }, state)
    val links = s.links.map { Link(it.id, it.item, it.rate, it.from, it.to, it.fluid, it.routed, it.fromSide, it.toSide, it.pair) }
    val part = if (s.partitionCost != null) Partition(s.blocks, s.partitionCost, s.partitionPorts ?: 0, s.greedyCost ?: 0.0, s.greedyPorts ?: 0) else null
    return Split(s.blocks, links, s.warnings, g, s.plan, part, s.ports)
}

/** The layouts saved for block [b] in [out] (empty if none). */
fun loadBlock(out: File, b: Int): List<TrialResult> {
    val f = File(out, "resume/block_${b + 1}.json").takeIf { it.exists() } ?: return emptyList()
    val ls = strictJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SLayout.serializer()), f.readText())
    return ls.map { l ->
        val ents = l.entities.map { e ->
            PlacedEntity(e.name, Kind.valueOf(e.kind), e.x, e.y, Dir.valueOf(e.dir), e.recipe, e.item, e.fluid, e.net, e.mirror, e.drain, e.w, e.h)
        }
        val placement = Placement(l.w, l.h, l.machines.map { PlacedMachine(it.id, it.x, it.y, Dir.valueOf(it.rot), it.mirror) })
        TrialResult(true, l.w, l.h, l.seed, Layout(l.w, l.h, ents), placement, emptyList(), null, l.score, "ok", 0.0, 0, listOf("resumed"),
            sources = l.sources.map { SourceSpec(Tile(it.x, it.y), it.item, it.rate, it.fluid, it.lane, it.port) },
            outputs = l.outputs.map { OutputSpec(Tile(it.x, it.y), it.item, it.rate, it.fluid, it.port) })
    }
}
