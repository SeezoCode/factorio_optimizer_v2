package power

import kotlin.math.abs

import config.RunConfiguration
import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import plan.inserterCount
import plan.inserterRate
import validate.OutputSpec
import validate.SourceSpec

data class Harness(val entities: List<PlacedEntity>, val tiles: Set<Tile>, val warnings: List<String>)

private val PIPE_KINDS = setOf(Kind.PIPE, Kind.PIPE_UG, Kind.INFINITY_PIPE)

/**
 * Sides of a fluid port tile holding [port] where a feed pipe connects, best first, out of the [outward] ones: a
 * pipe-to-ground only on its open side (on a corner the first outward side may be its closed one), a plain pipe on
 * any of them.
 */
internal fun fluidSides(port: PlacedEntity?, outward: List<Dir>): List<Dir> =
    if (port?.kind == Kind.PIPE_UG) outward.filter { it == port.dir } else outward

/**
 * Sides of an item input tile holding [port] a feed can come from, best first, out of the [outward] ones: from
 * behind first (a corner belt fed from its side gets one lane only), then from the sides for a plain belt, never
 * head-on. Undergrounds and splitters only take items from behind.
 */
internal fun beltFeedSides(port: PlacedEntity?, outward: List<Dir>): List<Dir> = when {
    port == null -> outward
    port.kind == Kind.BELT -> outward.filter { it == port.dir.opposite } + outward.filter { it != port.dir && it != port.dir.opposite }
    else -> outward.filter { it == port.dir.opposite }
}

/**
 * H1 (test mode): feed every source belt from outside the box with a straight feeder belt whose sides
 * carry inserters taking from infinity chests, enough inserters for the source's rate. Every output belt
 * runs on outside the box past inserters that empty it into void infinity chests; fluid ports get an
 * infinity pipe (a drain for outputs).
 */
fun buildHarness(
    sources: List<SourceSpec>, entities: List<PlacedEntity>, w: Int, h: Int, config: RunConfiguration,
    outputs: List<OutputSpec> = emptyList(),
): Harness {
    val tier = GameConstants.beltTier(config.beltTier)
    val occupied = HashSet<Tile>()
    entities.forEach { e -> e.rect.tiles().forEach { occupied += it } }
    val byTile = entities.associateBy { it.tile }
    val insName = config.inserters.allowed.filter { it != "long-handed-inserter" }
        .maxByOrNull { inserterRate(it, config, fromBelt = false) } ?: "inserter"
    val cap = inserterRate(insName, config, fromBelt = false)
    val out = ArrayList<PlacedEntity>()
    val tiles = HashSet<Tile>()
    val warnings = ArrayList<String>()
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    fun free(t: Tile) = !inBox(t) && t !in occupied && t !in tiles

    fun fluidPort(at: Tile, fluid: String, drain: Boolean) {
        // A fluid port gets an infinity pipe just outside the box, next to its pipe.
        val o = fluidSides(byTile[at], Dir.entries.filter { d -> !inBox(at + d) }).firstOrNull { d -> free(at + d) }
        if (o == null) { warnings += "no room for a fluid harness at $at ($fluid)"; return }
        val t = at + o
        out += PlacedEntity(GameConstants.INFINITY_PIPE, Kind.INFINITY_PIPE, t.x, t.y, fluid = fluid, drain = drain)
        tiles += t
    }
    sources.filter { it.fluid }.forEach { fluidPort(it.tile, it.item, drain = false) }
    outputs.filter { it.fluid }.forEach { fluidPort(it.tile, it.item, drain = true) }
    // Item sources, grouped by tile: a shared belt gets one feeder with each item's inserters on the side that
    // drops onto its lane (an inserter drops on the far lane).
    sources.filter { !it.fluid }.groupBy { it.tile }.forEach { (tile, group) ->
        val first = byTile[tile]
        val laned = group.any { it.lane >= 0 }
        // Outward direction: off the box, and not the way the first belt faces (it cannot take items head-on);
        // a laned belt must be fed straight from behind so its lanes stay as they are.
        val dirs = if (laned && first != null) listOf(first.dir.opposite).filter { !inBox(tile + it) }
            else beltFeedSides(first, Dir.entries.filter { d -> !inBox(tile + d) })
        val o = dirs.firstOrNull { d -> free(tile + d) }
        if (o == null) { warnings += "no room for a harness at $tile (${group.map { it.item }})"; return@forEach }
        // Feeder heading o.opposite: inserters on its o.ccw side drop on the left lane, on o.cw the right lane.
        val pending = group.map { s -> s to inserterCount(s.rate, cap) }.toMutableList()
        fun sidesFor(s: SourceSpec) = when (s.lane) { 0 -> listOf(o.ccw); 1 -> listOf(o.cw); else -> listOf(o.cw, o.ccw) }
        val total = pending.sumOf { it.second }
        var i = 1
        while (pending.any { it.second > 0 } && i <= total + 4) {
            val f = tile.step(o, i)
            if (!free(f)) { warnings += "harness at $tile blocked at $f"; break }
            out += PlacedEntity(tier.belt, Kind.BELT, f.x, f.y, o.opposite)
            tiles += f
            for (side in listOf(o.cw, o.ccw)) {
                val k = pending.indexOfFirst { (s, n) -> n > 0 && side in sidesFor(s) }
                if (k < 0) continue
                val insT = f + side
                val chestT = f.step(side, 2)
                if (!free(insT) || !free(chestT)) continue
                out += PlacedEntity(insName, Kind.INSERTER, insT.x, insT.y, side)   // picks from the chest side
                out += PlacedEntity(GameConstants.INFINITY_CHEST, Kind.INFINITY_CHEST, chestT.x, chestT.y, item = pending[k].first.item)
                tiles += insT; tiles += chestT
                pending[k] = pending[k].first to pending[k].second - 1
            }
            i++
        }
        pending.filter { it.second > 0 }.forEach { (s, n) -> warnings += "harness for ${s.item} is short of $n inserters" }
    }
    // Outputs after inputs: an input's feeder must run straight out, an output's void inserters can go either side.
    val pickCap = inserterRate(insName, config, fromBelt = true)
    outputs.filter { !it.fluid }.forEach { o ->
        val d = byTile[o.tile]?.dir
        if (d == null || inBox(o.tile + d)) { warnings += "output of ${o.item} at ${o.tile} does not leave the box"; return@forEach }
        val k = inserterCount(o.rate, pickCap)
        var placedIns = 0
        var i = 1
        while (placedIns < k && i <= k + 4) {
            val f = o.tile.step(d, i)
            if (!free(f)) { warnings += "output harness for ${o.item} blocked at $f"; break }
            out += PlacedEntity(tier.belt, Kind.BELT, f.x, f.y, d)
            tiles += f
            for (side in listOf(d.cw, d.ccw)) {
                if (placedIns >= k) break
                val insT = f + side
                val chestT = f.step(side, 2)
                if (!free(insT) || !free(chestT)) continue
                out += PlacedEntity(insName, Kind.INSERTER, insT.x, insT.y, side.opposite)   // picks from the belt
                out += PlacedEntity(GameConstants.INFINITY_CHEST, Kind.INFINITY_CHEST, chestT.x, chestT.y)
                tiles += insT; tiles += chestT
                placedIns++
            }
            i++
        }
        if (placedIns < k) warnings += "output harness for ${o.item} has $placedIns of $k inserters"
    }
    return Harness(out, tiles, warnings)
}

/** A layout with test feeds on its ports, and what could not be fed. */
data class TestCopy(val layout: geom.Layout, val warnings: List<String>)

/**
 * The finished [layout] as it is, plus a feed on every port for trying it in the editor: an item input gets a
 * loader pointing at its port belt with an infinity chest of the item behind it, an item output a loader taking
 * its belt into a void infinity chest, and a fluid port an infinity pipe (a drain for outputs). Each feed runs
 * straight out from its port, one tile wide, so ports next to each other all fit. A belt carrying two items on
 * its lanes cannot take a loader (it fills both lanes); it gets the test-mode feeder with inserters instead,
 * where there is room. Nothing inside the box changes.
 */
fun testCopy(layout: geom.Layout, sources: List<SourceSpec>, outputs: List<OutputSpec>, config: RunConfiguration): TestCopy {
    val w = layout.w; val h = layout.h
    val loader = GameConstants.LOADERS[config.beltTier] ?: "express-loader"
    val occupied = HashSet<Tile>()
    layout.entities.forEach { e -> e.rect.tiles().forEach { occupied += it } }
    val byTile = HashMap<Tile, PlacedEntity>()
    layout.entities.forEach { e -> e.rect.tiles().forEach { byTile[it] = e } }
    val out = ArrayList<PlacedEntity>()
    val warnings = ArrayList<String>()
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    fun free(t: Tile) = !inBox(t) && t !in occupied
    fun take(e: PlacedEntity) { out += e; e.rect.tiles().forEach { occupied += it } }
    // Away from the box: the side a port on the ring (or on the edge) is on.
    fun outward(t: Tile): List<Dir> = listOfNotNull(
        Dir.W.takeIf { t.x <= 0 }, Dir.E.takeIf { t.x >= w - 1 }, Dir.N.takeIf { t.y <= 0 }, Dir.S.takeIf { t.y >= h - 1 },
    ).filter { !inBox(t + it) }

    // An infinity pipe next to a fluid port, on a side the port's pipe connects to, touching no pipe of another fluid.
    fun fluidFeed(tile: Tile, fluid: String, drain: Boolean): Boolean {
        val d = fluidSides(byTile[tile], outward(tile)).firstOrNull { d ->
            val t = tile + d
            free(t) && Dir.entries.all { n -> (t + n == tile) || byTile[t + n]?.let { it.kind !in PIPE_KINDS || it.fluid == fluid } ?: true } &&
                out.none { it.kind == Kind.INFINITY_PIPE && it.fluid != fluid && it.tile.manhattan(t) == 1 }
        } ?: return false
        (tile + d).let { take(PlacedEntity(GameConstants.INFINITY_PIPE, Kind.INFINITY_PIPE, it.x, it.y, fluid = fluid, drain = drain)) }
        return true
    }
    sources.filter { it.fluid }.forEach { s -> if (!fluidFeed(s.tile, s.item, drain = false)) warnings += "no room to feed ${s.item} at ${s.tile}" }
    outputs.filter { it.fluid }.forEach { o -> if (!fluidFeed(o.tile, o.item, drain = true)) warnings += "no room to drain ${o.item} at ${o.tile}" }
    // A loader on the two tiles out from the port, the chest on the third.
    fun loaderAt(port: Tile, d: Dir, into: Boolean): PlacedEntity? {
        val a = port + d; val b = port.step(d, 2); val c = port.step(d, 3)
        if (!free(a) || !free(b) || !free(c)) return null
        val travel = if (into) d.opposite else d
        val (lw, lh) = PlacedEntity.defaultSize(loader, Kind.LOADER_OUT, travel)
        val x = minOf(a.x, b.x); val y = minOf(a.y, b.y)
        return PlacedEntity(loader, if (into) Kind.LOADER_OUT else Kind.LOADER_IN, x, y, travel, w = lw, h = lh)
    }
    val laned = ArrayList<SourceSpec>()
    sources.filter { !it.fluid }.groupBy { it.tile }.forEach { (tile, group) ->
        if (group.map { it.item }.distinct().size > 1 || group.any { it.lane >= 0 }) { laned += group; return@forEach }
        val d = beltFeedSides(byTile[tile], outward(tile)).firstOrNull { loaderAt(tile, it, true) != null }
        if (d == null) { warnings += "no room to feed ${group[0].item} at $tile"; return@forEach }
        take(loaderAt(tile, d, true)!!)
        tile.step(d, 3).let { take(PlacedEntity(GameConstants.INFINITY_CHEST, Kind.INFINITY_CHEST, it.x, it.y, item = group[0].item)) }
    }
    outputs.filter { !it.fluid }.forEach { o ->
        val d = byTile[o.tile]?.dir
        if (d == null || inBox(o.tile + d) || loaderAt(o.tile, d, false) == null) { warnings += "no room to empty ${o.item} at ${o.tile}"; return@forEach }
        take(loaderAt(o.tile, d, false)!!)
        o.tile.step(d, 3).let { take(PlacedEntity(GameConstants.INFINITY_CHEST, Kind.INFINITY_CHEST, it.x, it.y)) }
    }
    if (laned.isNotEmpty()) {
        val hz = buildHarness(laned, layout.entities + out, w, h, config)
        hz.entities.forEach { take(it) }
        warnings += hz.warnings
    }
    // The feeders' inserters stand outside the box, beyond the factory's poles (loaders need no power, inserters
    // do): each one out of reach gets a pole beside it, wired to the nearest pole within reach.
    val poleName = GameConstants.pole(config.power.pole).takeIf { it.size == 1 }?.name ?: "medium-electric-pole"
    val spec = GameConstants.pole(poleName)
    val wires = ArrayList(layout.wires)
    fun all() = layout.entities + out
    fun poles() = all().withIndex().filter { (_, e) -> e.kind == Kind.POLE }
    fun covers(p: PlacedEntity, t: Tile): Boolean {
        val half = GameConstants.pole(p.name).supplyHalf
        return abs(t.x + 0.5 - (p.x + p.w / 2.0)) <= half && abs(t.y + 0.5 - (p.y + p.h / 2.0)) <= half
    }
    out.filter { it.kind == Kind.INSERTER }.forEach { ins ->
        if (poles().any { (_, p) -> covers(p, ins.tile) }) return@forEach
        val spot = (1..3).asSequence().flatMap { r -> (-r..r).asSequence().flatMap { dy -> (-r..r).asSequence().map { dx -> Tile(ins.tile.x + dx, ins.tile.y + dy) } } }
            .firstOrNull { free(it) } ?: run { warnings += "no room for a pole by the feeder at ${ins.tile}"; return@forEach }
        val near = poles().minByOrNull { (_, p) -> p.tile.manhattan(spot) }
        take(PlacedEntity(poleName, Kind.POLE, spot.x, spot.y))
        val idx = layout.entities.size + out.size - 1
        near?.let { (i, p) ->
            val reach = minOf(spec.wireReach, GameConstants.pole(p.name).wireReach)
            val dx = spot.x + 0.5 - (p.x + p.w / 2.0); val dy = spot.y + 0.5 - (p.y + p.h / 2.0)
            if (kotlin.math.sqrt(dx * dx + dy * dy) <= reach) wires += i to idx
        }
    }
    return TestCopy(layout.copy(entities = layout.entities + out, wires = wires, label = layout.label + " (test feeds)"), warnings)
}
