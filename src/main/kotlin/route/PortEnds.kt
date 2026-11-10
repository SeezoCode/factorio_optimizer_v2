package route

import config.RunConfiguration
import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import plan.ProductionPlan
import validate.OutputSpec
import validate.SourceSpec
import kotlin.math.ceil

/** A block's layout with its port ends built: entities added, ports marked, the tiles they took outside the box. */
class PortEnds(
    val entities: List<PlacedEntity>, val sources: List<SourceSpec>, val outputs: List<OutputSpec>,
    val outside: Set<Tile>,
    /** Ports no end found room at (the layout is no good: its port would lead nowhere). */
    val missing: List<String>,
)

/**
 * The ends of a block's ports that do not go on to the corridors or the factory's edge (plan.Port.end), built just
 * outside the block where its port belt or pipe ends, as part of the block (docs/STITCH_REWRITE.md): the floorplan
 * then keeps room for them and the stitch only copies them.
 * - `chest` (a local sink, blocks.local_sinks) and `provider` (a robot link's sending end, blocks.bot_links): an inserter
 *   off the port's last belt into a chest beyond it.
 * - `requester` (a robot link's receiving end): a requester chest asking for ten minutes' worth (at least 10) and an
 *   inserter from it onto the port's first belt.
 * - `void` (a small fluid by-product): an overflow valve on the tile out of the port's pipe (the block's own users keep
 *   what they need), then the building that destroys the fluid, turned to take from it, a few pipes further on where
 *   that does not fit.
 * Each goes straight out from the port, else out of either side where that leaves the box.
 */
fun withPortEnds(entities: List<PlacedEntity>, sources: List<SourceSpec>, outputs: List<OutputSpec>, plan: ProductionPlan,
                 config: RunConfiguration, w: Int, h: Int): PortEnds {
    val endOf = { port: Int -> plan.ports.getOrNull(port)?.end ?: "edge" }
    if (sources.none { endOf(it.port) != "edge" } && outputs.none { endOf(it.port) != "edge" }) return PortEnds(entities, sources, outputs, emptySet(), emptyList())
    val ents = ArrayList(entities)
    val occ = HashMap<Tile, PlacedEntity>()
    entities.forEach { e -> e.rect.tiles().forEach { occ[it] = e } }
    val outside = HashSet<Tile>()
    val missing = ArrayList<String>()
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    fun free(t: Tile) = t !in occ
    fun add(e: PlacedEntity) { ents += e; e.rect.tiles().forEach { occ[it] = e; if (!inBox(it)) outside += it } }
    /** Out of the box from [t]: the way the box's edge faces where [t] lies. */
    fun outward(t: Tile): Dir = when {
        t.x < 0 -> Dir.W; t.x >= w -> Dir.E; t.y < 0 -> Dir.N; t.y >= h -> Dir.S
        t.x == 0 -> Dir.W; t.x == w - 1 -> Dir.E; t.y == 0 -> Dir.N; else -> Dir.S
    }
    fun sides(t: Tile, d: Dir) = listOf(d) + listOf(d.cw, d.ccw).filter { !inBox(t + it) }
    val inserter = listOf("fast-inserter", "inserter", "bulk-inserter").firstOrNull { it in config.inserters.allowed } ?: "inserter"
    val blocks = config.blocks
    val fluidKinds = setOf(Kind.PIPE, Kind.PIPE_UG, Kind.TANK, Kind.INFINITY_PIPE, Kind.VALVE)

    val outs = outputs.map { o ->
        when (val end = endOf(o.port)) {
            "chest", "provider" -> {
                val e = occ[o.tile]
                val d = e?.takeIf { it.kind.beltFamily }?.dir ?: outward(o.tile)
                val s = sides(o.tile, d).firstOrNull { free(o.tile + it) && free(o.tile.step(it, 2)) }
                if (s == null) { missing += "no room for the ${o.item} $end at ${o.tile}"; o }
                else {
                    add(PlacedEntity(inserter, Kind.INSERTER, (o.tile + s).x, (o.tile + s).y, s.opposite, net = e?.net))
                    val chest = if (end == "provider") blocks?.botProviderChest ?: "passive-provider-chest" else blocks?.localSinkChest ?: "steel-chest"
                    o.tile.step(s, 2).let { add(PlacedEntity(chest, Kind.CHEST, it.x, it.y)) }
                    o.copy(local = true, bot = end == "provider")
                }
            }
            "void" -> {
                val (name, recipe) = GameConstants.fluidVoid(o.item) ?: run { missing += "nothing destroys ${o.item}"; return@map o }
                val t = o.tile
                val d = plan.ports.getOrNull(o.port)?.specs?.firstNotNullOfOrNull { s -> s.edge?.let { runCatching { Dir.fromName(it) }.getOrNull() } } ?: outward(t)
                fun along(dir: Dir): List<PlacedEntity>? {
                    val v0 = t + dir
                    for (k in 0..8) {
                        val chain = (1..k).map { v0.step(dir, it) }
                        if ((listOf(v0) + chain).any { !free(it) }) return null
                        if (chain.any { p -> listOf(dir.cw, dir.ccw).any { s -> occ[p + s]?.kind in fluidKinds } }) return null
                        val v = fluidVoidAt(name, recipe, chain.lastOrNull() ?: v0, dir.opposite) ?: return null
                        if (v.rect.tiles().any { !free(it) || it == v0 || it in chain }) continue
                        return listOf(PlacedEntity(GameConstants.OVERFLOW_VALVE, Kind.VALVE, v0.x, v0.y, dir.opposite, fluid = o.item, net = occ[t]?.net)) +
                            chain.map { PlacedEntity(config.fluids.pipe, Kind.PIPE, it.x, it.y, fluid = o.item, net = occ[t]?.net) } + v
                    }
                    return null
                }
                val dirs = listOf(d) + (if (occ[t]?.kind == Kind.PIPE) listOf(d.cw, d.ccw).filter { !inBox(t + it) } else emptyList())
                val placed = dirs.firstNotNullOfOrNull { along(it) }
                if (placed == null) { missing += "no room for a void for ${o.item} at $t"; o }
                else { placed.forEach(::add); o.copy(local = true, sink = placed[0].tile) }
            }
            else -> o
        }
    }
    val srcs = sources.map { s ->
        if (endOf(s.port) != "requester" || s.fluid) return@map s
        val e = occ[s.tile]
        val d = e?.takeIf { it.kind.beltFamily }?.dir?.opposite ?: outward(s.tile)
        val side = sides(s.tile, d).firstOrNull { free(s.tile + it) && free(s.tile.step(it, 2)) }
        if (side == null) { missing += "no room for the ${s.item} requester at ${s.tile}"; return@map s }
        add(PlacedEntity(inserter, Kind.INSERTER, (s.tile + side).x, (s.tile + side).y, side, net = e?.net))
        s.tile.step(side, 2).let {
            add(PlacedEntity(blocks?.botRequesterChest ?: "requester-chest", Kind.CHEST, it.x, it.y, item = s.item, request = maxOf(10, ceil(s.rate * 600).toInt())))
        }
        s.copy(requester = true)
    }
    return PortEnds(ents, srcs, outs, outside, missing)
}

/**
 * Building [name] (a fluid void, see GameConstants.fluidVoid) set to [recipe], turned so that its one fluid connection
 * takes from a pipe on [pipe] lying [toward] of it; null if no turn does.
 */
fun fluidVoidAt(name: String, recipe: String, pipe: Tile, toward: Dir): PlacedEntity? {
    val c = GameConstants.baseFluidConnections(name).singleOrNull { it.flow == GameConstants.Flow.INPUT } ?: return null
    val (w, h) = GameConstants.MACHINE_SIZES[name] ?: return null
    for (rot in 0..3) {
        if (GameConstants.placeSide(c, rot, false) != toward) continue
        val (dx, dy) = GameConstants.placeConnection(c, w, h, rot, false)
        val (rw, rh) = if (rot % 2 == 0) w to h else h to w
        return PlacedEntity(name, Kind.MACHINE, pipe.x - dx, pipe.y - dy, Dir.entries[rot], recipe, w = rw, h = rh)
    }
    return null
}
