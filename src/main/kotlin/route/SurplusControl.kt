package route

import config.RunConfiguration
import data.GameConstants
import geom.CircuitCondition
import geom.Dir
import geom.Kind
import geom.PlacedEntity
import geom.Rect
import geom.Tile
import geom.dropTile
import geom.pickupTile
import plan.ProductionPlan
import kotlin.math.hypot

/** Entities with tanks added and conditions set, the red wires between them, and what could not be done. */
class SurplusControl(val entities: List<PlacedEntity>, val circuitWires: List<Triple<Int, Int, Int>>, val notes: List<String>)

/**
 * Cracking only what is left over. A chemical plant cracking heavy (light) oil works whenever it has oil and room
 * for its product, and machine counts are rounded up, so crackers run faster than the plan needs: they take oil the
 * lubricant (or whatever else uses it) needs, petroleum gas fills up, and the refineries stop. So each group of
 * crackers ([config.FluidConfig.surplusOnly]: recipe -> the fluid it cracks) whose fluid other machines also use (or
 * that leaves the layout) gets a storage tank on its pipes and a red wire to every cracker, which works only while
 * the tank is more than [config.FluidConfig.surplusLevel] full: what the other machines do not take. The tank
 * reads its own contents, which follow the fill level of its whole pipe network, so the threshold does not depend
 * on how long the pipes are.
 *
 * [groups] are areas whose crackers share one network of each fluid (blocks of a factory; the whole box for one
 * block). A tank goes on a free 3 x 3 spot whose corner connection meets a pipe of the fluid in its area, its other
 * connections touching nothing of another fluid, as near the crackers as wire reach needs; wires run from the tank
 * from cracker to cracker along a shortest tree, each within reach. Crackers no tank can reach keep working
 * unconditionally (reported in the notes).
 */
fun addSurplusControl(entities: List<PlacedEntity>, plan: ProductionPlan, config: RunConfiguration, w: Int, h: Int, groups: List<Rect>): SurplusControl {
    val control = config.fluids.surplusOnly
    if (control.isEmpty() || config.fluids.mode != "pipes") return SurplusControl(entities, emptyList(), emptyList())
    // Fluids worth controlling: used by something other than its crackers, or leaving the layout.
    val fluids = control.values.toSet().filter { f ->
        plan.machines.any { m -> (m.fluidDemand[f] ?: 0.0) > 1e-9 && control[m.recipe] != f } || (plan.outputs[f] ?: 0.0) > 1e-9
    }.toSet()
    if (fluids.isEmpty()) return SurplusControl(entities, emptyList(), emptyList())
    val ents = entities.toMutableList()
    val wires = ArrayList<Triple<Int, Int, Int>>()
    val notes = ArrayList<String>()
    val occupied = HashSet<Tile>()
    ents.forEach { e -> e.rect.tiles().forEach { occupied += it } }
    // Tiles a tank must not cover: machine fluid connections (they would join it) and inserter hands.
    val keepOff = HashSet<Tile>()
    ents.filter { it.kind == Kind.INSERTER }.forEach { keepOff += it.pickupTile; keepOff += it.dropTile }
    ents.filter { it.kind == Kind.MACHINE }.forEach { m ->
        val inst = plan.machines.firstOrNull { it.recipe == m.recipe }
        place.connectionsOf(m.name, m.w, m.h, inst?.fluidIn ?: emptyMap(), inst?.fluidOut ?: emptyMap(), m.x, m.y, m.dir.ordinal, m.mirror).forEach { keepOff += it.tile }
    }
    val byTile = HashMap<Tile, PlacedEntity>().also { mp -> ents.forEach { e -> e.rect.tiles().forEach { mp[it] = e } } }
    val level = (GameConstants.STORAGE_TANK_CAPACITY * config.fluids.surplusLevel).toInt()
    fun centre(e: PlacedEntity) = (e.x + e.w / 2.0) to (e.y + e.h / 2.0)
    fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)
    val reach = GameConstants.CIRCUIT_WIRE_REACH

    /** Shortest tree from the tank over crackers, each edge within reach: the crackers it reaches and its edges. */
    fun tree(tank: Pair<Double, Double>, crackers: List<Int>): Pair<List<Int>, List<Pair<Int, Int>>> {
        val pts = listOf(tank) + crackers.map { centre(ents[it]) }
        val inTree = BooleanArray(pts.size).also { it[0] = true }
        val edges = ArrayList<Pair<Int, Int>>()
        while (true) {
            var best: Triple<Int, Int, Double>? = null
            for (a in pts.indices) if (inTree[a]) for (b in pts.indices) if (!inTree[b]) {
                val d = dist(pts[a], pts[b])
                if (d <= reach + 1e-9 && (best == null || d < best.third)) best = Triple(a, b, d)
            }
            val (a, b, _) = best ?: break
            inTree[b] = true; edges += a to b
        }
        return crackers.filterIndexed { i, _ -> inTree[i + 1] } to edges
    }

    for (f in fluids) for (area in groups) {
        val grown = Rect(area.x - 2, area.y - 2, area.w + 4, area.h + 4)
        var left = ents.indices.filter { i -> val e = ents[i]; e.kind == Kind.MACHINE && e.circuit == null && control[e.recipe] == f && Tile(e.x + e.w / 2, e.y + e.h / 2) in area }
        if (left.isEmpty()) continue
        val pipes = ents.filter { it.kind == Kind.PIPE && it.fluid == f && it.tile in grown }.map { it.tile }
        var tries = 0
        while (left.isNotEmpty() && tries++ < 4) {
            // Every spot where a tank corner meets one of these pipes; the one reaching the most crackers, then nearest.
            var best: Triple<PlacedEntity, List<Int>, List<Pair<Int, Int>>>? = null
            var bestScore = Double.MAX_VALUE
            val cx = left.map { centre(ents[it]).first }.average(); val cy = left.map { centre(ents[it]).second }.average()
            for (p in pipes) for (dir in listOf(Dir.N, Dir.E)) {
                val conns = GameConstants.storageTankConnections(dir)
                for ((off, d) in conns) {
                    val x0 = p.x - d.dx - off.x; val y0 = p.y - d.dy - off.y
                    val rect = Rect(x0, y0, 3, 3)
                    if (x0 < 0 || y0 < 0 || x0 + 3 > w || y0 + 3 > h) continue
                    if (rect.tiles().any { it in occupied || it in keepOff }) continue
                    // The other corners open onto nothing, or onto a pipe of this fluid.
                    val clash = conns.any { (o, e) ->
                        val t = Tile(x0 + o.x, y0 + o.y) + e
                        val other = byTile[t] ?: return@any false
                        !(other.kind == Kind.PIPE && other.fluid == f) && (other.kind in setOf(Kind.PIPE, Kind.PIPE_UG, Kind.INFINITY_PIPE, Kind.TANK) || other.kind == Kind.MACHINE)
                    }
                    if (clash) continue
                    val tc = (x0 + 1.5) to (y0 + 1.5)
                    val (reached, edges) = tree(tc, left)
                    if (reached.isEmpty()) continue
                    val score = -1000.0 * reached.size + dist(tc, cx to cy)
                    if (score < bestScore) { bestScore = score; best = Triple(PlacedEntity(GameConstants.STORAGE_TANK, Kind.TANK, x0, y0, dir), reached, edges) }
                }
            }
            val (tank, reached, edges) = best ?: break
            val ti = ents.size
            ents += tank
            tank.rect.tiles().forEach { occupied += it; byTile[it] = tank }
            reached.forEach { i -> ents[i] = ents[i].copy(circuit = CircuitCondition(f, ">", level)) }
            val node = listOf(ti) + left
            edges.forEach { (a, b) -> wires += Triple(node[a], node[b], GameConstants.WIRE_RED) }
            left = left - reached.toSet()
        }
        if (left.isNotEmpty()) notes += "${left.size} ${f}-cracking machine(s) near (${area.x},${area.y}) have no tank in wire reach and crack unconditionally"
    }
    return SurplusControl(ents, wires, notes)
}
