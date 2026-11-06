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

/** Entities with conditions set, the red wires between them, and what could not be done. */
class SurplusControl(val entities: List<PlacedEntity>, val circuitWires: List<Triple<Int, Int, Int>>, val notes: List<String>)

/**
 * Cracking only what is left over. A chemical plant cracking heavy (light) oil works whenever it has oil and room
 * for its product, and machine counts are rounded up, so crackers run faster than the plan needs: they take oil the
 * lubricant (or whatever else uses it) needs, petroleum gas fills up, and the refineries stop. So each group of
 * crackers ([config.FluidConfig.surplusOnly]: recipe -> the fluid it cracks) whose fluid other machines also use (or
 * that leaves the layout) gets a red wire from one pipe of that fluid to every cracker, which works only while the
 * pipe is more than [config.FluidConfig.surplusLevel] full: what the other machines do not take. A wired pipe reads
 * its own share of the fluid, which follows the fill level of its whole pipe network (measured in game: 50 in a
 * line of 10 pipes or of 40 half full), so the threshold does not depend on how long the pipes are, and nothing has
 * to be built (a storage tank, which reads the same, needs a free 3 x 3 spot a packed block may not have).
 *
 * [groups] are areas whose crackers share one network of each fluid (blocks of a factory; the whole box for one
 * block). The pipe is one of the fluid's pipes (or pipe-to-ground ends) in the area, else in the corridors around it
 * within wire reach, as near the crackers as wire reach needs; wires run from it from cracker to cracker along a
 * shortest tree, each within reach. Crackers no pipe can reach keep working unconditionally (reported in the notes).
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
    val level = (GameConstants.PIPE_CAPACITY * config.fluids.surplusLevel).toInt()
    fun centre(e: PlacedEntity) = (e.x + e.w / 2.0) to (e.y + e.h / 2.0)
    fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)
    val reach = GameConstants.CIRCUIT_WIRE_REACH

    /** Shortest tree from the pipe over crackers, each edge within reach: the crackers it reaches and its edges. */
    fun tree(from: Pair<Double, Double>, crackers: List<Int>): Pair<List<Int>, List<Pair<Int, Int>>> {
        val pts = listOf(from) + crackers.map { centre(ents[it]) }
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
        fun pipesIn(r: Rect) = ents.indices.filter { val e = ents[it]; (e.kind == Kind.PIPE || e.kind == Kind.PIPE_UG) && e.fluid == f && e.tile in r }
        val near = pipesIn(grown)
        // The corridors around the area, within wire reach.
        val r = reach.toInt() + 3
        val far by lazy { pipesIn(Rect(area.x - r, area.y - r, area.w + 2 * r, area.h + 2 * r)) - near.toSet() }
        var tries = 0
        while (left.isNotEmpty() && tries++ < 4) {
            // The pipe reaching the most crackers, then nearest to them.
            var best: Triple<Int, List<Int>, List<Pair<Int, Int>>>? = null
            var bestScore = Double.MAX_VALUE
            val cx = left.map { centre(ents[it]).first }.average(); val cy = left.map { centre(ents[it]).second }.average()
            for (cands in listOf(near, far)) {
                if (best != null) break
                for (p in cands) {
                    val pc = centre(ents[p])
                    val (reached, edges) = tree(pc, left)
                    if (reached.isEmpty()) continue
                    val score = -1000.0 * reached.size + dist(pc, cx to cy)
                    if (score < bestScore) { bestScore = score; best = Triple(p, reached, edges) }
                }
            }
            val (pipe, reached, edges) = best ?: break
            reached.forEach { i -> ents[i] = ents[i].copy(circuit = CircuitCondition(f, ">", level)) }
            val node = listOf(pipe) + left
            edges.forEach { (a, b) -> wires += Triple(node[a], node[b], GameConstants.WIRE_RED) }
            left = left - reached.toSet()
        }
        if (left.isNotEmpty()) notes += "${left.size} ${f}-cracking machine(s) near (${area.x},${area.y}) have no ${f} pipe in wire reach and crack unconditionally"
    }
    return SurplusControl(ents, wires, notes)
}
