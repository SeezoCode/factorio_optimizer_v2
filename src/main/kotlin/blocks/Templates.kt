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
 * sides of the block the ports are on; what is left of A can leave as a pass-through output.
 */
object Templates {
    private const val LONG = "long-handed-inserter"

    /** Every template layout for [plan] (a block's plan), each validated; [note] explains why there is none. */
    fun layouts(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit = {}): List<TrialResult> {
        val col = column(data, config, plan, note) ?: return emptyList()
        return col
    }

    private class ColumnSpec(
        val machine: String, val recipe: String, val ingredient: String, val product: String,
        val input: Int, val output: Int, val pass: Int?,
        val shortIn: String, val shortOut: String,
    )

    /** The machine column in each of its four turns that fits the plan's port sides, smallest first. */
    private fun column(data: RootData, config: RunConfiguration, plan: ProductionPlan, note: (String) -> Unit): List<TrialResult>? {
        val ms = plan.machines
        if (ms.isEmpty()) return null
        val m0 = ms[0]
        fun no(why: String): List<TrialResult>? { note("no machine column: $why"); return null }
        if (ms.any { it.recipe != m0.recipe || it.machine != m0.machine }) return no("more than one recipe")
        if (m0.w != 3 || m0.h != 3) return no("${m0.machine} is not 3 x 3")
        if (ms.any { it.needsFluid != null || it.fluidDemand.isNotEmpty() || it.fluidOut.isNotEmpty() || it.fluidIn.isNotEmpty() }) return no("fluids")
        if (ms.any { it.demand.size != 1 || it.itemProducts.size != 1 }) return no("not one ingredient and one product")
        if (ms.any { it.beacons > 0 }) return no("beacons")
        if (!config.portsOnRing) return no("ports_on_ring is off")
        if (plan.terminals.any { it.role == Role.SINK }) return no("sink chests")
        val ingredient = m0.demand.keys.single()
        val product = m0.itemProducts.keys.single()
        if (ingredient == product) return no("ingredient and product are the same item")
        if (plan.ports.any { it.fluid || it.pairWith >= 0 || it.specs.size != 1 || it.specs[0].edge !in SIDES }) return no("ports other than one item per edge")
        val ins = plan.ports.filter { !it.output }
        val outs = plan.ports.filter { it.output }
        if (ins.size != 1 || ins[0].item != ingredient) return no("not one input port of $ingredient")
        val prodOut = outs.filter { it.item == product }
        val passOut = outs.filter { it.item == ingredient }
        if (prodOut.size != 1 || passOut.size > 1 || outs.size != prodOut.size + passOut.size) return no("output ports other than one of $product (and one of $ingredient passing on)")
        val tier = GameConstants.beltTier(config.beltTier)
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        if (portRate(ins[0].id) > tier.throughput + 1e-9) return no("input over one belt")

        // Inserters: the cheapest that keeps up for the short ones; long-handed ones must keep up as they are.
        val maxIn = ms.maxOf { it.demand.getValue(ingredient) }
        val maxOut = ms.maxOf { it.itemProducts.getValue(product) }
        val shortOnes = config.inserters.allowed.filter { it != LONG }.sortedBy { GameConstants.inserter(it).tierCost }
        val shortIn = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = true) >= maxIn - 1e-9 } ?: return no("no inserter takes ${"%.2f".format(maxIn)}/s from a belt")
        val shortOut = shortOnes.firstOrNull { inserterRate(it, config, fromBelt = false) >= maxOut - 1e-9 } ?: return no("no inserter moves ${"%.2f".format(maxOut)}/s")
        if (LONG !in config.inserters.allowed) return no("long-handed inserters not allowed")
        if (inserterRate(LONG, config, true) < maxIn - 1e-9 || inserterRate(LONG, config, false) < maxOut - 1e-9) return no("long-handed inserters too slow (${"%.2f".format(maxIn)}/s in, ${"%.2f".format(maxOut)}/s out)")

        val spec = ColumnSpec(m0.machine, m0.recipe, ingredient, product, ins[0].id, prodOut[0].id, passOut.firstOrNull()?.id, shortIn, shortOut)
        val sides = plan.ports.associate { it.id to Dir.fromName(it.specs[0].edge!!) }
        val found = ArrayList<TrialResult>()
        for (q in 0..3) {
            // Built upright (A heading south), then turned q quarter turns: the ports' sides turned back first.
            val built = sides.mapValues { (_, d) -> d.turned(-q) }
            val r = buildColumn(data, config, plan, spec, built, tier.belt) ?: continue
            found += turnLayout(r, q)
        }
        if (found.isEmpty()) return no("no header fits the port sides")
        // Keep the smallest of each shape (upright or lying).
        return found.groupBy { it.w to it.h }.map { (_, rs) -> rs.minBy { it.score } }.sortedBy { it.area }
    }

    private val SIDES = setOf("north", "east", "south", "west")

    /** One upright column with the ports on [sides]; tries both belt orders, both directions of B and header heights. */
    private fun buildColumn(
        data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>, belt: String,
    ): TrialResult? {
        var best: TrialResult? = null
        for (hTop in 0..2) for (hBot in 0..2) {
            if (best != null && best.h <= hTop + hBot + 3 * ((plan.machines.size + 1) / 2)) break
            for (xa in intArrayOf(4, 5)) for (bDir in arrayOf(Dir.N, Dir.S)) {
                val r = tryColumn(data, config, plan, s, sides, belt, hTop, hBot, xa, bDir) ?: continue
                if (best == null || r.score < best.score) best = r
            }
        }
        return best
    }

    private fun tryColumn(
        data: RootData, config: RunConfiguration, plan: ProductionPlan, s: ColumnSpec, sides: Map<Int, Dir>, belt: String,
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
        val netOf = plan.nets.associate { it.item to it.id }
        val ents = ArrayList<PlacedEntity>()
        val occ = HashSet<Tile>()
        fun put(e: PlacedEntity) { ents += e; occ += e.rect.tiles() }
        val machines = ArrayList<PlacedMachine>()
        val left = xa == 4   // A left of B: left side takes from A with a short inserter
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
                // Which of the two inserters is short: the one next to the belt it serves.
                val inShort = (side == 0) == left
                val toward = if (side == 0) Dir.E else Dir.W   // from the machine towards the belts
                // Input: picks from A (pickup side towards the belts), drops into the machine.
                val inName = if (inShort) s.shortIn else LONG
                put(PlacedEntity(inName, Kind.INSERTER, ix, y0, toward, net = netOf[s.ingredient]))
                // Output: picks from the machine (pickup side away from the belts), drops onto B.
                val outName = if (inShort) LONG else s.shortOut
                put(PlacedEntity(outName, Kind.INSERTER, ix, y0 + 1, toward.opposite, item = null, net = netOf[s.product]))
            }
        }
        for (y in core0 until core1) {
            put(PlacedEntity(belt, Kind.BELT, xa, y, Dir.S, net = netOf[s.ingredient]))
            put(PlacedEntity(belt, Kind.BELT, xb, y, bDir, net = netOf[s.product]))
        }
        // Pole slots: the third tile of each three in the inserter columns.
        val poleSlots = (0 until units).flatMap { k -> listOf(Tile(3, core0 + 3 * k + 2), Tile(6, core0 + 3 * k + 2)) }

        fun ring(t: Tile) = (t.x == -1 || t.x == W || t.y == -1 || t.y == H) && !(t.x !in 0 until W && t.y !in 0 until H)
        fun inBox(t: Tile) = t.x in 0 until W && t.y in 0 until H
        fun sideOfRing(t: Tile): Dir? = when { t.y == -1 -> Dir.N; t.y == H -> Dir.S; t.x == -1 -> Dir.W; t.x == W -> Dir.E; else -> null }
        // Tiles nothing may use: below A's end when nothing passes on (A's leftovers would spill there), and the tile
        // before B's first belt (a belt there would feed B).
        val reserved = HashSet<Tile>()
        if (s.pass == null) reserved += Tile(xa, core1)
        reserved += if (bDir == Dir.N) Tile(xb, core1) else Tile(xb, core0 - 1)

        /**
         * A belt path from one of [starts] (tile, heading) to [done] (true when a belt on that tile with that heading
         * finishes it), over free box tiles; returns the belts.
         */
        fun path(starts: List<Pair<Tile, Dir>>, done: (Tile, Dir) -> Boolean, free: (Tile) -> Boolean): List<Pair<Tile, Dir>>? {
            val prev = HashMap<Pair<Tile, Dir>, Pair<Tile, Dir>?>()
            val queue = ArrayDeque<Pair<Tile, Dir>>()
            starts.forEach { st -> if (st !in prev) { prev[st] = null; queue += st } }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (done(cur.first, cur.second)) {
                    val out = ArrayList<Pair<Tile, Dir>>()
                    var c: Pair<Tile, Dir>? = cur
                    while (c != null) { out += c; c = prev[c] }
                    return out.reversed()
                }
                val nt = cur.first + cur.second
                if (!inBox(nt) || !free(nt)) continue
                for (nd in listOf(cur.second, cur.second.cw, cur.second.ccw)) {
                    val st = nt to nd
                    if (st !in prev) { prev[st] = cur; queue += st }
                }
            }
            return null
        }
        val used = HashSet<Tile>()
        fun free(t: Tile) = t !in occ && t !in used && t !in reserved
        fun ringTiles(d: Dir): List<Tile> = when (d) {
            Dir.N -> (0 until W).map { Tile(it, -1) }
            Dir.S -> (0 until W).map { Tile(it, H) }
            Dir.W -> (0 until H).map { Tile(-1, it) }
            Dir.E -> (0 until H).map { Tile(W, it) }
        }
        val aHead = Tile(xa, core0)
        val bExit = if (bDir == Dir.N) Tile(xb, core0 - 1) else Tile(xb, core1)
        val aTail = Tile(xa, core1)
        class Routed(val belts: List<Pair<Tile, Dir>>, val port: Tile)
        // Input: from the ring on its side, straight in, to a belt heading south into A's first tile.
        fun routeIn(): Routed? {
            val d = sides.getValue(s.input)
            val starts = ringTiles(d).filter { it !in used && it !in occ }.map { it to d.opposite }
            val p = path(starts, { t, h -> t + h == aHead && h == Dir.S }, ::free) ?: return null
            return Routed(p, p.first().first)
        }
        // An output: from [from] (where a belt end pushes items), to a belt on the ring of its side heading out.
        fun routeOut(port: Int, from: Tile, heading: Dir): Routed? {
            val d = sides.getValue(port)
            if (!inBox(from)) {
                // The belt end already reaches the ring: it is the port if it is on the right side.
                if (sideOfRing(from) != d || heading != d || from in used) return null
                return Routed(listOf(from to d), from)
            }
            if (!free(from)) return null
            val starts = listOf(heading, heading.cw, heading.ccw).map { from to it }
            val p = path(starts, { t, h -> val nt = t + h; ring(nt) && sideOfRing(nt) == d && h == d && nt !in used && nt !in occ }, ::free) ?: return null
            val last = p.last()
            val portTile = last.first + last.second
            return Routed(p + (portTile to d), portTile)
        }
        val order = listOfNotNull("in", "out", s.pass?.let { "pass" })
        val perms = permutations(order)
        for (perm in perms) {
            used.clear()
            val got = HashMap<String, Routed>()
            var ok = true
            for (what in perm) {
                val r = when (what) {
                    "in" -> routeIn()
                    "out" -> routeOut(s.output, bExit, bDir)
                    else -> routeOut(s.pass!!, aTail, Dir.S)
                }
                if (r == null) { ok = false; break }
                r.belts.forEach { used += it.first }
                got[what] = r
            }
            if (!ok) continue
            val all = ArrayList(ents)
            fun beltsOf(r: Routed, item: String) = r.belts.forEach { (t, h) -> all += PlacedEntity(belt, Kind.BELT, t.x, t.y, h, net = netOf[item]) }
            beltsOf(got.getValue("in"), s.ingredient)
            beltsOf(got.getValue("out"), s.product)
            got["pass"]?.let { beltsOf(it, s.ingredient) }
            fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
            val sources = listOf(SourceSpec(got.getValue("in").port, s.ingredient, portRate(s.input), port = s.input))
            val outputs = listOfNotNull(
                OutputSpec(got.getValue("out").port, s.product, portRate(s.output), port = s.output),
                got["pass"]?.let { OutputSpec(it.port, s.ingredient, portRate(s.pass!!), port = s.pass) },
            )
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
            val errors = validate(layout, vspec)
            if (errors.isNotEmpty()) continue
            return TrialResult(true, W, H, 0L, layout, placement, emptyList(), null, score(layout, config), "ok", 0.0,
                System.currentTimeMillis() - t0, listOf("template: machine column of ${n} ${s.machine} (${s.recipe}), headers $hTop/$hBot"),
                sources, outputs)
        }
        return null
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }
}
