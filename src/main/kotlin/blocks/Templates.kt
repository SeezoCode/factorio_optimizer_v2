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
import plan.ProductionPlan
import plan.Role
import plan.inserterRate
import power.placePoles
import search.TrialResult
import search.score
import search.validationSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.validate

/**
 * Ready-made layouts ("templates") for simple blocks, offered next to the searched ones: the floorplan uses one
 * only where it packs better, so nothing is forced. Each is validated like any searched layout before it is offered.
 *
 * The machine column (single-recipe blocks of 3x3 machines with one solid ingredient and one product, such as
 * smelting or gears), two machines per three rows:
 *
 * ```
 * |machine|in, out, pole|A|B|in, out, pole|machine|
 * ```
 *
 * Belt A carries the ingredient down the middle, belt B the product. On each side one inserter stands next to A or B
 * and a long-handed one reaches the other belt across it: on the left a short inserter takes from A and a long one
 * puts on B, on the right a long one takes from A and a short one puts on B (or mirrored, B left of A). The third
 * tile of each three is free for a pole. Short headers above and below the column run the port belts to whichever
 * sides of the block the ports are on (a small belt search, undergrounds included); two input ports join A through
 * a splitter, two product ports leave B through one, and what is left of A can leave as a pass-through output.
 */
object Templates {
    private const val LONG = "long-handed-inserter"

    /** A machine a machine column can hold: 3 x 3, one solid ingredient, one item product, no fluids or beacons. */
    fun columnMachine(m: plan.MachineInstance): Boolean =
        m.w == 3 && m.h == 3 && m.needsFluid == null && m.fluidDemand.isEmpty() && m.fluidIn.isEmpty() && m.fluidOut.isEmpty() &&
            m.demand.size == 1 && m.itemProducts.size == 1 && m.demand.keys.single() != m.itemProducts.keys.single() && m.beacons == 0

    /**
     * Recipes of [plan] whose machines a machine column can hold with the inserters [config] allows (furnaces, mostly;
     * fast assemblers outrun long-handed inserters): blocks of only these get a column, so the split keeps them apart.
     */
    fun columnRecipes(plan: ProductionPlan, config: RunConfiguration): Set<String> {
        if (LONG !in config.inserters.allowed || !config.portsOnRing) return emptySet()
        val shortOnes = config.inserters.allowed.filter { it != LONG }
        return plan.machines.groupBy { it.recipe }.filter { (_, ms) ->
            ms.all(::columnMachine) && ms.map { it.machine }.distinct().size == 1 && run {
                val maxIn = ms.maxOf { it.demand.values.single() }
                val maxOut = ms.maxOf { it.itemProducts.values.single() }
                inserterRate(LONG, config, true) >= maxIn - 1e-9 && inserterRate(LONG, config, false) >= maxOut - 1e-9 &&
                    shortOnes.any { inserterRate(it, config, true) >= maxIn - 1e-9 } && shortOnes.any { inserterRate(it, config, false) >= maxOut - 1e-9 }
            }
        }.keys
    }

    /** Every template layout for [plan] (a block's plan), each validated; [note] explains why there is none. */
    fun layouts(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit = {}): List<TrialResult> =
        column(data, config, plan, note) ?: emptyList()

    private class ColumnSpec(
        val machine: String, val recipe: String, val ingredient: String, val product: String,
        /** Input ports of the ingredient (one or two), product output ports (one or two), a pass-through port. */
        val inputs: List<Int>, val outputs: List<Int>, val pass: Int?,
        val shortIn: String, val shortOut: String,
        val belt: String, val underground: String, val splitter: String, val maxGap: Int,
    )

    private val SIDES = setOf("north", "east", "south", "west")

    /** The machine column in each of its four turns that fits the plan's port sides, smallest first. */
    private fun column(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit): List<TrialResult>? {
        val ms = plan.machines
        if (ms.isEmpty()) return null
        val m0 = ms[0]
        fun no(why: String): List<TrialResult>? { note("no machine column: $why"); return null }
        if (ms.any { it.recipe != m0.recipe || it.machine != m0.machine }) return no("more than one recipe")
        if (ms.any { !columnMachine(it) }) return no("${m0.machine} (${m0.recipe}) is not a 3 x 3 machine with one ingredient and one product")
        if (!config.portsOnRing) return no("ports_on_ring is off")
        if (plan.terminals.any { it.role == Role.SINK }) return no("sink chests")
        val ingredient = m0.demand.keys.single()
        val product = m0.itemProducts.keys.single()
        if (plan.ports.any { it.fluid || it.pairWith >= 0 || it.specs.size != 1 || it.specs[0].edge !in SIDES }) return no("ports other than one item per edge")
        val ins = plan.ports.filter { !it.output }
        val outs = plan.ports.filter { it.output }
        if (ins.size !in 1..2 || ins.any { it.item != ingredient }) return no("not one or two input ports of $ingredient")
        val prodOut = outs.filter { it.item == product }
        val passOut = outs.filter { it.item == ingredient }
        if (prodOut.size !in 1..2 || passOut.size > 1 || outs.size != prodOut.size + passOut.size) return no("output ports other than one or two of $product (and one of $ingredient passing on)")
        val tier = GameConstants.beltTier(config.beltTier)
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        if (ins.sumOf { portRate(it.id) } > tier.throughput + 1e-9) return no("input over one belt")

        // Inserters: the cheapest that keeps up for the short ones; long-handed ones must keep up as they are.
        val maxIn = ms.maxOf { it.demand.getValue(ingredient) }
        val maxOut = ms.maxOf { it.itemProducts.getValue(product) }
        val shortOnes = config.inserters.allowed.filter { it != LONG }.sortedBy { GameConstants.inserter(it).tierCost }
        val shortIn = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = true) >= maxIn - 1e-9 } ?: return no("no inserter takes ${"%.2f".format(maxIn)}/s from a belt")
        val shortOut = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = false) >= maxOut - 1e-9 } ?: return no("no inserter moves ${"%.2f".format(maxOut)}/s")
        if (LONG !in config.inserters.allowed) return no("long-handed inserters not allowed")
        if (inserterRate(LONG, config, true) < maxIn - 1e-9 || inserterRate(LONG, config, false) < maxOut - 1e-9) return no("long-handed inserters too slow (${"%.2f".format(maxIn)}/s in, ${"%.2f".format(maxOut)}/s out)")

        val spec = ColumnSpec(m0.machine, m0.recipe, ingredient, product, ins.map { it.id }, prodOut.map { it.id }, passOut.firstOrNull()?.id,
            shortIn, shortOut, tier.belt, tier.underground, tier.splitter, tier.maxUndergroundGap)
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

    /** One upright column with the ports on [sides]: the smallest over belt orders, directions of B and header heights. */
    private fun buildColumn(data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>): TrialResult? {
        var best: TrialResult? = null
        val headers = (0..3).flatMap { a -> (0..3).map { b -> a to b } }.sortedBy { it.first + it.second }
        for ((hTop, hBot) in headers) {
            if (best != null && hTop + hBot > best.h - 3 * ((plan.machines.size + 1) / 2)) break
            for (xa in intArrayOf(4, 5)) for (bDir in arrayOf(Dir.N, Dir.S)) {
                val r = tryColumn(data, config, plan, s, sides, hTop, hBot, xa, bDir) ?: continue
                if (best == null || r.score < best.score) best = r
            }
        }
        return best
    }

    /** A belt-family entity of a header path: a belt, or an underground pair from [ugFrom] to [tile]. */
    private data class Step(val tile: Tile, val dir: Dir, val ugFrom: Tile? = null)

    private fun tryColumn(
        data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>,
        hTop: Int, hBot: Int, xa: Int, bDir: Dir,
    ): TrialResult? {
        val t0 = System.currentTimeMillis()
        val n = plan.machines.size
        val units = (n + 1) / 2
        val W = 10
        val core0 = hTop
        val core1 = hTop + 3 * units   // first row below the column
        val H = core1 + hBot
        val xb = 9 - xa
        val away = xb - xa             // from A towards B: +1 or -1
        val twoIn = s.inputs.size == 2
        val twoOut = s.outputs.size == 2
        // Splitters sit in the header row next to the column: they need that row.
        if (twoIn && hTop < 1) return null
        if (twoOut && (if (bDir == Dir.N) hTop < 1 else hBot < 1)) return null
        val netOf = plan.nets.associate { it.item to it.id }
        val ents = ArrayList<PlacedEntity>()
        val occ = HashSet<Tile>()
        fun put(e: PlacedEntity) { ents += e; occ += e.rect.tiles() }
        val machines = ArrayList<PlacedMachine>()
        val left = xa == 4   // A left of B: the left side takes from A with a short inserter
        for (k in 0 until units) {
            val y0 = core0 + 3 * k
            for (side in 0..1) {
                val id = 2 * k + side
                if (id >= n) continue
                val m = plan.machines[id]
                val mx = if (side == 0) 0 else 7
                put(PlacedEntity(m.machine, Kind.MACHINE, mx, y0, Dir.N, m.recipe, w = 3, h = 3))
                machines += PlacedMachine(id, mx, y0, Dir.N, false)
                val ix = if (side == 0) 3 else 6
                // The short one of the two inserters stands next to the belt it serves.
                val inShort = (side == 0) == left
                val toward = if (side == 0) Dir.E else Dir.W   // from the machine towards the belts
                // Input: picks from A (pickup side towards the belts), drops into the machine.
                put(PlacedEntity(if (inShort) s.shortIn else LONG, Kind.INSERTER, ix, y0, toward, net = netOf[s.ingredient]))
                // Output: picks from the machine (pickup side away from the belts), drops onto B.
                put(PlacedEntity(if (inShort) LONG else s.shortOut, Kind.INSERTER, ix, y0 + 1, toward.opposite, net = netOf[s.product]))
            }
        }
        for (y in core0 until core1) {
            put(PlacedEntity(s.belt, Kind.BELT, xa, y, Dir.S, net = netOf[s.ingredient]))
            put(PlacedEntity(s.belt, Kind.BELT, xb, y, bDir, net = netOf[s.product]))
        }
        // Splitters: two inputs join A at its head; two product outputs leave B's end.
        val aEntries: List<Tile>     // tiles a south-heading input belt feeds (one per input port)
        if (twoIn) {
            val xa2 = xa - away
            put(PlacedEntity(s.splitter, Kind.SPLITTER, minOf(xa, xa2), core0 - 1, Dir.S, net = netOf[s.ingredient]))
            aEntries = listOf(Tile(xa, core0 - 1), Tile(xa2, core0 - 1))
        } else aEntries = listOf(Tile(xa, core0))
        val bStarts: List<Tile>      // where product belts start (pushed into from behind, heading bDir)
        if (twoOut) {
            val xb2 = xb + away
            val y = if (bDir == Dir.N) core0 - 1 else core1
            put(PlacedEntity(s.splitter, Kind.SPLITTER, minOf(xb, xb2), y, bDir, net = netOf[s.product]))
            bStarts = listOf(Tile(xb, y) + bDir, Tile(xb2, y) + bDir)
        } else bStarts = listOf(if (bDir == Dir.N) Tile(xb, core0 - 1) else Tile(xb, core1))
        val aTail = Tile(xa, core1)
        // Pole slots: the third tile of each three in the inserter columns.
        val poleSlots = (0 until units).flatMap { k -> listOf(Tile(3, core0 + 3 * k + 2), Tile(6, core0 + 3 * k + 2)) }

        fun inBox(t: Tile) = t.x in 0 until W && t.y in 0 until H
        fun sideOfRing(t: Tile): Dir? = when {
            t.x in 0 until W && t.y == -1 -> Dir.N; t.x in 0 until W && t.y == H -> Dir.S
            t.y in 0 until H && t.x == -1 -> Dir.W; t.y in 0 until H && t.x == W -> Dir.E; else -> null
        }
        // Tiles nothing may use: below A's end when nothing passes on (leftovers would spill there), and the tile
        // before B's first belt (a belt there would feed B).
        val reserved = HashSet<Tile>()
        if (s.pass == null) reserved += aTail
        reserved += if (bDir == Dir.N) Tile(xb, core1) else Tile(xb, core0 - 1)

        val used = HashSet<Tile>()
        /** Tiles inside some underground pair's span (ends included), per axis: no other pair on that axis may touch them. */
        val spans = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        val ugEnds = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        fun free(t: Tile) = inBox(t) && t !in occ && t !in used && t !in reserved

        /**
         * A belt path from one of [starts] (each a belt that will stand there, heading that way) until [done] says
         * the last step finishes it; belts turn left or right, undergrounds jump up to the tier's gap.
         */
        fun path(starts: List<Step>, done: (Step) -> Boolean): List<Step>? {
            val prev = HashMap<Step, Step?>()
            val queue = ArrayDeque<Step>()
            starts.forEach { st -> if (st !in prev) { prev[st] = null; queue += st } }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (done(cur)) {
                    val out = ArrayList<Step>()
                    var c: Step? = cur
                    while (c != null) { out += c; c = prev[c] }
                    return out.reversed()
                }
                val nt = cur.tile + cur.dir
                if (!free(nt)) continue
                val nexts = ArrayList<Step>()
                // After an underground exit the next belt keeps going straight (a turn right after one is unverified).
                val turns = if (cur.ugFrom != null) listOf(cur.dir) else listOf(cur.dir, cur.dir.cw, cur.dir.ccw)
                turns.forEach { nexts += Step(nt, it) }
                val axis = cur.dir.axis
                if (nt !in spans[axis]) for (j in 2..s.maxGap + 1) {
                    val e = nt.step(cur.dir, j)
                    if (!inBox(e)) break
                    val between = (1 until j).map { nt.step(cur.dir, it) }
                    if (between.any { it in ugEnds[axis] }) break
                    if (free(e) && e !in spans[axis]) nexts += Step(e, cur.dir, nt)
                }
                nexts.forEach { st -> if (st !in prev) { prev[st] = cur; queue += st } }
            }
            return null
        }
        fun ringTiles(d: Dir): List<Tile> = when (d) {
            Dir.N -> (0 until W).map { Tile(it, -1) }
            Dir.S -> (0 until W).map { Tile(it, H) }
            Dir.W -> (0 until H).map { Tile(-1, it) }
            Dir.E -> (0 until H).map { Tile(W, it) }
        }
        class Routed(val steps: List<Step>, val port: Tile)
        // An input: from the ring on its side, straight in, to a belt heading south into [entry].
        fun routeIn(port: Int, entry: Tile): Routed? {
            val d = sides.getValue(port)
            val starts = ringTiles(d).filter { it !in used && it !in occ }.map { Step(it, d.opposite) }
            // The ring tile is the port: its belt points straight in; the first box tile must be free.
            val p = path(starts) { st -> st.tile + st.dir == entry && st.dir == Dir.S } ?: return null
            return Routed(p, p.first().tile)
        }
        // An output: a belt on [from] (pushed into heading [heading]), to a belt on the ring of its side heading out.
        fun routeOut(port: Int, from: Tile, heading: Dir): Routed? {
            val d = sides.getValue(port)
            if (!inBox(from)) {
                // The belt end already reaches the ring: it is the port if it is on the right side.
                if (sideOfRing(from) != d || heading != d || from in used) return null
                return Routed(listOf(Step(from, d)), from)
            }
            if (!free(from)) return null
            val starts = listOf(heading, heading.cw, heading.ccw).map { Step(from, it) }
            val p = path(starts) { st -> val nt = st.tile + st.dir; sideOfRing(nt) == d && st.dir == d && nt !in used && nt !in occ } ?: return null
            val portTile = p.last().tile + d
            return Routed(p + Step(portTile, d), portTile)
        }
        fun book(r: Routed) = r.steps.forEach { st ->
            used += st.tile
            st.ugFrom?.let { a ->
                used += a
                val axis = st.dir.axis
                var c = a
                while (true) { spans[axis] += c; if (c == st.tile) break; c += st.dir }
                ugEnds[axis] += a; ugEnds[axis] += st.tile
            }
        }

        // Connections: (kind, port, from or entry tile).
        data class Conn(val input: Boolean, val port: Int, val tile: Tile, val heading: Dir)
        val conns = s.inputs.mapIndexed { i, p -> Conn(true, p, aEntries[i], Dir.S) } +
            s.outputs.mapIndexed { i, p -> Conn(false, p, bStarts[i], bDir) } +
            listOfNotNull(s.pass?.let { Conn(false, it, aTail, Dir.S) })
        val orders = permutations(conns.indices.toList()).take(24)
        for (order in orders) {
            used.clear(); spans.forEach { it.clear() }; ugEnds.forEach { it.clear() }
            val got = arrayOfNulls<Routed>(conns.size)
            var ok = true
            for (i in order) {
                val c = conns[i]
                val r = (if (c.input) routeIn(c.port, c.tile) else routeOut(c.port, c.tile, c.heading)) ?: run { ok = false; null } ?: break
                book(r)
                got[i] = r
            }
            if (!ok) continue
            val all = ArrayList(ents)
            conns.forEachIndexed { i, c ->
                val item = if (c.input || c.port == s.pass) s.ingredient else s.product
                got[i]!!.steps.forEach { st ->
                    if (st.ugFrom != null) {
                        all += PlacedEntity(s.underground, Kind.UG_IN, st.ugFrom.x, st.ugFrom.y, st.dir, net = netOf[item])
                        all += PlacedEntity(s.underground, Kind.UG_OUT, st.tile.x, st.tile.y, st.dir, net = netOf[item])
                    } else all += PlacedEntity(s.belt, Kind.BELT, st.tile.x, st.tile.y, st.dir, net = netOf[item])
                }
            }
            fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
            val sources = conns.indices.filter { conns[it].input }.map { i -> SourceSpec(got[i]!!.port, s.ingredient, portRate(conns[i].port), port = conns[i].port) }
            val outputs = conns.indices.filter { !conns[it].input }.map { i ->
                OutputSpec(got[i]!!.port, if (conns[i].port == s.pass) s.ingredient else s.product, portRate(conns[i].port), port = conns[i].port)
            }
            // Poles on the free slots (and free header tiles if those are not enough).
            val taken = all.flatMap { it.rect.tiles() }.toSet()
            val headerFree = (0 until H).flatMap { y -> (0 until W).map { x -> Tile(x, y) } }.filter { (it.y < core0 || it.y >= core1) && it !in taken }
            val placement = Placement(W, H, machines.sortedBy { it.id })
            val layout: Layout
            if (config.power.mode == "none") layout = Layout(W, H, all, label = "template column ${W}x$H")
            else {
                val ps = placePoles(all, poleSlots.filter { it !in taken } + headerFree, config.power.pole)
                if (!ps.ok) continue
                val off = all.size
                layout = Layout(W, H, all + ps.poles, ps.wires.map { (a, b) -> a + off to b + off }, "template column ${W}x$H")
            }
            val outside = (sources.map { it.tile } + outputs.map { it.tile }).toSet()
            val vspec = validationSpec(data, plan, config, placement, sources, requirePower = config.power.mode != "none",
                outsideAllowed = outside, outputs = outputs)
            if (validate(layout, vspec).isNotEmpty()) continue
            return TrialResult(true, W, H, 0L, layout, placement, emptyList(), null, score(layout, config), "ok", 0.0,
                System.currentTimeMillis() - t0, listOf("template: machine column of $n ${s.machine} (${s.recipe}), headers $hTop/$hBot"),
                sources, outputs)
        }
        return null
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }
}
