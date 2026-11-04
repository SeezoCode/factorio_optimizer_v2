package power

import data.GameConstants
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.dropTile
import geom.outputTile
import geom.pickupTile
import java.util.PriorityQueue

/**
 * Aquilo heating: every entity that freezes (everything but poles by default, heat pipes, chests and test feeds)
 * must touch a heat pipe along an edge (not only at a corner), and every heat pipe must join the heat port through
 * other heat pipes. Heated entities do not pass heat on.
 *
 * [placeHeatPipes] lays them after routing: a tree grown from the port, each step the cheapest run to the nearest
 * tile beside an entity still cold, then pipes that warm nothing only they warm are taken off its leaves. Heat pipes
 * have no underground, so a belt line would wall an area off: a run may cross one where five belts go straight in a
 * line with nothing else touching the middle three, which become an underground hop (entrance, a free tile for the
 * heat pipe, exit). Where it still fails, the trial reroutes with room kept around what stayed cold.
 */
data class HeatResult(
    val ok: Boolean,
    /**
     * The input entities, in the same order (so wire indices stay valid): where a heat pipe crosses a belt line the
     * belts on either side are now an underground pair and the middle belt is a heat pipe.
     */
    val entities: List<PlacedEntity>,
    /** The other heat pipes, to add after [entities]. */
    val pipes: List<PlacedEntity>,
    /** Heat port tiles (outside the box, where heat comes in), the first one first; empty if none could start. */
    val ports: List<Tile>,
    /** Entities left cold. */
    val cold: List<PlacedEntity>,
)

/** Whether [e] freezes on Aquilo and so needs a heat pipe beside it. [G] everything but poles, VERIFY poles (v23) */
fun needsHeat(e: PlacedEntity, poles: Boolean): Boolean = when (e.kind) {
    Kind.POLE -> poles
    Kind.HEAT_PIPE, Kind.CHEST, Kind.INFINITY_CHEST, Kind.INFINITY_PIPE, Kind.LOADER_IN, Kind.LOADER_OUT -> false
    else -> true
}

/** Tiles of the heat lattice ([config.HeatConfig.lattice]): every pitch-th row of the box, none without heating. */
fun heatLatticeTiles(config: config.RunConfiguration, w: Int, h: Int): Set<Tile> {
    val p = config.heat.lattice ?: return emptySet()
    if (config.heat.mode == "off") return emptySet()
    return (p - 1 until h - 1 step p).flatMap { y -> (0 until w).map { Tile(it, y) } }.toSet()
}

/** Tiles sharing an edge with [e]'s footprint, outside it. */
fun edgeNeighbours(e: PlacedEntity): List<Tile> {
    val r = e.rect
    return Dir.entries.flatMap { r.sideTiles(it, 1) }
}

private const val CROSS_COST = 8
private const val NEW_PORT_COST = 12

/**
 * Heat pipes for [entities] in a [w] x [h] box, the port on one of [portTiles] (ring tiles outside the box), then a
 * tree over free box tiles (and the ring, with [ring]) and crossable belt runs.
 */
fun placeHeatPipes(
    entities: List<PlacedEntity>, w: Int, h: Int, portTiles: List<Tile>, poles: Boolean, ring: Boolean = true,
    /** Most heat ports the network may use: more join areas a single tree cannot reach (belts and ports wall them off). */
    maxPorts: Int = 1,
    /** Tiles that are heat pipes whatever else (a lattice laid first): each of their runs joins the network as a whole. */
    seeds: Set<Tile> = emptySet(),
): HeatResult {
    val ents = entities.toMutableList()
    val at = HashMap<Tile, Int>()
    // Heat pipes already laid (a block's own network, in a factory of blocks) join the tree like seeds.
    val laid = ents.filter { it.kind == Kind.HEAT_PIPE }.map { it.tile }.toSet()
    ents.forEachIndexed { i, e -> if (e.kind != Kind.HEAT_PIPE) e.rect.tiles().forEach { at[it] = i } }
    fun inBox(t: Tile) = t.x in 0 until w && t.y in 0 until h
    // Heat pipes may also run on the ring around the box: a loop around the block reaches its edges and ports.
    fun onRing(t: Tile) = !inBox(t) && t.x in -1..w && t.y in -1..h
    fun free(t: Tile) = (inBox(t) || (ring && onRing(t))) && t !in at
    val needs = ents.filter { needsHeat(it, poles) }
    // Entities beside each tile.
    val beside = HashMap<Tile, MutableList<Int>>()
    needs.forEachIndexed { i, e -> edgeNeighbours(e).forEach { beside.getOrPut(it) { ArrayList() } += i } }

    // Belt tiles a heat pipe may cross: the middle of five straight belts in a line, the middle three touched by no
    // inserter and fed by nothing but the belt behind; not inside an underground span of the same tier and axis.
    val touched = HashSet<Tile>()
    ents.filter { it.kind == Kind.INSERTER }.forEach { touched += it.pickupTile; touched += it.dropTile }
    val spans = HashMap<Pair<String, Int>, HashSet<Tile>>()   // (underground name, axis) -> tiles under a pair
    ents.filter { it.kind == Kind.UG_IN }.forEach { e ->
        val gap = GameConstants.tierOfEntity(e.name)?.maxUndergroundGap ?: return@forEach
        for (j in 1..gap + 1) {
            val o = at[e.tile.step(e.dir, j)]?.let { ents[it] } ?: continue
            if (o.name == e.name && o.kind == Kind.UG_OUT && o.dir == e.dir) {
                val s = spans.getOrPut(e.name to e.dir.axis) { HashSet() }
                for (k in 0..j) s += e.tile.step(e.dir, k)
                break
            }
        }
    }
    val locked = HashSet<Tile>()
    val crossings = HashSet<Tile>()
    fun beltAt(t: Tile, d: Dir) = at[t]?.let { ents[it] }?.takeIf { it.kind == Kind.BELT && it.dir == d }
    fun crossable(t: Tile): Boolean {
        val b = at[t]?.let { ents[it] } ?: return false
        if (b.kind != Kind.BELT || !inBox(t)) return false
        val d = b.dir
        val run = (-2..2).map { t.step(d, it) }
        // The middle three are belts in line; behind them anything that pushes items straight in (a belt, an exit, a
        // splitter), ahead anything that takes them straight on (a belt, an entrance).
        if (run.subList(1, 4).any { beltAt(it, d) == null } || run.any { it in locked }) return false
        val back = at[run[0]]?.let { ents[it] }
        if (back == null || back.dir != d || back.kind !in setOf(Kind.BELT, Kind.UG_OUT, Kind.SPLITTER)) return false
        val front = at[run[4]]?.let { ents[it] }
        if (front == null || front.dir != d || front.kind !in setOf(Kind.BELT, Kind.UG_IN)) return false
        val mid = run.subList(1, 4)
        if (mid.any { it in touched }) return false
        // Nothing but the belt behind feeds the middle three (no sideloads).
        for (m in mid) for (s in listOf(d.cw, d.ccw)) {
            val n = at[m + s]?.let { ents[it] } ?: continue
            if (n.kind.beltFamily && (n.outputTile == m || (n.kind == Kind.SPLITTER && n.rect.tiles().any { it + n.dir == m }))) return false
        }
        val ug = GameConstants.tierOfEntity(b.name)?.underground ?: return false
        val s = spans[ug to d.axis]
        return s == null || (run[1] !in s && run[3] !in s)
    }

    val warm = BooleanArray(needs.size)
    val tree = LinkedHashSet<Tile>()
    fun add(t: Tile) { tree += t; beside[t]?.forEach { warm[it] = true } }
    // A crossing: the belts on either side become an underground pair, the middle tile is freed.
    fun cross(t: Tile) {
        val b = ents[at.getValue(t)]
        val d = b.dir
        val ug = GameConstants.tierOfEntity(b.name)!!.underground
        val before = t + d.opposite; val after = t + d
        ents[at.getValue(before)] = PlacedEntity(ug, Kind.UG_IN, before.x, before.y, d, net = b.net)
        ents[at.getValue(after)] = PlacedEntity(ug, Kind.UG_OUT, after.x, after.y, d, net = b.net)
        val gone = at.remove(t)!!
        // The belt's place in the list goes to the heat pipe that replaces it (it stays, whatever pruning says).
        ents[gone] = PlacedEntity(GameConstants.HEAT_PIPE, Kind.HEAT_PIPE, t.x, t.y)
        crossings += t
        locked += (-2..2).map { t.step(d, it) }
        spans.getOrPut(ug to d.axis) { HashSet() }.addAll(listOf(before, t, after))
    }

    // The port: a free ring tile whose box neighbour is free; the one whose box tile warms the most first.
    val port = portTiles.filter { it !in at && Dir.entries.any { d -> free(it + d) } }
        .maxByOrNull { p -> Dir.entries.filter { free(p + it) }.maxOf { d -> beside[p + d]?.size ?: 0 } }
        ?: return HeatResult(false, ents, emptyList(), emptyList(), needs)
    add(port)
    val ports = arrayListOf(port)
    val portSet = portTiles.toHashSet()

    // Seed runs (the lattice): joined whole once a run reaches one of their tiles.
    val seedFree = (seeds + laid).filter { free(it) }.toHashSet()
    fun addSeedRun(t0: Tile) {
        val q = ArrayDeque(listOf(t0)); val seen = hashSetOf(t0)
        while (q.isNotEmpty()) { val t = q.removeFirst(); add(t); Dir.entries.forEach { d -> val n = t + d; if (n in seedFree && seen.add(n)) q += n } }
    }
    seedFree.filter { s -> s in tree || Dir.entries.any { (s + it) in tree } }.forEach { if (it !in tree) addSeedRun(it) }
    while (true) {
        val coldTargets = HashSet<Tile>()
        needs.indices.filter { !warm[it] }.forEach { i -> edgeNeighbours(needs[i]).filter { free(it) || crossable(it) }.forEach { coldTargets += it } }
        seedFree.filter { it !in tree }.forEach { coldTargets += it }
        if (coldTargets.isEmpty()) break
        // Cheapest run from the whole tree over free tiles (1) and crossable belts (CROSS_COST) to a tile beside
        // something cold.
        val dist = HashMap<Tile, Int>()
        val prev = HashMap<Tile, Tile?>()
        val pq = PriorityQueue<Pair<Int, Tile>>(compareBy { it.first })
        tree.forEach { dist[it] = 0; prev[it] = null; pq += 0 to it }
        // Another port may start a run, at a price, while the network has fewer than maxPorts.
        if (ports.size < maxPorts) portTiles.forEach { p ->
            if (p !in tree && p !in at && (dist[p] ?: Int.MAX_VALUE) > NEW_PORT_COST) { dist[p] = NEW_PORT_COST; prev[p] = null; pq += NEW_PORT_COST to p }
        }
        var hit: Tile? = null
        while (pq.isNotEmpty()) {
            val (dc, c) = pq.poll()
            if (dc > (dist[c] ?: Int.MAX_VALUE)) continue
            if (c in coldTargets && c !in tree) { hit = c; break }
            for (d in Dir.entries) {
                val n = c + d
                val step = when { free(n) -> 1; crossable(n) -> CROSS_COST; else -> continue }
                val nd = dc + step
                if (nd < (dist[n] ?: Int.MAX_VALUE)) { dist[n] = nd; prev[n] = c; pq += nd to n }
            }
        }
        if (hit == null) break
        val path = ArrayList<Tile>()
        var c: Tile? = hit
        while (c != null && c !in tree) { path += c; c = prev[c] }
        // A run that starts at a new port.
        path.lastOrNull()?.let { if (it in portSet && prev[it] == null && it !in tree) ports += it }
        // Crossings first (they change which tiles are free), then the pipes.
        var ok = true
        path.forEach { t -> if (!free(t)) { if (crossable(t)) cross(t) else ok = false } }
        if (!ok) break
        path.forEach { t -> add(t) }
        path.filter { it in seedFree }.forEach { addSeedRun(it) }
    }

    // Prune leaves that warm nothing only they warm.
    var pruned = true
    while (pruned) {
        pruned = false
        for (t in tree.toList()) {
            if (t in ports || t in crossings || t in seedFree) continue
            val degree = Dir.entries.count { (t + it) in tree }
            if (degree > 1) continue
            val warms = beside[t] ?: emptyList()
            if (warms.all { i -> edgeNeighbours(needs[i]).any { it != t && it in tree } }) { tree -= t; pruned = true }
        }
    }
    val warmNow = BooleanArray(needs.size)
    tree.forEach { t -> beside[t]?.forEach { warmNow[it] = true } }
    // Underground ends made by crossings need heat like the belts they replaced (same tiles, so the same neighbours);
    // a belt that became a heat pipe needs none.
    val cold = needs.indices.filter { !warmNow[it] && needs[it].tile !in crossings }.map { needs[it] }
    val pipes = tree.filter { it !in crossings && it !in laid }.map { PlacedEntity(GameConstants.HEAT_PIPE, Kind.HEAT_PIPE, it.x, it.y) }
    return HeatResult(cold.isEmpty(), ents, pipes, ports, cold)
}
