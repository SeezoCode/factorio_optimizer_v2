package data

import geom.Dir
import geom.Tile

/**
 * Factorio 2.0 (Space Age data set) facts the generator relies on, keyed by entity name so tiers can be
 * swapped by config. Every value tagged VERIFY has not been confirmed in game yet; see the verify list in
 * the plan. Sources:
 *  - [W1] https://lua-api.factorio.com/latest/defines.html (16-way defines.direction)
 *  - [W2] https://wiki.factorio.com/Talk:Blueprint_string_format (wires tuple, wire_connector_id, version example)
 *  - [W3] https://lua-api.factorio.com/latest/concepts/BlueprintEntity.html (entity fields)
 *  - [W4] https://lua-api.factorio.com/latest/concepts/InfinityPipeFilter.html
 *  - [W5] https://lua-api.factorio.com/latest/concepts/BlueprintInfinityInventorySettings.html
 *  - [W6] https://wiki.factorio.com/Inserter_capacity_bonus_(research) (hand sizes per research level)
 *  - [P] base-game prototype data (data.raw) as remembered; VERIFY.
 */
object GameConstants {
    /** Floor tiles usable as markers (block borders, reserved areas). */
    val MARKER_TILES = listOf(
        "stone-path", "concrete", "hazard-concrete-left", "hazard-concrete-right",
        "refined-concrete", "refined-hazard-concrete-left", "refined-hazard-concrete-right",
    )

    /** major << 48 | minor << 32 | patch << 16 | build. 2.0.10.1 is the example in [W2]; newer games migrate it. */
    const val BLUEPRINT_VERSION: Long = (2L shl 48) or (0L shl 32) or (10L shl 16) or 1L

    /** Copper wire connector id for poles in the 2.0 "wires" tuple [a, 5, b, 5]. [W2] */
    const val WIRE_POLE_COPPER = 5
    /** Circuit connector ids of an entity with one connection point: red 1, green 2. [W2] VERIFY (v19) */
    const val WIRE_RED = 1
    const val WIRE_GREEN = 2
    /** Longest red or green wire between a storage tank, a chemical plant or a pole (tile centres). [P] VERIFY (v19) */
    const val CIRCUIT_WIRE_REACH = 9.0

    data class BeltTier(
        val key: String,
        val belt: String,
        val underground: String,
        val splitter: String,
        /** Tiles allowed between an underground entrance and its exit (prototype max_distance - 1). [P] */
        val maxUndergroundGap: Int,
        /** Items per second for the whole belt; each lane carries half. [P] */
        val throughput: Double,
    )

    val BELT_TIERS: Map<String, BeltTier> = listOf(
        BeltTier("basic", "transport-belt", "underground-belt", "splitter", 4, 15.0),
        BeltTier("fast", "fast-transport-belt", "fast-underground-belt", "fast-splitter", 6, 30.0),
        BeltTier("express", "express-transport-belt", "express-underground-belt", "express-splitter", 8, 45.0),
        BeltTier("turbo", "turbo-transport-belt", "turbo-underground-belt", "turbo-splitter", 10, 60.0),
    ).associateBy { it.key }

    fun beltTier(key: String): BeltTier = BELT_TIERS[key] ?: error("Unknown belt tier '$key' (known: ${BELT_TIERS.keys})")

    /** Which tier a belt-family entity name belongs to, or null. */
    fun tierOfEntity(name: String): BeltTier? =
        BELT_TIERS.values.firstOrNull { name == it.belt || name == it.underground || name == it.splitter }

    data class InserterSpec(
        val name: String,
        /** Tiles from the inserter's own tile to its pickup and drop tiles. In a blueprint the inserter's
         *  direction points at the pickup side (prototype pickup_position {0,-d}, insert_position {0,d+0.2}). [P] VERIFY */
        val reach: Int,
        val filterable: Boolean,
        /** Items/s chest to chest at hand size 1, no research. [P] VERIFY */
        val baseRate: Double,
        val bulk: Boolean = false,
        /** Relative cost used when picking the cheapest type that meets a rate. */
        val tierCost: Int,
    )

    val INSERTERS: Map<String, InserterSpec> = listOf(
        InserterSpec("inserter", 1, false, 0.83, tierCost = 1),
        InserterSpec("long-handed-inserter", 2, false, 1.2, tierCost = 2),
        InserterSpec("fast-inserter", 1, true, 2.31, tierCost = 3),
        // Same swing speed as the fast inserter; its larger hand comes from [handSizes].
        InserterSpec("bulk-inserter", 1, true, 2.31, bulk = true, tierCost = 4),
    ).associateBy { it.name }

    /** Hand sizes (non-bulk, bulk) per inserter capacity research level 0..7. [W6] */
    val HAND_SIZES: List<Pair<Int, Int>> = listOf(1 to 2, 1 to 3, 2 to 4, 2 to 5, 2 to 6, 2 to 8, 2 to 10, 3 to 12)

    fun handSizes(level: Int): Pair<Int, Int> = HAND_SIZES[level.coerceIn(0, HAND_SIZES.size - 1)]

    fun inserter(name: String): InserterSpec = INSERTERS[name] ?: error("Unknown inserter '$name' (known: ${INSERTERS.keys})")

    data class PoleSpec(
        val name: String,
        val size: Int,
        /** Half-width of the square supply area, measured from the pole centre. [P] VERIFY */
        val supplyHalf: Double,
        val wireReach: Double,
    )

    val POLES: Map<String, PoleSpec> = listOf(
        PoleSpec("small-electric-pole", 1, 2.5, 7.5),
        PoleSpec("medium-electric-pole", 1, 3.5, 9.0),
        PoleSpec("big-electric-pole", 2, 2.0, 30.0),
        PoleSpec("substation", 2, 9.0, 18.0),
    ).associateBy { it.name }

    fun pole(name: String): PoleSpec = POLES[name] ?: error("Unknown pole '$name' (known: ${POLES.keys})")

    /** 1x1 chests usable as sinks or harness sources (2.0 names). [W3] */
    val CHESTS = setOf(
        "wooden-chest", "iron-chest", "steel-chest",
        "passive-provider-chest", "active-provider-chest", "storage-chest", "requester-chest", "buffer-chest",
        "infinity-chest",
    )

    const val INFINITY_CHEST = "infinity-chest"
    const val INFINITY_PIPE = "infinity-pipe"

    /** Default size of non-machine entities (recipes.json lists only machines). */
    fun footprint(name: String): Pair<Int, Int> = when {
        POLES[name] != null -> POLES.getValue(name).size to POLES.getValue(name).size
        tierOfEntity(name)?.splitter == name -> 2 to 1
        else -> 1 to 1
    }

    /**
     * Assembling machines 2 and 3 have their fluid input in the middle of the side they face and the fluid
     * output in the middle of the opposite side. With direction north the input pipe goes on the tile above
     * the top-middle tile. [P] VERIFY (v9, v14)
     */
    const val FLUID_INPUT_ON_FACING_SIDE = true

    /** Lane an inserter drops onto when it stands in line with the belt (0 left, 1 right). [P] VERIFY (v2) */
    const val PARALLEL_DROP_LANE = 1

    /**
     * Lane (0 left, 1 right of [out]) an inserter swinging [swing] drops onto a belt that items enter heading
     * [into] and leave heading [out] (the same for a straight belt). Straight: the far lane, or
     * [PARALLEL_DROP_LANE] for an inserter in line with the belt. Curve: always the far side, so the inside lane
     * from outside the bend (the side straight ahead of [into], or behind [out]) and the outside lane from inside
     * it (long-handed, across the belt before or after the curve).
     */
    fun dropLane(swing: geom.Dir, into: geom.Dir, out: geom.Dir): Int {
        if (into == out || into == out.opposite) return when (swing) {
            out.cw -> 1          // coming from the belt's left, lands on the right lane
            out.ccw -> 0
            else -> PARALLEL_DROP_LANE
        }
        val inside = if (out == into.ccw) 0 else 1
        return if (swing == into.opposite || swing == out) inside else 1 - inside
    }

    // ---------------- pipes ----------------
    const val PIPE = "pipe"
    const val PIPE_TO_GROUND = "pipe-to-ground"
    /** Tiles allowed between two pipe-to-ground ends (max distance 10). [P] VERIFY (v12) */
    const val PIPE_TO_GROUND_GAP = 9

    // ---------------- storage tank ----------------
    const val STORAGE_TANK = "storage-tank"
    /** Fluid a storage tank holds; it reads out its contents on a circuit wire. [P] VERIFY (v19) */
    const val STORAGE_TANK_CAPACITY = 25000.0
    /**
     * A storage tank's (3 x 3) fluid connections: tile inside it (from its top-left) and the side it opens to. Facing
     * north the top-left corner opens north and west and the bottom-right corner south and east; facing east the
     * other two corners do. It has only those two orientations. [P] VERIFY (v19)
     */
    fun storageTankConnections(dir: Dir): List<Pair<Tile, Dir>> =
        if (dir.axis == 1) listOf(Tile(0, 0) to Dir.N, Tile(0, 0) to Dir.W, Tile(2, 2) to Dir.S, Tile(2, 2) to Dir.E)
        else listOf(Tile(2, 0) to Dir.N, Tile(2, 0) to Dir.E, Tile(0, 2) to Dir.S, Tile(0, 2) to Dir.W)

    // ---------------- machines ----------------
    enum class Flow { INPUT, OUTPUT }

    /**
     * A fluid connection of a machine facing north: the pipe tile just outside the machine, relative to the
     * machine's top-left tile, and the side of the machine it sits on. [index] is 1-based among connections
     * of the same flow, which is how recipes map fluids to connections (their fluidbox_index, else order).
     */
    data class FluidConnection(val flow: Flow, val index: Int, val dx: Int, val dy: Int, val side: Dir)

    data class MachineGeom(
        val name: String,
        val w: Int,
        val h: Int,
        val connections: List<FluidConnection>,
        /** Assembling machines expose their connections only while set to a recipe with fluids. */
        val connectionsOnlyWithFluidRecipe: Boolean,
        val burner: Boolean,
    )

    /** Fluid connections of base-game machines facing north (prototype fluid_boxes as remembered). [P] VERIFY (v9-v11, v14) */
    private val FLUID_TABLE: Map<String, Pair<List<FluidConnection>, Boolean>> = mapOf(
        "assembling-machine-2" to (assemblerFluids() to true),
        "assembling-machine-3" to (assemblerFluids() to true),
        "chemical-plant" to (listOf(
            FluidConnection(Flow.INPUT, 1, 0, -1, Dir.N), FluidConnection(Flow.INPUT, 2, 2, -1, Dir.N),
            FluidConnection(Flow.OUTPUT, 1, 0, 3, Dir.S), FluidConnection(Flow.OUTPUT, 2, 2, 3, Dir.S),
        ) to false),
        "oil-refinery" to (listOf(
            FluidConnection(Flow.INPUT, 1, 1, 5, Dir.S), FluidConnection(Flow.INPUT, 2, 3, 5, Dir.S),
            FluidConnection(Flow.OUTPUT, 1, 0, -1, Dir.N), FluidConnection(Flow.OUTPUT, 2, 2, -1, Dir.N),
            FluidConnection(Flow.OUTPUT, 3, 4, -1, Dir.N),
        ) to false),
        // Space Age, from the 2.0 prototypes (space-age/prototypes/entity/entities.lua), converted to north-facing
        // top-left-relative pipe tiles. All four expose them only while set to a recipe with fluids. VERIFY (v17)
        "foundry" to (listOf(
            FluidConnection(Flow.INPUT, 1, 1, 5, Dir.S), FluidConnection(Flow.INPUT, 2, 3, 5, Dir.S),
            FluidConnection(Flow.OUTPUT, 1, 1, -1, Dir.N), FluidConnection(Flow.OUTPUT, 2, 3, -1, Dir.N),
        ) to true),
        "electromagnetic-plant" to (listOf(
            FluidConnection(Flow.INPUT, 1, -1, 2, Dir.W), FluidConnection(Flow.INPUT, 2, 4, 1, Dir.E),
            FluidConnection(Flow.OUTPUT, 1, 2, 4, Dir.S), FluidConnection(Flow.OUTPUT, 2, 1, -1, Dir.N),
        ) to true),
        "cryogenic-plant" to (listOf(
            FluidConnection(Flow.INPUT, 1, 0, 5, Dir.S), FluidConnection(Flow.INPUT, 2, 2, 5, Dir.S), FluidConnection(Flow.INPUT, 3, 4, 5, Dir.S),
            FluidConnection(Flow.OUTPUT, 1, 0, -1, Dir.N), FluidConnection(Flow.OUTPUT, 2, 2, -1, Dir.N), FluidConnection(Flow.OUTPUT, 3, 4, -1, Dir.N),
        ) to true),
        "biochamber" to (listOf(
            FluidConnection(Flow.INPUT, 1, 0, -1, Dir.N), FluidConnection(Flow.INPUT, 2, 2, -1, Dir.N),
            FluidConnection(Flow.OUTPUT, 1, 2, 3, Dir.S), FluidConnection(Flow.OUTPUT, 2, 0, 3, Dir.S),
        ) to true),
    )

    /** Assembling machines expose their connections only while set to a recipe with fluids. */
    fun connectionsOnlyWithFluidRecipe(machine: String): Boolean = FLUID_TABLE[machine]?.second ?: false

    /** Fluid connections of a base machine facing north (empty for machines without fluids). */
    fun baseFluidConnections(machine: String): List<FluidConnection> = FLUID_TABLE[machine]?.first ?: emptyList()

    private fun assemblerFluids() = listOf(FluidConnection(Flow.INPUT, 1, 1, -1, Dir.N), FluidConnection(Flow.OUTPUT, 1, 1, 3, Dir.S))

    /** Footprints of crafting machines, for layouts read without recipes.json (decoded blueprints). */
    val MACHINE_SIZES: Map<String, Pair<Int, Int>> = mapOf(
        "assembling-machine-1" to (3 to 3), "assembling-machine-2" to (3 to 3), "assembling-machine-3" to (3 to 3),
        "stone-furnace" to (2 to 2), "steel-furnace" to (2 to 2), "electric-furnace" to (3 to 3),
        "chemical-plant" to (3 to 3), "oil-refinery" to (5 to 5), "centrifuge" to (3 to 3),
        "foundry" to (5 to 5), "electromagnetic-plant" to (4 to 4), "cryogenic-plant" to (5 to 5),
        "biochamber" to (3 to 3), "crusher" to (2 to 3), "recycler" to (2 to 4),
    )

    /** Machines whose blueprint entity carries a recipe (furnaces and the recycler pick theirs from their input). */
    fun takesRecipe(name: String) = !name.endsWith("furnace") && name != "recycler"

    /** Base-game crafting machines. */
    val BASE_MACHINES = listOf(
        "assembling-machine-1", "assembling-machine-2", "assembling-machine-3",
        "stone-furnace", "steel-furnace", "electric-furnace", "chemical-plant", "oil-refinery", "centrifuge",
    )

    /** Space Age crafting machines (the crusher and recycler have no fluid connections). */
    val SPACE_AGE_MACHINES = listOf("foundry", "electromagnetic-plant", "cryogenic-plant", "biochamber", "crusher", "recycler")

    val SUPPORTED_MACHINES = BASE_MACHINES + SPACE_AGE_MACHINES

    fun machineGeom(data: RootData, name: String): MachineGeom {
        val e = data.entities[name] ?: error("Unknown machine '$name'")
        val (conns, onlyFluid) = FLUID_TABLE[name] ?: (emptyList<FluidConnection>() to false)
        return MachineGeom(name, e.width, e.height, conns, onlyFluid, (e as? FurnaceEntity)?.energySource == "burner" ||
            (e as? AssemblingMachineEntity)?.energySource == "burner")
    }

    /**
     * Where a connection ends up for a machine of size [w] x [h] at rotation [rot] (clockwise quarter turns
     * from north) and optional horizontal mirroring (applied first, in the machine's own frame). [P] VERIFY
     */
    fun placeConnection(c: FluidConnection, w: Int, h: Int, rot: Int, mirror: Boolean): Pair<Int, Int> {
        // Work in doubled coordinates around the machine centre so odd and even sizes stay exact.
        var x = 2 * c.dx + 1 - w
        var y = 2 * c.dy + 1 - h
        if (mirror) x = -x
        repeat(((rot % 4) + 4) % 4) { val nx = -y; y = x; x = nx }
        val (rw, rh) = if (rot % 2 == 0) w to h else h to w
        return (x + rw - 1) / 2 to (y + rh - 1) / 2
    }

    fun placeSide(c: FluidConnection, rot: Int, mirror: Boolean): Dir {
        var d = c.side
        if (mirror && d.axis == 0) d = d.opposite
        repeat(((rot % 4) + 4) % 4) { d = d.cw }
        return d
    }
}
