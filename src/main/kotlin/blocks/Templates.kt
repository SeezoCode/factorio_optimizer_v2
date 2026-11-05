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
import plan.MachineInstance
import plan.ProductionPlan
import plan.BeaconSpec
import plan.Role
import plan.beaconNeed
import plan.beaconSpec
import plan.inBeaconRange
import plan.inserterRate
import plan.machineModules
import power.placePoles
import route.spoilProne
import search.TrialResult
import search.score
import search.validationSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.validate

/**
 * Ready-made layouts ("templates") for simple blocks, offered next to the searched ones: the floorplan (or a
 * single block's search) uses one only where it packs better, so nothing is forced. Each is validated like any
 * searched layout before it is offered.
 *
 * The machine column: single-recipe blocks of 3x3 machines with one to four solid ingredients (fuel counts) and one
 * product, such as smelting, gears, circuits, science packs or engines; two machines per three rows. With one or two
 * ingredients (the narrow column, ten wide):
 *
 * ```
 * |machine|in, out, pole|A|B|in, out, pole|machine|
 * ```
 *
 * Belt A carries the ingredients down the middle (two of them a lane each), belt B the product. On each side one
 * inserter stands next to A or B and a long-handed one reaches the other belt across it: on the left a short inserter
 * takes from A and a long one puts on B, on the right a long one takes from A and a short one puts on B (or mirrored,
 * B left of A). The third tile of each three is free for a pole.
 *
 * With three or four ingredients (the wide column, fourteen wide), or two that do not fit a lane each:
 *
 * ```
 * |B1|out|machine|in, in, pole|A1|A2|in, in, pole|machine|out|B2|
 * ```
 *
 * Two ingredient belts down the middle (four lanes): each machine takes from the nearer with a short inserter and from
 * the farther with a long-handed one, and puts its product out of its other side onto an outer belt (B1 or B2, one
 * lane each). A2 may run the other way to A1, so each has a header end of its own for its junction.
 *
 * Short headers above and below the column run the port belts to whichever sides of the block the ports are on (a
 * small belt search, undergrounds included). A belt of two ingredients gets them sideloaded from either side of the
 * belt just above its head, so each lands on a lane of its own whatever lanes it came on; so does a belt of one fed by
 * two ports. Further input ports merge into their item's belt through splitters; more product ports branch off
 * through splitters, and two product belts with one port between them meet by a splitter or a sideload. What is left
 * of a one-ingredient A can leave as a pass-through output.
 */
object Templates {
    private const val LONG = "long-handed-inserter"
    private val DEBUG = System.getenv("TEMPLATE_DEBUG") != null
    /** Most ingredients a column takes: two belts of two lanes. */
    const val MAX_INGREDIENTS = 4

    /** A machine a machine column can hold: 3 x 3, one to four solid ingredients (fuel counts), one item product, no fluids. */
    fun columnMachine(m: MachineInstance): Boolean =
        m.w == 3 && m.h == 3 && m.needsFluid == null && m.fluidDemand.isEmpty() && m.fluidIn.isEmpty() && m.fluidOut.isEmpty() &&
            m.demand.size in 1..MAX_INGREDIENTS && m.itemProducts.size == 1 && m.itemProducts.keys.single() !in m.demand

    /** How a column carries a recipe: its ingredients per input belt (one belt or two; two items ride a lane each), and its short inserters. */
    private class Fit(val belts: List<List<String>>, val shortIn: String, val shortOut: String)

    /**
     * The narrowest arrangement of [ms]'s ingredients whose inserters keep up: the short and long-handed inserters
     * that take from a belt move everything the machine needs from it. With [laneCap], what the whole column needs
     * of an item must fit its lane (a belt where it is alone); without, only the inserters count. Items in one of
     * [together] share a belt.
     */
    private fun fit(ms: List<MachineInstance>, config: RunConfiguration, laneCap: Double?, together: List<Set<String>> = emptyList(), why: (String) -> Unit): Fit? {
        val total = ms[0].demand.keys.associateWith { i -> ms.sumOf { it.demand.getValue(i) } }
        val items = total.keys.sortedByDescending { total.getValue(it) }
        val perMachine = items.associateWith { i -> ms.maxOf { it.demand.getValue(i) } }
        val maxOut = ms.maxOf { it.itemProducts.values.single() }
        if (LONG !in config.inserters.allowed) { why("long-handed inserters not allowed"); return null }
        val shortOnes = config.inserters.allowed.filter { it != LONG }.sortedBy { GameConstants.inserter(it).tierCost }
        fun lanesOk(g: List<String>) = laneCap == null ||
            (if (g.size == 1) total.getValue(g[0]) <= 2 * laneCap + 1e-9 else g.all { total.getValue(it) <= laneCap + 1e-9 })
        val options: List<List<List<String>>> = when (items.size) {
            1 -> listOf(listOf(items))
            2 -> listOf(listOf(items), items.map { listOf(it) })
            3 -> items.map { a -> listOf(listOf(a), items - a) }
            else -> (1..3).map { j -> listOf(listOf(items[0], items[j]), items.drop(1) - items[j]) }
        }
        fun need(belts: List<List<String>>) = belts.maxOf { g -> g.sumOf { perMachine.getValue(it) } }
        // Items that come on one belt (a lane pair) stay on one.
        val fitting = options.filter { o -> o.all(::lanesOk) && together.all { t -> o.any { g -> g.containsAll(t) } } }
            .sortedWith(compareBy({ it.size }, { need(it) }))
        if (fitting.isEmpty()) { why("more of an ingredient than a lane holds (${items.joinToString { "$it ${"%.2f".format(total[it])}/s" }})"); return null }
        var reason = ""
        for (belts in fitting) {
            val n = need(belts)
            val wide = belts.size == 2
            val shortIn = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = true) >= n - 1e-9 }
            val shortOut = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = false) >= maxOut - 1e-9 }
            reason = when {
                shortIn == null -> "no inserter takes ${"%.2f".format(n)}/s from a belt"
                shortOut == null -> "no inserter moves ${"%.2f".format(maxOut)}/s"
                inserterRate(LONG, config, true) < n - 1e-9 || (!wide && inserterRate(LONG, config, false) < maxOut - 1e-9) ->
                    "long-handed inserters too slow (${"%.2f".format(n)}/s in, ${"%.2f".format(maxOut)}/s out)"
                else -> return Fit(belts, shortIn, shortOut)
            }
        }
        why(reason)
        return null
    }

    /**
     * Recipes of [plan] whose machines a machine column can hold with the inserters [config] allows, with at most
     * [maxIngredients] ingredients and at least [minMachines] machines (fast assemblers outrun long-handed inserters):
     * blocks of only these get a column, so the split keeps them apart.
     */
    fun columnRecipes(plan: ProductionPlan, config: RunConfiguration, maxIngredients: Int = MAX_INGREDIENTS, minMachines: Int = 1): Set<String> {
        if (LONG !in config.inserters.allowed || !config.portsOnRing || config.heat.mode != "off") return emptySet()
        return plan.machines.groupBy { it.recipe }.filter { (_, ms) ->
            ms.size >= minMachines && ms.all(::columnMachine) && ms.map { it.machine }.distinct().size == 1 && ms[0].demand.size <= maxIngredients &&
                !(config.spoilage.mode != "off" && ms.any(::spoilProne)) && fit(ms, config, null) {} != null
        }.keys
    }

    /** Every template layout for [plan] (a block's plan), each validated; [note] explains why there is none. */
    fun layouts(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit = {}): List<TrialResult> =
        column(data, config, plan, note) ?: emptyList()

    private class ColumnSpec(
        val machine: String, val recipe: String,
        /** Ingredients per input belt: one belt (narrow column) or two (wide); two items on a belt ride a lane each. */
        val belts: List<List<String>>, val product: String,
        /** Input ports per ingredient, product output ports, a pass-through port (narrow, one ingredient) or none. */
        val inputs: Map<String, List<Int>>, val outputs: List<Int>, val pass: Int?,
        val shortIn: String, val shortOut: String,
        val belt: String, val underground: String, val splitter: String, val maxGap: Int,
        /** Items/s one lane of the tier holds. */
        val laneCap: Double,
        /** Beacons each machine needs in range (0: none), the beacon, and the modules in the machines' own slots. */
        val need: Int = 0, val beacon: BeaconSpec? = null, val modules: List<String>? = null,
        /** Lane pairs: two input ports whose items come on one belt, one a lane (a factory's link pair). */
        val pairs: List<Pair<Int, Int>> = emptyList(),
        /** Items/s through each port. */
        val portRate: (Int) -> Double = { 0.0 },
    ) {
        val wide: Boolean get() = belts.size == 2
        val itemOf: Map<Int, String> = inputs.flatMap { (i, ps) -> ps.map { it to i } }.toMap()
        /**
         * An input belt gets a junction above its head: two items from ports of their own, or one from two ports or
         * more, each within a lane (a port carrying more runs straight into the head, the others merging into it
         * through splitters).
         */
        fun junction(items: List<String>) =
            if (items.size == 2) pairs.none { (a, b) -> setOf(itemOf[a], itemOf[b]) == items.toSet() }
            else inputs.getValue(items[0]).let { ps -> ps.size >= 2 && ps.all { portRate(it) <= laneCap * 0.98 } }
    }

    private val SIDES = setOf("north", "east", "south", "west")

    /** The machine column in each of its four turns that fits the plan's port sides, smallest first. */
    private fun column(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit): List<TrialResult>? {
        val ms = plan.machines
        if (ms.isEmpty()) return null
        val m0 = ms[0]
        fun no(why: String): List<TrialResult>? { note("no machine column: $why"); return null }
        if (ms.any { it.recipe != m0.recipe || it.machine != m0.machine }) return no("more than one recipe")
        if (ms.any { !columnMachine(it) }) return no("${m0.machine} (${m0.recipe}) is not a 3 x 3 machine with one to four solid ingredients and one product")
        if (!config.portsOnRing) return no("ports_on_ring is off")
        // Its inserters sit between machines and belts with no tile a heat pipe could take.
        if (config.heat.mode != "off") return no("heating (Aquilo)")
        if (config.spoilage.mode != "off" && ms.any(::spoilProne)) return no("spoilage (drains)")
        if (plan.terminals.any { it.role == Role.SINK }) return no("sink chests")
        val ingredients = m0.demand.keys.toList()
        val product = m0.itemProducts.keys.single()
        if (plan.ports.any { p -> p.fluid || p.specs.size != 1 || p.specs[0].let { it.edge !in SIDES || it.from != null || it.to != null } })
            return no("ports other than one item per edge")
        val ins = plan.ports.filter { !it.output }
        val outs = plan.ports.filter { it.output }
        if (ins.any { it.item !in ingredients } || ingredients.any { i -> ins.none { it.item == i } }) return no("input ports other than of ${ingredients.joinToString()}")
        val prodOut = outs.filter { it.item == product }
        val passOut = outs.filter { it.item in ingredients }
        if (prodOut.isEmpty() || passOut.size > 1 || outs.size != prodOut.size + passOut.size) return no("output ports other than of $product (and one of an ingredient passing on)")
        if (passOut.isNotEmpty() && ingredients.size > 1) return no("${passOut[0].item} passing on a belt it shares with other ingredients")
        val tier = GameConstants.beltTier(config.beltTier, config.beltStackSize)
        val laneCap = tier.throughput / 2
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        val inPorts = ingredients.associateWith { i -> ins.filter { it.item == i }.map { it.id } }
        // Lane pairs: two ingredients on one belt from one side, each from that port alone (another port of either
        // would put it on the other's lane).
        val pairs = ins.filter { it.pairWith >= 0 }.map { listOf(it.id, it.pairWith).sorted().let { (a, b) -> a to b } }.distinct()
        for ((a, b) in pairs) {
            val pa = plan.ports.getOrNull(a); val pb = plan.ports.getOrNull(b)
            if (pa == null || pb == null || pa.output || pb.output || pa.item == pb.item || pa.specs[0].edge != pb.specs[0].edge ||
                pairs.count { it.first == a || it.second == a || it.first == b || it.second == b } > 1) return no("lane pairs other than two ingredients on one side")
            if (ins.count { it.item == pa.item } > 1 || ins.count { it.item == pb.item } > 1) return no("${pa.item} or ${pb.item} on a lane pair and on another port")
        }
        val together = pairs.map { (a, b) -> setOf(plan.ports[a].item, plan.ports[b].item) }
        val f = fit(ms, config, laneCap, together) { note("no machine column: $it") } ?: return null
        for (g in f.belts) {
            if (g.size == 1 && inPorts.getValue(g[0]).sumOf(::portRate) > tier.throughput + 1e-9) return no("input over one belt")
            if (g.size == 2 && g.any { i -> inPorts.getValue(i).sumOf(::portRate) > laneCap + 1e-9 }) return no("an ingredient over a lane")
        }

        // Beacons: a column of them along each side, outside the machines.
        val need = ms.maxOf { beaconNeed(it.crafts, it.beaconCrafts) }
        val beacon = if (need > 0) beaconSpec(data, config) ?: return no("$need beacon(s) per machine but no beacon") else null
        val modules = if (config.beacons == null && config.qualityProduction == null) null else data.recipes[m0.recipe]?.let { machineModules(data, config, it, m0.machine) }?.takeIf { it.isNotEmpty() }
        val spec = ColumnSpec(m0.machine, m0.recipe, f.belts, product, inPorts, prodOut.map { it.id }, passOut.firstOrNull()?.id,
            f.shortIn, f.shortOut, tier.belt, tier.underground, tier.splitter, tier.maxUndergroundGap, laneCap, need, beacon, modules, pairs, ::portRate)
        val sides = plan.ports.associate { it.id to Dir.fromName(it.specs[0].edge!!) }
        val found = ArrayList<TrialResult>()
        for (q in 0..3) {
            // Built upright (A heading south), then turned q quarter turns: the ports' sides turned back first.
            val built = sides.mapValues { (_, d) -> d.turned(-q) }
            val r = buildColumn(data, config, plan, spec, built) ?: continue
            found += turnLayout(r, q)
        }
        if (found.isEmpty()) return no("no header fits the port sides")
        // Keep the smallest of each shape (upright or lying).
        return found.groupBy { it.w to it.h }.map { (_, rs) -> rs.minBy { it.score } }.sortedBy { it.area }
    }

    /**
     * Beacon rows beside a column of [rows] core rows (machines every three rows from row 0, [gap] tiles from the
     * beacons): beacon start rows and the rows between two beacons where a pole stands (every beacon touches one), so
     * that every machine has [need] beacons in range, or null. Units stacked from a few first rows: beacon, pole row,
     * beacon (or beacon, pole row), and one more beacon with its pole row where the rest has room; also mirrored, from
     * the bottom. The fewest beacons.
     */
    private fun beaconRows(rows: Int, b: BeaconSpec, need: Int, gap: Int): Pair<List<Int>, List<Int>>? {
        val sz = b.size
        fun enough(starts: List<Int>) = (0 until rows / 3).all { k -> starts.count { inBeaconRange(b, 0, it, sz + gap, 3 * k, 3, 3) } >= need }
        val found = ArrayList<Pair<List<Int>, List<Int>>>()
        for (pair in listOf(true, false)) for (off in 0..2 * sz) for (flip in listOf(false, true)) {
            val starts = ArrayList<Int>(); val gaps = ArrayList<Int>()
            val unit = if (pair) 2 * sz + 1 else sz + 1
            var r = off
            while (r + unit <= rows) {
                starts += r; gaps += r + sz
                if (pair) starts += r + sz + 1
                r += unit
            }
            if (r + sz + 1 <= rows) { starts += r; gaps += r + sz }
            val s2 = if (flip) starts.map { rows - sz - it } else starts
            val g2 = if (flip) gaps.map { rows - 1 - it } else gaps
            if (s2.isNotEmpty() && enough(s2)) found += s2.sorted() to g2.sorted()
        }
        return found.minByOrNull { it.first.size }
    }

    /**
     * Where a column's belts run, upright. Narrow: A at [xa0] (4 or 5; B mirrored), B heading [b1]. Wide: belt groups
     * swapped or not between A1 (heading south) and A2 (heading [a2]); B1 heading [b1], B2 heading [b2].
     */
    private data class Variant(val xa0: Int = 4, val b1: Dir = Dir.S, val swap: Boolean = false, val a2: Dir = Dir.S, val b2: Dir = Dir.S)

    /** One upright column with the ports on [sides]: the smallest over belt orders, directions and header heights. */
    private fun buildColumn(data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>): TrialResult? {
        var best: TrialResult? = null
        val headers = (0..4).flatMap { a -> (0..4).map { b -> a to b } }.sortedBy { it.first + it.second }
        val variants = if (!s.wide) listOf(4, 5).flatMap { xa -> listOf(Dir.N, Dir.S).map { Variant(xa0 = xa, b1 = it) } }
        else listOf(false, true).flatMap { sw -> listOf(Dir.S, Dir.N).flatMap { a2 -> listOf(Dir.N, Dir.S).flatMap { b1 -> listOf(Dir.N, Dir.S).map { b2 -> Variant(swap = sw, a2 = a2, b1 = b1, b2 = b2) } } } }
            // Two junctions at one end would meet: each needs the tile beside the other's belt.
            .filter { v -> v.a2 == Dir.N || s.belts.count { s.junction(it) } < 2 }
        val jks = if (s.belts.any { s.junction(it) }) 0..1 else 0..0
        // A bypass lane along the column (one tile wider) only when the ports cannot be reached without one: it lets
        // belts reach ports at the column's other end.
        for (bypass in listOf(false, true)) {
            for ((hTop, hBot) in headers) {
                if (best != null && hTop + hBot > best.h - 3 * ((plan.machines.size + 1) / 2)) break
                for (v in variants) for (jk in jks) {
                    val r = tryColumn(data, config, plan, s, sides, hTop, hBot, v, bypass, jk) ?: continue
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
        data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>,
        hTop: Int, hBot: Int, v: Variant, bypass: Boolean = false,
        /** Rows between an input junction and its belt's head (straight belts). */
        jk: Int = 0,
    ): TrialResult? {
        val t0 = System.currentTimeMillis()
        val n = plan.machines.size
        val units = (n + 1) / 2
        // With beacons a column of them stands outside each column of machines: everything else moves right by o.
        val o = s.beacon?.size ?: 0
        // Wide: the product belt and its inserters stand between the beacons and the machines.
        val gap = if (s.wide) 2 else 0
        if (s.beacon != null && !inBeaconRange(s.beacon, 0, 0, o + gap, 0, 3, 3)) return null
        val beaconRows = if (s.beacon == null) null else beaconRows(3 * units, s.beacon, s.need, gap) ?: return null
        val coreW = (if (s.wide) 14 else 10) + 2 * o
        val W = coreW + if (bypass) 1 else 0
        val core0 = hTop
        val core1 = hTop + 3 * units   // first row below the column
        val H = core1 + hBot
        val netOf = plan.nets.associate { it.item to it.id }
        /** A belt's net: its item's, none for two items (sideloads onto it from either net are its shape). */
        fun netFor(items: List<String>) = items.singleOrNull()?.let { netOf[it] }

        /** A belt down the column, x [x], heading [dir], carrying [items] (in: ingredients to the machines; out: the product). */
        class Lane(val x: Int, val dir: Dir, val items: List<String>) {
            /** Its first belt (in its heading). */
            val head = Tile(x, if (dir == Dir.S) core0 else core1 - 1)
            /** The tile before its first belt, and the one its last belt pushes into. */
            val entrance = head + dir.opposite
            val exit = Tile(x, if (dir == Dir.S) core1 else core0 - 1)
        }
        val feeds: List<Lane>
        val drains: List<Lane>
        if (!s.wide) {
            val xa = v.xa0 + o
            feeds = listOf(Lane(xa, Dir.S, s.belts[0]))
            drains = listOf(Lane(9 - v.xa0 + o, v.b1, listOf(s.product)))
        } else {
            val (g1, g2) = if (v.swap) s.belts[1] to s.belts[0] else s.belts[0] to s.belts[1]
            feeds = listOf(Lane(o + 6, Dir.S, g1), Lane(o + 7, v.a2, g2))
            drains = listOf(Lane(o, v.b1, listOf(s.product)), Lane(o + 13, v.b2, listOf(s.product)))
        }
        val passLane = if (s.pass != null) feeds[0] else null
        val feedOf = feeds.flatMap { f -> f.items.map { it to f } }.toMap()
        /** Junction of each input belt that has one: the belt [jk] + 1 tiles before its head, sideloaded from both sides. */
        val junction = feeds.associateWith { f -> if (s.junction(f.items)) f.head.step(f.dir.opposite, 1 + jk) else null }
        for (f in feeds) if (junction[f] != null && (if (f.dir == Dir.S) hTop else hBot) < 1 + jk) return null

        val ents = ArrayList<PlacedEntity>()
        val occ = HashSet<Tile>()
        fun put(e: PlacedEntity) { ents += e; occ += e.rect.tiles() }
        val machines = ArrayList<PlacedMachine>()
        val poleSlots = ArrayList<Tile>()
        for (k in 0 until units) {
            val y0 = core0 + 3 * k
            for (side in 0..1) {
                val id = 2 * k + side
                poleSlots += if (!s.wide) listOf(Tile(o + if (side == 0) 3 else 6, y0 + 2))
                else (if (side == 0) o + 1 else o + 12).let { outer -> listOf(Tile(o + if (side == 0) 5 else 8, y0 + 2), Tile(outer, y0), Tile(outer, y0 + 2)) }
                if (id >= n) continue
                val m = plan.machines[id]
                val toward = if (side == 0) Dir.E else Dir.W   // from the machine towards the middle
                if (!s.wide) {
                    val mx = o + if (side == 0) 0 else 7
                    put(PlacedEntity(m.machine, Kind.MACHINE, mx, y0, Dir.N, m.recipe, w = 3, h = 3, modules = s.modules))
                    machines += PlacedMachine(id, mx, y0, Dir.N, false)
                    val ix = o + if (side == 0) 3 else 6
                    // The short one of the two inserters stands next to the belt it serves.
                    val inShort = (side == 0) == (v.xa0 == 4)
                    // Input: picks from A (pickup side towards the belts), drops into the machine.
                    put(PlacedEntity(if (inShort) s.shortIn else LONG, Kind.INSERTER, ix, y0, toward, net = netFor(feeds[0].items)))
                    // Output: picks from the machine (pickup side away from the belts), drops onto B.
                    put(PlacedEntity(if (inShort) LONG else s.shortOut, Kind.INSERTER, ix, y0 + 1, toward.opposite, net = netOf[s.product]))
                } else {
                    val mx = o + if (side == 0) 2 else 9
                    put(PlacedEntity(m.machine, Kind.MACHINE, mx, y0, Dir.N, m.recipe, w = 3, h = 3, modules = s.modules))
                    machines += PlacedMachine(id, mx, y0, Dir.N, false)
                    val inner = o + if (side == 0) 5 else 8
                    val outer = o + if (side == 0) 1 else 12
                    val near = feeds[side]; val far = feeds[1 - side]
                    put(PlacedEntity(s.shortIn, Kind.INSERTER, inner, y0, toward, net = netFor(near.items)))
                    put(PlacedEntity(LONG, Kind.INSERTER, inner, y0 + 1, toward, net = netFor(far.items)))
                    // Output: picks from the machine, drops on the outer belt.
                    put(PlacedEntity(s.shortOut, Kind.INSERTER, outer, y0 + 1, toward, net = netOf[s.product]))
                }
            }
        }
        for (l in feeds + drains) for (y in core0 until core1) put(PlacedEntity(s.belt, Kind.BELT, l.x, y, l.dir, net = netFor(l.items)))
        // Each junction and the straight belts from it to its belt's head.
        for (f in feeds) {
            val j = junction[f] ?: continue
            var t = j
            while (t != f.head) { put(PlacedEntity(s.belt, Kind.BELT, t.x, t.y, f.dir, net = netFor(f.items))); t += f.dir }
        }
        // Beacons beside the machines, a pole in the row between two (the middle of the beacon column).
        val beaconArea = HashSet<Tile>()
        if (s.beacon != null && beaconRows != null) {
            for (bx in intArrayOf(0, coreW - o)) {
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
        fun ringTiles(d: Dir): List<Tile> = when (d) {
            Dir.N -> (0 until W).map { Tile(it, -1) }
            Dir.S -> (0 until W).map { Tile(it, H) }
            Dir.W -> (0 until H).map { Tile(-1, it) }
            Dir.E -> (0 until H).map { Tile(W, it) }
        }
        // Tiles no belt may use: below an input belt's end when nothing passes on (leftovers would spill there), and
        // the column's pole slots and beacon gaps (the room of a missing last machine stays free for belts).
        val reserved0 = HashSet<Tile>()
        feeds.filter { it !== passLane }.forEach { reserved0 += it.exit }
        reserved0 += (poleSlots + beaconArea).filter { it !in occ }
        // A belt may run past the tile before a product belt's first one, but not point into it (it would feed it).
        val noHeading = drains.associate { it.entrance to it.dir }

        // Routing state, reset for each order of the connections.
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        val used = HashSet<Tile>()
        val reserved = HashSet<Tile>()
        /** Tiles inside some underground pair's span (ends included), per axis: no other pair on that axis may touch them. */
        val spans = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        val ugEnds = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        /**
         * Belts routed so far per item, in path order, each path with whether a straight belt feeds its first step from
         * behind (a product belt's or A's end) and whether its last step runs straight on into an input belt's head;
         * splitters go across straight belts of these (branches and merges), and a product belt may be sideloaded onto them.
         */
        class Tree(val steps: List<Step>, val fedBehind: Boolean, val goesOn: Boolean, val side: Int)
        val trees = HashMap<String, MutableList<Tree>>()
        /** Per input belt with a junction: what reaches each of its sides (all of it goes onto one lane), the item there, and the heading it comes in. */
        val sideLoad = feeds.associateWith { DoubleArray(2) }
        val sideItem = feeds.associateWith { arrayOfNulls<String>(2) }
        val sideDir = feeds.associateWith { arrayOfNulls<Dir>(2) }
        /** Product belts whose end is routed (to a port, or merged into another's path). */
        val drained = HashSet<Lane>()
        /** Belts a product belt is sideloaded onto: no splitter may replace them. */
        val sideloaded = HashSet<Tile>()
        val coreBelts = (feeds + drains).flatMap { l -> (core0 until core1).map { Tile(l.x, it) } }.toSet()
        /** Nothing belt-like stands or will stand on [t] (an inserter or machine there is fine). */
        fun noBelt(t: Tile) = t !in used && t !in coreBelts && (inBox(t) || sideOfRing(t) == null || t !in used)
        class Split(val trunk: Tile, val half: Tile, val dir: Dir, val item: String)
        val splits = ArrayList<Split>()
        fun free(t: Tile) = inBox(t) && t !in occ && t !in used && t !in reserved

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
         * reaches it, too; -1 for none), [takes] whether it is the first there; [drain] the product belt it starts at.
         */
        class Routed(val steps: List<Step>, val port: Tile?, val split: Split? = null, val feed: Lane? = null, val side: Int = -1,
                     val takes: Boolean = false, val rate: Double = 0.0, val drain: Lane? = null, val sideloads: Tile? = null)
        // An input: from the ring on its side, straight in, to a belt heading into its belt's head, onto a free side of
        // its belt's junction, or into the free half of a splitter across the belt of an input of its item already
        // routed (a merge; its other output leads nowhere).
        fun routeIn(port: Int, item: String, paired: Boolean = false): Routed? {
            val f = feedOf.getValue(item)
            val d = sides.getValue(port)
            val starts = ringTiles(d).filter { it !in used && it !in occ }.map { Step(it, d.opposite) }
            val j = junction[f]
            // A lane pair runs into its belt's head as it is (turns and undergrounds keep lanes).
            if (paired) {
                val p = path(starts) { st -> st.tile + st.dir == f.head && st.dir == f.dir } ?: return null
                return Routed(p, p.first().tile, feed = f)
            }
            val items = sideItem.getValue(f)
            if (j != null && null in items && (f.items.size == 1 || item !in items)) {
                // Onto the junction from a side no other input has taken, carrying at most a lane's worth.
                if (portRate(port) > s.laneCap * 0.98) return null
                val side = items.indexOf(null)
                val taken = sideDir.getValue(f)[1 - side]
                val p = path(starts) { st -> st.tile + st.dir == j && st.dir.axis != f.dir.axis && st.dir != taken } ?: return null
                return Routed(p, p.first().tile, feed = f, side = side, takes = true, rate = portRate(port))
            }
            if (trees[item].isNullOrEmpty()) {
                if (j != null) return null
                val p = path(starts) { st -> st.tile + st.dir == f.head && st.dir == f.dir } ?: return null
                return Routed(p, p.first().tile, feed = f)
            }
            /** The junction side a belt of the item already routed leads to (-1 without a junction). */
            fun sideOf(t: Tile) = trees[item]?.firstOrNull { tr -> tr.steps.any { it.tile == t } }?.side ?: -1
            // A merge adds to its side's lane: it stays within a lane's worth there.
            val merges = splitSpots(item, merge = true)
                .filter { (t, _, _) -> sideOf(t).let { it < 0 || sideLoad.getValue(f)[it] + portRate(port) <= s.laneCap * 0.98 } }
            val byHalf = merges.associateBy { it.second }
            val p = path(starts) { st -> byHalf[st.tile + st.dir]?.let { (_, _, h) -> st.dir == h } == true } ?: return null
            val last = p.last()
            val split = byHalf.getValue(last.tile + last.dir).let { (t, h, d2) -> Split(t, h, d2, item) }
            // The merge's other output must lead nowhere: not onto this path.
            if (p.any { it.tile == split.half + split.dir || it.ugFrom == split.half + split.dir }) return null
            return Routed(p, p.first().tile, split, feed = f, side = sideOf(split.trunk), rate = portRate(port))
        }
        /** A path's starts at the ends of [lanes]: a belt pushed into from each end, heading on or turning. */
        fun endStarts(lanes: List<Lane>): List<Step> = lanes.filter { free(it.exit) }.flatMap { l -> listOf(l.dir, l.dir.cw, l.dir.ccw).map { Step(l.exit, it) } }
        // An output: a belt pushed into from the end of a product belt not yet routed (or A's, passing on), or a branch
        // off a splitter across a straight belt of the item already routed, to a belt on the ring of its side heading out.
        fun routeOut(port: Int, item: String, pass: Boolean, merged: Boolean): Routed? {
            val d = sides.getValue(port)
            // [merged]: the product belts meet first and every port after the first branches off (ports whose rates
            // are not what each belt carries).
            val ends = if (pass) listOfNotNull(passLane) else if (merged && !trees[item].isNullOrEmpty()) emptyList() else drains.filter { it !in drained }
            // A belt end already on the ring: it is the port if it is on the right side.
            ends.firstOrNull { l -> !inBox(l.exit) && sideOfRing(l.exit) == d && l.dir == d && l.exit !in used }?.let { l ->
                return Routed(listOf(Step(l.exit, d)), l.exit, drain = l)
            }
            val from = ends.filter { inBox(it.exit) }
            if (ends.isNotEmpty() && from.isEmpty()) return null
            val branches = if (from.isEmpty()) splitSpots(item, merge = false) else emptyList()
            // A branch whose half points straight out of the box on the port's side is the port itself.
            if (from.isEmpty()) branches.firstOrNull { (_, half, h) -> val st = half + h; h == d && sideOfRing(st) == d && st !in used && st !in occ }?.let { (t, half, h) ->
                return Routed(listOf(Step(half + h, d)), half + h, Split(t, half, h, item))
            }
            val starts = if (from.isNotEmpty()) endStarts(from)
            else branches.flatMap { (_, half, h) -> val st = half + h; if (free(st)) listOf(h, h.cw, h.ccw).map { Step(st, it) } else emptyList() }
            val p = path(starts) { st -> val nt = st.tile + st.dir; sideOfRing(nt) == d && st.dir == d && nt !in used && nt !in occ } ?: return null
            val portTile = p.last().tile + d
            val drain = from.firstOrNull { it.exit == p.first().tile }
            val split = if (from.isNotEmpty()) null else branches.first { (_, half, h) -> half + h == p.first().tile }.let { (t, h, d2) -> Split(t, h, d2, item) }
            // Nothing of this path may feed the branch's half from behind.
            if (split != null && p.any { it.tile == split.half + split.dir.opposite || it.ugFrom == split.half + split.dir.opposite }) return null
            return Routed(p + Step(portTile, d), portTile, split, drain = drain)
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
            return Routed(p, null, split, drain = l, sideloads = if (split == null) nt else null)
        }
        fun book(r: Routed, item: String) {
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
            val first = r.steps.first()
            val end = (drains + listOfNotNull(passLane)).firstOrNull { r.split == null && first.tile == it.exit && (r.drain == null || r.drain === it) }
            val f = r.feed
            trees.getOrPut(item) { ArrayList() } += Tree(r.steps,
                fedBehind = end != null && first.dir == end.dir,
                goesOn = r.split == null && f != null && junction[f] == null && r.steps.last().let { it.tile + it.dir == f.head }, side = r.side)
            if (f != null && r.side >= 0) {
                sideLoad.getValue(f)[r.side] += r.rate
                if (r.takes) { sideItem.getValue(f)[r.side] = item; sideDir.getValue(f)[r.side] = r.steps.last().dir }
            }
            r.drain?.let { drained += it }
            r.sideloads?.let { sideloaded += it }
            r.split?.let { sp ->
                splits += sp
                used += sp.half
                // A merge's free half leads nowhere: nothing may stand in front of it; nothing may feed a branch's half.
                val keep = if (r.steps.last().tile + r.steps.last().dir == sp.half) sp.half + sp.dir else sp.half + sp.dir.opposite
                if (inBox(keep) && keep !in occ) reserved += keep
            }
        }

        // Connections: inputs first (each item's first onto its belt's head or junction side, the rest merge into it),
        // then outputs (the first from a product belt's end, the rest branch off, product belts left over merge in),
        // the pass-through last from A's end; a few orders of each group.
        /** A connection: an input or output port, a product belt to merge in, or a lane pair (input [port] and [with]). */
        data class Conn(val input: Boolean, val port: Int, val item: String, val merge: Lane? = null, val with: Int = -1)
        val pairedPorts = s.pairs.flatMap { listOf(it.first, it.second) }.toSet()
        val ins = (s.inputs.flatMap { (i, ps) -> ps.filter { it !in pairedPorts }.map { Conn(true, it, i) } } +
            s.pairs.map { (a, b) -> Conn(true, a, s.itemOf.getValue(a), with = b) }).sortedByDescending { portRate(it.port) + if (it.with >= 0) portRate(it.with) else 0.0 }
        val outs = s.outputs.sortedByDescending { portRate(it) }.map { Conn(false, it, s.product) }
        val pass = listOfNotNull(s.pass?.let { Conn(false, it, feeds[0].items[0]) })
        fun rotations(xs: List<Conn>) = if (xs.size <= 1) listOf(xs) else xs.indices.map { i -> xs.drop(i) + xs.take(i) }
        // Product belts without a port of their own merge in once the ports are routed, or (merged) right after the
        // first port, the other ports branching off after them.
        val merges = if (drains.size > 1) drains.map { Conn(false, -1, s.product, it) } else emptyList()
        val modes = if (drains.size > 1 && outs.size > 1) listOf(false, true) else listOf(false)
        val orders = rotations(ins).flatMap { a -> rotations(outs).flatMap { b -> modes.flatMap { m ->
            val bm = if (m) b.take(1) + merges + b.drop(1) else b + merges
            listOf(a + bm + pass, bm + a + pass, pass + a + bm).map { it to m }
        } } }.distinct().take(32)
        // Routing is the same for the same first connections: an order starting with ones that failed fails too.
        val failed = HashSet<Pair<List<Conn>, Boolean>>()
        for ((order, merged) in orders) {
            if ((1..order.size).any { k -> order.take(k) to merged in failed }) continue
            used.clear(); reserved.clear(); reserved += reserved0; spans.forEach { it.clear() }; ugEnds.forEach { it.clear() }
            trees.clear(); splits.clear(); drained.clear(); sideloaded.clear()
            sideLoad.values.forEach { it.fill(0.0) }; sideItem.values.forEach { it.fill(null) }; sideDir.values.forEach { it.fill(null) }
            val got = ArrayList<Pair<Conn, Routed>>()
            var ok = true
            for (c in order) {
                if (c.merge != null && c.merge in drained) continue
                val r = when {
                    c.input -> routeIn(c.port, c.item, paired = c.with >= 0)
                    c.merge != null -> routeMerge(c.merge)
                    else -> routeOut(c.port, c.item, pass = c.port == s.pass, merged = merged)
                }
                if (r == null) { ok = false; failed += order.take(order.indexOf(c) + 1) to merged; if (DEBUG) println("  ${W}x$H top $hTop bot $hBot $v: ${if (c.input) "in" else "out"} ${c.item} port ${c.port} (${sides[c.port]}) fails after ${got.size}"); break }
                book(r, c.item)
                got += c to r
            }
            if (!ok || drains.any { it !in drained }) continue
            val all = ArrayList(ents)
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
                all += PlacedEntity(s.splitter, Kind.SPLITTER, minOf(sp.trunk.x, sp.half.x), minOf(sp.trunk.y, sp.half.y), sp.dir, net = netOf[sp.item])
            }
            // A lane pair: its first item on the left lane, the other on the right (looking along the belt into the block).
            val sources = got.filter { it.first.input }.flatMap { (c, r) ->
                if (c.with < 0) listOf(SourceSpec(r.port!!, c.item, portRate(c.port), port = c.port))
                else listOf(SourceSpec(r.port!!, c.item, portRate(c.port), lane = 0, port = c.port), SourceSpec(r.port, s.itemOf.getValue(c.with), portRate(c.with), lane = 1, port = c.with))
            }
            val outputs = got.filter { !it.first.input && it.first.merge == null }.map { (c, r) -> OutputSpec(r.port!!, c.item, portRate(c.port), port = c.port) }
            // Poles on the free slots (and free header tiles if those are not enough).
            val taken = all.flatMap { it.rect.tiles() }.toSet()
            // (With the room of a missing last machine.)
            val headerFree = (0 until H).flatMap { y -> (0 until W).map { x -> Tile(x, y) } }.filter { it !in taken && (it.y < core0 || it.y >= core1 || it !in reserved0) }
            val placement = Placement(W, H, machines.sortedBy { it.id })
            val layout: Layout
            val label = "template ${if (s.wide) "wide " else ""}column ${W}x$H"
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
            val errs = validate(layout, vspec)
            if (DEBUG && errs.isNotEmpty()) println("  ${W}x$H top $hTop bot $hBot $v: invalid ${errs.take(3)}")
            if (errs.isNotEmpty()) continue
            val what = if (s.wide) "wide machine column" else "machine column"
            return TrialResult(true, W, H, 0L, layout, placement, emptyList(), null, score(layout, config), "ok", 0.0,
                System.currentTimeMillis() - t0, listOf("template: $what of $n ${s.machine} (${s.recipe}), headers $hTop/$hBot" + if (bypass) ", bypass lane" else ""),
                sources, outputs)
        }
        return null
    }
}
