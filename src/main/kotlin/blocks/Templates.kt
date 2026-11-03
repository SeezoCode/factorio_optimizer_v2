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
 * sides of the block the ports are on (a small belt search, undergrounds included); more input ports merge into the
 * first one's belt through splitters, more product ports branch off the first one's through splitters, and what is
 * left of A can leave as a pass-through output.
 */
object Templates {
    private const val LONG = "long-handed-inserter"
    private val DEBUG = System.getenv("TEMPLATE_DEBUG") != null

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
        /** Input ports of the ingredient, product output ports, a pass-through port (or none). */
        val inputs: List<Int>, val outputs: List<Int>, val pass: Int?,
        val shortIn: String, val shortOut: String,
        val belt: String, val underground: String, val splitter: String, val maxGap: Int,
        /** Items/s one lane of the tier holds. */
        val laneCap: Double,
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
        if (ins.isEmpty() || ins.any { it.item != ingredient }) return no("input ports other than of $ingredient")
        val prodOut = outs.filter { it.item == product }
        val passOut = outs.filter { it.item == ingredient }
        if (prodOut.isEmpty() || passOut.size > 1 || outs.size != prodOut.size + passOut.size) return no("output ports other than of $product (and one of $ingredient passing on)")
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
            shortIn, shortOut, tier.belt, tier.underground, tier.splitter, tier.maxUndergroundGap, tier.throughput / 2)
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
        val headers = (0..4).flatMap { a -> (0..4).map { b -> a to b } }.sortedBy { it.first + it.second }
        // A bypass lane along the column (one tile wider) only when the ports cannot be reached without one: it lets
        // belts reach ports at the column's other end.
        for (bypass in listOf(false, true)) {
            for ((hTop, hBot) in headers) {
                if (best != null && hTop + hBot > best.h - 3 * ((plan.machines.size + 1) / 2)) break
                for (xa in intArrayOf(4, 5)) for (bDir in arrayOf(Dir.N, Dir.S)) for (jk in if (s.inputs.size >= 2) 0..1 else 0..0) {
                    val r = tryColumn(data, config, plan, s, sides, hTop, hBot, xa, bDir, bypass, jk) ?: continue
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
        hTop: Int, hBot: Int, xa: Int, bDir: Dir, bypass: Boolean = false,
        /** Rows between the input junction and A's head (straight belts). */
        jk: Int = 0,
    ): TrialResult? {
        val t0 = System.currentTimeMillis()
        val n = plan.machines.size
        val units = (n + 1) / 2
        val W = if (bypass) 11 else 10
        val core0 = hTop
        val core1 = hTop + 3 * units   // first row below the column
        val H = core1 + hBot
        val xb = 9 - xa
        val away = xb - xa             // from A towards B: +1 or -1
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
        val aHead = Tile(xa, core0)
        // Two or more inputs: the first two join on the belt just above A's head, one sideloaded from each side, so each
        // lands on a lane of its own whatever lanes it came on (a splitter would keep a lopsided input's lanes, and one
        // lane could overflow). Further inputs merge into theirs through splitters.
        val junction = if (s.inputs.size >= 2) Tile(xa, core0 - 1 - jk) else null
        if (junction != null) {
            if (hTop < 1 + jk) return null
            for (y in junction.y until core0) put(PlacedEntity(s.belt, Kind.BELT, xa, y, Dir.S, net = netOf[s.ingredient]))
        }
        val bExit = if (bDir == Dir.N) Tile(xb, core0 - 1) else Tile(xb, core1)
        val aTail = Tile(xa, core1)
        // Pole slots: the third tile of each three in the inserter columns.
        val poleSlots = (0 until units).flatMap { k -> listOf(Tile(3, core0 + 3 * k + 2), Tile(6, core0 + 3 * k + 2)) }

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
        // Tiles nothing may use: below A's end when nothing passes on (leftovers would spill there), and the tile
        // before B's first belt (a belt there would feed B).
        val reserved0 = HashSet<Tile>()
        if (s.pass == null) reserved0 += aTail
        // A belt there may run past, but not point into B (it would feed it).
        val noHeading = mapOf(if (bDir == Dir.N) Tile(xb, core1) to Dir.N else Tile(xb, core0 - 1) to Dir.S)

        // Routing state, reset for each order of the connections.
        fun portRate(p: Int) = plan.terminals.filter { it.port == p }.sumOf { it.rate }
        val used = HashSet<Tile>()
        val reserved = HashSet<Tile>()
        /** Tiles inside some underground pair's span (ends included), per axis: no other pair on that axis may touch them. */
        val spans = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        val ugEnds = arrayOf(HashSet<Tile>(), HashSet<Tile>())
        /**
         * Belts routed so far per item, in path order, each path with whether a straight belt feeds its first step from
         * behind (B's or A's end) and whether its last step runs straight on into one (A's head); splitters go across
         * straight belts of these (branches and merges).
         */
        class Tree(val steps: List<Step>, val fedBehind: Boolean, val goesOn: Boolean)
        val trees = HashMap<String, MutableList<Tree>>()
        val coreBelts = (core0 until core1).flatMap { y -> listOf(Tile(xa, y), Tile(xb, y)) }.toSet()
        /** Nothing belt-like stands or will stand on [t] (an inserter or machine there is fine). */
        fun noBelt(t: Tile) = t !in used && t !in coreBelts && (inBox(t) || sideOfRing(t) == null || t !in used)
        class Split(val trunk: Tile, val half: Tile, val dir: Dir, val item: String)
        val splits = ArrayList<Split>()
        fun free(t: Tile) = inBox(t) && t !in occ && t !in used && t !in reserved

        /**
         * A belt path from one of [starts] (each a belt that will stand there, heading that way) until [done] says
         * the last step finishes it; belts turn left or right, undergrounds jump up to the tier's gap.
         */
        fun path(starts: List<Step>, done: (Step) -> Boolean): List<Step>? {
            val prev = HashMap<Step, Step?>()
            val queue = ArrayDeque<Step>()
            starts.filter { noHeading[it.tile] != it.dir }.forEach { st -> if (st !in prev) { prev[st] = null; queue += st } }
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
                turns.filter { noHeading[nt] != it }.forEach { nexts += Step(nt, it) }
                val axis = cur.dir.axis
                if (nt !in spans[axis]) for (j in 2..s.maxGap + 1) {
                    val e = nt.step(cur.dir, j)
                    if (!inBox(e)) break
                    val between = (1 until j).map { nt.step(cur.dir, it) }
                    if (between.any { it in ugEnds[axis] }) break
                    if (free(e) && e !in spans[axis] && noHeading[e] != cur.dir) nexts += Step(e, cur.dir, nt)
                }
                nexts.forEach { st -> if (st !in prev) { prev[st] = cur; queue += st } }
            }
            return null
        }
        /**
         * Straight belts of [item]'s paths a splitter could go across: fed from behind, going on straight ahead, with a
         * free tile beside it for the splitter's other half. Returns (trunk, other half, heading).
         */
        fun splitSpots(item: String, merge: Boolean): List<Triple<Tile, Tile, Dir>> {
            val out = ArrayList<Triple<Tile, Tile, Dir>>()
            val split = splits.flatMap { listOf(it.trunk, it.half) }.toSet()
            trees[item]?.forEach { tr ->
                val p = tr.steps
                for (i in p.indices) {
                    val b = p[i]
                    if (b.ugFrom != null || !inBox(b.tile) || b.tile in split) continue
                    val behind = if (i > 0) p[i - 1].let { a -> a.dir == b.dir && a.tile + a.dir == b.tile } else tr.fedBehind
                    val ahead = if (i < p.size - 1) p[i + 1].let { c -> c.dir == b.dir && c.ugFrom == null && b.tile + b.dir == c.tile } else tr.goesOn
                    if (!behind || !ahead) continue
                    for (side in listOf(b.dir.cw, b.dir.ccw)) {
                        val h = b.tile + side
                        // A merge's other output leads nowhere, inside the box (outside it, a belt between blocks
                        // could stand there); nothing feeds a branch's half from behind.
                        val keep = if (merge) h + b.dir else h + b.dir.opposite
                        if (free(h) && inBox(keep) && noBelt(keep)) out += Triple(b.tile, h, b.dir)
                    }
                }
            }
            return out
        }
        class Routed(val steps: List<Step>, val port: Tile, val split: Split? = null)
        // An input: from the ring on its side, straight in, to a belt heading south into A's head, or into the free
        // half of a splitter across the belt of an input already routed (a merge; its other output leads nowhere).
        fun routeIn(port: Int): Routed? {
            val d = sides.getValue(port)
            val starts = ringTiles(d).filter { it !in used && it !in occ }.map { Step(it, d.opposite) }
            val joined = trees[s.ingredient]?.size ?: 0
            if (junction != null && joined < 2) {
                // Onto the junction from a side the other input has not taken, carrying at most a lane's worth.
                if (portRate(port) > s.laneCap * 0.98) return null
                val taken = trees[s.ingredient]?.firstOrNull()?.steps?.last()?.dir
                val p = path(starts) { st -> st.tile + st.dir == junction && (st.dir == Dir.E || st.dir == Dir.W) && st.dir != taken } ?: return null
                return Routed(p, p.first().tile)
            }
            val merges = if (trees[s.ingredient].isNullOrEmpty()) emptyList() else splitSpots(s.ingredient, merge = true)
            val byHalf = merges.associateBy { it.second }
            val p = path(starts) { st ->
                val nt = st.tile + st.dir
                if (trees[s.ingredient].isNullOrEmpty()) nt == aHead && st.dir == Dir.S
                else byHalf[nt]?.let { (_, _, h) -> st.dir == h } == true
            } ?: return null
            val last = p.last()
            val split = if (trees[s.ingredient].isNullOrEmpty()) null else byHalf.getValue(last.tile + last.dir).let { (t, h, d2) -> Split(t, h, d2, s.ingredient) }
            // The merge's other output must lead nowhere: not onto this path.
            if (split != null && p.any { it.tile == split.half + split.dir || it.ugFrom == split.half + split.dir }) return null
            return Routed(p, p.first().tile, split)
        }
        // An output: a belt pushed into from [from] heading [heading] (B's end, A's end), or a branch off a splitter
        // across a straight belt of the item already routed, to a belt on the ring of its side heading out.
        fun routeOut(port: Int, item: String, from: Tile?, heading: Dir): Routed? {
            val d = sides.getValue(port)
            if (from != null && !inBox(from)) {
                // The belt end already reaches the ring: it is the port if it is on the right side.
                if (sideOfRing(from) != d || heading != d || from in used) return null
                return Routed(listOf(Step(from, d)), from)
            }
            val branches = if (from == null) splitSpots(item, merge = false) else emptyList()
            // A branch whose half points straight out of the box on the port's side is the port itself.
            if (from == null) branches.firstOrNull { (_, half, h) -> val st = half + h; h == d && sideOfRing(st) == d && st !in used && st !in occ }?.let { (t, half, h) ->
                return Routed(listOf(Step(half + h, d)), half + h, Split(t, half, h, item))
            }
            val starts = if (from != null) {
                if (!free(from)) return null
                listOf(heading, heading.cw, heading.ccw).map { Step(from, it) }
            } else branches.flatMap { (_, half, h) -> val st = half + h; if (free(st)) listOf(h, h.cw, h.ccw).map { Step(st, it) } else emptyList() }
            val p = path(starts) { st -> val nt = st.tile + st.dir; sideOfRing(nt) == d && st.dir == d && nt !in used && nt !in occ } ?: return null
            val portTile = p.last().tile + d
            val split = if (from != null) null else branches.first { (_, half, h) -> half + h == p.first().tile }.let { (t, h, d2) -> Split(t, h, d2, item) }
            // Nothing of this path may feed the branch's half from behind.
            if (split != null && p.any { it.tile == split.half + split.dir.opposite || it.ugFrom == split.half + split.dir.opposite }) return null
            return Routed(p + Step(portTile, d), portTile, split)
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
            val fromEnd = r.split == null && (first.tile == bExit || first.tile == aTail)
            val heading = if (first.tile == aTail) Dir.S else bDir
            trees.getOrPut(item) { ArrayList() } += Tree(r.steps,
                fedBehind = fromEnd && first.dir == heading,
                goesOn = r.split == null && r.steps.last().let { it.tile + it.dir == aHead })
            r.split?.let { sp ->
                splits += sp
                used += sp.half
                // A merge's free half leads nowhere: nothing may stand in front of it; nothing may feed a branch's half.
                val keep = if (r.steps.last().tile + r.steps.last().dir == sp.half) sp.half + sp.dir else sp.half + sp.dir.opposite
                if (inBox(keep) && keep !in occ) reserved += keep
            }
        }

        // Connections: inputs first (the first joins A, the rest merge into it), then outputs (the first leaves B's end,
        // the rest branch off), the pass-through last from A's end; a few orders of each group.
        data class Conn(val input: Boolean, val port: Int, val item: String)
        val ins = s.inputs.sortedByDescending { portRate(it) }.map { Conn(true, it, s.ingredient) }
        val outs = s.outputs.sortedByDescending { portRate(it) }.map { Conn(false, it, s.product) }
        val pass = listOfNotNull(s.pass?.let { Conn(false, it, s.ingredient) })
        fun rotations(xs: List<Conn>) = if (xs.size <= 1) listOf(xs) else xs.indices.map { i -> xs.drop(i) + xs.take(i) }
        val orders = rotations(ins).flatMap { a -> rotations(outs).flatMap { b -> listOf(a + b + pass, b + a + pass, pass + a + b) } }.distinct().take(24)
        for (order in orders) {
            used.clear(); reserved.clear(); reserved += reserved0; spans.forEach { it.clear() }; ugEnds.forEach { it.clear() }
            trees.clear(); splits.clear()
            val got = HashMap<Conn, Routed>()
            var ok = true
            for (c in order) {
                val r = when {
                    c.input -> routeIn(c.port)
                    c.port == s.pass -> routeOut(c.port, c.item, aTail, Dir.S)
                    trees[s.product].isNullOrEmpty() -> routeOut(c.port, c.item, bExit, bDir)
                    else -> routeOut(c.port, c.item, null, bDir)
                }
                if (r == null) { ok = false; if (DEBUG) println("  ${W}x$H top $hTop bot $hBot xa $xa b $bDir: ${if (c.input) "in" else "out"} ${c.item} port ${c.port} (${sides[c.port]}) fails after ${got.size}"); break }
                book(r, c.item)
                got[c] = r
            }
            if (!ok) continue
            val all = ArrayList(ents)
            val trunks = splits.associateBy { it.trunk }
            got.forEach { (c, r) ->
                r.steps.forEach { st ->
                    when {
                        st.ugFrom != null -> {
                            all += PlacedEntity(s.underground, Kind.UG_IN, st.ugFrom.x, st.ugFrom.y, st.dir, net = netOf[c.item])
                            all += PlacedEntity(s.underground, Kind.UG_OUT, st.tile.x, st.tile.y, st.dir, net = netOf[c.item])
                        }
                        st.tile in trunks -> {}   // a splitter stands there
                        else -> all += PlacedEntity(s.belt, Kind.BELT, st.tile.x, st.tile.y, st.dir, net = netOf[c.item])
                    }
                }
            }
            splits.forEach { sp ->
                all += PlacedEntity(s.splitter, Kind.SPLITTER, minOf(sp.trunk.x, sp.half.x), minOf(sp.trunk.y, sp.half.y), sp.dir, net = netOf[sp.item])
            }
            val sources = got.filter { it.key.input }.map { (c, r) -> SourceSpec(r.port, s.ingredient, portRate(c.port), port = c.port) }
            val outputs = got.filter { !it.key.input }.map { (c, r) -> OutputSpec(r.port, c.item, portRate(c.port), port = c.port) }
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
            val errs = validate(layout, vspec)
            if (DEBUG && errs.isNotEmpty()) println("  ${W}x$H top $hTop bot $hBot xa $xa b $bDir: invalid ${errs.take(3)}")
            if (errs.isNotEmpty()) continue
            return TrialResult(true, W, H, 0L, layout, placement, emptyList(), null, score(layout, config), "ok", 0.0,
                System.currentTimeMillis() - t0, listOf("template: machine column of $n ${s.machine} (${s.recipe}), headers $hTop/$hBot" + if (bypass) ", bypass lane" else ""),
                sources, outputs)
        }
        return null
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }
}
