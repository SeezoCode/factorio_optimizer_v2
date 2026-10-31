package power

import geom.Kind
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import plan.BeaconSpec
import plan.ProductionPlan
import plan.beaconNeed
import plan.inBeaconRange

/** Beacons placed, and the machines (top-left tiles) still short of what their planned rate needs. */
data class BeaconResult(val beacons: List<PlacedEntity>, val short: List<Tile>) {
    val ok get() = short.isEmpty()
}

/** Beacons each machine of [plan] needs for its planned crafts, by machine top-left tile ([placed]: id -> tile). */
fun beaconNeeds(plan: ProductionPlan, placed: Map<Int, Tile>): Map<Tile, Int> =
    placed.mapNotNull { (id, t) -> val m = plan.machines[id]; beaconNeed(m.crafts, m.beaconCrafts).takeIf { it > 0 }?.let { t to it } }.toMap()

/**
 * Beacon spots kept free before placement, like the pole lattice: rows of beacons across the longer side of the box,
 * every [pitch] tiles, in runs of four with a one-tile gap (so belts can also cross on the surface) and a tile off
 * every edge (ports). The default pitch leaves room for a row of machines on each side of a beacon row with an
 * inserter between, and belts between the two rows (2m + 9 for machines m tiles tall); with more than four beacons
 * per machine one machine row sits between two beacon rows (m + 7), so both reach it. Spots on [blocked] tiles go.
 */
fun beaconLattice(w: Int, h: Int, spec: BeaconSpec, plan: ProductionPlan, perMachine: Int, pitch: Int?, blocked: Set<Tile>): List<Tile> {
    val beaconed = plan.machines.filter { it.beacons > 0 }
    if (beaconed.isEmpty()) return emptyList()
    val m = beaconed.maxOf { maxOf(it.w, it.h) }
    val s = spec.size
    val p = pitch ?: if (perMachine > 4) s + m + 4 else s + 2 * m + 6
    val horizontal = w >= h
    val across = if (horizontal) h else w
    val along = if (horizontal) w else h
    if (across < s + 2 || along < s + 2) return emptyList()
    val rows = (across - 2 - s) / p + 1
    val span = (rows - 1) * p + s
    val off = 1 + (across - 2 - span) / 2
    val out = ArrayList<Tile>()
    for (r in 0 until rows) {
        val a = off + r * p
        var j = 0
        while (true) {
            val b = 1 + j * s + j / 4
            if (b + s > along - 1) break
            val t = if (horizontal) Tile(b, a) else Tile(a, b)
            if (Rect(t.x, t.y, s, s).tiles().none { it in blocked }) out += t
            j++
        }
    }
    return out
}

/**
 * The lattice [spots] worth keeping for machines placed on [rects] with [needs] (by top-left tile): greedily the spot
 * in range of the most machines still short, until none is short or no spot helps.
 */
fun pickBeaconSpots(spec: BeaconSpec, needs: Map<Tile, Int>, rects: List<Rect>, spots: List<Tile>): List<Tile> {
    val ms = rects.filter { (needs[Tile(it.x, it.y)] ?: 0) > 0 }
    val left = IntArray(ms.size) { needs.getValue(Tile(ms[it].x, ms[it].y)) }
    val reach = spots.map { t -> ms.indices.filter { i -> inBeaconRange(spec, t.x, t.y, ms[i].x, ms[i].y, ms[i].w, ms[i].h) } }
    val taken = BooleanArray(spots.size)
    while (true) {
        var best = -1; var gain = 0
        for (s in spots.indices) if (!taken[s]) {
            val g = reach[s].count { left[it] > 0 }
            if (g > gain) { best = s; gain = g }
        }
        if (best < 0) break
        taken[best] = true
        reach[best].forEach { left[it]-- }
    }
    return spots.filterIndexed { i, _ -> taken[i] }
}

/**
 * After routing, like set-cover poles: starting from the free [lattice] spots, beacons go on free tiles in the box
 * (none on [keepFree]) where they bring the most machines short of their [needs] closer to them, until none is
 * short or no spot helps; then beacons every machine in range can do without are taken out, lattice spots first.
 */
fun placeBeacons(entities: List<PlacedEntity>, spec: BeaconSpec, needs: Map<Tile, Int>, lattice: List<Tile>, w: Int, h: Int, keepFree: Set<Tile>): BeaconResult {
    val machines = entities.filter { it.kind == Kind.MACHINE && (needs[it.tile] ?: 0) > 0 }
    if (machines.isEmpty()) return BeaconResult(emptyList(), emptyList())
    val need = IntArray(machines.size) { needs.getValue(machines[it].tile) }
    val have = IntArray(machines.size)
    val occupied = HashSet<Tile>()
    entities.forEach { e -> e.rect.tiles().forEach { occupied += it } }
    keepFree.forEach { occupied += it }
    val s = spec.size
    fun free(t: Tile) = t.x >= 0 && t.y >= 0 && t.x + s <= w && t.y + s <= h && Rect(t.x, t.y, s, s).tiles().none { it in occupied }
    fun inRange(t: Tile) = machines.indices.filter { i -> val e = machines[i]; inBeaconRange(spec, t.x, t.y, e.x, e.y, e.w, e.h) }
    val beacons = ArrayList<Tile>()
    val reach = ArrayList<List<Int>>()
    val fromLattice = ArrayList<Boolean>()
    fun add(t: Tile, lat: Boolean) {
        Rect(t.x, t.y, s, s).tiles().forEach { occupied += it }
        val r = inRange(t)
        r.forEach { have[it]++ }
        beacons += t; reach += r; fromLattice += lat
    }
    lattice.filter { free(it) }.forEach { add(it, true) }

    // Greedy cover of the shortfall.
    if (machines.indices.any { have[it] < need[it] }) {
        val cands = ArrayList<Pair<Tile, List<Int>>>()
        for (y in 0..h - s) for (x in 0..w - s) {
            val t = Tile(x, y)
            if (!free(t)) continue
            val r = inRange(t)
            if (r.any { have[it] < need[it] }) cands += t to r
        }
        while (machines.indices.any { have[it] < need[it] }) {
            var best: Pair<Tile, List<Int>>? = null
            var bestGain = 0
            for (c in cands) {
                if (!free(c.first)) continue
                val gain = c.second.count { have[it] < need[it] }
                if (gain > bestGain) { best = c; bestGain = gain }
            }
            if (best == null) break
            add(best.first, false)
        }
    }

    // Take out what nobody needs: lattice spots first, then the beacons reaching fewest machines.
    val order = beacons.indices.sortedWith(compareBy({ !fromLattice[it] }, { reach[it].size }))
    val keep = BooleanArray(beacons.size) { true }
    for (b in order) {
        if (reach[b].all { have[it] - 1 >= need[it] }) { keep[b] = false; reach[b].forEach { have[it]-- } }
    }
    val placed = beacons.indices.filter { keep[it] }.map { PlacedEntity(spec.name, Kind.BEACON, beacons[it].x, beacons[it].y, w = s, h = s, modules = spec.modules) }
    return BeaconResult(placed, machines.indices.filter { have[it] < need[it] }.map { machines[it].tile })
}
