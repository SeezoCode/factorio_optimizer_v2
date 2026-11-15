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
 * inserter; and the item is one a provider chest holds somewhere ([stocked]), or the condition would never hold.
 */
fun addOverflowLast(entities: List<PlacedEntity>, outputs: List<OutputSpec>, stocked: Set<String>, level: (String) -> Int): OverflowLast {
    val sinks = outputs.filter { it.local && !it.bot && !it.fluid && it.sink == null && it.item in stocked }
    if (sinks.isEmpty()) return OverflowLast(entities, 0, emptyList())
    val ents = ArrayList(entities)
    val beltAt = HashMap<Tile, Int>()
    val machineAt = HashMap<Tile, Int>()
    ents.forEachIndexed { i, e ->
        if (e.kind.beltFamily) e.rect.tiles().forEach { beltAt[it] = i }
        if (e.kind == Kind.MACHINE) e.rect.tiles().forEach { machineAt[it] = i }
    }
    val inserters = ents.indices.filter { ents[it].kind == Kind.INSERTER }
    val picks = inserters.groupBy { ents[it].pickupTile }
    val drops = inserters.groupBy { ents[it].dropTile }
    val outOf = inserters.filter { ents[it].pickupTile in machineAt }.groupBy { machineAt.getValue(ents[it].pickupTile) }

    /** The belt tiles that run onto the belt at [t]; null past a splitter. */
    fun upstream(t: Tile): List<Tile>? {
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

    var gated = 0
    for (o in sinks) {
        if (o.tile !in beltAt) continue
        val chain = LinkedHashSet<Tile>().apply { add(o.tile) }
        val queue = ArrayDeque(listOf(o.tile))
        var plain = true
        while (queue.isNotEmpty() && plain && chain.size <= 200) {
            val ups = upstream(queue.removeFirst())
            if (ups == null) { plain = false; break }
            ups.forEach { if (chain.add(it)) queue += it }
        }
        if (!plain || chain.size > 200) continue
        // (The chest's own inserter takes from the port's tile; anything taking from elsewhere on these belts is a user.)
        if ((picks[o.tile]?.size ?: 0) > 1 || chain.any { t -> t != o.tile && picks[t] != null }) continue
        val makers = chain.flatMap { drops[it] ?: emptyList() }.filter { ents[it].pickupTile in machineAt }
        for (i in makers) {
            val m = machineAt.getValue(ents[i].pickupTile)
            val others = outOf[m]?.any { j -> j != i && ents[j].dropTile !in chain } == true
            if (!others || ents[i].circuit != null || ents[i].logistic != null) continue
            ents[i] = ents[i].copy(logistic = CircuitCondition(o.item, ">", level(o.item), fluid = false))
            gated++
        }
    }
    val notes = if (gated > 0) listOf("$gated inserter(s) toward overflow chests work only once the robot network's stock of their item is filled") else emptyList()
    return OverflowLast(ents, gated, notes)
}
