package route

import config.RunConfiguration
import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.outputTile
import plan.MachineInstance
import plan.ProductionPlan
import plan.Role

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

/** Spoil results each belt net of [plan] may carry: its item's own, and those of the machines that put items on it. */
fun spoilingNets(plan: ProductionPlan): Map<Int, Set<String>> {
    val out = HashMap<Int, MutableSet<String>>()
    plan.nets.filter { !it.fluid }.forEach { n ->
        val rs = HashSet<String>()
        GameConstants.spoilResult(n.item)?.let { rs += it }
        n.terminals.map { plan.terminals[it] }.filter { it.role == Role.PRODUCER && it.machine != null }
            .forEach { t -> val m = plan.machines[t.machine!!]; if (spoilProne(m)) rs += spoilResults(m) }
        if (rs.isNotEmpty()) out.getOrPut(n.id) { HashSet() } += rs
    }
    return out
}

/** Drains placed, and the dead ends left without one. */
class DrainResult(val ok: Boolean, val drains: List<PlacedEntity>, val stuck: List<Tile>)

/**
 * A drain at every dead end of a belt whose net may carry spoilage ([spoilingNets]): beside the last belt, an
 * inserter facing it and a chest behind the inserter, on free box tiles not in [blocked]. A belt is a dead end when
 * nothing belt-like takes its items on and it does not leave the box (a port).
 */
fun placeDrains(entities: List<PlacedEntity>, plan: ProductionPlan, config: RunConfiguration, w: Int, h: Int, blocked: Set<Tile>): DrainResult {
    val nets = spoilingNets(plan)
    if (nets.isEmpty()) return DrainResult(true, emptyList(), emptyList())
    val byTile = HashMap<Tile, PlacedEntity>()
    entities.forEach { e -> e.rect.tiles().forEach { byTile[it] = e } }
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    val taken = HashSet<Tile>()
    fun free(t: Tile) = inBox(t) && t !in byTile && t !in blocked && t !in taken
    /** Whether a belt-family entity on [t] takes items arriving from [from] heading [d]. */
    fun accepts(t: Tile, d: Dir): Boolean {
        val e = byTile[t] ?: return false
        return when (e.kind) {
            Kind.BELT -> e.dir != d.opposite
            Kind.UG_IN, Kind.SPLITTER -> e.dir == d || (e.kind == Kind.UG_IN && e.dir.axis != d.axis)
            Kind.UG_OUT -> e.dir.axis != d.axis
            else -> false
        }
    }
    val inserter = config.inserters.allowed.filter { GameConstants.inserter(it).filterable }
        .minByOrNull { GameConstants.inserter(it).tierCost } ?: "fast-inserter"
    val drains = ArrayList<PlacedEntity>()
    val stuck = ArrayList<Tile>()
    entities.filter { (it.kind == Kind.BELT || it.kind == Kind.UG_OUT) && it.net != null && it.net in nets }.forEach { b ->
        val out = b.outputTile ?: return@forEach
        if (!inBox(out) || accepts(out, b.dir)) return@forEach
        val results = nets.getValue(b.net!!)
        // One spoil result: a filter for it; several: a blacklist of the net's own item, taking everything else.
        val item = results.singleOrNull()
        val blacklist = if (item == null) listOf(plan.nets[b.net].item) else null
        val spot = Dir.entries.filter { it != b.dir.opposite }.firstNotNullOfOrNull { d ->
            val ins = b.tile + d; val chest = ins + d
            if (free(ins) && free(chest)) Triple(ins, chest, d) else null
        }
        if (spot == null) { stuck += b.tile; return@forEach }
        val (ins, chest, d) = spot
        // Facing the belt (its pickup side), dropping into the chest beyond.
        drains += PlacedEntity(inserter, Kind.INSERTER, ins.x, ins.y, d.opposite, item = item, blacklist = blacklist)
        drains += PlacedEntity(config.spoilage.chest, Kind.CHEST, chest.x, chest.y)
        taken += ins; taken += chest
    }
    return DrainResult(stuck.isEmpty(), drains, stuck)
}
