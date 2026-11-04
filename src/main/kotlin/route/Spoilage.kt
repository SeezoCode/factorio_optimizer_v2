package route

import config.RunConfiguration
import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.outputTile
import plan.MachineInstance

/**
 * Gleba spoilage (spoilage.mode "drain"). Items that spoil on a belt ride on to its end as what they spoil into, so
 * every dead end of a belt that may carry such an item gets a drain: a filter inserter taking it into a chest. A
 * machine whose ingredients, fuel or products spoil hands the spoilage out with its products (see PathFinder's
 * output inserters and assignDirectInsertion), so the belts its products go on carry it too.
 */

/** Whether anything going into or out of [m] spoils (its fuel counts: a biochamber's nutrients). */
fun spoilProne(m: MachineInstance): Boolean =
    m.demand.keys.any { GameConstants.spoils(it) } || m.itemProducts.keys.any { GameConstants.spoils(it) }

/** What spoils into what around [m]: the spoil results of its ingredients and products. */
fun spoilResults(m: MachineInstance): Set<String> =
    (m.demand.keys + m.itemProducts.keys).mapNotNull { GameConstants.spoilResult(it) }.toSet()

/** Drains placed, and the dead ends left without one. */
class DrainResult(val ok: Boolean, val drains: List<PlacedEntity>, val stuck: List<Tile>)

/**
 * A drain at every belt end where spoilage would collect, as the validator reads the layout ([validate.spoilageEnds]:
 * its items, lane pairs and all), on free box tiles not in [blocked]: beside the last belt a filter inserter facing it
 * and a chest behind; where there is no room, the belt runs on a few tiles over free ones to where there is. One spoil result there: a filter for it; several: a blacklist
 * of the belt's own items.
 */
fun placeDrains(entities: List<PlacedEntity>, config: RunConfiguration, w: Int, h: Int, blocked: Set<Tile>, spec: validate.ValidationSpec): DrainResult {
    val ends = validate.spoilageEnds(geom.Layout(w, h, entities), spec)
    if (ends.isEmpty()) return DrainResult(true, emptyList(), emptyList())
    val byTile = HashMap<Tile, PlacedEntity>()
    entities.forEach { e -> e.rect.tiles().forEach { byTile[it] = e } }
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    val taken = HashSet<Tile>()
    fun free(t: Tile) = inBox(t) && t !in byTile && t !in blocked && t !in taken
    /** Nothing belt-like pushes into [t] (a belt we lay there must not be fed by another). */
    fun quiet(t: Tile, from: Tile) = Dir.entries.none { d -> val u = t + d; u != from && byTile[u]?.let { e -> e.kind.beltFamily && e.outputTile == t } == true }
    val inserter = config.inserters.allowed.filter { GameConstants.inserter(it).filterable }
        .minByOrNull { GameConstants.inserter(it).tierCost } ?: "fast-inserter"
    val drains = ArrayList<PlacedEntity>()
    val stuck = ArrayList<Tile>()
    /** A drain picking from the belt on [b] that arrived heading [h]: the inserter, its chest, their tiles; or null. */
    fun drainAt(b: Tile, h: Dir, item: String?, blacklist: List<String>?): List<PlacedEntity>? {
        for (d in Dir.entries.filter { it != h.opposite }) {
            val ins = b + d; val chest = ins + d
            if (free(ins) && free(chest)) return listOf(PlacedEntity(inserter, Kind.INSERTER, ins.x, ins.y, d.opposite, item = item, blacklist = blacklist),
                PlacedEntity(config.spoilage.chest, Kind.CHEST, chest.x, chest.y))
        }
        return null
    }
    ends.forEach { end ->
        val b = entities[end.belt]
        val item = end.results.singleOrNull()
        val blacklist = if (item == null) end.items.filter { it !in end.results }.sorted() else null
        drainAt(b.tile, b.dir, item, blacklist)?.let { es -> drains += es; es.forEach { taken += it.tile }; return@forEach }
        // Run the belt on over free tiles (up to six) to where a drain fits.
        data class St(val t: Tile, val h: Dir)
        val prev = HashMap<St, St?>()
        val start = b.tile + b.dir
        if (!free(start) || !quiet(start, b.tile)) { stuck += b.tile; return@forEach }
        val queue = ArrayDeque(listOf(St(start, b.dir))); prev[St(start, b.dir)] = null
        var found: Pair<St, List<PlacedEntity>>? = null
        while (queue.isNotEmpty() && found == null) {
            val cur = queue.removeFirst()
            val path = generateSequence(cur) { prev[it] }.toList()
            if (path.size > 6) continue
            val onPath = path.map { it.t }.toSet()
            taken += onPath
            // The last new belt is a dead end: it points at nothing belt-like (it would feed it, or change its shape).
            val deadEnd = byTile[cur.t + cur.h]?.kind?.beltFamily != true && (cur.t + cur.h) !in onPath
            val es = if (deadEnd) drainAt(cur.t, cur.h, item, blacklist) else null
            taken -= onPath
            if (es != null) { found = cur to es; break }
            for (nh in listOf(cur.h, cur.h.cw, cur.h.ccw)) {
                val n = St(cur.t + nh, nh)
                if (n in prev || n.t in onPath || !free(n.t) || !quiet(n.t, cur.t)) continue
                prev[n] = cur; queue += n
            }
        }
        val (last, es) = found ?: run { stuck += b.tile; return@forEach }
        val path = generateSequence(last) { prev[it] }.toList().reversed()
        // Each new belt heads to the next tile; the last ends where the drain picks from it.
        path.forEachIndexed { i, st ->
            val heading = if (i + 1 < path.size) path[i + 1].h else st.h
            drains += PlacedEntity(b.name.let { n -> GameConstants.tierOfEntity(n)?.belt ?: n }, Kind.BELT, st.t.x, st.t.y, heading, net = b.net)
            taken += st.t
        }
        drains += es; es.forEach { taken += it.tile }
    }
    return DrainResult(stuck.isEmpty(), drains, stuck)
}
