package blocks

import config.RunConfiguration
import data.GameConstants
import data.RootData
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import place.PlacedMachine
import place.Placement
import place.connectionsOf
import plan.MachineInstance
import plan.ProductionPlan
import plan.BeaconSpec
import plan.Role
import plan.beaconNeed
import plan.beaconSpec
import plan.inBeaconRange
import plan.inserterRate
import plan.machineModules
import plan.needsModules
import power.placePoles
import route.spoilProne
import search.TrialResult
import search.score
import search.validationSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.validate
import kotlin.math.ceil

/**
 * Ready-made layouts ("templates") for simple blocks, offered next to the searched ones: the floorplan (or a
 * single block's search) uses one only where it packs better, so nothing is forced. Each is validated like any
 * searched layout before it is offered.
 *
 * The machine column: single-recipe blocks of machines of any size with one to four solid ingredients (fuel counts),
 * at most one fluid ingredient and one item product, such as smelting, gears, circuits, science packs, engines,
 * plastic or casting. Machines stand in two columns (or one), a belt or two down the middle, in one of three styles
 * (mw is the machine's width):
 *
 * ```
 * narrow (2 mw + 4):  |machine|in, out, pole|A|B|in, out, pole|machine|
 * split  (2 mw + 7):  |B1|out|machine|in|A|in|machine|out|B2|
 * wide   (2 mw + 8):  |B1|out|machine|in, in|A1|A2|in, in|machine|out|B2|
 * ```
 *
 * Narrow: belt A carries the ingredients (two of them a lane each), belt B the product; each machine reaches the
 * farther of the two with long-handed inserters. Split (a classic smelting column): A down the middle, the products
 * out of each machine's other side onto an outer belt (a lane each); short inserters only, so it suits fast machines
 * that outrun long-handed inserters, and small machines whose sides have no room for a pole beside two inserters.
 * Wide: three or four ingredients (or two that each fill more than a lane) on two belts down the middle, each machine
 * taking from the nearer with short inserters and from the farther with long-handed ones.
 *
 * A machine gets as many inserters per belt as its rates need, as long as they fit along its side; its side's other
 * tiles take poles (a machine whose sides have no tile left gets a row more for them). Machines are stacked a
 * machine's height apart.
 *
 * When one column's belts would overflow (or the block is large), two columns stand side by side (a double column),
 * sharing their outer product belt or the beacons between them; the planner keeps a recipe's blocks within what a
 * double column carries (see [columnRecipes]). Small blocks are also offered as a one-sided column (machines on one
 * side of the belts only).
 *
 * A fluid ingredient comes in a pipe down each column's outer side (narrow style, or a casting column with only the
 * product belt between the machines when the fluid is all they take): the machines turned so that their used fluid
 * inputs face it, pipe-to-ground pairs passing connections the recipe does not use, the header piping the fluid from
 * its port to the top of each pipe column.
 *
 * Short headers above and below the column run the port belts to whichever sides of the block the ports are on (a
 * small belt search, undergrounds included). A belt of two ingredients gets them sideloaded from either side of the
 * belt just above its head, so each lands on a lane of its own whatever lanes it came on; so does a belt of one fed by
 * two ports. Further input ports merge into their item's belt through splitters; an item that feeds more belts than
 * it has ports branches off through splitters to the others. More product ports branch off through splitters, and
 * product belts without a port of their own meet the others by a splitter or a sideload. What the machines leave of
 * an ingredient passes on through its output port: out of the end of a belt it has to itself, or branched off its
 * input belt by a splitter.
 */
object Templates {
    private const val LONG = "long-handed-inserter"
    private val DEBUG = System.getenv("TEMPLATE_DEBUG") != null
    private val TIMING = System.getenv("TEMPLATE_TIMING") != null
    private val tries = java.util.concurrent.atomic.AtomicLong()
    private val routed = java.util.concurrent.atomic.AtomicLong()
    private val validated = java.util.concurrent.atomic.AtomicLong()
    /** Most ingredients a column takes: two belts of two lanes. */
    const val MAX_INGREDIENTS = 4
    /** Most columns side by side in one block. */
    const val MAX_MODULES = 2
    /** Most machines a one-sided column is offered for. */
    private const val ONE_SIDED_MAX = 6
    /** Fewest machines a double column is offered for when a single one fits. */
    private const val DOUBLE_MIN = 10

    /**
     * A machine a machine column can hold: one to four solid ingredients (fuel counts), one item product, and at most
     * one fluid ingredient (none: casting needs only the fluid), no fluid product.
     */
    fun columnMachine(m: MachineInstance): Boolean =
        m.w in 2..6 && m.h in 2..6 && m.machine != "recycler" &&
            (m.needsFluid == null || m.needsFluid in m.fluidDemand) && m.fluidOut.isEmpty() && m.fluidDemand.size <= 1 && m.fluidIn.keys == m.fluidDemand.keys &&
            m.demand.size in (if (m.fluidDemand.isEmpty()) 1 else 0)..MAX_INGREDIENTS &&
            m.itemProducts.size == 1 && m.itemProducts.keys.single() !in m.demand

    private enum class Style(val label: String) {
        NARROW("machine column"), SPLIT("split machine column"), WIDE("wide machine column"),
        /** No ingredient belt (the machines take only a fluid): |pipe|machine|out|B|out|machine|pipe|. */
        CAST("casting column"),
    }

    /**
     * A machine turned ([rot] quarter turns, [mirror]ed) so that its used fluid input faces the pipe outside it:
     * the rows (from its top) of the used and the unused connections on that side; none on its top or bottom.
     */
    private class Orient(val rot: Int, val mirror: Boolean, val used: List<Int>, val unused: List<Int>)

    /** How [m] turns so that its fluid comes in on side [outer] only, or null. */
    private fun orient(m: MachineInstance, outer: Dir): Orient? {
        for (rot in 0..3) for (mirror in listOf(false, true)) {
            val cs = connectionsOf(m.machine, m.w, m.h, m.fluidIn, m.fluidOut, 0, 0, rot, mirror)
            if (cs.none { it.fluid != null } || cs.any { it.fluid != null && it.side != outer }) continue
            // Stacked machines touch at their tops and bottoms: no connection there.
            if (cs.any { it.side == Dir.N || it.side == Dir.S }) continue
            return Orient(rot, mirror, cs.filter { it.fluid != null }.map { it.tile.y }.sorted(), cs.filter { it.fluid == null && it.side == outer }.map { it.tile.y }.sorted())
        }
        return null
    }

    /** An inserter beside a machine: [group] the ingredient group it takes from ([Design.belts]), -1 for one putting out the product. */
    private class Ins(val name: String, val group: Int)

    /**
     * A machine's inserters in slot order on its inner track (between it and the middle belts) and its outer track
     * (split and wide). [rA]: narrow, how far belt A is from the track (1 next to it, 2 beyond B); [near]: wide, the
     * group on the nearer middle belt.
     */
    private fun sideIns(style: Style, si: String, so: String, inC: (Int, Int) -> Int, outC: (Int) -> Int, rA: Int, near: Int): Pair<List<Ins>, List<Ins>> =
        when (style) {
            Style.NARROW -> (List(inC(0, rA)) { Ins(if (rA == 1) si else LONG, 0) } + List(outC(3 - rA)) { Ins(if (rA == 2) so else LONG, -1) }) to emptyList()
            Style.SPLIT -> List(inC(0, 1)) { Ins(si, 0) } to List(outC(1)) { Ins(so, -1) }
            Style.WIDE -> (List(inC(near, 1)) { Ins(si, near) } + List(inC(1 - near, 2)) { Ins(LONG, 1 - near) }) to List(outC(1)) { Ins(so, -1) }
            Style.CAST -> List(outC(1)) { Ins(so, -1) } to emptyList()
        }

    /** The sides a style's machines can have: (rA, near) as in [sideIns]. */
    private fun sideConfigs(style: Style): List<Pair<Int, Int>> = when (style) {
        Style.NARROW -> listOf(1 to 0, 2 to 0)
        Style.SPLIT -> listOf(1 to 0)
        Style.WIDE -> listOf(0 to 0, 0 to 1)
        Style.CAST -> listOf(1 to 0)
    }

    /**
     * How a column holds a recipe: its style, how many columns side by side ([modules]) and whether machines stand on
     * one side of the belts only, the ingredient groups on its middle belts (one per belt; two items on a belt ride
     * a lane each), the machines' size and rows per machine, and their inserters.
     */
    private class Design(
        val style: Style, val modules: Int, val oneSided: Boolean, val belts: List<List<String>>,
        val mw: Int, val mh: Int, val unit: Int, val shortIn: String, val shortOut: String,
        /** Inserters per machine taking group g's items at reach r (1: from the belt next to them; 2: long-handed). */
        val inC: (Int, Int) -> Int,
        /** Inserters per machine putting the product out at reach r. */
        val outC: (Int) -> Int,
        /** The fluid ingredient (piped along each column's outer side), and how the left and right machines turn for it. */
        val fluid: String? = null, val orients: List<Orient> = emptyList(),
    ) {
        val sidesPer: Int get() = if (oneSided) 1 else 2
        /** Pipe columns: one outside each column of machines that takes a fluid. */
        val pipes: Int get() = if (fluid == null) 0 else sidesPer
        fun units(n: Int) = (n + modules * sidesPer - 1) / (modules * sidesPer)
        val moduleWidth: Int get() = pipes + when (style) {
            Style.NARROW -> if (oneSided) mw + 3 else 2 * mw + 4
            Style.SPLIT -> if (oneSided) mw + 4 else 2 * mw + 7
            Style.WIDE -> if (oneSided) mw + 5 else 2 * mw + 8
            Style.CAST -> if (oneSided) mw + 2 else 2 * mw + 3
        }
        /** Whether neighbouring columns share their outer product belt. */
        val sharesOuter: Boolean get() = style == Style.SPLIT || style == Style.WIDE
        /** Tiles between a beacon column and the machines (a product belt and the inserters onto it, or a pipe). */
        val gap: Int get() = if (sharesOuter) 2 else pipes.coerceAtMost(1)
        /** Core width with beacon columns [o] wide: beacons outside and between the columns; without, neighbours share an outer belt. */
        fun width(o: Int) = modules * moduleWidth + if (o > 0) (modules + if (oneSided) 0 else 1) * o else if (sharesOuter) -(modules - 1) else 0
        /** Estimated area: the core and two header rows a column (more columns route more belts). */
        fun area(n: Int, o: Int) = width(o) * (unit * units(n) + 2 * modules)
        fun sideIns(rA: Int, near: Int) = sideIns(style, shortIn, shortOut, inC, outC, rA, near)
        /** Product belts: one per narrow column, an outer one per side (neighbours sharing one without beacons). */
        fun drains(beacons: Boolean) = when {
            !sharesOuter || oneSided -> modules
            beacons -> 2 * modules
            else -> modules + 1
        }
        val label: String get() = style.label + (if (modules > 1) ", $modules columns" else "") + (if (oneSided) ", one-sided" else "") + if (fluid != null) ", piped" else ""
    }

    private fun fmt(x: Double) = "%.2f".format(x)
    private fun sharesOuterOf(style: Style) = style == Style.SPLIT || style == Style.WIDE

    /**
     * [style] with [modules] columns ([oneSided]) and ingredient groups [belts] for machines [ms], or null: the
     * inserters (the cheapest that fit beside a machine, leaving a tile for a pole if they can) keep up, and with
     * [laneCap] each belt carries what its machines need of an item (a lane of it on a belt of two items) and each
     * side's machines put no more on their product lane than it holds.
     */
    private fun designFor(style: Style, modules: Int, oneSided: Boolean, belts: List<List<String>>, ms: List<MachineInstance>,
                          config: RunConfiguration, laneCap: Double?, why: (String) -> Unit): Design? {
        val mw = ms[0].w; val mh = ms[0].h
        val fluid = ms[0].fluidDemand.keys.singleOrNull()
        if ((style == Style.CAST) != belts.isEmpty()) return null
        var orients = emptyList<Orient>()
        if (fluid != null) {
            // A pipe along each column's outer side; only narrow columns (and casting ones) leave that side free.
            if (style != Style.NARROW && style != Style.CAST) return null
            if (mw != mh) { why("${ms[0].machine} with a fluid is not square"); return null }
            orients = listOf(orient(ms[0], Dir.W) ?: run { why("no turn of a ${ms[0].machine} takes $fluid on one side only"); return null },
                orient(ms[0], Dir.E) ?: run { why("no turn of a ${ms[0].machine} takes $fluid on one side only"); return null })
        }
        if (style == Style.NARROW && LONG !in config.inserters.allowed || style == Style.WIDE && LONG !in config.inserters.allowed) { why("long-handed inserters not allowed"); return null }
        val shorts = config.inserters.allowed.filter { it != LONG }.sortedBy { GameConstants.inserter(it).tierCost }
        if (shorts.isEmpty()) { why("no inserter but long-handed ones allowed"); return null }
        val needs = belts.map { g -> ms.maxOf { mi -> g.sumOf { mi.demand.getValue(it) } } }
        val maxOut = ms.maxOf { it.itemProducts.values.single() }
        fun count(rate: Double, ins: String, fromBelt: Boolean) = maxOf(1, ceil(rate / inserterRate(ins, config, fromBelt) - 1e-9).toInt())
        var best: Design? = null
        var bestKey: Triple<Int, Int, Int>? = null
        for (si in shorts) for (so in shorts) {
            val inC = { g: Int, r: Int -> count(needs[g], if (r == 1) si else LONG, true) }
            val outC = { r: Int -> count(maxOut, if (r == 1) so else LONG, false) }
            val sides = sideConfigs(style).map { (rA, near) -> sideIns(style, si, so, inC, outC, rA, near) }
            if (sides.any { (a, b) -> a.size > mh || b.size > mh }) continue
            val free = sides.all { (a, b) -> mh - a.size + (if (sharesOuterOf(style)) mh - b.size else 0) >= 1 }
            val key = Triple(if (free) 0 else 1, sides.sumOf { it.first.size + it.second.size },
                GameConstants.inserter(si).tierCost + GameConstants.inserter(so).tierCost)
            if (bestKey == null || compareValuesBy(key, bestKey, { it.first }, { it.second }, { it.third }) < 0) {
                bestKey = key
                best = Design(style, modules, oneSided, belts, mw, mh, if (free) mh else mh + 1, si, so, inC, outC, fluid, orients)
            }
        }
        val d = best
        if (d == null) {
            why("no inserter takes ${fmt(needs.maxOrNull() ?: 0.0)}/s from a belt (or puts ${fmt(maxOut)}/s out) along a $mw x $mh machine's side")
            return null
        }
        if (laneCap != null) {
            val sp = d.sidesPer
            val per = modules * sp
            for (j in 0 until modules) {
                val mine = ms.indices.filter { (it % per) / sp == j }
                for (g in belts) for (i in g) {
                    val t = mine.sumOf { ms[it].demand.getValue(i) }
                    if (t > (if (g.size == 1) 2 * laneCap else laneCap) + 1e-9) {
                        why("more of an ingredient than a ${if (g.size == 1) "belt" else "lane"} holds ($i ${fmt(t)}/s)"); return null
                    }
                }
                for (sd in 0 until sp) {
                    val p = mine.filter { it % sp == sd }.sumOf { ms[it].itemProducts.values.single() }
                    if (p > laneCap * 0.98 + 1e-9) { why("more product than a lane takes (${fmt(p)}/s)"); return null }
                }
            }
        }
        return d
    }

    /** Ways to put [items] (most needed first) on a style's middle belts; items in one of [together] share a belt. */
    private fun beltOptions(style: Style, items: List<String>, together: List<Set<String>>): List<List<List<String>>> {
        val options = when (style) {
            Style.NARROW, Style.SPLIT -> if (items.size in 1..2) listOf(listOf(items)) else emptyList()
            Style.CAST -> if (items.isEmpty()) listOf(emptyList()) else emptyList()
            Style.WIDE -> when (items.size) {
                0, 1 -> emptyList()
                2 -> listOf(items.map { listOf(it) })
                3 -> items.map { a -> listOf(listOf(a), items - a) }
                else -> (1..3).map { j -> listOf(listOf(items[0], items[j]), items.drop(1) - items[j]) }
            }
        }
        return options.filter { o -> together.all { t -> o.any { g -> g.containsAll(t) } } }
    }

    /** Every design that holds [ms] (see [designFor]), up to [maxModules] columns side by side; [why] hears why none does. */
    private fun designs(ms: List<MachineInstance>, config: RunConfiguration, laneCap: Double?, together: List<Set<String>> = emptyList(),
                        maxModules: Int = MAX_MODULES, why: (String) -> Unit = {}): List<Design> {
        val n = ms.size
        val total = ms[0].demand.keys.associateWith { i -> ms.sumOf { it.demand.getValue(i) } }
        val items = total.keys.sortedByDescending { total.getValue(it) }
        val out = ArrayList<Design>()
        var reason: String? = null
        for (m in 1..maxModules) for (one in listOf(false, true)) {
            if (one && (m > 1 || n > ONE_SIDED_MAX)) continue
            if (m > 1 && n < 2 * m) continue
            for (style in Style.entries) {
                for (belts in beltOptions(style, items, together)) {
                    val d = designFor(style, m, one, belts, ms, config, laneCap) { if (reason == null || m == 1 && !one) reason = it }
                    if (d != null) { out += d; break }
                }
            }
        }
        if (out.isEmpty()) why(reason ?: "${items.size} ingredients")
        return out
    }

    /**
     * Most machines of [ms] (one recipe) one block's column holds ([modules] columns side by side at most): what its
     * belts carry, with a margin for machines that run a little faster once the plan is split. 0 if none fits. The
     * planner counts single columns: a block's ports always reach one, while a double column needs ports that
     * divide between its columns (see [feedings]).
     */
    fun columnCapacity(ms: List<MachineInstance>, config: RunConfiguration, margin: Double = 0.95, modules: Int = 1): Int {
        val laneCap = GameConstants.beltTier(config.beltTier, config.beltStackSize).throughput / 2 * margin
        var best = 0
        for (k in 1..ms.size) {
            if (designs(ms.take(k), config, laneCap, maxModules = modules).isNotEmpty()) best = k
            else if (k >= 2 * modules) break
        }
        return best
    }

    /**
     * Recipes of [plan] whose machines a machine column can hold with the inserters [config] allows, with at most
     * [maxIngredients] ingredients and at least [minMachines] machines, each with the most machines one block's
     * column holds (Int.MAX_VALUE: all of them): blocks of only these get a column, so the split keeps them apart,
     * and splits a recipe whose belts one column cannot carry over several blocks. A recipe whose column holds fewer
     * than [minCap] machines (and not all of them) stays free to mix: many small blocks of it would cost more links
     * than their columns save (and machines in a mixed block may hand their products straight to the next).
     */
    fun columnRecipes(plan: ProductionPlan, config: RunConfiguration, maxIngredients: Int = MAX_INGREDIENTS, minMachines: Int = 1, minCap: Int = minMachines,
                      columns: Int = 1): Map<String, Int> {
        if (!config.portsOnRing || config.heat.mode != "off") return emptyMap()
        val out = LinkedHashMap<String, Int>()
        plan.machines.groupBy { it.recipe }.forEach { (r, ms) ->
            if (ms.size < minMachines || !ms.all(::columnMachine) || ms.map { it.machine }.distinct().size != 1 || ms[0].demand.size > maxIngredients ||
                (config.spoilage.mode != "off" && ms.any(::spoilProne))) return@forEach
            val cap = columnCapacity(ms, config, modules = columns.coerceIn(1, MAX_MODULES))
            if (cap >= ms.size) out[r] = Int.MAX_VALUE
            else if (cap >= maxOf(minMachines, minCap)) out[r] = cap
        }
        return out
    }

    /** How an ingredient that feeds the belts of several columns gets there from its ports. */
    private sealed class Feeding {
        /** One port: straight onto one column's belt, branching off by splitters to the others' (they share what comes). */
        object Branch : Feeding()
        /** Each port to the belt of its column [module], more than one merging there: each column gets what it needs. */
        class Assign(val module: Map<Int, Int>) : Feeding()
        /** Every port merges into the first one's belt (the trunk), which branches to the other columns after the last merge. */
        object Trunk : Feeding()
    }

    /**
     * How each ingredient reaches the columns of design [d] (empty for one column), or null when the ports cannot
     * feed them: a port's items go to one column unless they branch off, so either some ports cover each column's
     * need, or all of them fit one trunk belt that branches.
     */
    private fun feedings(d: Design, ms: List<MachineInstance>, s: ColumnSpec, beltCap: Double): Map<String, Feeding>? {
        if (d.modules == 1) return emptyMap()
        val per = d.modules * d.sidesPer
        val paired = s.pairs.flatMap { listOf(it.first, it.second) }.map { s.itemOf.getValue(it) }.toSet()
        val out = HashMap<String, Feeding>()
        for ((item, ports) in s.inputs) {
            if (item in paired) continue   // a lane pair comes on one port and branches
            if (ports.size == 1) { out[item] = Feeding.Branch; continue }
            val need = DoubleArray(d.modules)
            ms.indices.forEach { need[(it % per) / d.sidesPer] += ms[it].demand.getValue(item) }
            val rates = ports.map(s.portRate)
            val a = assignPorts(rates, need)
            out[item] = when {
                a != null -> Feeding.Assign(ports.indices.associate { ports[it] to a[it] })
                rates.sum() <= beltCap * 0.98 -> Feeding.Trunk
                else -> return null
            }
        }
        return out
    }

    /** Ports (by [rates]) to columns so that each column's ports bring at least its [need], or null. */
    private fun assignPorts(rates: List<Double>, need: DoubleArray): IntArray? {
        if (rates.size > 8) return null
        val a = IntArray(rates.size)
        val got = DoubleArray(need.size)
        fun rec(i: Int): Boolean {
            if (i == rates.size) return need.indices.all { got[it] >= need[it] - 1e-6 }
            for (j in need.indices) {
                a[i] = j; got[j] += rates[i]
                if (rec(i + 1)) return true
                got[j] -= rates[i]
            }
            return false
        }
        return if (rec(0)) a else null
    }

    /** Every template layout for [plan] (a block's plan), each validated; [note] explains why there is none. */
    fun layouts(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit = {}): List<TrialResult> =
        column(data, config, plan, note) ?: emptyList()

    private class ColumnSpec(
        val machine: String, val recipe: String, val product: String,
        /** Input ports per ingredient, product output ports, and output ports of ingredients passing on. */
        val inputs: Map<String, List<Int>>, val outputs: List<Int>, val passes: List<Int>,
        val belt: String, val underground: String, val splitter: String, val maxGap: Int,
        /** Items/s one lane of the tier holds. */
        val laneCap: Double,
        /** Beacons each machine needs in range (0: none), the beacon, and the modules in the machines' own slots. */
        val need: Int = 0, val beacon: BeaconSpec? = null, val modules: List<String>? = null,
        /** Lane pairs: two input ports whose items come on one belt, one a lane (a factory's link pair). */
        val pairs: List<Pair<Int, Int>> = emptyList(),
        /** Items/s through each port, and each port's item. */
        val portRate: (Int) -> Double = { 0.0 },
        val portItem: Map<Int, String> = emptyMap(),
        /** The machines' fluid ingredient and its input ports. */
        val fluid: String? = null, val fluidPorts: List<Int> = emptyList(),
    ) {
        val itemOf: Map<Int, String> = inputs.flatMap { (i, ps) -> ps.map { it to i } }.toMap()
        /**
         * An input belt gets a junction above its head: two items from ports of their own, or one from two ports or
         * more, each within a lane, when no other belt takes that item (a port carrying more runs straight into the
         * head, the others merging into it through splitters).
         */
        fun junction(items: List<String>, feedsOfItem: Int) =
            if (items.size == 2) pairs.none { (a, b) -> setOf(itemOf[a], itemOf[b]) == items.toSet() }
            else feedsOfItem == 1 && inputs.getValue(items[0]).let { ps -> ps.size >= 2 && ps.all { portRate(it) <= laneCap * 0.98 } }
    }

    private val SIDES = setOf("north", "east", "south", "west")

    /** The machine column's designs, each in its four turns that fit the plan's port sides, smallest first. */
    private fun column(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit): List<TrialResult>? {
        val ms = plan.machines
        if (ms.isEmpty()) return null
        val m0 = ms[0]
        fun no(why: String): List<TrialResult>? { note("no machine column: $why"); return null }
        if (ms.any { it.recipe != m0.recipe || it.machine != m0.machine }) return no("more than one recipe")
        if (ms.any { !columnMachine(it) }) return no("${m0.machine} (${m0.recipe}) is not a machine with one to four solid ingredients and one product")
        if (!config.portsOnRing) return no("ports_on_ring is off")
        // Its inserters sit between machines and belts with no tile a heat pipe could take.
        if (config.heat.mode != "off") return no("heating (Aquilo)")
        if (config.spoilage.mode != "off" && ms.any(::spoilProne)) return no("spoilage (drains)")
        if (plan.terminals.any { it.role == Role.SINK }) return no("sink chests")
        val ingredients = m0.demand.keys.toList()
        val product = m0.itemProducts.keys.single()
        val fluid = m0.fluidDemand.keys.singleOrNull()
        if (plan.ports.any { p -> p.specs.size != 1 || p.specs[0].let { it.edge !in SIDES || it.from != null || it.to != null } })
            return no("ports other than one item per edge")
        val fluidPorts = plan.ports.filter { it.fluid }
        if (fluidPorts.any { it.output || it.item != fluid }) return no("fluid ports other than of ${fluid ?: "no fluid"} in")
        if (fluid != null && fluidPorts.isEmpty()) return no("no port of $fluid")
        val ins = plan.ports.filter { !it.output && !it.fluid }
        val outs = plan.ports.filter { it.output }
        if (ins.any { it.item !in ingredients } || ingredients.any { i -> ins.none { it.item == i } }) return no("input ports other than of ${ingredients.joinToString()}")
        val prodOut = outs.filter { it.item == product }
        val passOut = outs.filter { it.item in ingredients }
        if (prodOut.isEmpty() || outs.size != prodOut.size + passOut.size) return no("output ports other than of $product (and of ingredients passing on)")
        val tier = GameConstants.beltTier(config.beltTier, config.beltStackSize)
        val laneCap = tier.throughput / 2
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        // A port's belt is one belt of the block's tier.
        plan.ports.firstOrNull { !it.fluid && portRate(it.id) > tier.throughput + 1e-9 }?.let { return no("${it.item} port carries ${fmt(portRate(it.id))}/s, more than a belt") }
        val inPorts = ingredients.associateWith { i -> ins.filter { it.item == i }.map { it.id } }
        // Lane pairs: two ingredients on one belt from one side, each from that port alone (another port of either
        // would put it on the other's lane).
        val pairs = ins.filter { it.pairWith >= 0 }.map { listOf(it.id, it.pairWith).sorted().let { (a, b) -> a to b } }.distinct()
        for ((a, b) in pairs) {
            val pa = plan.ports.getOrNull(a); val pb = plan.ports.getOrNull(b)
            if (pa == null || pb == null || pa.output || pb.output || pa.item == pb.item || pa.specs[0].edge != pb.specs[0].edge ||
                pairs.count { it.first == a || it.second == a || it.first == b || it.second == b } > 1) return no("lane pairs other than two ingredients on one side")
            if (ins.count { it.item == pa.item } > 1 || ins.count { it.item == pb.item } > 1) return no("${pa.item} or ${pb.item} on a lane pair and on another port")
            // Branching one item off the pair's belt would take the other along.
            if (passOut.any { it.item == pa.item || it.item == pb.item }) return no("${pa.item} or ${pb.item} passing on from a lane pair")
        }
        val together = pairs.map { (a, b) -> setOf(plan.ports[a].item, plan.ports[b].item) }
        val all = designs(ms, config, laneCap, together) { note("no machine column: $it") }
        if (all.isEmpty()) return null

        // Beacons: a column of them along each side, outside the machines.
        val need = ms.maxOf { beaconNeed(it.crafts, it.beaconCrafts) }
        val beacon = if (need > 0) beaconSpec(data, config) ?: return no("$need beacon(s) per machine but no beacon") else null
        val modules = if (config.beacons == null && config.qualityProduction == null && !needsModules(data, m0.machine)) null else data.recipes[m0.recipe]?.let { machineModules(data, config, it, m0.machine) }?.takeIf { it.isNotEmpty() }
        val spec = ColumnSpec(m0.machine, m0.recipe, product, inPorts, prodOut.map { it.id }, passOut.map { it.id },
            tier.belt, tier.underground, tier.splitter, tier.maxUndergroundGap, laneCap, need, beacon, modules, pairs, ::portRate,
            plan.ports.associate { it.id to it.item }, fluid, fluidPorts.map { it.id })
        // The designs worth building: per number of columns (and one-sided or not) the smallest; a double column only
        // where one column overflows, or for a large block where it is no bigger (its headers route more belts).
        val o = beacon?.size ?: 0
        val n = ms.size
        val smallest = all.groupBy { it.modules to it.oneSided }.mapValues { (_, ds) -> ds.minBy { it.area(n, o) } }
        val single = smallest[1 to false]
        val chosen = smallest.values.filter { d -> d.modules == 1 || single == null || (n >= DOUBLE_MIN && d.area(n, o) <= single.area(n, o)) }.sortedBy { it.area(n, o) }
        val sides = plan.ports.associate { it.id to Dir.fromName(it.specs[0].edge!!) }
        val found = ArrayList<TrialResult>()
        for (d in chosen) for (q in 0..3) {
            val feeding = feedings(d, ms, spec, tier.throughput) ?: break
            // Built upright (A heading south), then turned q quarter turns: the ports' sides turned back first.
            val built = sides.mapValues { (_, dir) -> dir.turned(-q) }
            val t0 = System.currentTimeMillis(); tries.set(0); routed.set(0); validated.set(0)
            val r = buildColumn(data, config, plan, spec, d, built, feeding)
            if (TIMING) println("  ${d.label} turn $q: ${System.currentTimeMillis() - t0} ms, ${tries.get()} tries, ${routed.get()} routed, ${validated.get()} validated -> ${r?.let { "${it.w}x${it.h}" }}")
            if (r != null) found += turnLayout(r, q)
        }
        if (found.isEmpty()) return no("no header fits the port sides")
        // Keep the smallest of each shape (upright or lying).
        return found.groupBy { it.w to it.h }.map { (_, rs) -> rs.minBy { it.score } }.sortedBy { it.area }
    }

    /**
     * Beacon rows beside a column of [units] machines [unit] rows apart ([mw] x [mh], [gap] tiles from the beacons):
     * beacon start rows and the rows between two beacons where a pole stands (every beacon touches one), so that every
     * machine has [need] beacons in range, or null. Units stacked from a few first rows: beacon, pole row, beacon (or
     * beacon, pole row), and one more beacon with its pole row where the rest has room; also mirrored, from the
     * bottom. The fewest beacons.
     */
    private fun beaconRows(b: BeaconSpec, need: Int, gap: Int, mw: Int, mh: Int, unit: Int, units: Int): Pair<List<Int>, List<Int>>? {
        val sz = b.size
        val rows = unit * units
        fun enough(starts: List<Int>) = (0 until units).all { k -> starts.count { inBeaconRange(b, 0, it, sz + gap, unit * k, mw, mh) } >= need }
        val found = ArrayList<Pair<List<Int>, List<Int>>>()
        for (pair in listOf(true, false)) for (off in 0..2 * sz) for (flip in listOf(false, true)) {
            val starts = ArrayList<Int>(); val gaps = ArrayList<Int>()
            val step = if (pair) 2 * sz + 1 else sz + 1
            var r = off
            while (r + step <= rows) {
                starts += r; gaps += r + sz
                if (pair) starts += r + sz + 1
                r += step
            }
            if (r + sz + 1 <= rows) { starts += r; gaps += r + sz }
            val s2 = if (flip) starts.map { rows - sz - it } else starts
            val g2 = if (flip) gaps.map { rows - 1 - it } else gaps
            if (s2.isNotEmpty() && enough(s2)) found += s2.sorted() to g2.sorted()
        }
        return found.minByOrNull { it.first.size }
    }

    /**
     * Where a column's belts run, upright. Narrow: A next to the left machines' inserters ([aNear]) or B. Wide: belt
     * groups swapped or not between A1 (heading south) and A2 (heading [a2]). Product belts heading [bDirs] (left to
     * right).
     */
    private data class Variant(val aNear: Boolean = true, val swap: Boolean = false, val a2: Dir = Dir.S, val bDirs: List<Dir>)

    private fun variants(d: Design, drains: Int): List<Variant> {
        val ns = listOf(Dir.S, Dir.N)
        val bOpts: List<List<Dir>> = if (drains <= 2) (0 until (1 shl drains)).map { bits -> List(drains) { if ((bits shr it) and 1 == 0) Dir.S else Dir.N } }
        else listOf(List(drains) { Dir.S }, List(drains) { Dir.N })
        return when (d.style) {
            Style.NARROW -> listOf(true, false).flatMap { a -> bOpts.map { Variant(aNear = a, bDirs = it) } }
            Style.SPLIT, Style.CAST -> bOpts.map { Variant(bDirs = it) }
            Style.WIDE -> listOf(false, true).flatMap { sw -> ns.flatMap { a2 -> bOpts.map { Variant(swap = sw, a2 = a2, bDirs = it) } } }
        }
    }

    /** One upright column of design [d] with the ports on [sides]: the smallest over belt orders, directions and header heights. */
    private fun buildColumn(data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, d: Design, sides: Map<Int, Dir>,
                            feeding: Map<String, Feeding>): TrialResult? {
        var best: TrialResult? = null
        val coreRows = d.unit * d.units(plan.machines.size)
        val hMax = 3 + d.modules
        val headers = (0..hMax).flatMap { a -> (0..hMax).map { b -> a to b } }.sortedBy { it.first + it.second }
        val vs = variants(d, d.drains(s.beacon != null))
        val feedsOf = s.inputs.keys.associateWith { i -> d.modules * d.belts.count { i in it } }
        val jks = if (d.belts.any { s.junction(it, feedsOf.getValue(it[0])) }) 0..1 else 0..0
        // A bypass lane along the column (one tile wider) only when the ports cannot be reached without one: it lets
        // belts reach ports at the column's other end.
        for (bypass in if (d.modules == 1) listOf(false, true) else listOf(false)) {
            for ((hTop, hBot) in headers) {
                if (best != null && hTop + hBot > best.h - coreRows) break
                for (v in vs) for (jk in jks) {
                    val r = tryColumn(data, config, plan, s, d, sides, hTop, hBot, v, bypass, jk, feeding) ?: continue
                    if (best == null || r.score < best.score) best = r
                }
            }
            if (best != null) break
        }
        return best
    }

    /** A belt-family entity of a header path: a belt, or an underground pair from [ugFrom] to [tile]. */
    private data class Step(val tile: Tile, val dir: Dir, val ugFrom: Tile? = null)

    private fun tryColumn(
        data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, d: Design, sides: Map<Int, Dir>,
        hTop: Int, hBot: Int, v: Variant, bypass: Boolean = false,
        /** Rows between an input junction and its belt's head (straight belts). */
        jk: Int = 0,
        /** How each ingredient reaches several columns (see [feedings]). */
        feeding: Map<String, Feeding> = emptyMap(),
    ): TrialResult? {
        val t0 = System.currentTimeMillis()
        tries.incrementAndGet()
        val n = plan.machines.size
        val m = d.modules
        val sp = d.sidesPer
        val units = d.units(n)
        val unit = d.unit
        val mw = d.mw; val mh = d.mh
        // With beacons a column of them stands outside each column of machines (and between two columns).
        val o = s.beacon?.size ?: 0
        if (s.beacon != null && !inBeaconRange(s.beacon, 0, 0, o + d.gap, 0, mw, mh)) return null
        val beaconRows = if (s.beacon == null) null else beaconRows(s.beacon, s.need, d.gap, mw, mh, unit, units) ?: return null
        val beaconXs = ArrayList<Int>()
        val x0s = ArrayList<Int>()
        var cx = 0
        for (j in 0 until m) {
            if (o > 0) { beaconXs += cx; cx += o } else if (j > 0 && d.sharesOuter) cx -= 1   // the outer product belt between them is shared
            x0s += cx; cx += d.moduleWidth
        }
        if (o > 0 && !d.oneSided) { beaconXs += cx; cx += o }
        val coreW = cx
        val W = coreW + if (bypass) 1 else 0
        val core0 = hTop
        val core1 = hTop + unit * units   // first row below the column
        val H = core1 + hBot
        val netOf = plan.nets.associate { it.item to it.id }
        /** A belt's net: its item's, none for two items (sideloads onto it from either net are its shape). */
        fun netFor(items: List<String>) = items.singleOrNull()?.let { netOf[it] }

        /** A belt down the column, x [x], heading [dir], carrying [items] (in: ingredients to the machines of column [module]; out: the product). */
        class Lane(val x: Int, val dir: Dir, val items: List<String>, val module: Int = -1) {
            /** Its first belt (in its heading). */
            val head = Tile(x, if (dir == Dir.S) core0 else core1 - 1)
            /** The tile before its first belt, and the one its last belt pushes into. */
            val entrance = head + dir.opposite
            val exit = Tile(x, if (dir == Dir.S) core1 else core0 - 1)
        }
        val feeds = ArrayList<Lane>()
        val drains = ArrayList<Lane>()
        fun drainAt(x: Int): Lane = drains.firstOrNull { it.x == x } ?: Lane(x, v.bDirs[drains.size], listOf(s.product)).also { drains += it }
        /**
         * One side of a column: its machines' x, their inner and outer inserter tracks, towards the middle, their
         * inserters, belts per group, product belt; and the pipe outside it with how its machines turn for the fluid.
         */
        class Side(val mx: Int, val inner: Int, val outer: Int?, val toward: Dir, val ins: Pair<List<Ins>, List<Ins>>, val feedOf: (Int) -> Lane, val drain: Lane,
                   val pipeX: Int? = null, val orient: Orient? = null)
        val sideGeo = List(m) { ArrayList<Side>() }
        val pw = if (d.fluid != null) 1 else 0
        for (j in 0 until m) {
            val x0 = x0s[j]
            when (d.style) {
                Style.NARROW -> {
                    val xm = x0 + pw
                    val a = Lane(xm + mw + if (v.aNear) 1 else 2, Dir.S, d.belts[0], j).also { feeds += it }
                    val b = drainAt(xm + mw + if (v.aNear) 2 else 1)
                    sideGeo[j] += Side(xm, xm + mw, null, Dir.E, d.sideIns(if (v.aNear) 1 else 2, 0), { a }, b, if (pw > 0) x0 else null, d.orients.getOrNull(0))
                    if (!d.oneSided) sideGeo[j] += Side(xm + mw + 4, xm + mw + 3, null, Dir.W, d.sideIns(if (v.aNear) 2 else 1, 0), { a }, b,
                        if (pw > 0) xm + 2 * mw + 4 else null, d.orients.getOrNull(1))
                }
                Style.CAST -> {
                    val xm = x0 + pw
                    val b = drainAt(xm + mw + 1)
                    val none = { _: Int -> error("a casting column has no ingredient belt") }
                    sideGeo[j] += Side(xm, xm + mw, null, Dir.E, d.sideIns(1, 0), none, b, x0, d.orients.getOrNull(0))
                    if (!d.oneSided) sideGeo[j] += Side(xm + mw + 3, xm + mw + 2, null, Dir.W, d.sideIns(1, 0), none, b, xm + 2 * mw + 3, d.orients.getOrNull(1))
                }
                Style.SPLIT -> {
                    val a = Lane(x0 + mw + 3, Dir.S, d.belts[0], j).also { feeds += it }
                    sideGeo[j] += Side(x0 + 2, x0 + mw + 2, x0 + 1, Dir.E, d.sideIns(1, 0), { a }, drainAt(x0))
                    if (!d.oneSided) sideGeo[j] += Side(x0 + mw + 5, x0 + mw + 4, x0 + 2 * mw + 5, Dir.W, d.sideIns(1, 0), { a }, drainAt(x0 + 2 * mw + 6))
                }
                Style.WIDE -> {
                    val (g1, g2) = if (v.swap) 1 to 0 else 0 to 1
                    val a1 = Lane(x0 + mw + 3, Dir.S, d.belts[g1], j).also { feeds += it }
                    val a2 = Lane(x0 + mw + 4, v.a2, d.belts[g2], j).also { feeds += it }
                    val byGroup = { g: Int -> if (g == g1) a1 else a2 }
                    sideGeo[j] += Side(x0 + 2, x0 + mw + 2, x0 + 1, Dir.E, d.sideIns(0, g1), byGroup, drainAt(x0))
                    if (!d.oneSided) sideGeo[j] += Side(x0 + mw + 6, x0 + mw + 5, x0 + 2 * mw + 6, Dir.W, d.sideIns(0, g2), byGroup, drainAt(x0 + 2 * mw + 7))
                }
            }
        }
        if (drains.size != v.bDirs.size) return null
        /** Machines by column. */
        val moduleOf = IntArray(n) { (it % (m * sp)) / sp }
        /** What the machines of [f]'s column take of [item] (all of it comes on [f]: no other belt of the column carries it). */
        fun demand(f: Lane, item: String) = (0 until n).filter { moduleOf[it] == f.module }.sumOf { plan.machines[it].demand[item] ?: 0.0 }
        val feedsOfItem = s.inputs.keys.associateWith { i -> feeds.count { i in it.items } }
        /** Junction of each input belt that has one: the belt [jk] + 1 tiles before its head, sideloaded from both sides. */
        val junction = feeds.associateWith { f -> if (s.junction(f.items, feedsOfItem.getValue(f.items[0]))) f.head.step(f.dir.opposite, 1 + jk) else null }
        for (f in feeds) if (junction[f] != null && (if (f.dir == Dir.S) hTop else hBot) < 1 + jk) return null
        // Two junctions side by side would meet: each needs the tile beside the other's belt.
        val jts = junction.values.filterNotNull()
        if (jts.any { a -> jts.any { b -> a.y == b.y && kotlin.math.abs(a.x - b.x) == 1 } }) return null

        val ents = ArrayList<PlacedEntity>()
        val occ = HashSet<Tile>()
        fun put(e: PlacedEntity) { ents += e; occ += e.rect.tiles() }
        val machines = ArrayList<PlacedMachine>()
        val poleSlots = ArrayList<Tile>()
        /** Per pipe column (by x): the rows of used and of unused fluid connections on it. */
        val pipeRows = HashMap<Int, Pair<MutableList<Int>, MutableList<Int>>>()
        /** Every fluid connection tile of the machines: no header pipe may stand there. */
        val connTiles = HashSet<Tile>()
        for (k in 0 until units) {
            val y0 = core0 + unit * k
            for (j in 0 until m) for ((si, g) in sideGeo[j].withIndex()) {
                val id = (k * m + j) * sp + si
                val (inner, outer) = g.ins
                val outStart = if (outer.size < mh) 1 else 0
                if (id < n) {
                    val mi = plan.machines[id]
                    val rot = Dir.entries[g.orient?.rot ?: 0]
                    val mirror = g.orient?.mirror ?: false
                    put(PlacedEntity(mi.machine, Kind.MACHINE, g.mx, y0, rot, mi.recipe, w = mw, h = mh, modules = s.modules, mirror = mirror))
                    machines += PlacedMachine(id, g.mx, y0, rot, mirror)
                    if (g.pipeX != null && g.orient != null) {
                        val rows = pipeRows.getOrPut(g.pipeX) { mutableListOf<Int>() to mutableListOf() }
                        rows.first += g.orient.used.map { y0 + it }; rows.second += g.orient.unused.map { y0 + it }
                        connTiles += place.connections(mi, g.mx, y0, rot.ordinal, mirror).map { it.tile }
                    }
                    // Inner track: inputs pick from the middle belts (pickup side towards them), outputs from the machine.
                    inner.forEachIndexed { i, ins ->
                        if (ins.group >= 0) put(PlacedEntity(ins.name, Kind.INSERTER, g.inner, y0 + i, g.toward, net = netFor(g.feedOf(ins.group).items)))
                        else put(PlacedEntity(ins.name, Kind.INSERTER, g.inner, y0 + i, g.toward.opposite, net = netOf[s.product]))
                    }
                    // Outer track: outputs pick from the machine (towards the middle) and drop on the outer belt.
                    if (g.outer != null) outer.forEachIndexed { i, ins -> put(PlacedEntity(ins.name, Kind.INSERTER, g.outer, y0 + outStart + i, g.toward, net = netOf[s.product])) }
                }
                // The tracks' tiles no inserter takes (as if the machine stood there), and the rows below the machine.
                for (y in y0 until y0 + unit) {
                    val iy = y - y0
                    if (iy >= inner.size) poleSlots += Tile(g.inner, y)
                    if (g.outer != null && iy !in outStart until outStart + outer.size) poleSlots += Tile(g.outer, y)
                    if (iy >= mh) for (x in g.mx until g.mx + mw) poleSlots += Tile(x, y)
                }
            }
        }
        for (l in feeds + drains) for (y in core0 until core1) put(PlacedEntity(s.belt, Kind.BELT, l.x, y, l.dir, net = netFor(l.items)))
        // The fluid down each column's outer side, fed from the header above: a pipe on every used connection, pipes
        // between them, and pipe-to-ground pairs past unused connections (which no pipe may touch).
        val pipeTops = ArrayList<Tile>()
        /** Pipe-to-ground ends per axis: a pair must not have another on its line between its ends. */
        val ugLine = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        if (d.fluid != null) {
            if (hTop < 1) return null
            val fnet = netOf[d.fluid]
            for ((px, rows) in pipeRows) {
                val usedRows = rows.first.sorted()
                val unused = rows.second.toSet()
                if (usedRows.isEmpty()) continue
                pipeTops += Tile(px, core0 - 1)
                var a = core0 - 1
                for (b in usedRows) {
                    if ((a + 1 until b).any { it in unused }) {
                        if (b - a < 3 || b - a - 3 > GameConstants.PIPE_TO_GROUND_GAP) return null
                        put(PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, px, a + 1, Dir.N, fluid = d.fluid, net = fnet))
                        put(PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, px, b - 1, Dir.S, fluid = d.fluid, net = fnet))
                        ugLine[1] += Tile(px, a + 1); ugLine[1] += Tile(px, b - 1)
                    } else for (y in a + 1 until b) put(PlacedEntity(GameConstants.PIPE, Kind.PIPE, px, y, fluid = d.fluid, net = fnet))
                    put(PlacedEntity(GameConstants.PIPE, Kind.PIPE, px, b, fluid = d.fluid, net = fnet))
                    a = b
                }
            }
            // The pipe columns' other tiles take poles.
            for (px in pipeRows.keys) for (y in core0 until core1) if (Tile(px, y) !in occ) poleSlots += Tile(px, y)
        }
        // Each junction and the straight belts from it to its belt's head.
        for (f in feeds) {
            val j = junction[f] ?: continue
            var t = j
            while (t != f.head) { put(PlacedEntity(s.belt, Kind.BELT, t.x, t.y, f.dir, net = netFor(f.items))); t += f.dir }
        }
        // Beacons beside the machines, a pole in the row between two (the middle of the beacon column).
        val beaconArea = HashSet<Tile>()
        if (s.beacon != null && beaconRows != null) {
            for (bx in beaconXs) {
                for (y in core0 until core1) for (x in bx until bx + o) beaconArea += Tile(x, y)
                beaconRows.first.forEach { r -> put(PlacedEntity(s.beacon.name, Kind.BEACON, bx, core0 + r, w = o, h = o, modules = s.beacon.modules)) }
                beaconRows.second.forEach { r -> poleSlots += Tile(bx + o / 2, core0 + r) }
            }
        }

        fun inBox(t: Tile) = t.x in 0 until W && t.y in 0 until H
        fun sideOfRing(t: Tile): Dir? = when {
            t.x in 0 until W && t.y == -1 -> Dir.N; t.x in 0 until W && t.y == H -> Dir.S
            t.y in 0 until H && t.x == -1 -> Dir.W; t.y in 0 until H && t.x == W -> Dir.E; else -> null
        }
        fun ringTiles(dd: Dir): List<Tile> = when (dd) {
            Dir.N -> (0 until W).map { Tile(it, -1) }
            Dir.S -> (0 until W).map { Tile(it, H) }
            Dir.W -> (0 until H).map { Tile(-1, it) }
            Dir.E -> (0 until H).map { Tile(W, it) }
        }
        // Tiles no belt may use: below an input belt's end (leftovers would spill there; a pass-through leaving
        // from there is the exception), and the column's pole slots and beacon gaps (the room of a missing last
        // machine stays free for belts).
        val feedExits = feeds.map { it.exit }.filter { inBox(it) }.toSet()
        /** Every input belt's end, on the ring too: no port belt may stand there (leftovers would push onto it). */
        val allExits = feeds.map { it.exit }.toSet()
        val reserved0 = HashSet<Tile>()
        reserved0 += (poleSlots + beaconArea).filter { it !in occ }
        // A belt may run past the tile before a product belt's first one, but not point into it (it would feed it).
        val noHeading = drains.associate { it.entrance to it.dir }

        // Routing state, reset for each order of the connections.
        val portRate = s.portRate
        val used = HashSet<Tile>()
        val reserved = HashSet<Tile>()
        /** Tiles inside some underground pair's span (ends included), per axis: no other pair on that axis may touch them. */
        val spans = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        val ugEnds = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        /**
         * Belts routed so far per item, in path order, each path with whether a straight belt feeds its first step from
         * behind (a product belt's or an input belt's end) and whether its last step runs straight on into an input
         * belt's head; splitters go across straight belts of these (branches and merges), and a product belt may be
         * sideloaded onto them. [feed] and [side]: the input belt and junction side it leads to.
         */
        class Tree(val steps: List<Step>, val fedBehind: Boolean, val goesOn: Boolean, val side: Int, val feed: Lane?)
        val trees = HashMap<String, MutableList<Tree>>()
        /** Per input belt with a junction: what reaches each of its sides (all of it goes onto one lane), the item there, and the heading it comes in. */
        val sideLoad = feeds.associateWith { DoubleArray(2) }
        val sideItem = feeds.associateWith { arrayOfNulls<String>(2) }
        val sideDir = feeds.associateWith { arrayOfNulls<Dir>(2) }
        /** Per input belt without a junction: whether a path reaches its head. */
        val supplied = HashSet<Lane>()
        /** Product belts (and input belts passing on) whose end is routed. */
        val drained = HashSet<Lane>()
        /** Belts a product belt is sideloaded onto: no splitter may replace them. */
        val sideloaded = HashSet<Tile>()
        val coreBelts = (feeds + drains).flatMap { l -> (core0 until core1).map { Tile(l.x, it) } }.toSet()
        /** Nothing belt-like stands or will stand on [t] (an inserter or machine there is fine). */
        fun noBelt(t: Tile) = t !in used && t !in coreBelts && (inBox(t) || sideOfRing(t) == null || t !in used)
        class Split(val trunk: Tile, val half: Tile, val dir: Dir, val item: String, val pair: Boolean = false)
        val splits = ArrayList<Split>()
        fun free(t: Tile) = inBox(t) && t !in occ && t !in used && t !in reserved && t !in feedExits

        /**
         * A belt path from one of [starts] (each a belt that will stand there, heading that way) until [done] says
         * the last step finishes it; belts turn left or right, undergrounds jump up to the tier's gap. The cheapest:
         * a belt costs one, an underground pair one more than the tiles it spans, so belts go round or straight on
         * where they can (straight runs are where splitters branch and merge) and undergrounds cross other belts.
         */
        fun path(starts: List<Step>, done: (Step) -> Boolean): List<Step>? {
            // States are tile, heading and whether an underground exit stands there (where it came from does not
            // matter for what can follow).
            val cost = HashMap<Triple<Tile, Dir, Boolean>, Int>()
            val prev = HashMap<Step, Step?>()
            val queue = java.util.PriorityQueue<Triple<Int, Int, Step>>(compareBy<Triple<Int, Int, Step>>({ it.first }, { it.second }))
            var seq = 0
            fun key(st: Step) = Triple(st.tile, st.dir, st.ugFrom != null)
            fun visit(st: Step, from: Step?, c: Int) {
                if (c < (cost[key(st)] ?: Int.MAX_VALUE)) { cost[key(st)] = c; prev[st] = from; queue += Triple(c, seq++, st) }
            }
            starts.filter { noHeading[it.tile] != it.dir }.forEach { visit(it, null, 0) }
            while (queue.isNotEmpty()) {
                val (c, _, cur) = queue.poll()
                if (c > cost.getValue(key(cur))) continue
                if (done(cur)) {
                    val out = ArrayList<Step>()
                    var p: Step? = cur
                    while (p != null) { out += p; p = prev[p] }
                    return out.reversed()
                }
                val nt = cur.tile + cur.dir
                if (!free(nt)) continue
                // After an underground exit the next belt keeps going straight (a turn right after one is unverified).
                if (noHeading[nt] != cur.dir) visit(Step(nt, cur.dir), cur, c + 1)
                if (cur.ugFrom == null) for (t in arrayOf(cur.dir.cw, cur.dir.ccw)) if (noHeading[nt] != t) visit(Step(nt, t), cur, c + 1)
                val axis = cur.dir.axis
                if (nt !in spans[axis]) for (j in 2..s.maxGap + 1) {
                    val e = nt.step(cur.dir, j)
                    if (!inBox(e) || nt.step(cur.dir, j - 1) in ugEnds[axis]) break
                    if (free(e) && e !in spans[axis] && noHeading[e] != cur.dir) visit(Step(e, cur.dir, nt), cur, c + j + 2)
                }
            }
            return null
        }
        /** Plain straight belts of [item]'s paths, fed from behind: each with its heading and whether a straight belt goes on from it. */
        fun straights(item: String): List<Triple<Step, Boolean, Tree>> {
            val out = ArrayList<Triple<Step, Boolean, Tree>>()
            val split = splits.flatMap { listOf(it.trunk, it.half) }.toSet()
            trees[item]?.forEach { tr ->
                val p = tr.steps
                for (i in p.indices) {
                    val b = p[i]
                    if (b.ugFrom != null || !inBox(b.tile) || b.tile in split || b.tile in sideloaded) continue
                    val behind = if (i > 0) p[i - 1].let { a -> a.dir == b.dir && a.tile + a.dir == b.tile } else tr.fedBehind
                    if (!behind) continue
                    val ahead = if (i < p.size - 1) p[i + 1].let { c -> c.dir == b.dir && c.ugFrom == null && b.tile + b.dir == c.tile } else tr.goesOn
                    out += Triple(b, ahead, tr)
                }
            }
            return out
        }
        /**
         * Straight belts of [item]'s paths a splitter could go across: fed from behind, going on straight ahead, with a
         * free tile beside it for the splitter's other half. Returns (trunk, other half, heading).
         */
        fun splitSpots(item: String, merge: Boolean): List<Triple<Tile, Tile, Dir>> {
            val out = ArrayList<Triple<Tile, Tile, Dir>>()
            for ((b, ahead, _) in straights(item)) {
                if (!ahead) continue
                for (side in listOf(b.dir.cw, b.dir.ccw)) {
                    val h = b.tile + side
                    // A merge's other output leads nowhere, inside the box (outside it, a belt between blocks
                    // could stand there); nothing feeds a branch's half from behind.
                    val keep = if (merge) h + b.dir else h + b.dir.opposite
                    if (free(h) && inBox(keep) && noBelt(keep)) out += Triple(b.tile, h, b.dir)
                }
            }
            return out
        }
        /**
         * A routed path: [feed] the input belt it reaches, [side] the junction side it reaches (merged into a path that
         * reaches it, too; -1 for none), [takes] whether it is the first there; [drain] the belt whose end it starts at;
         * [mergeEnd]: it ends in the free half of a splitter (else a splitter it has starts it, a branch).
         */
        class Routed(val steps: List<Step>, val port: Tile?, val split: Split? = null, val feed: Lane? = null, val side: Int = -1,
                     val takes: Boolean = false, val rate: Double = 0.0, val drain: Lane? = null, val sideloads: Tile? = null,
                     val mergeEnd: Boolean = false)
        /** Whether [f] still takes [item] (with [pairItem]: the two of a lane pair) from a path of its own. */
        fun open(f: Lane, item: String, pairItem: String? = null): Boolean {
            if (item !in f.items || (pairItem != null && pairItem !in f.items)) return false
            if (junction[f] != null) return pairItem == null && sideItem.getValue(f).let { its -> null in its && (f.items.size == 1 || item !in its) }
            return f !in supplied && (pairItem != null || f.items.size == 1)
        }
        /** Whether [st] finishes a path into [f]: into its head straight on, or onto a free side of its junction. */
        fun reaches(f: Lane, st: Step): Boolean {
            val j = junction[f] ?: return st.tile + st.dir == f.head && st.dir == f.dir
            val taken = sideDir.getValue(f).let { ds -> ds[0] ?: ds[1] }
            return st.tile + st.dir == j && st.dir.axis != f.dir.axis && st.dir != taken
        }
        /** [p] as a path into one of [cands] (each still open): which it reached, and the junction side it takes. */
        fun into(p: List<Step>, cands: List<Lane>, port: Tile?, split: Split?, item: String, rate: Double): Routed {
            val f = cands.first { reaches(it, p.last()) }
            if (junction[f] == null) return Routed(p, port, split, feed = f)
            return Routed(p, port, split, feed = f, side = sideItem.getValue(f).indexOf(null), takes = true, rate = minOf(rate, demand(f, item)))
        }
        // An input: from the ring on its side, straight in, to a belt heading into an input belt's head, onto a free
        // side of an input belt's junction (carrying at most a lane's worth there), or into the free half of a
        // splitter across the belt of an input of its item already routed (a merge; its other output leads nowhere).
        // ([module]: only into that column's belt; [trunk]: only merging into the item's first path, the trunk.)
        fun routeIn(port: Int, item: String, pairItem: String? = null, module: Int = -1, trunk: Boolean = false): Routed? {
            val dd = sides.getValue(port)
            // A lane pair's two belts join right outside its port when the factory has no room for that next to the
            // supplier: the ring tiles beside it stay free of other ports.
            val starts = ringTiles(dd).filter { it !in used && it !in occ && it !in allExits && (pairItem == null || listOf(it + dd.cw, it + dd.ccw).none { n -> n in used }) }
                .map { Step(it, dd.opposite) }
            val cands = if (trunk) emptyList() else feeds.filter { f ->
                (module < 0 || f.module == module) && open(f, item, pairItem) && (junction[f] == null || minOf(portRate(port), demand(f, item)) <= s.laneCap * 0.98)
            }
            if (cands.isNotEmpty()) {
                val p = path(starts) { st -> cands.any { reaches(it, st) } } ?: return null
                return into(p, cands, p.first().tile, null, item, portRate(port))
            }
            if (pairItem != null || trees[item].isNullOrEmpty()) return null
            /** The path of the item already routed that [t] is on. */
            fun treeAt(t: Tile) = trees[item]?.firstOrNull { tr -> tr.steps.any { it.tile == t } }
            val first = trees.getValue(item).first()
            // A merge adds to its side's lane: it stays within a lane's worth there (a trunk branches off before).
            val merges = splitSpots(item, merge = true).filter { (t, _, _) ->
                val tr = treeAt(t)
                when {
                    trunk -> tr === first
                    tr == null -> module < 0
                    module >= 0 && tr.feed?.module != module -> false
                    else -> tr.side < 0 || tr.feed == null || sideLoad.getValue(tr.feed)[tr.side] + portRate(port) <= s.laneCap * 0.98
                }
            }
            val byHalf = merges.associateBy { it.second }
            val p = path(starts) { st -> byHalf[st.tile + st.dir]?.let { (_, _, h) -> st.dir == h } == true } ?: return null
            val last = p.last()
            val split = byHalf.getValue(last.tile + last.dir).let { (t, h, d2) -> Split(t, h, d2, item) }
            // The merge's other output must lead nowhere: not onto this path.
            if (p.any { it.tile == split.half + split.dir || it.ugFrom == split.half + split.dir }) return null
            val tr = treeAt(split.trunk)
            return Routed(p, p.first().tile, split, feed = tr?.feed, side = tr?.side ?: -1, rate = portRate(port), mergeEnd = true)
        }
        /** Starts of a path at the ends of [lanes]: a belt pushed into from each end, heading on or turning. */
        fun endStarts(lanes: List<Lane>): List<Step> = lanes.filter { inBox(it.exit) && it.exit !in occ && it.exit !in used && it.exit !in reserved }
            .flatMap { l -> listOf(l.dir, l.dir.cw, l.dir.ccw).map { Step(l.exit, it) } }
        /** Branches off [item]'s paths: splitters across them whose free half starts a path. Returns (trunk, half, heading). */
        fun branches(item: String) = splitSpots(item, merge = false)
        fun branchStarts(bs: List<Triple<Tile, Tile, Dir>>) = bs.flatMap { (_, half, h) -> val st = half + h; if (free(st)) listOf(h, h.cw, h.ccw).map { Step(st, it) } else emptyList() }
        // An ingredient's belt that has no port of its own left: a branch off a path of the item already routed, into
        // its head or onto its junction (a lane pair's belt: off the pair's path, both lanes along).
        // ([afterMerges]: off the trunk only, after every splitter on it, so that each branch shares what all ports bring.)
        fun routeBranch(item: String, pairItem: String?, afterMerges: Boolean = false): Routed? {
            val cands = feeds.filter { f -> open(f, item, pairItem) && (junction[f] == null || demand(f, item) <= s.laneCap * 0.98) }
            if (cands.isEmpty()) return null
            var bs = branches(item)
            if (afterMerges) {
                val trunk = trees[item]?.firstOrNull() ?: return null
                val idx = trunk.steps.withIndex().associate { (i, st) -> st.tile to i }
                val last = splits.filter { it.item == item }.mapNotNull { idx[it.trunk] }.maxOrNull() ?: -1
                bs = bs.filter { (t, _, _) -> (idx[t] ?: -1) > last }
            }
            // A splitter right before the head: its free half feeds the belt straight away.
            bs.firstOrNull { (_, half, h) -> cands.any { f -> junction[f] == null && half + h == f.head && h == f.dir } }?.let { (t, half, h) ->
                val f = cands.first { f -> junction[f] == null && half + h == f.head && h == f.dir }
                return Routed(emptyList(), null, Split(t, half, h, item, pairItem != null), feed = f)
            }
            val p = path(branchStarts(bs)) { st -> cands.any { reaches(it, st) } } ?: return null
            val split = bs.first { (_, half, h) -> half + h == p.first().tile }.let { (t, h, d2) -> Split(t, h, d2, item, pairItem != null) }
            // Nothing of this path may feed the branch's half from behind.
            if (p.any { it.tile == split.half + split.dir.opposite || it.ugFrom == split.half + split.dir.opposite }) return null
            return into(p, cands, null, split, item, Double.MAX_VALUE)
        }
        // An output: a belt pushed into from the end of a product belt not yet routed (or of an input belt carrying
        // only the item, passing on), or a branch off a splitter across a straight belt of the item already routed,
        // to a belt on the ring of its side heading out.
        fun fromEnds(dd: Dir, ends: List<Lane>): Routed? {
            // A belt end already on the ring: it is the port if it is on the right side.
            ends.firstOrNull { l -> !inBox(l.exit) && sideOfRing(l.exit) == dd && l.dir == dd && l.exit !in used }?.let { l ->
                return Routed(listOf(Step(l.exit, dd)), l.exit, drain = l)
            }
            val from = ends.filter { inBox(it.exit) }
            if (from.isEmpty()) return null
            val p = path(endStarts(from)) { st -> val nt = st.tile + st.dir; sideOfRing(nt) == dd && st.dir == dd && nt !in used && nt !in occ && nt !in allExits } ?: return null
            val portTile = p.last().tile + dd
            return Routed(p + Step(portTile, dd), portTile, drain = from.first { it.exit == p.first().tile })
        }
        fun branchOut(dd: Dir, item: String): Routed? {
            val bs = branches(item)
            // A branch whose half points straight out of the box on the port's side is the port itself.
            bs.firstOrNull { (_, half, h) -> val st = half + h; h == dd && sideOfRing(st) == dd && st !in used && st !in occ && st !in allExits }?.let { (t, half, h) ->
                return Routed(listOf(Step(half + h, dd)), half + h, Split(t, half, h, item))
            }
            val p = path(branchStarts(bs)) { st -> val nt = st.tile + st.dir; sideOfRing(nt) == dd && st.dir == dd && nt !in used && nt !in occ && nt !in allExits } ?: return null
            val portTile = p.last().tile + dd
            val split = bs.first { (_, half, h) -> half + h == p.first().tile }.let { (t, h, d2) -> Split(t, h, d2, item) }
            if (p.any { it.tile == split.half + split.dir.opposite || it.ugFrom == split.half + split.dir.opposite }) return null
            return Routed(p + Step(portTile, dd), portTile, split)
        }
        fun routeOut(port: Int, item: String, pass: Boolean, merged: Boolean): Routed? {
            val dd = sides.getValue(port)
            if (pass) {
                val ends = feeds.filter { it.items == listOf(item) && it !in drained }
                return fromEnds(dd, ends) ?: branchOut(dd, item)
            }
            // [merged]: the product belts meet first and every port after the first branches off (ports whose rates
            // are not what each belt carries).
            val ends = if (merged && !trees[item].isNullOrEmpty()) emptyList() else drains.filter { it !in drained }
            return if (ends.isNotEmpty()) fromEnds(dd, ends) else branchOut(dd, item)
        }
        // A product belt without a port of its own: from its end into the free half of a splitter across a straight
        // belt of the product already routed (its other output leading nowhere), or sideloaded onto one.
        fun routeMerge(l: Lane): Routed? {
            if (!inBox(l.exit)) return null
            val item = s.product
            val byHalf = splitSpots(item, merge = true).associateBy { it.second }
            val sideTargets = straights(item).map { it.first }.associateBy { it.tile }
            val p = path(endStarts(listOf(l))) { st ->
                val nt = st.tile + st.dir
                byHalf[nt]?.let { (_, _, h) -> st.dir == h } == true || sideTargets[nt]?.let { b -> st.dir.axis != b.dir.axis } == true
            } ?: return null
            val last = p.last()
            val nt = last.tile + last.dir
            val split = byHalf[nt]?.takeIf { (_, _, h) -> last.dir == h }?.let { (t, h, d2) -> Split(t, h, d2, item) }
            if (split != null && p.any { it.tile == split.half + split.dir || it.ugFrom == split.half + split.dir }) return null
            return Routed(p, null, split, drain = l, sideloads = if (split == null) nt else null, mergeEnd = split != null)
        }
        fun book(r: Routed, item: String, pairItem: String?) {
            r.steps.forEach { st ->
                used += st.tile
                st.ugFrom?.let { a ->
                    used += a
                    val axis = st.dir.axis
                    var c = a
                    while (true) { spans[axis] += c; if (c == st.tile) break; c += st.dir }
                    ugEnds[axis] += a; ugEnds[axis] += st.tile
                }
            }
            val f = r.feed
            val first = r.steps.firstOrNull()
            if (first != null) {
                val end = (drains + feeds).firstOrNull { r.split == null && first.tile == it.exit && (r.drain == null || r.drain === it) }
                trees.getOrPut(item) { ArrayList() } += Tree(r.steps,
                    fedBehind = end != null && first.dir == end.dir,
                    goesOn = !r.mergeEnd && f != null && junction[f] == null && r.steps.last().let { it.tile + it.dir == f.head }, side = r.side, feed = f)
            }
            if (f != null && junction[f] != null && r.side >= 0) {
                sideLoad.getValue(f)[r.side] += r.rate
                if (r.takes) { sideItem.getValue(f)[r.side] = item; sideDir.getValue(f)[r.side] = r.steps.last().dir }
            }
            if (f != null && junction[f] == null && !r.mergeEnd) supplied += f
            r.drain?.let { drained += it }
            r.sideloads?.let { sideloaded += it }
            r.split?.let { sp ->
                splits += sp
                used += sp.half
                // A merge's free half leads nowhere: nothing may stand in front of it; nothing may feed a branch's half.
                val keep = if (r.mergeEnd) sp.half + sp.dir else sp.half + sp.dir.opposite
                if (inBox(keep) && keep !in occ) reserved += keep
            }
        }

        /**
         * The fluid's header pipes, once the belts are routed: from its first port to the top of a pipe column, then
         * from those pipes to the other columns' tops, then the other ports onto them; pipe-to-ground pairs pass
         * under belts. The pipes and the fluid's sources, or null.
         */
        fun routePipes(): Pair<List<PlacedEntity>, List<SourceSpec>>? {
            val fl = d.fluid ?: return emptyList<PlacedEntity>() to emptyList()
            val fnet = netOf[fl]
            val taken = HashSet<Tile>()
            /** Plain pipes of the network: anything pipe-like beside them joins it. */
            val plain = HashSet<Tile>()
            val spansP = arrayOf(HashSet<Tile>(), HashSet<Tile>())
            val lines = arrayOf(HashSet(ugLine[0]), HashSet(ugLine[1]))
            val halves = splits.map { it.half }.toSet()
            val out = ArrayList<PlacedEntity>()
            fun pfree(t: Tile) = inBox(t) && t !in occ && t !in used && t !in halves && t !in reserved && t !in taken && t !in connTiles && t !in feedExits
            /** A path state: a plain pipe on [tile], or ([exit]) a pipe-to-ground exit there opening that way, its entrance on [from]. */
            data class PS(val tile: Tile, val exit: Dir?, val from: Tile? = null)
            fun search(starts: List<Tile>, goal: (Tile) -> Boolean): List<PS>? {
                val cost = HashMap<Pair<Tile, Dir?>, Int>()
                val prev = HashMap<PS, PS?>()
                val q = java.util.PriorityQueue<Triple<Int, Int, PS>>(compareBy<Triple<Int, Int, PS>>({ it.first }, { it.second }))
                var seq = 0
                fun visit(st: PS, from: PS?, c: Int) {
                    val k = st.tile to st.exit
                    if (c < (cost[k] ?: Int.MAX_VALUE)) { cost[k] = c; prev[st] = from; q += Triple(c, seq++, st) }
                }
                starts.forEach { visit(PS(it, null), null, 0) }
                while (q.isNotEmpty()) {
                    val (c, _, cur) = q.poll()
                    if (c > cost.getValue(cur.tile to cur.exit)) continue
                    if (cur.exit == null && goal(cur.tile)) {
                        val path = ArrayList<PS>()
                        var x: PS? = cur
                        while (x != null) { path += x; x = prev[x] }
                        return path.reversed()
                    }
                    for (dd in if (cur.exit != null) listOf(cur.exit) else Dir.entries) {
                        val nt = cur.tile + dd
                        if (!pfree(nt)) continue
                        visit(PS(nt, null), cur, c + 1)
                        val axis = dd.axis
                        if (nt in spansP[axis]) continue
                        for (k in 1..GameConstants.PIPE_TO_GROUND_GAP + 1) {
                            val e = nt.step(dd, k)
                            if (!inBox(e) || e in lines[axis] || e in spansP[axis]) break
                            if (pfree(e)) visit(PS(e, dd, nt), cur, c + k + 3)
                        }
                    }
                }
                return null
            }
            fun book(path: List<PS>) {
                for (st in path) {
                    if (st.exit != null) {
                        val a = st.from!!
                        out += PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, a.x, a.y, st.exit.opposite, fluid = fl, net = fnet)
                        out += PlacedEntity(GameConstants.PIPE_TO_GROUND, Kind.PIPE_UG, st.tile.x, st.tile.y, st.exit, fluid = fl, net = fnet)
                        taken += a; taken += st.tile
                        val axis = st.exit.axis
                        lines[axis] += a; lines[axis] += st.tile
                        var t = a
                        while (true) { spansP[axis] += t; if (t == st.tile) break; t += st.exit }
                    } else if (st.tile !in plain) {
                        out += PlacedEntity(GameConstants.PIPE, Kind.PIPE, st.tile.x, st.tile.y, fluid = fl, net = fnet)
                        taken += st.tile; plain += st.tile
                    }
                }
            }
            val sources = ArrayList<SourceSpec>()
            fun portStart(p: Int): Tile? = ringTiles(sides.getValue(p)).firstOrNull { it !in used && it !in occ && it !in allExits && it !in taken &&
                (it + sides.getValue(p).opposite).let { inn -> pfree(inn) } }
            fun near(t: Tile) = Dir.entries.any { t + it in plain }
            val left = pipeTops.toMutableSet()
            for ((i, p) in s.fluidPorts.withIndex()) {
                // The port's pipe stands on the ring; the first port leads to a column's top, later ones join the pipes.
                val starts = ringTiles(sides.getValue(p)).filter { it !in used && it !in occ && it !in allExits && it !in taken }
                if (starts.isEmpty() || portStart(p) == null) return null
                // From the ring a path may only step into the box.
                val path = search(starts) { t -> if (i == 0) t in left else inBox(t) && near(t) } ?: return null
                book(path)
                left -= path.map { it.tile }.toSet()
                sources += SourceSpec(path.first().tile, fl, portRate(p), fluid = true, port = p)
                if (i == 0) while (left.isNotEmpty()) {
                    val more = search(plain.filter { inBox(it) }) { t -> t in left } ?: return null
                    book(more)
                    left -= more.map { it.tile }.toSet()
                }
            }
            if (left.isNotEmpty()) return null
            return out to sources
        }

        // Connections: inputs first (each item's first ports onto its belts' heads or junction sides, the rest merge
        // in), branches to the belts of an item that has fewer ports than belts, then outputs (the first from a product
        // belt's end, the rest branch off, product belts left over merge in), ingredients passing on last; a few
        // orders of each group.
        /**
         * A connection: an input or output port, a product belt to merge in, a lane pair (input [port] and [with]), a
         * branch to another belt of [item] ([branch]), or an ingredient passing on ([pass]).
         */
        data class Conn(val input: Boolean, val port: Int, val item: String, val merge: Lane? = null, val with: Int = -1,
                        val branch: Boolean = false, val pass: Boolean = false, val module: Int = -1, val trunk: Boolean = false)
        val pairedPorts = s.pairs.flatMap { listOf(it.first, it.second) }.toSet()
        /** Per item fed as a trunk: its first port (the largest), the one not merging. */
        val trunkHead = feeding.filterValues { it is Feeding.Trunk }.keys.associateWith { i -> s.inputs.getValue(i).maxBy { portRate(it) } }
        val ins = (s.inputs.flatMap { (i, ps) -> ps.filter { it !in pairedPorts }.map { p ->
            Conn(true, p, i, module = (feeding[i] as? Feeding.Assign)?.module?.get(p) ?: -1, trunk = trunkHead[i]?.let { it != p } == true)
        } } + s.pairs.map { (a, b) -> Conn(true, a, s.itemOf.getValue(a), with = b) }).sortedByDescending { portRate(it.port) + if (it.with >= 0) portRate(it.with) else 0.0 }
        val pairedItems = pairedPorts.map { s.itemOf.getValue(it) }.toSet()
        fun branchCount(i: String, ports: Int) = when (feeding[i]) {
            is Feeding.Assign -> 0
            Feeding.Trunk -> feedsOfItem.getValue(i) - 1
            else -> maxOf(0, feedsOfItem.getValue(i) - ports)
        }
        val brs = s.inputs.filterKeys { it !in pairedItems }.flatMap { (i, ps) -> List(branchCount(i, ps.size)) { Conn(true, -1, i, branch = true) } } +
            s.pairs.flatMap { (a, b) -> List(maxOf(0, feeds.count { s.itemOf.getValue(a) in it.items && s.itemOf.getValue(b) in it.items } - 1)) { Conn(true, -1, s.itemOf.getValue(a), with = b, branch = true) } }
        val outs = s.outputs.sortedByDescending { portRate(it) }.map { Conn(false, it, s.product) }
        val pass = s.passes.sortedByDescending { portRate(it) }.map { Conn(false, it, s.portItem.getValue(it), pass = true) }
        fun rotations(xs: List<Conn>) = if (xs.size <= 1) listOf(xs) else xs.indices.map { i -> xs.drop(i) + xs.take(i) }
        // Product belts without a port of their own merge in once the ports are routed, or (merged) right after the
        // first port, the other ports branching off after them.
        val merges = if (drains.size > 1) drains.map { Conn(false, -1, s.product, it) } else emptyList()
        val modes = if (drains.size > 1 && outs.size > 1) listOf(false, true) else listOf(false)
        val orders = rotations(ins).flatMap { a -> rotations(outs).flatMap { b -> modes.flatMap { md ->
            val bm = if (md) b.take(1) + merges + b.drop(1) else b + merges
            listOf(a + brs + bm + pass, bm + a + brs + pass, a + brs + pass + bm, pass + a + brs + bm).map { it to md }
        } } }.distinct().take(32)
        // Routing is the same for the same first connections: an order starting with ones that failed fails too.
        val failed = HashSet<Pair<List<Conn>, Boolean>>()
        for ((order, merged) in orders) {
            if ((1..order.size).any { k -> order.take(k) to merged in failed }) continue
            used.clear(); reserved.clear(); spans.forEach { it.clear() }; ugEnds.forEach { it.clear() }
            reserved += reserved0
            trees.clear(); splits.clear(); drained.clear(); sideloaded.clear(); supplied.clear()
            sideLoad.values.forEach { it.fill(0.0) }; sideItem.values.forEach { it.fill(null) }; sideDir.values.forEach { it.fill(null) }
            val got = ArrayList<Pair<Conn, Routed>>()
            var ok = true
            for (c in order) {
                if (c.merge != null && c.merge in drained) continue
                val pairItem = if (c.with >= 0) s.itemOf.getValue(c.with) else null
                val r = when {
                    c.branch -> routeBranch(c.item, pairItem, afterMerges = feeding[c.item] == Feeding.Trunk)
                    c.input -> routeIn(c.port, c.item, pairItem, c.module, c.trunk)
                    c.merge != null -> routeMerge(c.merge)
                    else -> routeOut(c.port, c.item, pass = c.pass, merged = merged)
                }
                if (r == null) {
                    ok = false; failed += order.take(order.indexOf(c) + 1) to merged
                    if (DEBUG) println("  ${d.label} ${W}x$H top $hTop bot $hBot $v: ${if (c.input) "in" else "out"}${if (c.branch) " branch" else ""} ${c.item} port ${c.port} (${sides[c.port]}) fails after ${got.size}")
                    break
                }
                book(r, c.item, pairItem)
                if (c.with >= 0 && !c.branch) r.port?.let { pt -> val dd = sides.getValue(c.port); used += pt + dd.cw; used += pt + dd.ccw }
                got += c to r
            }
            if (!ok || drains.any { it !in drained }) continue
            // Every input belt gets all its items.
            if (feeds.any { f -> if (junction[f] != null) f.items.any { it !in sideItem.getValue(f) } else f !in supplied }) continue
            val (pipeEnts, fluidSources) = routePipes() ?: continue
            routed.incrementAndGet()
            val all = ArrayList(ents)
            all += pipeEnts
            val trunks = splits.associateBy { it.trunk }
            got.forEach { (c, r) ->
                // (A lane pair's belt carries two items: no net of one.)
                val net = if (c.with >= 0) null else netOf[c.item]
                r.steps.forEach { st ->
                    when {
                        st.ugFrom != null -> {
                            all += PlacedEntity(s.underground, Kind.UG_IN, st.ugFrom.x, st.ugFrom.y, st.dir, net = net)
                            all += PlacedEntity(s.underground, Kind.UG_OUT, st.tile.x, st.tile.y, st.dir, net = net)
                        }
                        st.tile in trunks -> {}   // a splitter stands there
                        else -> all += PlacedEntity(s.belt, Kind.BELT, st.tile.x, st.tile.y, st.dir, net = net)
                    }
                }
            }
            splits.forEach { sp ->
                all += PlacedEntity(s.splitter, Kind.SPLITTER, minOf(sp.trunk.x, sp.half.x), minOf(sp.trunk.y, sp.half.y), sp.dir, net = if (sp.pair) null else netOf[sp.item])
            }
            // A lane pair: its first item on the left lane, the other on the right (looking along the belt into the block).
            val sources = got.filter { it.first.input && !it.first.branch }.flatMap { (c, r) ->
                if (c.with < 0) listOf(SourceSpec(r.port!!, c.item, portRate(c.port), port = c.port))
                else listOf(SourceSpec(r.port!!, c.item, portRate(c.port), lane = 0, port = c.port), SourceSpec(r.port, s.itemOf.getValue(c.with), portRate(c.with), lane = 1, port = c.with))
            } + fluidSources
            val outputs = got.filter { !it.first.input && it.first.merge == null }.map { (c, r) -> OutputSpec(r.port!!, c.item, portRate(c.port), port = c.port) }
            // Poles on the free slots (and free header tiles if those are not enough).
            val taken = all.flatMap { it.rect.tiles() }.toSet()
            // (With the room of a missing last machine.)
            val headerFree = (0 until H).flatMap { y -> (0 until W).map { x -> Tile(x, y) } }.filter { it !in taken && (it.y < core0 || it.y >= core1 || it !in reserved0) }
            val placement = Placement(W, H, machines.sortedBy { it.id })
            val layout: Layout
            val label = "template ${d.label} ${W}x$H"
            if (config.power.mode == "none") layout = Layout(W, H, all, label = label)
            else {
                val ps = placePoles(all, poleSlots.filter { it !in taken } + headerFree, config.power.pole)
                if (!ps.ok) continue
                val off = all.size
                layout = Layout(W, H, all + ps.poles, ps.wires.map { (a, b) -> a + off to b + off }, label)
            }
            val outside = (sources.map { it.tile } + outputs.map { it.tile }).toSet()
            val vspec = validationSpec(data, plan, config, placement, sources, requirePower = config.power.mode != "none",
                outsideAllowed = outside, outputs = outputs)
            validated.incrementAndGet()
            val errs = validate(layout, vspec)
            if (DEBUG && errs.isNotEmpty()) println("  ${d.label} ${W}x$H top $hTop bot $hBot $v: invalid ${errs.take(3)}")
            if (errs.isNotEmpty()) continue
            return TrialResult(true, W, H, 0L, layout, placement, emptyList(), null, score(layout, config), "ok", 0.0,
                System.currentTimeMillis() - t0, listOf("template: ${d.style.label} of $n ${s.machine} (${s.recipe}), headers $hTop/$hBot" +
                    (if (m > 1) ", $m columns" else "") + (if (d.oneSided) ", one-sided" else "") + (if (bypass) ", bypass lane" else "")),
                sources, outputs)
        }
        return null
    }
}
