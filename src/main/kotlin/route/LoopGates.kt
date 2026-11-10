package route

import data.GameConstants
import geom.CircuitCondition
import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.dropTile
import geom.outputTile
import geom.pickupTile
import kotlin.math.hypot

/** Entities with gates set (same indices as given), the circuit wires (given ones first), and what could not be done. */
class LoopGates(val entities: List<PlacedEntity>, val circuitWires: List<Triple<Int, Int, Int>>, val notes: List<String>)

/**
 * A machine's own return loop first. A machine that gives back some of what it takes (Py's catalysts) has its own
 * loop (see PathFinder's chains): a belt from its output of the item to its own input, which only it puts onto and
 * takes from. When it also takes that item from another belt, nothing makes it use its returns first: with its input
 * kept full from there, its returns pile up on the loop, then in its output, and once the output holds a couple of
 * crafts' worth the game stops it. It was the only one emptying the loop, so it never starts again (Py production
 * science's ulric corral, with 2 bedding in its output).
 *
 * So each other inserter bringing that item into the machine gets a condition, item < 1, and a green wire to the loop's
 * belt, which reads what the whole belt holds: it works only while the loop is empty. The machine takes more than it
 * gives back, so the loop empties every craft or two and never fills.
 *
 * Loops are found from the entities alone (the same after a stitch or a resume): a belt chain carrying one net's item
 * that only inserters out of one machine put onto and only inserters into that machine take from. [itemOfNet] gives a
 * net's item (null: leave it, a mixed belt). A gated inserter out of wire reach of the loop is wired through the
 * machine's other inserters (no condition, on no circuit network yet); one still out of reach stays ungated (a note,
 * and the validator's DEADLOCK error). Setting a gate again is harmless: existing wires are not doubled.
 */
fun addLoopGates(entities: List<PlacedEntity>, circuitWires: List<Triple<Int, Int, Int>>, itemOfNet: (Int) -> String?): LoopGates {
    val ents = entities.toMutableList()
    val wires = circuitWires.toMutableList()
    val notes = ArrayList<String>()
    val at = HashMap<Tile, Int>()
    ents.forEachIndexed { i, e -> e.rect.tiles().forEach { at[it] = i } }

    // Belt chains (union-find over belt-family successors, undergrounds paired as the validator pairs them).
    val parent = IntArray(ents.size) { it }
    fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
    fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
    ents.forEachIndexed { i, e ->
        if (!e.kind.beltFamily) return@forEachIndexed
        when (e.kind) {
            Kind.UG_IN -> {
                val gap = GameConstants.tierOfEntity(e.name)?.maxUndergroundGap ?: return@forEachIndexed
                for (j in 1..gap + 1) {
                    val k = at[e.tile.step(e.dir, j)] ?: continue
                    val o = ents[k]
                    if (o.name != e.name || o.dir.axis != e.dir.axis) continue
                    if (o.kind == Kind.UG_OUT && o.dir == e.dir) union(i, k)
                    break
                }
            }
            Kind.SPLITTER -> e.rect.tiles().forEach { t -> at[t + e.dir]?.takeIf { it != i && ents[it].kind.beltFamily }?.let { union(i, it) } }
            else -> e.outputTile?.let { t -> at[t]?.takeIf { ents[it].kind.beltFamily && ents[it].dir != e.dir.opposite }?.let { union(i, it) } }
        }
    }

    // Per belt chain: which machines put onto it and take from it (by the inserters' nets), and anything else touching it.
    class Chain { val puts = HashMap<Int, MutableSet<Int>>(); val takes = HashMap<Int, MutableSet<Int>>(); var other = false }
    val chains = HashMap<Int, Chain>()
    val inserters = ents.indices.filter { ents[it].kind == Kind.INSERTER }
    for (i in inserters) {
        val ins = ents[i]
        val p = at[ins.pickupTile]; val d = at[ins.dropTile]
        val n = ins.net
        if (d != null && ents[d].kind.beltFamily) {
            val c = chains.getOrPut(find(d)) { Chain() }
            if (p != null && ents[p].kind == Kind.MACHINE && n != null) c.puts.getOrPut(n) { HashSet() } += p else c.other = true
        }
        if (p != null && ents[p].kind.beltFamily) {
            val c = chains.getOrPut(find(p)) { Chain() }
            if (d != null && ents[d].kind == Kind.MACHINE && n != null) c.takes.getOrPut(n) { HashSet() } += d else c.other = true
        }
    }
    // Loaders (test copies' ports) beside a chain make it no loop.
    ents.forEachIndexed { i, e ->
        if (e.kind != Kind.LOADER_IN && e.kind != Kind.LOADER_OUT) return@forEachIndexed
        e.rect.tiles().flatMap { t -> geom.Dir.entries.map { t + it } }.mapNotNull { at[it] }.filter { it != i && ents[it].kind.beltFamily }
            .forEach { chains.getOrPut(find(it)) { Chain() }.other = true }
    }

    // Loops: (machine, net) -> the belt-family entities of its loops.
    val loops = HashMap<Pair<Int, Int>, MutableList<Int>>()
    val members = ents.indices.filter { ents[it].kind.beltFamily }.groupBy { find(it) }
    chains.forEach { (root, c) ->
        if (c.other || c.puts.size != 1 || c.takes.size != 1) return@forEach
        val (n, makers) = c.puts.entries.single()
        val users = c.takes[n] ?: return@forEach
        if (makers.size != 1 || users != makers) return@forEach
        // Only the loop's own item on its belts (no second item on the other lane).
        val belts = members[root] ?: return@forEach
        if (belts.any { ents[it].net != null && ents[it].net != n }) return@forEach
        loops.getOrPut(makers.single() to n) { ArrayList() } += belts
    }

    val reach = GameConstants.CIRCUIT_WIRE_REACH
    fun centre(e: PlacedEntity) = (e.x + e.w / 2.0) to (e.y + e.h / 2.0)
    fun dist(a: Int, b: Int): Double { val (ax, ay) = centre(ents[a]); val (bx, by) = centre(ents[b]); return hypot(ax - bx, ay - by) }
    val wired = HashSet<Int>().also { s -> wires.forEach { (a, b, _) -> s += a; s += b } }
    fun hasWire(a: Int, b: Int) = wires.any { (x, y, c) -> c == GameConstants.WIRE_GREEN && ((x == a && y == b) || (x == b && y == a)) }
    var gated = 0
    // Per loop, the machine's other inserters of its item (by net), not taking from its loop.
    val supplies = loops.mapValues { (key, loopBelts) ->
        val (m, n) = key
        val loopRoots = loopBelts.map(::find).toSet()
        inserters.filter { i ->
            val ins = ents[i]
            val p = at[ins.pickupTile]
            ins.net == n && at[ins.dropTile] == m && p != null && find(p) !in loopRoots
        }
    }
    // (Never a relay: what another gate reads or works by, or its network would join this one's.)
    val reserved = (supplies.values.flatten() + loops.values.flatten()).toSet()
    for ((key, loopBelts) in loops) {
        val (m, n) = key
        val item = itemOfNet(n) ?: continue
        val supply = supplies.getValue(key)
        if (supply.isEmpty()) continue
        val machine = ents[m]
        // Relays: inserters and plain belts around the machine with no condition, reading nothing, on no circuit network
        // yet (a wire to them changes nothing they do). Py's 12 x 12 particle accelerators have inserters 13 tiles
        // apart, beyond wire reach.
        val around = geom.Rect(machine.x - 12, machine.y - 12, machine.w + 24, machine.h + 24)
        val relays = ents.indices.filter { i ->
            val e = ents[i]
            (e.kind == Kind.INSERTER || e.kind == Kind.BELT) && i !in reserved && e.circuit == null && !e.readBelt &&
                i !in wired && e.tile in around
        }
        val readers = loopBelts.filter { ents[it].kind == Kind.BELT }.ifEmpty { loopBelts }
        for (g in supply) {
            // Shortest wire path from g to a loop belt, hop by hop within reach (Dijkstra over relays).
            val nodes = listOf(g) + relays + readers
            val best = DoubleArray(nodes.size) { Double.MAX_VALUE }; val from = IntArray(nodes.size) { -1 }
            best[0] = 0.0
            val done = BooleanArray(nodes.size)
            var hit = -1
            while (true) {
                var u = -1
                for (k in nodes.indices) if (!done[k] && best[k] < Double.MAX_VALUE && (u < 0 || best[k] < best[u])) u = k
                if (u < 0) break
                done[u] = true
                if (u > relays.size) { hit = u; break }
                for (v in nodes.indices) if (!done[v]) {
                    val d = dist(nodes[u], nodes[v])
                    if (d <= reach + 1e-9 && best[u] + d < best[v]) { best[v] = best[u] + d; from[v] = u }
                }
            }
            if (hit < 0) {
                notes += "${machine.recipe} at ${machine.tile}: the inserter at ${ents[g].tile} bringing $item is out of wire reach of its return loop; left ungated"
                continue
            }
            var v = hit
            while (from[v] >= 0) {
                val a = nodes[from[v]]; val b = nodes[v]
                if (!hasWire(a, b)) wires += Triple(minOf(a, b), maxOf(a, b), GameConstants.WIRE_GREEN)
                wired += a; wired += b
                v = from[v]
            }
            ents[g] = ents[g].copy(circuit = CircuitCondition(item, "<", 1, fluid = false))
            ents[nodes[hit]] = ents[nodes[hit]].copy(readBelt = true)
            gated++
        }
    }
    if (gated > 0) notes += "$gated inserter(s) bring a catalyst only while its machine's own return loop is empty"
    return LoopGates(ents, wires, notes)
}
