package validate

import data.GameConstants
import data.RootData
import data.fluidBoxes
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import geom.dropTile
import geom.outputTile
import geom.pickupTile
import kotlin.math.max
import kotlin.math.min

enum class ErrorType {
    OVERLAP, OUT_OF_BOUNDS, UNKNOWN_RECIPE,
    CONTAMINATION, SHAPE_CHANGED, UNSUPPORTED, CIRCUIT,
    UG_UNPAIRED,
    UNREACHABLE, STARVED, MISSING_INPUT, MISSING_SINK,
    INSERTER_BAD_PICKUP, INSERTER_BAD_DROP, INSERTER_WRONG_ITEM, INSERTER_RATE,
    BELT_OVER_CAPACITY, LANE_BLOCKED,
    UNPOWERED, POLES_DISCONNECTED, WIRE_TOO_LONG,
    FLUID_MISSING, FLUID_BLOCKED, FLUID_MIX, FLUID_STUCK, FLUID_LEAK,
    OUTPUT_MISSING,
    BEACON_SHORT,
    COLD, HEAT_DISCONNECTED,
    SPOILAGE_STUCK, SPOILAGE_CLOGS,
    SPLIT_OUTPUT,
}

data class ValidationError(val type: ErrorType, val tile: Tile, val message: String) {
    override fun toString() = "$type at $tile: $message"
}

/** What a machine is supposed to do, for item and rate checks. Rates are items/s. */
/**
 * Rates at or below this (items or fluid units per second) are slivers: rounding left by rebalancing, or a machine
 * planned to craft about once a day. The split, router and planner leave them unconnected, so they are not checked.
 */
const val TINY_RATE = 1e-5

data class MachineSpec(
    val product: String,
    val ingredients: Set<String>,
    val outputRate: Double = 0.0,
    val sinkRate: Double = 0.0,
    val demand: Map<String, Double> = emptyMap(),
    val needsFluid: String? = null,
    /** Fluid -> 1-based input / output connection the recipe uses. */
    val fluidIn: Map<String, Int> = emptyMap(),
    val fluidOut: Map<String, Int> = emptyMap(),
    /** Item products and their rates when there are several (asteroid crushing, uranium processing). */
    val products: Map<String, Double> = emptyMap(),
    /** Of [products], what goes into sink chests. */
    val sinks: Map<String, Double> = emptyMap(),
    /** Beacons that must reach this machine for its planned rate (0: none). */
    val beaconNeed: Int = 0,
    /**
     * Item products at full speed (items/s), when the machine may run faster than planned (machine counts are
     * rounded up): a machine runs as fast as its output is taken, so the machines making one item share its work.
     * Empty: no faster than planned.
     */
    val fullRates: Map<String, Double> = emptyMap(),
    /** Fluid ingredients per second (null: unknown, every fluid input is checked). */
    val fluidDemand: Map<String, Double>? = null,
    /**
     * A filter's item -> the product it stands for here: a product leaving on a mixed belt counts as the belt (one
     * product, its name), and an inserter filtered to it (by its own or the game's name) takes the belt's share.
     */
    val filterAliases: Map<String, String> = emptyMap(),
) {
    /** Item products leaving the machine, with their rates. */
    val outputs: Map<String, Double> get() = products.ifEmpty { if (outputRate > 0) mapOf(product to outputRate) else emptyMap() }
    fun sinkOf(item: String): Double = if (products.isEmpty()) (if (item == product) sinkRate else 0.0) else sinks[item] ?: 0.0
    /** Whether an inserter taking from this machine needs a filter (it would take every product). */
    val multiProduct: Boolean get() = products.size > 1
}

/** Items entering the layout on a belt tile (or a fluid on a pipe tile) from outside it (ports). */
data class SourceSpec(
    val tile: Tile, val item: String, val rate: Double, val fluid: Boolean = false,
    /** Lane the item arrives on (0 left, 1 right of the first belt's heading), or -1 for both. */
    val lane: Int = -1,
    /** Plan port it belongs to (-1: unknown). */
    val port: Int = -1,
)

/** Items (or a fluid) leaving the layout from a belt (pipe) on this tile at this rate (output ports). */
data class OutputSpec(val tile: Tile, val item: String, val rate: Double, val fluid: Boolean = false, val port: Int = -1)

data class ValidationSpec(
    /** Machine top-left tile -> what it should do. Machines missing here get item checks from their recipe only. */
    val machines: Map<Tile, MachineSpec> = emptyMap(),
    val sources: List<SourceSpec> = emptyList(),
    val outputs: List<OutputSpec> = emptyList(),
    /** Ring around the box where poles may stand. */
    val poleRing: Int = 0,
    /** Tiles outside the box that may hold anything (ports, test harness). */
    val outsideAllowed: Set<Tile> = emptySet(),
    val requirePower: Boolean = true,
    /** "infinity-pipe" or "manual". */
    val fluidMode: String = "infinity-pipe",
    val checkRates: Boolean = true,
    /** Effective items/s of an inserter by name, picking from a belt or not. */
    /** Items/s of an inserter: its name, whether it picks from a belt, whether it drops onto one. */
    val inserterRate: (String, Boolean, Boolean) -> Double = { name, fromBelt, _ -> GameConstants.inserter(name).baseRate * (if (fromBelt) 0.7 else 1.0) },
    /** recipe -> (product, solid ingredients, fluid ingredient) for machines without a [MachineSpec]. */
    /** Recipe facts by recipe and machine name, for machines without a [MachineSpec]. */
    val recipeInfo: (String, String?) -> MachineSpec? = { _, _ -> null },
    /** The job's beacon when machines need some ([MachineSpec.beaconNeed]). */
    val beacon: plan.BeaconSpec? = null,
    /** Aquilo heating: everything that freezes must touch a heat pipe joined to a heat port; null: no heating. */
    val heat: HeatSpec? = null,
    /**
     * Belt stacking (Space Age): items from sources and stack inserters arrive in stacks this high and a stack takes
     * one slot of a lane; anything another inserter drops takes a slot per item.
     */
    val beltStack: Int = 1,
    /**
     * Gleba spoilage: every belt end where spoilage could collect has a drain (a filter inserter into a chest), and a
     * machine holding something that spoils has an inserter able to take the spoilage out.
     */
    val spoilage: Boolean = false,
)

/** Heat ports (heat pipe tiles where heat comes in) and whether poles freeze too. */
data class HeatSpec(val ports: Set<Tile>, val poles: Boolean = false)

/**
 * Re-derives belts, items, rates, power and fluids from the entity list alone, so a router bug cannot
 * hide itself. Net ids on entities are used only in messages.
 */
class Validator(private val layout: Layout, private val spec: ValidationSpec) {
    private val errors = mutableListOf<ValidationError>()
    private val ents = layout.entities
    private val at = HashMap<Tile, Int>()
    private fun err(type: ErrorType, tile: Tile, msg: String) { errors += ValidationError(type, tile, msg) }

    fun run(): List<ValidationError> {
        checkOverlapAndBounds()
        checkMachines()
        val pairs = pairUndergrounds()
        val belts = BeltGraph(pairs)
        checkInserterEnds()
        belts.propagateItems()
        checkInserterItems(belts)
        checkOutputs(belts)
        if (spec.checkRates) checkRates(belts) else checkReachabilityOnly(belts)
        checkPower()
        checkBeacons()
        checkFluids()
        checkHeat()
        if (spec.spoilage) checkSpoilage(belts)
        return errors.distinct()
    }

    // ---- 1. overlap and bounds ----
    private fun checkOverlapAndBounds() {
        val box = Rect(0, 0, layout.w, layout.h)
        ents.forEachIndexed { i, e ->
            e.rect.tiles().forEach { t ->
                val prev = at.put(t, i)
                if (prev != null) err(ErrorType.OVERLAP, t, "${ents[prev].name} and ${e.name} share a tile")
                val inside = t in box
                val ring = e.kind == Kind.POLE && spec.poleRing > 0 &&
                    t.x >= -spec.poleRing && t.y >= -spec.poleRing && t.x < layout.w + spec.poleRing && t.y < layout.h + spec.poleRing
                if (!inside && !ring && t !in spec.outsideAllowed) err(ErrorType.OUT_OF_BOUNDS, t, "${e.name} lies outside the box")
            }
        }
    }

    private fun entityAt(t: Tile): PlacedEntity? = at[t]?.let { ents[it] }
    private fun indexAt(t: Tile): Int? = at[t]

    private fun machineInfo(e: PlacedEntity): MachineSpec? =
        spec.machines[e.tile] ?: e.recipe?.let { spec.recipeInfo(it, e.name) }

    private fun checkMachines() {
        ents.filter { it.kind == Kind.MACHINE }.forEach { m ->
            if (m.recipe == null || machineInfo(m) == null) err(ErrorType.UNKNOWN_RECIPE, m.tile, "machine without a known recipe (${m.recipe})")
            // A recycler would put its results onto a belt or into a container in front of its output end.
            if (m.name == "recycler") GameConstants.recyclerOutputTiles(m.rect, m.dir).forEach { t ->
                entityAt(t)?.takeIf { it.kind.beltFamily || it.kind == Kind.CHEST || it.kind == Kind.INFINITY_CHEST }?.let {
                    err(ErrorType.UNSUPPORTED, t, "the recycler at ${m.tile} would drop its results onto ${it.name}")
                }
            }
            // A machine that puts what it makes onto the tile in front of it (Py's wood processing units): a belt,
            // chest, loader or machine there gets it, wanted or not.
            GameConstants.resultTile(m.name, m.w, m.h, m.x, m.y, m.dir.ordinal, m.mirror)?.let { t ->
                entityAt(t)?.takeIf { it.kind.beltFamily || it.kind == Kind.CHEST || it.kind == Kind.INFINITY_CHEST ||
                    it.kind == Kind.LOADER_IN || it.kind == Kind.LOADER_OUT || it.kind == Kind.MACHINE }?.let {
                    err(ErrorType.UNSUPPORTED, t, "${m.name} at ${m.tile} puts what it makes onto the tile in front of it, where ${it.name} stands")
                }
            }
        }
        // The game drops an inserter given more filters than it has slots (Py's long-handed inserter has 3).
        ents.filter { it.kind == Kind.INSERTER && (it.item != null || it.blacklist != null) }.forEach { ins ->
            val slots = GameConstants.INSERTERS[ins.name]?.filterSlots ?: return@forEach
            val filters = ins.blacklist?.size ?: 1
            if (filters > slots) err(ErrorType.UNSUPPORTED, ins.tile, "${ins.name} has $filters filters but $slots filter slots: the game leaves it out")
        }
    }

    // ---- 3. underground pairing, simulated with the game's nearest-same-axis rule ----
    /** entrance index -> exit index */
    private fun pairUndergrounds(): Map<Int, Int> {
        val pairs = HashMap<Int, Int>()
        val pairedExits = HashSet<Int>()
        ents.forEachIndexed { i, e ->
            if (e.kind != Kind.UG_IN) return@forEachIndexed
            val tier = GameConstants.tierOfEntity(e.name)!!
            var found: Int? = null
            for (j in 1..tier.maxUndergroundGap + 1) {
                val t = e.tile.step(e.dir, j)
                val k = indexAt(t) ?: continue
                val o = ents[k]
                if (o.name != e.name || o.dir.axis != e.dir.axis) continue
                if (o.kind == Kind.UG_OUT && o.dir == e.dir) found = k
                break
            }
            if (found == null) err(ErrorType.UG_UNPAIRED, e.tile, "underground entrance has no exit within reach (net ${e.net})")
            else { pairs[i] = found; pairedExits += found }
        }
        ents.forEachIndexed { i, e ->
            if (e.kind == Kind.UG_OUT && i !in pairedExits) err(ErrorType.UG_UNPAIRED, e.tile, "underground exit has no entrance (net ${e.net})")
        }
        return pairs
    }

    // ---- 2. belt graph ----
    private inner class BeltGraph(val ugPairs: Map<Int, Int>) {
        /** entity index -> successor entity indices (two for a splitter) */
        val next = HashMap<Int, MutableList<Int>>()
        val prev = HashMap<Int, MutableList<Int>>()
        /** items present on each belt-family entity */
        val items = HashMap<Int, MutableSet<String>>()
        /** items injected onto a belt entity by inserters and sources */
        val injected = HashMap<Int, MutableSet<String>>()
        /** items on each lane of a belt-family entity (0 left, 1 right of its heading), and injected per lane */
        val laneItems = HashMap<Int, Array<MutableSet<String>>>()
        val injectedLane = HashMap<Int, Array<MutableSet<String>>>()

        init {
            ents.forEachIndexed { i, e ->
                if (!e.kind.beltFamily) return@forEachIndexed
                val succs = when (e.kind) {
                    Kind.UG_IN -> listOfNotNull(ugPairs[i])
                    Kind.SPLITTER -> e.rect.tiles().mapNotNull { t -> indexAt(t + e.dir)?.takeIf { it != i && accepts(ents[it], e.dir) } }.distinct()
                    else -> listOfNotNull(e.outputTile?.let { t -> indexAt(t)?.takeIf { accepts(ents[it], e.dir) } })
                }
                succs.forEach { succ -> next.getOrPut(i) { mutableListOf() } += succ; prev.getOrPut(succ) { mutableListOf() } += i }
            }
        }

        /** Does belt-family [f] take items pushed into it travelling in direction [d]? */
        fun accepts(f: PlacedEntity, d: Dir): Boolean = when (f.kind) {
            Kind.BELT -> d != f.dir.opposite
            Kind.UG_IN -> d != f.dir.opposite
            Kind.UG_OUT -> d.axis != f.dir.axis   // sides only; its back is underground, its front faces away
            Kind.SPLITTER -> d == f.dir
            else -> false
        }

        private fun lanes(m: HashMap<Int, Array<MutableSet<String>>>, i: Int) = m.getOrPut(i) { arrayOf(mutableSetOf(), mutableSetOf()) }

        /** Puts [item] onto entity [i]: on [lane] (0 left, 1 right), or both lanes for -1. */
        fun inject(i: Int, item: String, lane: Int = -1) {
            injected.getOrPut(i) { mutableSetOf() } += item
            val l = lanes(injectedLane, i)
            if (lane != 1) l[0] += item
            if (lane != 0) l[1] += item
        }

        /** Items arriving on [lane] of entity [i]: from its feeders, and injected there. */
        private fun arriving(i: Int, lane: Int): List<Set<String>> =
            (prev[i] ?: emptyList()).map { p ->
                val (toL, toR) = laneInto(ents[p], ents[i], this)
                val pl = laneItems[p]
                (if (toL == lane) pl?.get(0).orEmpty() else emptySet()) + (if (toR == lane) pl?.get(1).orEmpty() else emptySet())
            } + listOf(injectedLane[i]?.get(lane).orEmpty())

        fun propagateItems() {
            spec.sources.filter { !it.fluid }.forEach { s ->
                val i = indexAt(s.tile)
                if (i == null || !ents[i].kind.beltFamily) err(ErrorType.UNREACHABLE, s.tile, "source of ${s.item} has no belt")
                else inject(i, s.item, s.lane)
            }
            ents.forEachIndexed { _, e ->
                if (e.kind != Kind.INSERTER) return@forEachIndexed
                val d = indexAt(e.dropTile) ?: return@forEachIndexed
                if (ents[d].kind != Kind.BELT) return@forEachIndexed
                carriedByInserter(e)?.let { inject(d, it, dropLane(e, d, this)) }
            }
            // Fixpoint: items flow downstream along next, lane by lane.
            val queue = ArrayDeque<Int>()
            injectedLane.forEach { (i, l) -> val t = lanes(laneItems, i); t[0] += l[0]; t[1] += l[1]; queue += i }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val src = laneItems.getValue(i)
                next[i]?.forEach { n ->
                    val (toL, toR) = laneInto(ents[i], ents[n], this)
                    val t = lanes(laneItems, n)
                    val a = toL >= 0 && t[toL].addAll(src[0])
                    val b = toR >= 0 && t[toR].addAll(src[1])
                    if (a || b) queue += n
                }
            }
            laneItems.forEach { (i, l) -> items[i] = (l[0] + l[1]).toMutableSet() }
            // An inserter putting its item onto a lane that already carries something else.
            ents.forEach { e ->
                if (e.kind != Kind.INSERTER) return@forEach
                val d = indexAt(e.dropTile) ?: return@forEach
                if (ents[d].kind != Kind.BELT) return@forEach
                val mine = carriedByInserter(e) ?: return@forEach
                val lane = dropLane(e, d, this)
                val there = arriving(d, lane).flatten().toSet()
                if (there.any { it != mine }) err(ErrorType.INSERTER_WRONG_ITEM, e.tile, "${e.name} drops $mine onto a lane of ${there.sorted()}")
            }
            checkShapes()
            // Report merge points: a lane carrying several items where no single input already did. Two items
            // on the two lanes of one belt are fine (lanes move independently).
            laneItems.forEach { (i, l) ->
                for (lane in 0..1) {
                    val set = l[lane]
                    if (set.size <= 1) continue
                    if ((injectedLane[i]?.get(lane)?.size ?: 0) > 1 || arriving(i, lane).none { it.containsAll(set) }) {
                        err(ErrorType.CONTAMINATION, ents[i].tile, "items ${set.sorted()} meet on one lane (net ${ents[i].net})")
                    }
                }
            }
        }

        /** Connected belt chains: entity index -> a representative of its chain. */
        fun components(): Map<Int, Int> {
            val parent = HashMap<Int, Int>()
            fun find(x: Int): Int { var a = x; while (parent.getOrPut(a) { a } != a) a = parent.getValue(a); return a }
            ents.indices.filter { ents[it].kind.beltFamily }.forEach { find(it) }
            next.forEach { (a, bs) -> bs.forEach { b -> val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb } }
            return parent.keys.associateWith { find(it) }
        }

        /** Lanes of entity [i] that carry [item] (bit 0 left, bit 1 right). */
        fun lanesWith(i: Int, item: String): Int {
            val l = laneItems[i] ?: return 0
            return (if (item in l[0]) 1 else 0) or (if (item in l[1]) 2 else 0)
        }

        /**
         * Belt shape follows its feeders: a rear feeder makes it straight; else exactly one side feeder makes
         * it a curve. When net ids are known, report belts whose shape a feeder from another net changes.
         */
        fun checkShapes() {
            prev.forEach { (i, feeders) ->
                val e = ents[i]
                if (e.kind != Kind.BELT || e.net == null || feeders.any { ents[it].net == null }) return@forEach
                val own = feeders.filter { ents[it].net == e.net }
                if (own.size == feeders.size) return@forEach
                if (shape(e, own) != shape(e, feeders)) {
                    val f = ents[feeders.first { ents[it].net != e.net }]
                    err(ErrorType.SHAPE_CHANGED, e.tile, "a belt of net ${f.net} (${f.kind.name.lowercase()} ${f.dir} at ${f.tile}${f.item?.let { " $it" } ?: ""}) changes the shape of net ${e.net}'s belt (heading ${e.dir}, fed by ${own.size} of its own)")
                }
            }
        }

        private fun shape(e: PlacedEntity, feeders: List<Int>): String {
            val dirs = feeders.map { ents[it].dir }
            return when {
                e.dir in dirs -> "straight"
                dirs.size == 1 -> "curve-${dirs[0]}"
                else -> "straight"
            }
        }

        /** The single item on a belt entity, if exactly one. */
        fun itemOn(i: Int): String? = items[i]?.singleOrNull()
    }

    /** What an inserter moves, judged from its pickup tile alone. */
    private fun carriedByInserter(ins: PlacedEntity, belts: BeltGraph? = null): String? {
        val p = indexAt(ins.pickupTile) ?: return null
        val src = ents[p]
        return when (src.kind) {
            Kind.MACHINE -> machineInfo(src)?.let { mi -> takes(ins, mi) }
            Kind.INFINITY_CHEST, Kind.CHEST -> src.item
            Kind.BELT, Kind.SPLITTER -> belts?.itemOn(p)
            else -> null
        }
    }

    /** What an inserter takes out of a machine: its filter, else the machine's product. */
    private fun machineOutput(ins: Int): String? {
        val p = indexAt(ents[ins].pickupTile) ?: return null
        if (ents[p].kind != Kind.MACHINE) return null
        return machineInfo(ents[p])?.let { takes(ents[ins], it) }
    }

    /**
     * The one product [ins] takes out of a machine of [mi]: its filter; with a blacklist the one product it leaves in
     * (a group of items leaving on a mixed belt counts as one); else the machine's product.
     */
    private fun takes(ins: PlacedEntity, mi: MachineSpec): String =
        ins.item?.let { mi.filterAliases[it] ?: it } ?: ins.blacklist?.let { bl -> mi.outputs.keys.filter { it !in bl }.singleOrNull() } ?: mi.product

    // ---- 5. inserter ends ----
    private fun checkInserterEnds() {
        ents.filter { it.kind == Kind.INSERTER }.forEach { ins ->
            val pick = entityAt(ins.pickupTile)
            val drop = entityAt(ins.dropTile)
            // A splitter is a pickup too: the dead-end splitter trick takes items off a splitter half whose output
            // leads nowhere (B14).
            val okPick = pick != null && pick.kind in setOf(Kind.BELT, Kind.SPLITTER, Kind.MACHINE, Kind.CHEST, Kind.INFINITY_CHEST)
            val okDrop = drop != null && drop.kind in setOf(Kind.BELT, Kind.MACHINE, Kind.CHEST, Kind.INFINITY_CHEST)
            if (!okPick) err(ErrorType.INSERTER_BAD_PICKUP, ins.tile, "${ins.name} picks from ${pick?.name ?: "nothing"} at ${ins.pickupTile}")
            if (!okDrop) err(ErrorType.INSERTER_BAD_DROP, ins.tile, "${ins.name} drops into ${drop?.name ?: "nothing"} at ${ins.dropTile}")
            // Out of a machine with several products, an unfiltered inserter takes all of them.
            if (pick?.kind == Kind.MACHINE) machineInfo(pick)?.let { mi ->
                val left = ins.blacklist?.let { bl -> mi.outputs.keys.filter { it !in bl } }
                if (ins.item == null && mi.multiProduct && (left == null || left.size != 1)) {
                    err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "${ins.name} takes ${(left ?: mi.outputs.keys).sorted()} out of ${pick.recipe}; it needs a filter that leaves one")
                }
                if (ins.item != null && (mi.filterAliases[ins.item] ?: ins.item) !in mi.outputs) err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "${ins.name} is filtered to ${ins.item}, which ${pick.recipe} does not make")
            }
        }
    }

    private fun checkInserterItems(belts: BeltGraph) {
        ents.filter { it.kind == Kind.INSERTER }.forEach { ins ->
            val pickIdx = indexAt(ins.pickupTile) ?: return@forEach
            val drop = entityAt(ins.dropTile) ?: return@forEach
            val pick = ents[pickIdx]
            val item = carriedByInserter(ins, belts)
            when (drop.kind) {
                Kind.MACHINE -> {
                    val info = machineInfo(drop) ?: return@forEach
                    if (pick.kind == Kind.BELT || pick.kind == Kind.SPLITTER) {
                        val onBelt = belts.items[pickIdx] ?: emptySet()
                        if (onBelt.isNotEmpty() && onBelt.none { it in info.ingredients }) {
                            err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "${ins.name} feeds ${drop.recipe} from a belt of ${onBelt.sorted()}")
                        }
                        // A recycler takes any item: from a belt of several, its inserter must be filtered.
                        if (drop.name == "recycler" && onBelt.any { it !in info.ingredients } && (ins.item == null || ins.item !in info.ingredients)) {
                            err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "${ins.name} feeds a recycler from a belt of ${onBelt.sorted()} without a filter")
                        }
                    } else if (item != null && item !in info.ingredients) {
                        err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "${ins.name} moves $item into ${drop.recipe}, which does not use it")
                    }
                }
                Kind.CHEST -> if (pick.kind != Kind.MACHINE && !isDrain(ins)) err(ErrorType.INSERTER_WRONG_ITEM, ins.tile, "chest is fed from ${pick.name}, not a machine")
                else -> {}
            }
        }
    }

    // ---- 4 and 6. reachability, prefix condition and rates ----
    private fun beltCapacity(e: PlacedEntity): Double = GameConstants.tierOfEntity(e.name)?.throughput ?: Double.MAX_VALUE

    private fun checkReachabilityOnly(belts: BeltGraph) {
        ents.forEach { ins ->
            if (ins.kind != Kind.INSERTER) return@forEach
            val p = indexAt(ins.pickupTile) ?: return@forEach
            if ((ents[p].kind == Kind.BELT || ents[p].kind == Kind.SPLITTER) && belts.items[p].isNullOrEmpty() && !isDrain(ins)) {
                err(ErrorType.UNREACHABLE, ins.pickupTile, "${ins.name} picks from a belt no item reaches")
            }
        }
    }

    private fun checkRates(belts: BeltGraph) {
        checkReachabilityOnly(belts)
        val inserters = ents.indices.filter { ents[it].kind == Kind.INSERTER }
        fun pickIdx(i: Int) = indexAt(ents[i].pickupTile)
        fun dropIdx(i: Int) = indexAt(ents[i].dropTile)
        fun fromBelt(i: Int) = pickIdx(i)?.let { ents[it].kind == Kind.BELT || ents[it].kind == Kind.SPLITTER } == true
        fun toBelt(i: Int) = dropIdx(i)?.let { ents[it].kind == Kind.BELT || ents[it].kind == Kind.SPLITTER || ents[it].kind == Kind.UG_IN || ents[it].kind == Kind.UG_OUT } == true
        fun cap(i: Int) = spec.inserterRate(ents[i].name, fromBelt(i), toBelt(i))

        val machines = ents.indices.filter { ents[it].kind == Kind.MACHINE }
        val info = machines.associateWith { machineInfo(ents[it]) }
        val outIns = machines.associateWith { m -> inserters.filter { pickIdx(it) == m } }
        val inIns = machines.associateWith { m -> inserters.filter { dropIdx(it) == m } }

        // Output and input inserter capacity per machine; sinks present.
        machines.forEach { m ->
            val mi = info[m] ?: return@forEach
            val e = ents[m]
            mi.outputs.forEach { (item, rate) ->
                val outCap = outIns.getValue(m).filter { machineOutput(it) == item }.sumOf { cap(it) }
                if (rate > TINY_RATE && outCap + 1e-9 < rate) {
                    val what = if (mi.outputs.size > 1) "$item " else ""
                    err(ErrorType.INSERTER_RATE, e.tile, "${e.recipe} makes ${fmt(rate)}/s ${what}but its output inserters move ${fmt(outCap)}/s")
                }
                if (mi.sinkOf(item) > 1e-9 && outIns.getValue(m).none { machineOutput(it) == item && dropIdx(it)?.let { d -> ents[d].kind == Kind.CHEST } == true }) {
                    err(ErrorType.MISSING_SINK, e.tile, "${e.recipe} makes $item for the sink but has no output chest for it")
                }
            }
            // Input inserters against demand. An inserter taking from a shared belt moves whichever of its items
            // the machine is short of, so capacity goes where it is needed: max flow inserter -> item -> demand.
            val feeders = inIns.getValue(m)
            val needs = mi.demand.entries.toList()
            val g = MaxFlow(2 + feeders.size + needs.size)
            feeders.forEachIndexed { k, ins -> g.edge(0, 2 + k, cap(ins)) }
            needs.forEachIndexed { j, (item, _) -> feeders.forEachIndexed { k, ins -> if (feederCarries(ins, item, belts)) g.edge(2 + k, 2 + feeders.size + j, cap(ins)) } }
            val demandEdge = needs.mapIndexed { j, (_, need) -> g.edge(2 + feeders.size + j, 1, need) }
            g.run(0, 1)
            needs.forEachIndexed { j, (item, need) ->
                if (need <= TINY_RATE) return@forEachIndexed   // a sliver (see TINY_RATE): nothing is routed for it
                if (feeders.none { feederCarries(it, item, belts) }) err(ErrorType.MISSING_INPUT, e.tile, "${e.recipe} gets no $item")
                else {
                    val c = g.flowOn(demandEdge[j])
                    if (c + 1e-9 < need) err(ErrorType.INSERTER_RATE, e.tile, "${e.recipe} needs ${fmt(need)}/s of $item, its inserters move ${fmt(c)}/s")
                }
            }
        }

        // Direct insertion: delivered = min(capacity, consumer share), scaled down to the producer's spare output (at
        // full speed: a machine whose output is taken runs as fast as it can).
        val delivered = HashMap<Int, Double>()
        inserters.filter { i -> pickIdx(i)?.let { ents[it].kind == Kind.MACHINE } == true && dropIdx(i)?.let { ents[it].kind == Kind.MACHINE } == true }
            .groupBy { pickIdx(it)!! to machineOutput(it)!! }
            .forEach { (key, list) ->
                val (p, item) = key
                val pi = info[p] ?: return@forEach
                val want = list.associateWith { i ->
                    val m = dropIdx(i)!!
                    val need = info[m]?.demand?.get(item) ?: 0.0
                    val nDi = inIns.getValue(m).count { feederIsMachineOf(it, item) }
                    min(cap(i), need / max(1, nDi))
                }
                val spare = max(0.0, (pi.fullRates[item] ?: pi.outputs[item] ?: 0.0) - pi.sinkOf(item))
                val total = want.values.sum()
                val scale = if (total > spare && total > 0) spare / total else 1.0
                want.forEach { (i, w) -> delivered[i] = w * scale }
            }

        // Producer outputs onto belts, after direct insertion.
        val beltRate = HashMap<Int, Double>()   // inserter -> items/s dropped onto its belt
        val beltOut = HashMap<Pair<Int, String>, Double>()
        val beltFull = HashMap<Pair<Int, String>, Double>()
        val toBelt = HashMap<Pair<Int, String>, List<Int>>()
        machines.forEach { m ->
            val mi = info[m] ?: return@forEach
            mi.outputs.forEach { (item, rate) ->
                val mine = outIns.getValue(m).filter { machineOutput(it) == item }
                toBelt[m to item] = mine.filter { dropIdx(it)?.let { d -> ents[d].kind == Kind.BELT } == true }
                val diOut = mine.sumOf { delivered[it] ?: 0.0 }
                beltOut[m to item] = max(0.0, rate - mi.sinkOf(item) - diOut)
                beltFull[m to item] = max(beltOut.getValue(m to item), (mi.fullRates[item] ?: rate) - mi.sinkOf(item) - diOut)
            }
        }
        inserters.filter { pickIdx(it)?.let { p -> ents[p].kind == Kind.INFINITY_CHEST } == true && dropIdx(it)?.let { d -> ents[d].kind == Kind.BELT } == true }
            .forEach { beltRate[it] = cap(it) }

        // Consumers: what each machine still needs of each item after direct insertion, drawn through any of its
        // belt inserters (on one chain or several): it takes items as they pass, upstream machines first.
        val comp = belts.components()
        /** [full]: what the machine takes running flat out (as a rounded-up machine does while its output is taken). */
        class Draw(val item: String, val quota: Double, val pickups: List<Int>, val full: Double = quota) { var left = quota }
        val draws = ArrayList<Draw>()
        machines.forEach { m ->
            val mi = info[m] ?: return@forEach
            mi.demand.forEach { (item, need) ->
                val diIn = inIns.getValue(m).filter { feederIsMachineOf(it, item) }.sumOf { delivered[it] ?: 0.0 }
                val rest = max(0.0, need - diIn)
                val fromBelts = inIns.getValue(m).filter { fromBelt(it) && belts.items[pickIdx(it)!!]?.contains(item) == true }
                if (rest < 1e-12 || fromBelts.isEmpty()) return@forEach
                val made = mi.outputs.values.sum()
                val speedUp = if (made > 1e-12 && mi.fullRates.isNotEmpty()) max(1.0, mi.fullRates.values.sum() / made) else 1.0
                draws += Draw(item, rest, fromBelts, rest + need * (speedUp - 1))
            }
        }
        val drawsAt = HashMap<Int, MutableList<Pair<Draw, Int>>>()   // pickup belt -> (draw, inserter)
        draws.forEach { d -> d.pickups.forEach { ins -> drawsAt.getOrPut(pickIdx(ins)!!) { mutableListOf() } += d to ins } }
        val outputsAt = HashMap<Int, MutableList<OutputSpec>>()
        spec.outputs.filter { !it.fluid }.forEach { o -> indexAt(o.tile)?.takeIf { ents[it].kind.beltFamily }?.let { outputsAt.getOrPut(it) { mutableListOf() } += o } }
        val outputList = outputsAt.flatMap { (b, os) -> os.map { b to it } }
        val drawList = draws.map { Triple(it.item, it.quota, it.pickups) }
        val outList = outputList.map { (b, o) -> Triple(o.item, o.rate, b) }
        // Once as the game divides split producers' output (for what comes up short), once steered as planned
        // (for the walk below: a full lane makes a split producer send the rest the other way).
        val drawFull = draws.map { it.full }
        val splitShort = allocateProducers(belts, comp, HashMap(beltRate), beltOut, beltFull, toBelt, ::cap, drawList, outList, drawFull, splitShares = true)
        val short = allocateProducers(belts, comp, beltRate, beltOut, beltFull, toBelt, ::cap, drawList, outList, drawFull, splitShares = false)
        // The max flow decides whether a chain is short of an item (a splitter sends items wherever they are
        // drawn); the walk below decides who starves when it is (upstream machines draw first).
        val shortGroups = HashSet<Pair<String, Int>>()
        draws.forEachIndexed { j, d -> if (short[j] > 1e-6) d.pickups.forEach { shortGroups += d.item to comp.getValue(pickIdx(it)!!) } }
        outputList.forEachIndexed { j, (b, o) -> if (short[draws.size + j] > 1e-6) shortGroups += o.item to comp.getValue(b) }
        // A machine putting one item onto separate belts, and a belt chain of that item comes up more than
        // SPLIT_TOLERANCE short of what its consumers and output ports take, once split producers divide their
        // output as the game does (see allocateProducers).
        val chainWant = HashMap<Pair<String, Int?>, Double>(); val chainMiss = HashMap<Pair<String, Int?>, Double>()
        drawList.forEachIndexed { j, d ->
            val c = d.third.firstOrNull()?.let { ins -> indexAt(ents[ins].pickupTile)?.let { comp[it] } }
            chainWant.merge(d.first to c, d.second, Double::plus); chainMiss.merge(d.first to c, splitShort[j], Double::plus)
        }
        outList.forEachIndexed { j, o ->
            chainWant.merge(o.first to comp[o.third], o.second, Double::plus); chainMiss.merge(o.first to comp[o.third], splitShort[draws.size + j], Double::plus)
        }
        val worst = HashMap<String, Pair<Double, Double>>()   // item -> (largest share a chain misses, items/s missing in all)
        chainMiss.forEach { (key, miss) ->
            val want = chainWant[key] ?: return@forEach
            if (miss <= 1e-6 || want <= 1e-9) return@forEach
            val (f, total) = worst[key.first] ?: (0.0 to 0.0)
            worst[key.first] = max(f, miss / want) to total + miss
        }
        splitProducers(comp, toBelt).forEach { (key, chains) ->
            val (m, item) = key
            val (f, miss) = worst[item]?.takeIf { it.first > SPLIT_TOLERANCE } ?: return@forEach
            err(ErrorType.SPLIT_OUTPUT, ents[m].tile, "${ents[m].recipe} puts $item onto ${chains.size} separate belts; the game divides its output " +
                "between them by inserter speed wherever there is room, not as planned, and then ${fmt(miss)}/s of $item is missing " +
                "(up to ${(f * 100).toInt()}% of what one belt's consumers take)")
        }

        // Walk belt chains in flow order, lane by lane: consumers draw what passes, lane and belt capacity hold.
        // Straight feeds and curves keep lanes, a sideload goes to the near lane, an inserter drops on the far
        // lane, a splitter divides each lane by what the consumers below each side draw of its item.
        val addL = HashMap<Int, Double>(); val addR = HashMap<Int, Double>()
        // What of it arrived unstacked (belt stacking: dropped by an inserter other than a stack inserter).
        val addUL = HashMap<Int, Double>(); val addUR = HashMap<Int, Double>()
        val stack = spec.beltStack.coerceAtLeast(1)
        spec.sources.filter { !it.fluid }.forEach { s ->
            indexAt(s.tile)?.let {
                when (s.lane) {
                    0 -> addL.merge(it, s.rate, Double::plus)
                    1 -> addR.merge(it, s.rate, Double::plus)
                    else -> { addL.merge(it, s.rate / 2, Double::plus); addR.merge(it, s.rate / 2, Double::plus) }
                }
            }
        }
        beltRate.forEach { (ins, r) ->
            val b = dropIdx(ins)?.takeIf { ents[it].kind == Kind.BELT } ?: return@forEach
            val left = dropLane(ents[ins], b, belts) == 0
            if (left) addL.merge(b, r, Double::plus) else addR.merge(b, r, Double::plus)
            if (stack > 1 && ents[ins].name != GameConstants.STACK_INSERTER) (if (left) addUL else addUR).merge(b, r, Double::plus)
        }
        // Expected draw per belt and item (a machine's share spread over its pickups), for splitting at splitters.
        val est = HashMap<Int, HashMap<String, Double>>()
        draws.forEach { d -> d.pickups.forEach { ins -> est.getOrPut(pickIdx(ins)!!) { HashMap() }.merge(d.item, d.quota / d.pickups.size, Double::plus) } }
        outputsAt.forEach { (b, os) -> os.forEach { o -> est.getOrPut(b) { HashMap() }.merge(o.item, o.rate, Double::plus) } }
        val succs = belts.next
        val below = HashMap<Int, Double>()
        fun estAt(i: Int) = est[i]?.values?.sum() ?: 0.0
        fun demandBelow(i: Int, depth: Int = 0): Double = below[i] ?: run {
            below[i] = estAt(i)   // guards cycles
            val v = estAt(i) + if (depth > 4000) 0.0 else (succs[i] ?: emptyList()).sumOf { demandBelow(it, depth + 1) }
            below[i] = v; v
        }
        val belowItem = HashMap<Pair<Int, String>, Double>()
        fun demandBelow(i: Int, item: String, depth: Int = 0): Double = belowItem[i to item] ?: run {
            val here = est[i]?.get(item) ?: 0.0
            belowItem[i to item] = here
            val v = here + if (depth > 4000) 0.0 else (succs[i] ?: emptyList()).filter { passes(i, it, item, belts) }.sumOf { demandBelow(it, item, depth + 1) }
            belowItem[i to item] = v; v
        }
        val indeg = HashMap<Int, Int>()
        succs.forEach { (_, ns) -> ns.forEach { indeg.merge(it, 1, Int::plus) } }
        val inL = HashMap<Int, Double>(); val inR = HashMap<Int, Double>()
        val inUL = HashMap<Int, Double>(); val inUR = HashMap<Int, Double>()
        val queue = ArrayDeque(ents.indices.filter { ents[it].kind.beltFamily && (indeg[it] ?: 0) == 0 })
        val done = HashSet<Int>()
        val order = HashMap<Int, Int>()
        val insLeft = HashMap<Int, Double>()
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (!done.add(i)) continue
            order[i] = order.size
            val e = ents[i]
            var l = (inL[i] ?: 0.0) + (addL[i] ?: 0.0)
            var r = (inR[i] ?: 0.0) + (addR[i] ?: 0.0)
            var ul = (inUL[i] ?: 0.0) + (addUL[i] ?: 0.0)
            var ur = (inUR[i] ?: 0.0) + (addUR[i] ?: 0.0)
            val laneCap = beltCapacity(e) / 2 * (if (e.kind == Kind.SPLITTER) 2 else 1)
            // Slots a lane uses per second: a stack takes one, an unstacked item one.
            fun slots(all: Double, loose: Double) = (all - loose) / stack + loose
            if (slots(l, ul) > laneCap + 1e-6 || slots(r, ur) > laneCap + 1e-6) {
                err(ErrorType.BELT_OVER_CAPACITY, e.tile, "lanes carry ${fmt(l)}/s and ${fmt(r)}/s (${belts.items[i]?.joinToString() ?: "?"}); a lane holds ${fmt(laneCap)}/s" +
                    if (stack > 1) " (${fmt(laneCap * stack)}/s in stacks of $stack; ${fmt(ul)}/s and ${fmt(ur)}/s unstacked)" else "")
            }
            // Takes up to [want] of [item] from the lane(s) that carry it; a belt with one item on both lanes
            // drains both evenly.
            fun draw(item: String, want: Double): Double {
                val mask = belts.lanesWith(i, item)
                val have = (if (mask and 1 != 0) l else 0.0) + (if (mask and 2 != 0) r else 0.0)
                val t = min(want, have)
                if (have > 1e-12) {
                    val keep = max(0.0, 1 - t / have)
                    if (mask and 1 != 0) { l *= keep; ul *= keep }
                    if (mask and 2 != 0) { r *= keep; ur *= keep }
                }
                return t
            }
            drawsAt[i]?.forEach { (d, ins) ->
                if (d.left <= 1e-12) return@forEach
                val c = insLeft.getOrPut(ins) { cap(ins) }
                val t = draw(d.item, min(d.left, c))
                d.left -= t
                insLeft[ins] = c - t
            }
            outputsAt[i]?.forEach { o ->
                val t = draw(o.item, o.rate)
                if (t + 1e-6 < o.rate && (o.item to comp.getValue(i)) in shortGroups) err(ErrorType.STARVED, e.tile, "the ${o.item} output port gets ${fmt(t)}/s of ${fmt(o.rate)}/s")
            }
            val outs = succs[i] ?: emptyList()
            fun shares(lane: Int): List<Double> {
                if (outs.size == 1) return listOf(1.0)
                val item = belts.laneItems[i]?.get(lane)?.singleOrNull()
                // What the max flow sends through here to consumers below each side (a consumer below both counts
                // half on each): where several supplies feed the same consumers, demand below would count them on
                // every side that reaches them, and send one supply more than the consumers take of it.
                if (item != null) {
                    val via = DoubleArray(outs.size)
                    routes.forEach { r ->
                        if (r.item != item || i !in reachOf(r.from, item)) return@forEach
                        val sides = outs.indices.filter { k -> passes(i, outs[k], item, belts) && r.to in reachOf(outs[k], item) }
                        sides.forEach { k -> via[k] += r.flow / sides.size }
                    }
                    val sum = via.sum()
                    if (sum > 1e-9) return via.map { it / sum }
                }
                val wants = outs.map { if (item != null) demandBelow(it, item) else demandBelow(it) }
                val sum = wants.sum()
                return wants.map { if (sum > 1e-12) it / sum else 1.0 / outs.size }
            }
            val shareL = shares(0); val shareR = shares(1)
            outs.forEachIndexed { k, n ->
                val (toL, toR) = laneInto(e, ents[n], belts)
                val fl = l * shareL[k]; val fr = r * shareR[k]
                val ufl = ul * shareL[k]; val ufr = ur * shareR[k]
                // toL / toR: where this node's left and right lanes land on the successor (0 = left, 1 = right,
                // -1 = blocked by an underground's hood: those items stop there and back up).
                if ((toL < 0 && fl > 1e-6) || (toR < 0 && fr > 1e-6)) {
                    val lane = if (toL < 0) 0 else 1
                    val what = belts.laneItems[i]?.get(lane)?.sorted() ?: emptyList<String>()
                    err(ErrorType.LANE_BLOCKED, ents[n].tile, "${fmt(if (toL < 0) fl else fr)}/s of $what arrive on the lane an underground's hood blocks (side feed)")
                }
                if (toL == 0) inL.merge(n, fl, Double::plus) else if (toL == 1) inR.merge(n, fl, Double::plus)
                if (toR == 0) inL.merge(n, fr, Double::plus) else if (toR == 1) inR.merge(n, fr, Double::plus)
                if (toL == 0) inUL.merge(n, ufl, Double::plus) else if (toL == 1) inUR.merge(n, ufl, Double::plus)
                if (toR == 0) inUL.merge(n, ufr, Double::plus) else if (toR == 1) inUR.merge(n, ufr, Double::plus)
                indeg[n] = (indeg[n] ?: 1) - 1
                if (indeg[n] == 0) queue += n
            }
        }
        // A machine that did not get its share along a chain: reported at its last pickup on that chain.
        draws.forEach { d ->
            if (d.left <= 1e-6 || d.pickups.none { (d.item to comp.getValue(pickIdx(it)!!)) in shortGroups }) return@forEach
            val at = d.pickups.map { pickIdx(it)!! }.maxByOrNull { order[it] ?: -1 }!!
            err(ErrorType.STARVED, ents[at].tile, "consumers here need ${fmt(d.quota)}/s of ${d.item} but only ${fmt(d.quota - d.left)}/s arrives (net ${ents[at].net})")
        }
    }

    /**
     * Lane (0 left, 1 right of the belt's heading) inserter [ins] drops onto belt entity [b]: the far one, on a
     * curve the far side of the bend (see [GameConstants.dropLane]). [P] VERIFY (v2)
     */
    private fun dropLane(ins: PlacedEntity, b: Int, belts: BeltGraph): Int {
        val belt = ents[b]
        return GameConstants.dropLane(ins.dir.opposite, curveInto(b, belts) ?: belt.dir, belt.dir)
    }

    /** The heading items enter belt [b] with when it is a curve (fed from one side only), else null. */
    private fun curveInto(b: Int, belts: BeltGraph): Dir? {
        val f = ents[b]
        if (f.kind != Kind.BELT) return null
        val feeders = belts.prev[b] ?: return null
        if (feeders.size != 1 || feeders.any { ents[it].dir == f.dir }) return null
        return ents[feeders[0]].dir
    }

    /**
     * Where [e]'s left and right lanes land on successor [f] (0 left, 1 right, -1 blocked): kept for a straight
     * feed, a curve, an underground or a splitter; both onto the near lane for a sideload. Fed from the side, an
     * underground end takes only the lane on its open half ([GameConstants.ugSideFeedPasses]); the other lane is
     * blocked and backs up.
     */
    private fun laneInto(e: PlacedEntity, f: PlacedEntity, belts: BeltGraph): Pair<Int, Int> {
        if (e.kind == Kind.UG_IN || f.kind == Kind.SPLITTER || e.dir == f.dir) return 0 to 1
        if (f.kind == Kind.BELT) {
            val feeders = belts.prev[indexAt(f.tile)!!] ?: emptyList()
            val rear = feeders.any { ents[it].dir == f.dir }
            if (!rear && feeders.size == 1) return 0 to 1     // a curve keeps lanes
        }
        // Sideload: arriving from the belt's left side (moving towards its right) lands on its left lane.
        val near = if (e.dir == f.dir.cw) 0 else 1
        if ((f.kind == Kind.UG_IN || f.kind == Kind.UG_OUT) && e.dir.axis != f.dir.axis) {
            val pass = GameConstants.ugSideFeedPasses(e.dir, f.dir, exit = f.kind == Kind.UG_OUT)
            return if (pass == 0) near to -1 else -1 to near
        }
        return near to near
    }

    /** Whether some of [item] on belt-family [i] can move on to its successor [n] (not only on a blocked lane). */
    private fun passes(i: Int, n: Int, item: String, belts: BeltGraph): Boolean {
        val (a, b) = laneInto(ents[i], ents[n], belts)
        if (a >= 0 && b >= 0) return true
        val mask = belts.lanesWith(i, item)
        return mask == 0 || (mask and 1 != 0 && a >= 0) || (mask and 2 != 0 && b >= 0)
    }

    /**
     * Where each producer's belt output goes. Within one belt chain a producer's output goes wherever it is
     * taken (a full belt backs up and its inserters wait), and an item dropped on a belt reaches only what lies
     * downstream of the drop. A producer whose inserters drop onto separate chains cannot steer its output: each
     * inserter takes whenever its belt has room, so it divides between them by inserter speed (in game, a
     * machine planned to send nearly everything one way sent half each way, and the factory starved). The
     * producers of one item share the item's planned total, each up to full speed ([MachineSpec.fullRates]).
     * Max flow: item pool -> producer -> each of its belt inserters (capped by the inserter's rate, and by its
     * share for a split producer) -> each consumer inserter of its item picking downstream of the drop (capped by that inserter's
     * rate) -> the machine's draw of the item along that chain -> sink (capped by the draw); output ports
     * take directly. Fixed supplies (ports, harness chests) join as sources at their rate. Each producer
     * inserter's belt rate is the flow through it; what the flow cannot meet shows up as starvation.
     */
    /** A max-flow route: [flow] items/s of [item] dropped on belt [from] taken at belt [to] (a pickup or an output port). */
    private class Route(val from: Int, val to: Int, val item: String, val edge: Int, var flow: Double = 0.0)
    /** The routes [allocateProducers] found that carry anything. */
    private var routes: List<Route> = emptyList()
    /** Belts an item dropped on a belt can reach (set by [allocateProducers]). */
    private var reachOf: (Int, String?) -> Set<Int> = { b, _ -> setOf(b) }

    private fun allocateProducers(
        belts: BeltGraph, comp: Map<Int, Int>, beltRate: HashMap<Int, Double>, beltOut: Map<Pair<Int, String>, Double>,
        beltFull: Map<Pair<Int, String>, Double>, toBelt: Map<Pair<Int, String>, List<Int>>,
        cap: (Int) -> Double,
        /** Machine draws: (item, items/s, consumer inserters that may serve it). */
        draws: List<Triple<String, Double, List<Int>>>,
        /** Output ports: (item, items/s, belt). */
        outputs: List<Triple<String, Double, Int>>,
        /** What each draw takes running flat out. */
        drawFull: List<Double>,
        /** Split producers divide their output as the game does (true, see [splitCaps]), or as the flow needs (false). */
        splitShares: Boolean,
    ): DoubleArray {
        val producers = toBelt.filter { it.value.isNotEmpty() && (beltOut[it.key] ?: 0.0) > 1e-12 }.keys.toList()
        fun dropBelt(ins: Int) = indexAt(ents[ins].dropTile)?.takeIf { ents[it].kind.beltFamily }
        val fixed = ArrayList<Triple<Int, String, Double>>()   // belt, item, items/s
        spec.sources.filter { !it.fluid }.forEach { s -> indexAt(s.tile)?.takeIf { ents[it].kind.beltFamily }?.let { fixed += Triple(it, s.item, s.rate) } }
        beltRate.forEach { (ins, r) -> if (r > 0) dropBelt(ins)?.let { b -> carriedByInserter(ents[ins])?.let { fixed += Triple(b, it, r) } } }

        val reach = HashMap<Pair<Int, String?>, Set<Int>>()
        /** Belts [item] dropped on [b] can reach (not through a lane an underground's hood blocks). */
        fun downstream(b: Int, item: String?): Set<Int> = reach.getOrPut(b to item) {
            val seen = hashSetOf(b)
            val q = ArrayDeque(listOf(b))
            while (q.isNotEmpty()) {
                val u = q.removeFirst()
                belts.next[u]?.forEach { if ((item == null || passes(u, it, item, belts)) && seen.add(it)) q += it }
            }
            seen
        }
        val insList = producers.flatMap { m -> toBelt.getValue(m).map { m to it } }
        /**
         * What [ins] can put on the belt it drops onto: its rate, but no more than the one lane it drops onto holds (a
         * bulk inserter outruns a lane).
         */
        fun dropCap(ins: Int): Double {
            val b = dropBelt(ins) ?: return cap(ins)
            val stack = spec.beltStack.coerceAtLeast(1)
            val lane = beltCapacity(ents[b]) / 2 * (if (stack > 1 && ents[ins].name == GameConstants.STACK_INSERTER) stack else 1)
            return minOf(cap(ins), lane)
        }
        // A machine on separate belt chains makes its planned rate and divides it between them in proportion to its
        // inserters' speeds: each inserter takes from the machine whenever its belt has room.
        val split = if (splitShares) splitProducers(comp, toBelt).keys else emptySet()
        val capOf = HashMap<Int, Double>()   // producer inserter -> what it may move (split producers)
        split.forEach { m -> capOf += splitCaps(m, comp, beltOut, beltFull, toBelt, cap, draws, outputs, drawFull) { b -> downstream(b, null) } }
        /** One max flow with each producer inserter capped by [insCap]; what each draw (then each output) misses. */
        fun solve(insCap: (Pair<Int, String>, Int) -> Double): DoubleArray {
            // Consumer inserter nodes, one per (inserter, item), each capped by the inserter's rate.
            val pickNodes = draws.flatMap { (item, _, list) -> list.map { it to item } }.distinct()
            // Nodes: 0 source, 1 sink, producers, producer inserters, fixed supplies, pickups (in, out), draws, outputs.
            val pBase = 2; val iBase = pBase + producers.size; val fBase = iBase + insList.size; val kBase = fBase + fixed.size
            val dBase = kBase + 2 * pickNodes.size; val oBase = dBase + draws.size
            val poolOf = producers.map { it.second }.distinct().withIndex().associate { (k, item) -> item to oBase + outputs.size + k }
            val g = MaxFlow(oBase + outputs.size + poolOf.size)
            val pickIndex = pickNodes.withIndex().associate { (k, p) -> p to kBase + 2 * k }
            pickNodes.forEachIndexed { k, (ins, _) -> g.edge(kBase + 2 * k, kBase + 2 * k + 1, cap(ins)) }
            val sinkEdge = IntArray(draws.size + outputs.size)
            draws.forEachIndexed { j, (item, q, list) ->
                list.forEach { ins -> g.edge(pickIndex.getValue(ins to item) + 1, dBase + j, Double.MAX_VALUE) }
                sinkEdge[j] = g.edge(dBase + j, 1, q)
            }
            outputs.forEachIndexed { j, (_, r, _) -> sinkEdge[draws.size + j] = g.edge(oBase + j, 1, r) }
            val allRoutes = ArrayList<Route>()
            fun connect(from: Int, b: Int, item: String?) {
                val down = downstream(b, item)
                pickNodes.forEachIndexed { k, (ins, it2) ->
                    val p = indexAt(ents[ins].pickupTile)
                    if (it2 == item && p != null && p in down) allRoutes += Route(b, p, item, g.edge(from, kBase + 2 * k, Double.MAX_VALUE))
                }
                outputs.forEachIndexed { j, (it2, _, ob) -> if (it2 == item && ob in down) allRoutes += Route(b, ob, item, g.edge(from, oBase + j, Double.MAX_VALUE)) }
            }
            // The machines making one item share its work: together they make what the plan says, each up to full
            // speed (planned rates first, then faster where something is still short).
            poolOf.forEach { (item, node) -> g.edge(0, node, producers.filter { it.second == item }.sumOf { beltOut.getValue(it) }) }
            val prodEdge = producers.mapIndexed { k, m -> g.edge(poolOf.getValue(m.second), pBase + k, beltOut.getValue(m)) }
            val insEdge = insList.mapIndexed { k, (m, ins) ->
                val e = g.edge(pBase + producers.indexOf(m), iBase + k, min(insCap(m, ins), capOf[ins] ?: Double.MAX_VALUE))
                dropBelt(ins)?.let { b -> connect(iBase + k, b, m.second) }
                e
            }
            fixed.forEachIndexed { k, (b, item, r) -> g.edge(0, fBase + k, r); connect(fBase + k, b, item) }
            g.run(0, 1)
            producers.forEachIndexed { k, m -> g.raise(prodEdge[k], (beltFull[m] ?: 0.0) - beltOut.getValue(m)) }
            g.run(0, 1)
            insList.forEachIndexed { k, (_, ins) -> beltRate[ins] = g.flowOn(insEdge[k]) }
            allRoutes.forEach { it.flow = g.flowOn(it.edge) }
            routes = allRoutes.filter { it.flow > 1e-9 }
            reachOf = { b, item -> downstream(b, item) }
            // What each draw (then each output) misses.
            return DoubleArray(sinkEdge.size) { j -> (if (j < draws.size) draws[j].second else outputs[j - draws.size].second) - g.flowOn(sinkEdge[j]) }
        }
        /**
         * How much each producer inserter drops, with no lane of any belt tile carrying more than a lane holds: per
         * item, a max flow over belt lanes (items keep their lane straight on, round a curve and into an underground;
         * a sideload puts them on the near lane; an underground's hood blocks one lane of a side feed) from producer
         * inserters and supplies to the inserters taking the item and to output ports. In the game a machine's
         * inserters share its output and one whose lane is full waits, so a split like this is what settles.
         * Producers make their planned rate first, then up to full speed where a draw is still short (as in [solve]).
         * Items for which no split meets every draw are left out of the map and listed in the second value.
         */
        fun laneDrops(): Pair<Map<Int, Double>, Set<Int>> {
            val out = HashMap<Int, Double>()
            val failed = HashSet<Int>()   // producer inserters on a chain with no lane-true split
            // Per item and belt chain: one chain without a lane-true split must not send every chain of the item
            // to the rough split (in a factory of blocks that piled lanes full in blocks that were fine).
            val chains = (draws.map { it.first } + outputs.map { it.first }).distinct().flatMap { item ->
                ents.indices.filter { ents[it].kind.beltFamily && belts.items[it]?.contains(item) == true }.groupBy { comp[it] }.values.map { item to it }
            }
            for ((item, bs) in chains) {
                if (bs.isEmpty()) continue
                val at = HashMap<Int, Int>().also { m -> bs.forEachIndexed { k, b -> m[b] = k } }
                fun laneIn(b: Int, lane: Int) = 2 + 4 * at.getValue(b) + 2 * lane
                val myIns = producers.filter { it.second == item }.flatMap { m -> toBelt.getValue(m).map { m to it } }.filter { (_, ins) -> dropBelt(ins)?.let { it in at } == true }
                val mine = myIns.map { it.first }.distinct()
                // A producer also dropping on other chains gives this one its inserters' share.
                fun share(m: Pair<Int, String>): Double {
                    val all = toBelt.getValue(m); val here = all.filter { dropBelt(it)?.let { b -> b in at } == true }
                    return if (here.size == all.size) 1.0 else here.sumOf { dropCap(it) } / all.sumOf { dropCap(it) }.coerceAtLeast(1e-9)
                }
                // Supplies: input ports (on their lane, or half on each) and harness chests' inserters.
                val supplies = ArrayList<Triple<Int, Int, Double>>()
                spec.sources.filter { !it.fluid && it.item == item }.forEach { src ->
                    indexAt(src.tile)?.takeIf { it in at }?.let { b -> if (src.lane >= 0) supplies += Triple(b, src.lane, src.rate) else { supplies += Triple(b, 0, src.rate / 2); supplies += Triple(b, 1, src.rate / 2) } }
                }
                beltRate.forEach { (ins, r) ->
                    if (r > 0 && carriedByInserter(ents[ins]) == item) dropBelt(ins)?.takeIf { it in at && ents[it].kind == Kind.BELT }?.let { b -> supplies += Triple(b, dropLane(ents[ins], b, belts), r) }
                }
                // The draws this chain serves: those picking from it. A draw also picking from other chains takes
                // what this one can give but need not get all of it here.
                fun picksHere(d: Triple<String, Double, List<Int>>) = d.third.count { ins -> indexAt(ents[ins].pickupTile)?.let { it in at } == true }
                val myDraws = draws.withIndex().filter { it.value.first == item && picksHere(it.value) > 0 }
                val onlyHere = myDraws.map { picksHere(it.value) == it.value.third.size }
                val myPicks = myDraws.flatMap { (j, d) -> d.third.mapNotNull { ins -> indexAt(ents[ins].pickupTile)?.takeIf { it in at }?.let { Triple(j, ins, it) } } }
                val myOuts = outputs.withIndex().filter { it.value.first == item && it.value.third in at }
                val pBase = 2 + 4 * bs.size; val iBase = pBase + mine.size; val sBase = iBase + myIns.size
                val kBase = sBase + supplies.size; val dBase = kBase + myPicks.size; val oBase = dBase + myDraws.size
                val g = MaxFlow(oBase + myOuts.size)
                for (b in bs) {
                    val e = ents[b]
                    val laneCap = beltCapacity(e) / 2 * (if (e.kind == Kind.SPLITTER) 2 else 1)
                    val mask = belts.lanesWith(b, item)
                    for (lane in 0..1) if (mask == 0 || mask and (1 shl lane) != 0) g.edge(laneIn(b, lane), laneIn(b, lane) + 1, laneCap)
                    belts.next[b]?.forEach { n ->
                        if (n !in at) return@forEach
                        val (a, c) = laneInto(e, ents[n], belts)
                        if (a >= 0) g.edge(laneIn(b, 0) + 1, laneIn(n, a), Double.MAX_VALUE)
                        if (c >= 0) g.edge(laneIn(b, 1) + 1, laneIn(n, c), Double.MAX_VALUE)
                    }
                }
                val prodEdge = mine.mapIndexed { k, m -> g.edge(0, pBase + k, beltOut.getValue(m) * share(m)) }
                val insEdge = myIns.mapIndexed { k, (m, ins) ->
                    val b = dropBelt(ins)!!
                    g.edge(iBase + k, laneIn(b, dropLane(ents[ins], b, belts)), Double.MAX_VALUE)
                    g.edge(pBase + mine.indexOf(m), iBase + k, dropCap(ins))
                }
                supplies.forEachIndexed { k, (b, lane, r) -> g.edge(0, sBase + k, r); g.edge(sBase + k, laneIn(b, lane), Double.MAX_VALUE) }
                myPicks.forEachIndexed { k, (j, ins, b) ->
                    for (lane in 0..1) g.edge(laneIn(b, lane) + 1, kBase + k, Double.MAX_VALUE)
                    g.edge(kBase + k, dBase + myDraws.indexOfFirst { it.index == j }, cap(ins))
                }
                val sink = myDraws.mapIndexed { k, (_, d) -> g.edge(dBase + k, 1, d.second) } +
                    myOuts.mapIndexed { k, (_, o) -> for (lane in 0..1) g.edge(laneIn(o.third, lane) + 1, oBase + k, Double.MAX_VALUE); g.edge(oBase + k, 1, o.second) }
                g.run(0, 1)
                val want = myDraws.map { it.value.second } + myOuts.map { it.value.second }
                // Draws picking from other chains too are not required here (indices past the draws are outputs).
                fun short() = sink.indices.any { (it >= myDraws.size || onlyHere[it]) && g.flowOn(sink[it]) < want[it] - 1e-6 }
                if (short()) {
                    mine.forEachIndexed { k, m -> g.raise(prodEdge[k], ((beltFull[m] ?: 0.0) - beltOut.getValue(m)) * share(m)) }
                    g.run(0, 1)
                }
                if (short()) {
                    myIns.forEach { failed += it.second }
                    // LANE_DEBUG=1: what a chain without a lane-true split has and needs.
                    if (System.getenv("LANE_DEBUG") != null) {
                        val tiles = bs.map { Tile(ents[it].x, ents[it].y) }
                        System.err.println("LANE_DEBUG $item chain of ${bs.size} belts x ${tiles.minOf { it.x }}..${tiles.maxOf { it.x }} y ${tiles.minOf { it.y }}..${tiles.maxOf { it.y }}: " +
                            "producers ${mine.size} (planned ${"%.2f".format(mine.sumOf { beltOut.getValue(it) * share(it) })}, full ${"%.2f".format(mine.sumOf { (beltFull[it] ?: 0.0) * share(it) })}), " +
                            "supplies ${"%.2f".format(supplies.sumOf { it.third })}, draws ${"%.2f".format(myDraws.sumOf { it.value.second })} over ${myPicks.size} pickups, outputs ${"%.2f".format(myOuts.sumOf { it.value.second })}")
                        mine.forEach { m -> System.err.println("LANE_DEBUG   producer at ${ents[m.first].x},${ents[m.first].y} planned ${"%.2f".format(beltOut.getValue(m))} share ${"%.2f".format(share(m))} inserters ${toBelt.getValue(m).size}") }
                        sink.indices.filter { g.flowOn(sink[it]) < want[it] - 1e-6 }.forEach { k -> System.err.println("LANE_DEBUG   short: sink $k gets ${"%.2f".format(g.flowOn(sink[k]))} of ${"%.2f".format(want[k])}") }
                    }
                    continue
                }
                myIns.forEachIndexed { k, (_, ins) -> out[ins] = g.flowOn(insEdge[k]) }
            }
            return out to failed
        }
        // First the lane-true split (each producer inserter capped at what it drops there); where that misses (an
        // approximation: items sharing a lane are counted apart), any split, each up to its rate and lane.
        val (lanes, failed) = laneDrops()
        // Items with a lane-true split keep it; the others (an approximation: items sharing a lane are counted apart)
        // may split any way, each inserter up to its rate and lane. If that does worse than any split for every item,
        // any split for every item.
        val mixed = { _: Pair<Int, String>, ins: Int -> if (ins in failed) dropCap(ins) else (lanes[ins] ?: 0.0) + 1e-9 }
        // Per item: the lane-true split stands unless a split ignoring lanes meets more of that item's draws and
        // outputs (items do not share a flow). Deciding for all items at once let one item's gain pile another's lane
        // past what it holds: a 52/s rail link carried 33/s on one lane of a stitched factory.
        val r = solve(mixed)
        if (r.all { it < 1e-6 }) return r
        val any = solve { _, ins -> dropCap(ins) }
        val itemOf = draws.map { it.first } + outputs.map { it.first }
        val better = itemOf.indices.groupBy { itemOf[it] }.filter { (_, js) -> js.sumOf { any[it] } < js.sumOf { r[it] } - 1e-6 }.keys
        if (better.isEmpty()) return r
        return solve { m, ins -> if (m.second in better) dropCap(ins) else mixed(m, ins) }
    }

    /**
     * How a producer on separate belt chains divides its output in the game, per inserter. Each chain pulls what
     * the consumers downstream of the producer's drop take running flat out (a machine whose output is taken runs
     * as fast as it can, and machine counts are rounded up), less what producers upstream of the drop bring it
     * running flat out (they fill its gaps first; producers downstream of it get what is left, so it takes their
     * share); a chain gets at most its inserters' share of the machine's speed, and what one chain cannot take
     * goes to the others (a full belt makes its inserter wait).
     */
    private fun splitCaps(
        m: Pair<Int, String>, comp: Map<Int, Int>, beltOut: Map<Pair<Int, String>, Double>, beltFull: Map<Pair<Int, String>, Double>,
        toBelt: Map<Pair<Int, String>, List<Int>>, cap: (Int) -> Double,
        draws: List<Triple<String, Double, List<Int>>>, outputs: List<Triple<String, Double, Int>>, drawFull: List<Double>,
        downstream: (Int) -> Set<Int>,
    ): Map<Int, Double> {
        val item = m.second
        fun dropOf(ins: Int) = indexAt(ents[ins].dropTile)
        val mine = toBelt.getValue(m)
        val byChain = mine.groupBy { dropOf(it)?.let { d -> comp[d] } }
        // A machine whose outputs are all taken runs as fast as it can: the split only bites at full speed.
        val total = max(beltOut.getValue(m), beltFull[m] ?: 0.0)
        val allCap = mine.sumOf { cap(it) }
        val pull = byChain.mapValues { (c, list) ->
            val down = list.mapNotNull { dropOf(it) }.flatMap { downstream(it) }.toSet()
            var downDraw = 0.0; var upDraw = 0.0
            draws.forEachIndexed { j, (it2, _, pickups) ->
                if (it2 != item) return@forEachIndexed
                pickups.forEach { ins ->
                    val b = indexAt(ents[ins].pickupTile) ?: return@forEach
                    if (comp[b] != c) return@forEach
                    if (b in down) downDraw += drawFull[j] / pickups.size else upDraw += drawFull[j] / pickups.size
                }
            }
            outputs.forEach { (it2, r, b) -> if (it2 == item && b in down) downDraw += r }
            var upSupply = 0.0
            toBelt.forEach { (o, oList) ->
                if (o == m || o.second != item) return@forEach
                oList.forEach { ins -> val d = dropOf(ins) ?: return@forEach
                    if (comp[d] == c && list.any { mi -> dropOf(mi)?.let { it in downstream(d) } == true })
                        upSupply += min(cap(ins), (beltFull[o] ?: beltOut.getValue(o)) / oList.size) }
            }
            spec.sources.filter { !it.fluid && it.item == item }.forEach { s ->
                val b = indexAt(s.tile) ?: return@forEach
                if (comp[b] == c && list.any { mi -> dropOf(mi)?.let { it in downstream(b) } == true }) upSupply += s.rate
            }
            max(0.0, downDraw - max(0.0, upSupply - upDraw))
        }
        // Water-filling: chains that pull less than their inserters' share take what they pull; the rest is shared
        // by the others in proportion to their inserters.
        val take = HashMap<Int?, Double>()
        var open = byChain.keys.toMutableSet()
        var left = total
        while (open.isNotEmpty()) {
            val capOpen = open.sumOf { c -> byChain.getValue(c).sumOf { cap(it) } }
            val limited = open.filter { c -> pull.getValue(c) < left * byChain.getValue(c).sumOf { cap(it) } / capOpen }
            if (limited.isEmpty()) { open.forEach { c -> take[c] = left * byChain.getValue(c).sumOf { cap(it) } / capOpen }; break }
            limited.forEach { c -> take[c] = pull.getValue(c); left -= pull.getValue(c) }
            open = (open - limited.toSet()).toMutableSet()
        }
        if (allCap <= 0) return emptyMap()
        return mine.associateWith { ins -> val c = dropOf(ins)?.let { comp[it] }; (take[c] ?: 0.0) * cap(ins) / byChain.getValue(c).sumOf { cap(it) } }
    }

    /** Producers (machine, item) whose belt inserters drop onto more than one belt chain, with those chains. */
    private fun splitProducers(comp: Map<Int, Int>, toBelt: Map<Pair<Int, String>, List<Int>>): Map<Pair<Int, String>, Set<Int>> =
        toBelt.mapValues { (_, list) -> list.mapNotNull { ins -> indexAt(ents[ins].dropTile)?.let { comp[it] } }.toSet() }.filterValues { it.size > 1 }

    /** Item output ports: a belt of the item on the port tile that leads out of the box. */
    private fun checkOutputs(belts: BeltGraph) {
        val box = Rect(0, 0, layout.w, layout.h)
        spec.outputs.filter { !it.fluid }.forEach { o ->
            val i = indexAt(o.tile)
            val e = i?.let { ents[it] }
            when {
                e == null || !e.kind.beltFamily -> err(ErrorType.OUTPUT_MISSING, o.tile, "output port of ${o.item} has no belt")
                belts.items[i]?.contains(o.item) != true -> err(ErrorType.OUTPUT_MISSING, o.tile, "no ${o.item} reaches its output port")
                e.outputTile?.let { it in box } != false -> err(ErrorType.OUTPUT_MISSING, o.tile, "the belt at the ${o.item} output port does not lead out of the box")
            }
        }
    }

    private fun feederIsMachineOf(ins: Int, item: String): Boolean {
        val p = indexAt(ents[ins].pickupTile) ?: return false
        return ents[p].kind == Kind.MACHINE && machineOutput(ins) == item
    }

    private fun feederCarries(ins: Int, item: String, belts: BeltGraph): Boolean {
        val p = indexAt(ents[ins].pickupTile) ?: return false
        val src = ents[p]
        return when (src.kind) {
            Kind.BELT, Kind.SPLITTER -> belts.items[p]?.contains(item) == true
            Kind.MACHINE -> machineOutput(ins) == item
            Kind.CHEST, Kind.INFINITY_CHEST -> src.item == item
            else -> false
        }
    }

    // ---- spoilage (Gleba) ----
    private val spoilResults: Set<String> = GameConstants.SPOILS.values.mapNotNull { it.first }.toSet()

    /** Whether [ins] takes [item] (no filter, a whitelist of it, or a blacklist without it). */
    private fun lets(ins: PlacedEntity, item: String): Boolean =
        if (ins.item != null) ins.item == item else ins.blacklist?.contains(item) != true

    /** A spoilage drain: from a belt into a chest, filtered to a spoil result or leaving listed items behind. */
    private fun isDrain(ins: PlacedEntity): Boolean {
        if (!spec.spoilage || ins.kind != Kind.INSERTER) return false
        val pick = entityAt(ins.pickupTile) ?: return false
        val drop = entityAt(ins.dropTile) ?: return false
        return pick.kind.beltFamily && drop.kind == Kind.CHEST && (ins.item in spoilResults || ins.blacklist != null)
    }

    /**
     * What spoils rides to the end of its belt as what it spoils into: every belt that may carry such an item, or that
     * a machine holding something that spoils puts its output on, needs at each dead end a drain letting that through;
     * such a machine needs an inserter onto a belt or into a chest that takes its spoilage out.
     */
    private fun checkSpoilage(belts: BeltGraph) {
        val prone = HashMap<Int, Set<String>>()   // machine index -> what spoils into what there
        ents.forEachIndexed { i, m ->
            if (m.kind != Kind.MACHINE) return@forEachIndexed
            val mi = machineInfo(m) ?: return@forEachIndexed
            val rs = (mi.ingredients + mi.outputs.keys + mi.demand.keys).mapNotNull { GameConstants.spoilResult(it) }.toSet()
            if (rs.isNotEmpty()) prone[i] = rs
        }
        val carry = HashMap<Int, MutableSet<String>>()   // belt index -> spoil results it may carry
        belts.items.forEach { (b, items) -> items.mapNotNull { GameConstants.spoilResult(it) }.forEach { carry.getOrPut(b) { HashSet() } += it } }
        prone.forEach { (m, rs) ->
            val outlets = ents.filter { it.kind == Kind.INSERTER && indexAt(it.pickupTile) == m }.filter { ins ->
                val d = entityAt(ins.dropTile)
                d != null && (d.kind.beltFamily || d.kind == Kind.CHEST) && rs.all { lets(ins, it) }
            }
            if (outlets.isEmpty()) err(ErrorType.SPOILAGE_CLOGS, ents[m].tile, "${ents[m].recipe} holds what spoils into ${rs.sorted()} and no inserter onto a belt or into a chest takes it out")
            outlets.forEach { ins -> indexAt(ins.dropTile)?.takeIf { ents[it].kind.beltFamily }?.let { carry.getOrPut(it) { HashSet() } += rs } }
        }
        // Downstream, belt by belt.
        val queue = ArrayDeque(carry.keys)
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            belts.next[b]?.forEach { n -> if (carry.getOrPut(n) { HashSet() }.addAll(carry.getValue(b))) queue += n }
        }
        val box = Rect(0, 0, layout.w, layout.h)
        val drainsAt = ents.filter { isDrain(it) }.groupBy { indexAt(it.pickupTile) }
        carry.forEach { (b, rs) ->
            val e = ents[b]
            if (e.kind == Kind.UG_IN || !belts.next[b].isNullOrEmpty()) return@forEach
            if (e.outputTile?.let { it in box } == false) return@forEach   // leaves the box: a port
            val left = rs.filter { r -> drainsAt[b]?.none { lets(it, r) } != false }
            if (left.isNotEmpty()) {
                stuck += StuckEnd(b, left.sorted(), belts.items[b]?.toSet() ?: emptySet())
                err(ErrorType.SPOILAGE_STUCK, e.tile, "$left would collect at this belt end: no drain takes it")
            }
        }
    }
    private val stuck = ArrayList<StuckEnd>()

    /** The belt ends where spoilage would collect without a drain, as a full validation finds them. */
    fun spoilageEnds(): List<StuckEnd> {
        run()
        return stuck
    }

    // ---- heat (Aquilo) ----
    /** Heat pipes join the ports through heat pipes (edge to edge); everything that freezes touches one along an edge. */
    private fun checkHeat() {
        val hs = spec.heat ?: return
        val pipeTiles = ents.filter { it.kind == Kind.HEAT_PIPE }.map { it.tile }.toSet()
        val warm = HashSet<Tile>()
        val queue = ArrayDeque(hs.ports.filter { it in pipeTiles })
        warm += queue
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            Dir.entries.forEach { d -> val n = t + d; if (n in pipeTiles && warm.add(n)) queue += n }
        }
        pipeTiles.filter { it !in warm }.forEach { err(ErrorType.HEAT_DISCONNECTED, it, "heat pipe not joined to a heat port") }
        ents.filter { power.needsHeat(it, hs.poles) }.forEach { e ->
            if (power.edgeNeighbours(e).none { it in warm }) err(ErrorType.COLD, e.tile, "${e.name} touches no heat pipe joined to the heat port")
        }
    }

    // ---- 7. power ----
    private fun checkPower() {
        val poles = ents.indices.filter { ents[it].kind == Kind.POLE }
        if (!spec.requirePower) return
        val needs = ents.filter { it.kind == Kind.MACHINE || it.kind == Kind.INSERTER || it.kind == Kind.BEACON }
        needs.forEach { e ->
            val covered = poles.any { covers(ents[it], e.rect) }
            if (!covered) err(ErrorType.UNPOWERED, e.tile, "${e.name} is outside every pole's supply area")
        }
        if (poles.size > 1) {
            val adj = poles.associateWith { mutableListOf<Int>() }
            layout.wires.forEach { (a, b) ->
                val pa = ents[a]; val pb = ents[b]
                if (pa.kind != Kind.POLE || pb.kind != Kind.POLE) return@forEach
                val reach = min(GameConstants.pole(pa.name).wireReach, GameConstants.pole(pb.name).wireReach)
                val d = kotlin.math.hypot(pa.x + pa.w / 2.0 - pb.x - pb.w / 2.0, pa.y + pa.h / 2.0 - pb.y - pb.h / 2.0)
                if (d > reach + 1e-9) err(ErrorType.WIRE_TOO_LONG, pa.tile, "wire of ${fmt(d)} tiles exceeds reach ${fmt(reach)}")
                adj.getValue(a) += b; adj.getValue(b) += a
            }
            val seen = HashSet<Int>()
            val stack = ArrayDeque(listOf(poles[0]))
            while (stack.isNotEmpty()) { val p = stack.removeLast(); if (seen.add(p)) stack += adj.getValue(p) }
            poles.filter { it !in seen }.forEach { err(ErrorType.POLES_DISCONNECTED, ents[it].tile, "pole is not wired to the rest") }
        }
    }

    /** Every machine has the beacons in range its planned rate needs (a beacon reaches a machine its supply area overlaps). */
    private fun checkBeacons() {
        val b = spec.beacon ?: return
        val beacons = ents.filter { it.kind == Kind.BEACON }
        ents.filter { it.kind == Kind.MACHINE }.forEach { m ->
            val need = spec.machines[m.tile]?.beaconNeed ?: 0
            if (need == 0) return@forEach
            val have = beacons.count { plan.inBeaconRange(b, it.x, it.y, m.x, m.y, m.w, m.h) }
            if (have < need) err(ErrorType.BEACON_SHORT, m.tile, "${m.recipe ?: m.name} has $have beacon(s) in range, its rate needs $need")
        }
    }

    private fun covers(pole: PlacedEntity, r: Rect): Boolean {
        val s = GameConstants.pole(pole.name).supplyHalf
        val cx = pole.x + pole.w / 2.0
        val cy = pole.y + pole.h / 2.0
        // Overlap of open intervals: touching edges do not count.
        return r.x < cx + s && r.x + r.w > cx - s && r.y < cy + s && r.y + r.h > cy - s
    }

    // ---- 8. fluids ----
    /**
     * Rebuilds fluid networks: pipes join adjacent pipes, pipe-to-ground ends join along their open side and
     * to their pair, and a pipe on a machine's connection tile joins that connection. Each network may carry
     * one fluid, every used input needs a supply of its fluid, every used output a consumer or a drain, and
     * no pipe may touch a connection the recipe does not use.
     */
    private fun checkFluids() {
        val machines = ents.indices.filter { ents[it].kind == Kind.MACHINE }
        val conns = machines.associateWith { i ->
            val m = ents[i]
            val info = machineInfo(m)
            place.connectionsOf(m.name, m.w, m.h, info?.fluidIn ?: emptyMap(), info?.fluidOut ?: emptyMap(), m.x, m.y, m.dir.ordinal, m.mirror)
        }
        if (spec.fluidMode == "manual") {
            conns.values.flatten().filter { it.fluid != null }.forEach { c ->
                entityAt(c.tile)?.let { err(ErrorType.FLUID_BLOCKED, c.tile, "fluid connection must stay free for manual piping, found ${it.name}") }
            }
            return
        }
        val pipeKinds = setOf(Kind.PIPE, Kind.PIPE_UG, Kind.INFINITY_PIPE)
        val pipes = ents.indices.filter { ents[it].kind in pipeKinds }
        fun opensTo(i: Int, d: Dir): Boolean = ents[i].kind != Kind.PIPE_UG || ents[i].dir == d
        // Union-find over pipe entities and machine connections (ids after the entities).
        val connList = conns.flatMap { (m, cs) -> cs.map { m to it } }
        val parent = IntArray(ents.size + connList.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        fun join(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        pipes.forEach { i ->
            val e = ents[i]
            Dir.entries.forEach { d ->
                val j = indexAt(e.tile + d) ?: return@forEach
                if (ents[j].kind in pipeKinds && opensTo(i, d) && opensTo(j, d.opposite)) join(i, j)
            }
            // Pipe-to-ground pairing: the first pipe-to-ground on the axis behind the open side.
            if (e.kind == Kind.PIPE_UG) {
                val back = e.dir.opposite
                for (k in 1..GameConstants.PIPE_TO_GROUND_GAP + 1) {
                    val j = indexAt(e.tile.step(back, k)) ?: continue
                    val o = ents[j]
                    if (o.kind != Kind.PIPE_UG || o.dir.axis != e.dir.axis) continue
                    if (o.dir == back) join(i, j)
                    break
                }
            }
        }
        // Storage tanks join through their corner connections: to a pipe that opens back, a machine connection that
        // points at it, or another tank's connection facing it.
        val tanks = ents.indices.filter { ents[it].kind == Kind.TANK }
        fun tankOpens(i: Int): List<Pair<Tile, Dir>> = ents[i].let { t -> GameConstants.storageTankConnections(t.dir).map { (o, d) -> Tile(t.x + o.x, t.y + o.y) to d } }
        tanks.forEach { i ->
            tankOpens(i).forEach { (t, d) ->
                val j = indexAt(t + d) ?: return@forEach
                if (ents[j].kind in pipeKinds && opensTo(j, d.opposite)) join(i, j)
                if (ents[j].kind == Kind.TANK && tankOpens(j).any { (u, e) -> u == t + d && e == d.opposite }) join(i, j)
            }
        }
        val attached = BooleanArray(connList.size)
        connList.forEachIndexed { k, (_, c) ->
            val j = indexAt(c.tile) ?: return@forEachIndexed
            if (ents[j].kind in pipeKinds && opensTo(j, c.side.opposite)) { join(ents.size + k, j); attached[k] = true }
            if (ents[j].kind == Kind.TANK && tankOpens(j).any { (u, e) -> u == c.tile && e == c.side.opposite }) { join(ents.size + k, j); attached[k] = true }
        }
        // Connections of one box are one network through it (Py's soil extractor passes water from east to west).
        connList.withIndex().groupBy { (_, mc) -> Triple(mc.first, mc.second.flow, mc.second.index) }.values.forEach { box ->
            box.zipWithNext().forEach { (a, b) -> join(ents.size + a.index, ents.size + b.index) }
        }
        // What each network holds.
        val supplies = HashMap<Int, MutableSet<String>>()
        val drains = HashMap<Int, MutableSet<String>>()
        pipes.forEach { i ->
            val e = ents[i]
            if (e.kind == Kind.INFINITY_PIPE && e.fluid != null) (if (e.drain) drains else supplies).getOrPut(find(i)) { mutableSetOf() } += e.fluid
        }
        spec.sources.filter { it.fluid }.forEach { s ->
            val i = indexAt(s.tile)
            if (i == null || ents[i].kind !in pipeKinds) err(ErrorType.FLUID_MISSING, s.tile, "fluid port of ${s.item} has no pipe")
            else supplies.getOrPut(find(i)) { mutableSetOf() } += s.item
        }
        spec.outputs.filter { it.fluid }.forEach { o ->
            val i = indexAt(o.tile)
            if (i == null || ents[i].kind !in pipeKinds) err(ErrorType.OUTPUT_MISSING, o.tile, "fluid output port of ${o.item} has no pipe")
            else drains.getOrPut(find(i)) { mutableSetOf() } += o.item
        }
        connList.forEachIndexed { k, (m, c) ->
            if (!attached[k]) return@forEachIndexed
            val r = find(ents.size + k)
            if (c.fluid == null) { err(ErrorType.FLUID_LEAK, c.tile, "a pipe touches an unused fluid connection of ${ents[m].recipe}"); return@forEachIndexed }
            if (c.flow == GameConstants.Flow.OUTPUT) supplies.getOrPut(r) { mutableSetOf() } += c.fluid
            else drains.getOrPut(r) { mutableSetOf() } += c.fluid
        }
        (supplies.keys + drains.keys).forEach { r ->
            val all = (supplies[r] ?: emptySet()) + (drains[r] ?: emptySet())
            if (all.size > 1) {
                val at = pipes.firstOrNull { find(it) == r }?.let { ents[it].tile } ?: Tile(0, 0)
                err(ErrorType.FLUID_MIX, at, "fluids ${all.sorted()} share one pipe network")
            }
        }
        checkCircuitWires()
        // A machine working on a fluid condition is wired to a tank or pipe on the pipes that bring it that fluid
        // (each reads its own contents, which follow the fill level of its whole pipe network).
        val inputRoot = HashMap<Pair<Int, String>, Int>()
        connList.forEachIndexed { k, (m, c) -> if (c.fluid != null && c.flow == GameConstants.Flow.INPUT && attached[k]) inputRoot[m to c.fluid] = find(ents.size + k) }
        val tankRoots = ents.indices.filter { ents[it].kind == Kind.TANK || ents[it].kind in pipeKinds }.associateWith { find(it) }
        ents.indices.filter { ents[it].circuit?.fluid == true }.forEach { m ->
            val f = ents[m].circuit!!.signal
            val want = inputRoot[m to f]
            val wired = circuitNetworkOf(m).filter { it in tankRoots }
            if (want == null) err(ErrorType.CIRCUIT, ents[m].tile, "${ents[m].recipe} works on a $f condition but takes no $f from pipes")
            else if (wired.none { tankRoots[it] == want }) err(ErrorType.CIRCUIT, ents[m].tile, "${ents[m].recipe} works on a $f condition but no tank or pipe on its $f pipes is wired to it")
        }
        // Per box: one with two connections (Py's soil extractor) is fed through either.
        connList.withIndex().groupBy { (_, mc) -> Triple(mc.first, mc.second.flow, mc.second.index) }.values.forEach { box ->
            val k = box.firstOrNull { attached[it.index] }?.index ?: box.first().index
            val (m, c) = connList[k]
            val f = c.fluid ?: return@forEach
            val r = find(ents.size + k)
            val linked = attached[k]
            val tiny = machineInfo(ents[m])?.fluidDemand?.get(f)?.let { it <= TINY_RATE } == true
            if (c.flow == GameConstants.Flow.INPUT && !tiny && (!linked || supplies[r]?.contains(f) != true)) {
                err(ErrorType.FLUID_MISSING, c.tile, "${ents[m].recipe} gets no $f here")
            }
            if (c.flow == GameConstants.Flow.OUTPUT && (!linked || drains[r]?.contains(f) != true)) {
                err(ErrorType.FLUID_STUCK, c.tile, "${ents[m].recipe} outputs $f with nowhere to go")
            }
        }
    }

    /** Entities on the same red or green circuit network as entity [i] (itself included). */
    private fun circuitNetworkOf(i: Int): Set<Int> {
        val out = HashSet<Int>()
        for (color in listOf(GameConstants.WIRE_RED, GameConstants.WIRE_GREEN)) {
            val adj = layout.circuitWires.filter { it.third == color }.flatMap { (a, b, _) -> listOf(a to b, b to a) }.groupBy({ it.first }, { it.second })
            val seen = hashSetOf(i); val q = ArrayDeque(listOf(i))
            while (q.isNotEmpty()) { val c = q.removeFirst(); adj[c]?.forEach { if (seen.add(it)) q += it } }
            out += seen
        }
        return out
    }

    /** Circuit wires no longer than wire reach. */
    private fun checkCircuitWires() {
        layout.circuitWires.forEach { (a, b, _) ->
            val ea = ents[a]; val eb = ents[b]
            val d = kotlin.math.hypot(ea.x + ea.w / 2.0 - eb.x - eb.w / 2.0, ea.y + ea.h / 2.0 - eb.y - eb.h / 2.0)
            if (d > GameConstants.CIRCUIT_WIRE_REACH + 1e-9) err(ErrorType.CIRCUIT, ea.tile, "a circuit wire to ${eb.name} at ${eb.tile} is ${"%.1f".format(d)} tiles long")
        }
    }

    private fun fmt(d: Double) = "%.2f".format(d)

    private companion object {
        /**
         * How far below plan a split producer may leave a belt chain (what its consumers and output ports take)
         * before it is an error. Splits leave small gaps the model cannot place exactly (which of two such
         * producers on one belt fills first, which lane the consumers take from); single-block cable jobs leave
         * belts about 6% short in the model. The divisions that starved the 2/s six-science factory in game left
         * its gear and pipe belts 11% to 37% short in the model (11% to 23% measured in game).
         */
        const val SPLIT_TOLERANCE = 0.08
    }
}

fun validate(layout: Layout, spec: ValidationSpec): List<ValidationError> = Validator(layout, spec).run()

/** A belt end where spoilage would collect: the belt's entity index, the spoil results left there, the items on it. */
class StuckEnd(val belt: Int, val results: List<String>, val items: Set<String>)

/** Gleba: the belt ends of [layout] where spoilage would collect without a drain (the validator's own reading). */
fun spoilageEnds(layout: Layout, spec: ValidationSpec): List<StuckEnd> = Validator(layout, spec.copy(spoilage = true)).spoilageEnds()

/** Recipe facts for machines that have no [MachineSpec] (item checks only, no rates). */
fun recipeInfoFrom(data: RootData): (String, String?) -> MachineSpec? = { name, machine ->
    data.recipes[name]?.let { r ->
        val (fin, fout) = data.fluidBoxes(r, machine)
        MachineSpec(
            product = r.products.firstOrNull { it.type != "fluid" }?.name ?: r.mainProduct?.name ?: r.name,
            ingredients = r.ingredients.filter { !it.isFluid }.map { it.name }.toSet(),
            needsFluid = r.ingredients.firstOrNull { it.isFluid }?.name,
            fluidIn = fin,
            fluidOut = fout,
        )
    }
}
