package route

import geom.CircuitCondition
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.dropTile
import geom.pickupTile
import validate.OutputSpec

/** [entities] with their overflow exits made to wait for the stock; [gated] inserters changed. */
class OverflowLast(val entities: List<PlacedEntity>, val gated: Int, val notes: List<String>)

/** The belts of a block and the inserters on them, for walking a chest's belt back to the machines that fill it. */
private class Exits(val ents: ArrayList<PlacedEntity>) {
    val beltAt = HashMap<Tile, Int>()
    val machineAt = HashMap<Tile, Int>()
    init {
        ents.forEachIndexed { i, e ->
            if (e.kind.beltFamily) e.rect.tiles().forEach { beltAt[it] = i }
            if (e.kind == Kind.MACHINE) e.rect.tiles().forEach { machineAt[it] = i }
        }
    }
    val inserters = ents.indices.filter { ents[it].kind == Kind.INSERTER }
    val picks = inserters.groupBy { ents[it].pickupTile }
    val drops = inserters.groupBy { ents[it].dropTile }
    val outOf = inserters.filter { ents[it].pickupTile in machineAt }.groupBy { machineAt.getValue(ents[it].pickupTile) }

    /** The belt tiles that run onto the belt at [t]; null past a splitter. */
    private fun upstream(t: Tile): List<Tile>? {
        val b = ents[beltAt.getValue(t)]
        if (b.kind == Kind.SPLITTER) return null
        if (b.kind == Kind.UG_OUT) {
            for (k in 1..12) {
                val u = t.step(b.dir.opposite, k)
                val ub = beltAt[u]?.let { ents[it] } ?: continue
                if (ub.name == b.name && ub.dir == b.dir) return if (ub.kind == Kind.UG_IN) listOf(u) else emptyList()
            }
            return emptyList()
        }
        val out = ArrayList<Tile>()
        for (d in Dir.entries) {
            val u = t + d.opposite
            val ub = beltAt[u]?.let { ents[it] } ?: continue
            if (ub.kind == Kind.SPLITTER) return null
            if (ub.dir == d && ub.kind != Kind.UG_IN) out += u
        }
        return out
    }

    /**
     * The belts that end at a chest's port [port] and nowhere else: null where they pass a splitter, run long, or
     * anything but the chest's own inserter takes from them (a user).
     */
    fun chainTo(port: Tile): Set<Tile>? {
        if (port !in beltAt) return null
        val chain = LinkedHashSet<Tile>().apply { add(port) }
        val queue = ArrayDeque(listOf(port))
        while (queue.isNotEmpty()) {
            val ups = upstream(queue.removeFirst()) ?: return null
            ups.forEach { if (chain.add(it)) queue += it }
            if (chain.size > 200) return null
        }
        // (The chest's own inserter takes from the port's tile; anything taking from elsewhere on these belts is a user.)
        if ((picks[port]?.size ?: 0) > 1 || chain.any { t -> t != port && picks[t] != null }) return null
        return chain
    }

    /** Whether inserter [i] may carry [item]: its filter is that item, or it has none and does not refuse it. */
    fun carries(i: Int, item: String) = ents[i].item?.let { it == item } ?: (ents[i].blacklist?.contains(item) != true)

    /** The inserters that put a machine's output onto [chain]. */
    fun makersOnto(chain: Set<Tile>): List<Int> = chain.flatMap { drops[it] ?: emptyList() }.filter { ents[it].pickupTile in machineAt }
}

/**
 * A machine's way out to a block's overflow chest works only once the logistic network holds more than [level] of the
 * item (blocks.overflow_after_stacks): its inserter onto that belt gets a logistic condition.
 *
 * A block built for robots gives a maker two output inserters, one toward the provider chest its users are served
 * from and one toward the block's overflow chest (blocks.local_sinks), and they share whatever is made from the first
 * item on. Py logistic science, 60 game-hours from empty: of fourteen reduced tin, planned to overflow one in 60 hours,
 * nine lay on the three belts before the overflow chest and the molten tin machine got five. The belts before the 196
 * such exits of that factory hold 6,200 items; warm-started, it made 3 science packs in seven hours as built and 22
 * with the overflow exits shut until their machine had stood full.
 *
 * Only an exit that is nothing else: its belts, walked up from the port of a local item sink in [outputs], feed no
 * other inserter and pass no splitter; the inserters onto them come out of machines that have another output
 * inserter for the same item (a by-product whose only way out is its overflow chest must leave at once: Py's quartz
 * crusher and its stone); and the item is one a provider chest holds somewhere ([stocked]), or the condition would
 * never hold.
 */
fun addOverflowLast(entities: List<PlacedEntity>, outputs: List<OutputSpec>, stocked: Set<String>, level: (String) -> Int): OverflowLast {
    val sinks = outputs.filter { it.local && !it.bot && !it.fluid && it.sink == null && it.item in stocked }
    if (sinks.isEmpty()) return OverflowLast(entities, 0, emptyList())
    val x = Exits(ArrayList(entities))
    val ents = x.ents
    var gated = 0
    for (o in sinks) {
        val chain = x.chainTo(o.tile) ?: continue
        for (i in x.makersOnto(chain)) {
            val m = x.machineAt.getValue(ents[i].pickupTile)
            // (Another way out for this item: a by-product that leaves through its overflow chest alone must not wait.)
            val others = x.outOf[m]?.any { j -> j != i && ents[j].dropTile !in chain && x.carries(j, o.item) } == true
            if (!others || !x.carries(i, o.item) || ents[i].circuit != null || ents[i].logistic != null) continue
            ents[i] = ents[i].copy(logistic = CircuitCondition(o.item, ">", level(o.item), fluid = false))
            gated++
        }
    }
    val notes = if (gated > 0) listOf("$gated inserter(s) toward overflow chests work only once the robot network's stock of their item is filled") else emptyList()
    return OverflowLast(ents, gated, notes)
}

/**
 * A machine's way out to a robot provider chest works only while the logistic network holds at most [level] of the
 * item (blocks.provider_until_stacks), where the machine also feeds a user of that item itself, directly or by belt.
 *
 * Such a maker has one output inserter toward the provider chest and one toward its neighbour, and they take turns:
 * half and half, whatever each side needs. Py logistic science, warm-started: the limestone maker's provider chest held
 * 625 limestone after eight hours and 2,400 after twenty, robots asking for none of it, while the sodium alginate
 * machine on the maker's own belt ran at 78% of its plan for want of limestone (lead plate and the lead-antimony alloy
 * at 80%, fertilizer). With the overflow exit open above the same level ([addOverflowLast]) one of the two is always
 * open, and the neighbour comes first.
 *
 * Only an exit that carries that one item (the inserter's filter, or its machine's only item product, [products] of a
 * recipe), on belts that end at the provider chest alone ([outputs] with bot), where another output inserter of the
 * machine can carry the item to something that is neither a provider nor an overflow chest.
 */
fun addProviderLevel(entities: List<PlacedEntity>, outputs: List<OutputSpec>, level: (String) -> Int, products: (String) -> List<String>): OverflowLast {
    val ports = outputs.filter { it.local && it.bot && !it.fluid }
    if (ports.isEmpty()) return OverflowLast(entities, 0, emptyList())
    val x = Exits(ArrayList(entities))
    val ents = x.ents
    val chests = outputs.filter { it.local && !it.fluid }.mapNotNull { x.chainTo(it.tile) }.flatten().toSet()
    var gated = 0
    for (o in ports) {
        val chain = x.chainTo(o.tile) ?: continue
        for (i in x.makersOnto(chain)) {
            if (ents[i].circuit != null || ents[i].logistic != null) continue
            val m = x.machineAt.getValue(ents[i].pickupTile)
            val only = ents[i].item ?: ents[m].recipe?.let { products(it) }?.singleOrNull()
            if (only != o.item) continue
            val user = x.outOf[m]?.any { j -> j != i && ents[j].dropTile !in chests && x.carries(j, o.item) } == true
            if (!user) continue
            ents[i] = ents[i].copy(logistic = CircuitCondition(o.item, "<", level(o.item) + 1, fluid = false))
            gated++
        }
    }
    val notes = if (gated > 0) listOf("$gated inserter(s) toward provider chests stop once the robot network's stock of their item is filled: the user beside the maker comes first") else emptyList()
    return OverflowLast(ents, gated, notes)
}

/**
 * [entities] with every robot provider chest of [outputs] limited to [slots] slots (blocks.bot_provider_slots), where
 * it has no limit: block layouts saved before the limit was saved with them come back without it (Py logistic science,
 * built that way: 4,800 coke in one provider chest, 2,400 limestone in another while the user beside its maker ran
 * at 78%). The chest is the one the port's own inserter drops into.
 */
fun withProviderBars(entities: List<PlacedEntity>, outputs: List<OutputSpec>, slots: Int): List<PlacedEntity> {
    val ports = outputs.filter { it.local && it.bot && !it.fluid }.map { it.tile }.toSet()
    if (ports.isEmpty()) return entities
    val chestAt = HashMap<Tile, Int>()
    entities.forEachIndexed { i, e -> if (e.kind == Kind.CHEST) e.rect.tiles().forEach { chestAt[it] = i } }
    val out = ArrayList(entities)
    for (e in entities) {
        if (e.kind != Kind.INSERTER || e.pickupTile !in ports) continue
        val c = chestAt[e.dropTile] ?: continue
        if (out[c].bar == null) out[c] = out[c].copy(bar = slots)
    }
    return out
}
