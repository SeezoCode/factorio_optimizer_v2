package blocks

import geom.Dir
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import place.Placement
import search.TrialResult

/** [d] turned clockwise [r] quarter turns. */
fun Dir.turned(r: Int): Dir = Dir.entries[(ordinal + r.mod(4)) % 4]

/** Tile [t] of a [w] x [h] box after turning the box clockwise [r] quarter turns (the box becomes h x w if r is odd). */
fun turnTile(t: Tile, w: Int, h: Int, r: Int): Tile = when (r.mod(4)) {
    0 -> t
    1 -> Tile(h - 1 - t.y, t.x)
    2 -> Tile(w - 1 - t.x, h - 1 - t.y)
    else -> Tile(t.y, w - 1 - t.x)
}

/**
 * A finished block layout turned clockwise [r] quarter turns: every entity's footprint, direction (travel, pickup
 * side, open side, fluid input side) and every port tile turn with the box. Turning keeps handedness, so belt lanes,
 * inserter drop lanes and machine mirroring stay as they were; the factory is validated as a whole anyway.
 */
fun turnLayout(t: TrialResult, r: Int): TrialResult {
    val q = r.mod(4)
    if (q == 0) return t
    val w = t.w; val h = t.h
    val nw = if (q % 2 == 1) h else w
    val nh = if (q % 2 == 1) w else h
    fun rect(x: Int, y: Int, ew: Int, eh: Int): Pair<Tile, Pair<Int, Int>> {
        // The turned rectangle's top-left is the turned corner that ends up smallest in both coordinates.
        val a = turnTile(Tile(x, y), w, h, q); val b = turnTile(Tile(x + ew - 1, y + eh - 1), w, h, q)
        return Tile(minOf(a.x, b.x), minOf(a.y, b.y)) to (if (q % 2 == 1) eh to ew else ew to eh)
    }
    val ents = t.layout!!.entities.map { e ->
        val (p, s) = rect(e.x, e.y, e.w, e.h)
        PlacedEntity(e.name, e.kind, p.x, p.y, e.dir.turned(q), e.recipe, e.item, e.fluid, e.net, e.mirror, e.drain, s.first, s.second, e.circuit)
    }
    val machines = t.placement!!.machines.map { pm ->
        val size = t.layout.entities.firstOrNull { it.kind == geom.Kind.MACHINE && it.x == pm.x && it.y == pm.y }?.let { it.w to it.h } ?: (3 to 3)
        val (p, _) = rect(pm.x, pm.y, size.first, size.second)
        pm.copy(x = p.x, y = p.y, rot = pm.rot.turned(q))
    }
    return TrialResult(true, nw, nh, t.seed, Layout(nw, nh, ents, t.layout.wires, t.layout.label), Placement(nw, nh, machines),
        t.errors, null, t.score, t.stage, t.annealT0, t.millis, t.notes,
        sources = t.sources.map { it.copy(tile = turnTile(it.tile, w, h, q)) },
        outputs = t.outputs.map { it.copy(tile = turnTile(it.tile, w, h, q)) })
}
