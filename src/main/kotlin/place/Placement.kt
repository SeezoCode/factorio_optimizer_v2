package place

import config.ConfigSource
import config.RunConfiguration
import config.reservedRects
import config.strictJson
import data.GameConstants
import data.readResourceOrFile
import geom.Dir
import geom.Rect
import geom.Tile
import kotlinx.serialization.Serializable
import plan.MachineInstance
import plan.ProductionPlan

/**
 * Machine [id] (index into the plan's machines) with top-left (x, y), rotated [rot] (north = as in the
 * connection table) and optionally mirrored. Rotation only matters for machines with fluid connections.
 */
data class PlacedMachine(val id: Int, val x: Int, val y: Int, val rot: Dir = Dir.N, val mirror: Boolean = false)

/**
 * A placed machine's fluid connection: the pipe tile outside it, the machine side it is on, and the fluid
 * the recipe sends through it (null: the connection exists but this recipe does not use it, so no foreign
 * pipe may touch it).
 */
data class ConnTile(val tile: Tile, val side: Dir, val flow: GameConstants.Flow, val index: Int, val fluid: String?)

/** Fluid connections of [inst] at (x, y) with rotation [rot] (0..3) and [mirror]. */
fun connections(inst: MachineInstance, x: Int, y: Int, rot: Int, mirror: Boolean): List<ConnTile> =
    connectionsOf(inst.machine, inst.w, inst.h, inst.fluidIn, inst.fluidOut, x, y, rot, mirror)

/** Fluid connections of a [machine] whose recipe maps fluids to connection indices as given. */
fun connectionsOf(
    machine: String, w: Int, h: Int, fluidIn: Map<String, Int>, fluidOut: Map<String, Int>, x: Int, y: Int, rot: Int, mirror: Boolean,
): List<ConnTile> {
    val base = GameConstants.baseFluidConnections(machine)
    if (base.isEmpty()) return emptyList()
    if (GameConstants.connectionsOnlyWithFluidRecipe(machine) && fluidIn.isEmpty() && fluidOut.isEmpty()) return emptyList()
    return base.map { c ->
        val (dx, dy) = GameConstants.placeConnection(c, w, h, rot, mirror)
        val byIndex = if (c.flow == GameConstants.Flow.INPUT) fluidIn else fluidOut
        ConnTile(Tile(x + dx, y + dy), GameConstants.placeSide(c, rot, mirror), c.flow, c.index, byIndex.entries.firstOrNull { it.value == c.index }?.key)
    }
}

/** Whether rotating or mirroring [inst] changes anything (it has fluid connections). */
fun orientable(inst: MachineInstance): Boolean = connections(inst, 0, 0, 0, false).isNotEmpty()

data class Placement(val w: Int, val h: Int, val machines: List<PlacedMachine>) {
    fun rect(plan: ProductionPlan, m: PlacedMachine): Rect = plan.machines[m.id].let { Rect(m.x, m.y, it.w, it.h) }

    fun connections(plan: ProductionPlan, m: PlacedMachine): List<ConnTile> =
        connections(plan.machines[m.id], m.x, m.y, m.rot.ordinal, m.mirror)
}

/** Static tiles the placer and router must keep free: forbidden tiles and fixed port tiles inside the box. */
fun staticObstacles(config: RunConfiguration, plan: ProductionPlan, w: Int, h: Int): Set<Tile> {
    val out = HashSet<Tile>()
    config.forbiddenTiles.forEach { r -> Rect(r.x, r.y, r.w, r.h).tiles().filter { it.x in 0 until w && it.y in 0 until h }.forEach { out += it } }
    config.reservedRects(w, h).forEach { r -> r.tiles().filter { it.x in 0 until w && it.y in 0 until h }.forEach { out += it } }
    plan.ports.forEach { p -> p.specs.forEach { s -> if (s.x != null && s.y != null && s.x in 0 until w && s.y in 0 until h) out += Tile(s.x, s.y) } }
    return out
}

/** Candidate start tiles of a port in a W x H box, each with the direction that points out of the box (or null). */
fun portCandidates(specs: List<ConfigSource>, w: Int, h: Int, corners: Boolean = true): List<Pair<Tile, Dir?>> {
    val out = LinkedHashMap<Tile, Dir?>()
    fun edge(e: String, from: Int?, to: Int?) {
        when (e) {
            "north" -> range(from, to, w, corners).forEach { out.putIfAbsent(Tile(it, 0), Dir.N) }
            "south" -> range(from, to, w, corners).forEach { out.putIfAbsent(Tile(it, h - 1), Dir.S) }
            "west" -> range(from, to, h, corners).forEach { out.putIfAbsent(Tile(0, it), Dir.W) }
            "east" -> range(from, to, h, corners).forEach { out.putIfAbsent(Tile(w - 1, it), Dir.E) }
            "any" -> listOf("north", "east", "south", "west").forEach { edge(it, null, null) }
        }
    }
    specs.forEach { s ->
        if (s.edge != null) edge(s.edge, s.from, s.to)
        else out.putIfAbsent(Tile(s.x!!, s.y!!), s.dir?.let { Dir.fromName(it) })
    }
    return out.entries.map { it.key to it.value }
}

/**
 * The tiles of a side a port may use: [from]..[to] as given, else the whole side, or without its two corners when
 * [corners] is false (fluid ports: a corner belongs to two sides, and a pipe port's ring tile there may land on the
 * other side's ring; Py science 2's west pitch port came out on the north ring of a block flush with the factory's
 * top edge, outside the factory).
 */
private fun range(from: Int?, to: Int?, size: Int, corners: Boolean = true): IntRange {
    val trim = !corners && size >= 3
    val lo = from ?: if (trim) 1 else 0
    val hi = to ?: if (trim) size - 2 else size - 1
    return maxOf(0, lo)..minOf(size - 1, hi)
}

// ---- fixture files: hand placements for router tests ----

@Serializable
data class FixtureMachine(val recipe: String, val x: Int, val y: Int, val rot: String = "N")

@Serializable
data class PlacementFixture(val job: String, val w: Int, val h: Int, val machines: List<FixtureMachine>) {
    /** Matches fixture machines to plan instances by recipe, in order. */
    fun toPlacement(plan: ProductionPlan): Placement {
        val pool = plan.machines.groupBy { it.recipe }.mapValues { it.value.toMutableList() }
        val placed = machines.map { fm ->
            val inst: MachineInstance = pool[fm.recipe]?.removeFirstOrNull()
                ?: error("Fixture places more ${fm.recipe} machines than the plan has")
            PlacedMachine(inst.id, fm.x, fm.y, Dir.fromName(fm.rot))
        }
        val missing = pool.filterValues { it.isNotEmpty() }
        require(missing.isEmpty()) { "Fixture leaves machines unplaced: ${missing.mapValues { it.value.size }}" }
        return Placement(w, h, placed)
    }

    companion object {
        fun load(name: String): PlacementFixture = strictJson.decodeFromString(serializer(), readResourceOrFile(name))
    }
}
