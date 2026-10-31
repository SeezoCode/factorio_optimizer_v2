package config

import data.GameConstants
import data.readResourceOrFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Strict: unknown keys are an error, so typos in a config are caught. */
val strictJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

val DEFAULT_CATEGORIES = listOf(
    "crafting", "advanced-crafting", "electronics", "crafting-with-fluid", "electronics-with-fluid",
    // transport-belt and a few other plates-only items are "pressing" in the 2.0 data.
    "pressing",
)

/** Tricks from the plan's v2/v3 catalog that this version does not implement yet. */
val UNIMPLEMENTED_TRICKS = setOf("I10", "I11", "I12", "I13", "B7", "B8", "B10", "B11", "B12", "B13")

/** v1 tricks are on by default; later ones are off until implemented. */
val DEFAULT_TRICKS: Map<String, Boolean> = buildMap {
    listOf("I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9").forEach { put(it, true) }
    listOf("I10", "I11", "I12", "I13").forEach { put(it, false) }
    listOf("B1", "B2", "B3", "B4", "B5", "B6").forEach { put(it, true) }
    listOf("B7", "B8", "B10", "B11", "B12", "B13").forEach { put(it, false) }
    listOf("B9", "F1", "F2", "P1", "P2", "H1").forEach { put(it, true) }
}

@Serializable
data class RunConfiguration(
    val engine: String = "sa",
    val quality: String = "normal",
    val targets: List<ConfigTarget>,
    @SerialName("forbidden_recipes") val forbiddenRecipes: List<String> = emptyList(),
    /** Only recipes in these categories (default: whatever the machines can craft; the CP-SAT engine uses DEFAULT_CATEGORIES). */
    @SerialName("recipe_categories") val recipeCategories: List<String>? = null,
    @SerialName("recipe_overrides") val recipeOverrides: Map<String, String> = emptyMap(),
    /**
     * Items and fluids that may enter the block without a listed source. Default: the base game's mined and
     * pumped resources (ores, stone, coal, wood, crude oil, water, fish). An empty list means only [sources]
     * enter, as on a space platform fed by asteroid collectors.
     */
    @SerialName("raw_resources") val rawResources: List<String>? = null,

    val machine: String = "assembling-machine-3",
    /** Machines in preference order; the first that can craft a recipe's category makes it. Default: [machine],
     *  electric-furnace, chemical-plant, oil-refinery, centrifuge. */
    val machines: List<String>? = null,
    /** Fuel for burner machines (stone and steel furnaces). */
    val fuel: String = "coal",
    @SerialName("belt_tier") val beltTier: String = "fast",
    /**
     * Second underground tier for weaving (B5): undergrounds of different tiers do not pair, so one can pass
     * under a line the other already uses. "auto" (the next tier up, or fast under express), "none", or a tier.
     */
    @SerialName("weave_tier") val weaveTier: String = "auto",
    /**
     * Two items may share a belt, one per lane, when mostly the same machines consume both. Off by default
     * until the inserter drop-lane facts it relies on are checked in game (verify kit v2, v13).
     */
    @SerialName("lane_sharing") val laneSharing: Boolean = false,
    val inserters: InserterConfig = InserterConfig(),
    val research: ResearchConfig = ResearchConfig(),
    val tricks: Map<String, Boolean> = emptyMap(),

    val box: BoxConfig = BoxConfig(),
    @SerialName("forbidden_tiles") val forbiddenTiles: List<TileRect> = emptyList(),
    /** Legacy CP-SAT only: forbidden macro-grid cells. */
    @SerialName("forbidden_places") val forbiddenPlaces: List<ConfigCords> = emptyList(),
    val sources: List<ConfigSource> = emptyList(),
    /** Where products and surplus by-products leave the block (sinks.mode = port); whole edge when omitted. */
    val outputs: List<ConfigSource> = emptyList(),
    val sinks: SinkConfig = SinkConfig(),
    val fluids: FluidConfig = FluidConfig(),
    val power: PowerConfig = PowerConfig(),

    val search: SearchConfig = SearchConfig(),
    val score: ScoreConfig = ScoreConfig(),
    @SerialName("test_mode") val testMode: Boolean = false,
    /** Split a big job into blocks connected by belts (see [BlocksConfig]); null keeps one block. */
    val blocks: BlocksConfig? = null,
    /**
     * Empty areas kept free of everything (to build in by hand later): a size and either a fixed top-left
     * corner (x, y) or a box corner ("north-west", "north-east", "south-west", "south-east"; default
     * south-east, south-west, north-east, north-west in turn). In a factory of blocks they sit east of the blocks.
     */
    val reserved: List<ReservedArea> = emptyList(),
    /** Move input and output ports one tile out, onto the ring around the box, so they are easy to spot. */
    @SerialName("ports_on_ring") val portsOnRing: Boolean = true,
    /** Floor tile marking block borders and reserved areas in the blueprint, or "none". */
    @SerialName("marker_tile") val markerTile: String = "hazard-concrete-left",
    /**
     * Overrides for the tuning knobs (router, placer, search…), same layout as tuning.json, any subset, e.g.
     * `{"route": {"p_mult": 1.1}}`. `--print-tuning` lists every knob with its value.
     */
    val tuning: JsonObject? = null,
    @SerialName("debug_renders") val debugRenders: Boolean = false,
) {
    fun trick(id: String): Boolean = tricks[id] ?: DEFAULT_TRICKS[id] ?: error("Unknown trick '$id'")

    /** The underground tier used for weaving, or null when weaving is off. */
    fun weave(): GameConstants.BeltTier? = when {
        !trick("B5") || weaveTier == "none" -> null
        weaveTier == "auto" -> GameConstants.beltTier(when (beltTier) { "basic" -> "fast"; "fast" -> "express"; "express" -> "fast"; else -> "express" })
        else -> GameConstants.beltTier(weaveTier)
    }

    fun validate() {
        // The CP-SAT engine was removed in v2; it lives on in github.com/SeezoCode/factorio_optimizer.
        require(engine == "sa") { "engine must be 'sa' (the CP-SAT engine is in github.com/SeezoCode/factorio_optimizer), got '$engine'" }
        val unknownTricks = tricks.keys - DEFAULT_TRICKS.keys
        require(unknownTricks.isEmpty()) { "Unknown tricks: $unknownTricks" }
        val notYet = tricks.filter { it.value && it.key in UNIMPLEMENTED_TRICKS }.keys
        require(notYet.isEmpty()) { "Tricks $notYet are planned for v2/v3 and not implemented yet" }
        GameConstants.beltTier(beltTier)
        (machines ?: listOf(machine)).forEach { require(it in GameConstants.SUPPORTED_MACHINES) { "Unknown or unsupported machine '$it' (supported: ${GameConstants.SUPPORTED_MACHINES})" } }
        if (weaveTier != "auto" && weaveTier != "none") { require(weaveTier != beltTier) { "weave_tier must differ from belt_tier" }; GameConstants.beltTier(weaveTier) }
        inserters.allowed.forEach { GameConstants.inserter(it) }
        require(research.inserterCapacity in 0..7) { "research.inserter_capacity must be 0..7" }
        require(inserters.allowed.isNotEmpty()) { "inserters.allowed is empty" }
        GameConstants.pole(power.pole)
        box.startSize?.let { require(it >= 3) { "box.start_size must be at least 3" } }
        box.maxSize?.let { require(it >= 3) { "box.max_size must be at least 3" } }
        if (box.startSize != null && box.maxSize != null) require(box.startSize <= box.maxSize) { "box.start_size must not exceed box.max_size" }
        require(sinks.mode in setOf("port", "chest")) { "sinks.mode must be 'port' or 'chest'" }
        require(sinks.chest in GameConstants.CHESTS) { "Unknown chest '${sinks.chest}'" }
        require(fluids.mode in setOf("pipes", "infinity-pipe", "manual")) { "fluids.mode must be 'pipes', 'infinity-pipe' or 'manual'" }
        require(power.mode in setOf("set-cover", "lattice", "none")) { "power.mode must be 'set-cover', 'lattice' or 'none'" }
        sources.forEach { it.validate() }
        outputs.forEach { it.validate() }
        Tuning().withOverrides(tuning)   // unknown or mistyped tuning keys fail here, not mid-run
        reserved.forEach { r ->
            require(r.w >= 1 && r.h >= 1) { "reserved areas need w and h of at least 1" }
            require((r.x == null) == (r.y == null)) { "a reserved area needs both x and y, or neither" }
            r.corner?.let { require(it in RESERVED_CORNERS && r.x == null) { "reserved corner must be one of $RESERVED_CORNERS (and no x, y)" } }
        }
        require(markerTile == "none" || markerTile in GameConstants.MARKER_TILES) { "marker_tile must be \"none\" or one of ${GameConstants.MARKER_TILES}" }
        blocks?.let { b ->
            require(b.maxMachines >= 2) { "blocks.max_machines must be at least 2" }
            require(b.corridor >= 2) { "blocks.corridor must be at least 2" }
            require(b.linkCost > 0 && b.fluidLinkCost > 0 && b.blockCost >= 0) { "blocks.link_cost and fluid_link_cost must be positive, block_cost not negative" }
            require(b.aspectWeight >= 0 && b.wireWeight >= 0) { "blocks.aspect_weight and wire_weight must not be negative" }
            require(b.linkBeltShare > 0 && b.linkBeltShare <= 1) { "blocks.link_belt_share must be in (0, 1]" }
            require(b.splitAtWeight >= 0) { "blocks.split_at_weight must not be negative" }
            require(b.blockTimeShare > 0 && b.blockTimeShare <= 1) { "blocks.block_time_share must be in (0, 1]" }
            require(b.repackShare in 0.0..0.9 && b.mergeShare in 0.0..0.9 && b.repackShare + b.mergeShare < 0.95) { "blocks.repack_share and merge_share must leave time for the blocks" }
        }
    }

    companion object {
        fun parse(text: String): RunConfiguration = strictJson.decodeFromString(serializer(), text).also { it.validate() }
        fun load(name: String): RunConfiguration = parse(readResourceOrFile(name))
    }
}

@Serializable
data class ConfigTarget(val name: String, val amount: Double)

@Serializable
data class ConfigCords(val x: Long, val y: Long)

@Serializable
data class TileRect(val x: Int, val y: Int, val w: Int = 1, val h: Int = 1)

@Serializable
data class ReservedArea(val w: Int, val h: Int, val x: Int? = null, val y: Int? = null, val corner: String? = null)

val RESERVED_CORNERS = listOf("south-east", "south-west", "north-east", "north-west")

/** Where the reserved areas lie in a [w] x [h] box. */
fun RunConfiguration.reservedRects(w: Int, h: Int): List<geom.Rect> = reserved.mapIndexed { i, r ->
    if (r.x != null && r.y != null) geom.Rect(r.x, r.y, r.w, r.h)
    else when (r.corner ?: RESERVED_CORNERS[i % 4]) {
        "north-west" -> geom.Rect(0, 0, r.w, r.h)
        "north-east" -> geom.Rect(w - r.w, 0, r.w, r.h)
        "south-west" -> geom.Rect(0, h - r.h, r.w, r.h)
        else -> geom.Rect(w - r.w, h - r.h, r.w, r.h)
    }
}

/**
 * A raw input. Either a fixed tile ([x], [y], may lie one tile outside the box) or a segment of a box
 * edge ([edge] in north/east/south/west, tiles [from]..[to] along it; whole edge when omitted).
 */
@Serializable
data class ConfigSource(
    val name: String,
    val edge: String? = null,
    val from: Int? = null,
    val to: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    /**
     * With x and y: the direction that points out of the port (N, E, S, W). An input's first belt may not
     * head that way; an output's last belt must.
     */
    val dir: String? = null,
    /** Legacy CP-SAT only: distance weight. */
    val force: Double = 1.0,
) {
    fun validate() {
        require((edge != null) != (x != null && y != null)) { "Source '$name' needs either edge or x and y" }
        edge?.let { require(it in setOf("north", "east", "south", "west", "any")) { "Source '$name': unknown edge '$it'" } }
        dir?.let { require(edge == null && it in setOf("N", "E", "S", "W")) { "Source '$name': dir needs x and y and one of N, E, S, W" } }
    }
}

@Serializable
data class InserterConfig(
    val allowed: List<String> = listOf("inserter", "long-handed-inserter", "fast-inserter"),
    @SerialName("belt_pickup_derate") val beltPickupDerate: Double = 0.7,
)

/** Research that changes rates. Inserter capacity 0..7 sets hand sizes (normal 1-3, bulk 2-12). */
@Serializable
data class ResearchConfig(
    @SerialName("inserter_capacity") val inserterCapacity: Int = 0,
    /** Extra productivity per recipe from research (e.g. {"steel-plate": 0.2}), on top of the machine's own. */
    val productivity: Map<String, Double> = emptyMap(),
)

@Serializable
data class BoxConfig(
    val aspect: Double = 1.0,
    @SerialName("fixed_width") val fixedWidth: Int? = null,
    /** A fixed box height; the search then looks for the narrowest width (blocks fitted to a factory row). */
    @SerialName("fixed_height") val fixedHeight: Int? = null,
    /**
     * Box size the search tries first, as the side of a square of the box's area (the side itself for square
     * boxes). Default: max_size when set, else twice the estimate from machine area.
     */
    @SerialName("start_size") val startSize: Int? = null,
    /** Largest box size the search tries; it keeps trying new seeds there instead of growing past it. */
    @SerialName("max_size") val maxSize: Int? = null,
)

/**
 * A factory made of several blocks: the plan is split into blocks of at most [maxMachines] machines (by a cost on
 * the ports and belts between them, see blocks.PartitionModel), the blocks are floorplanned in any arrangement, each
 * is generated on its own with its ports facing what it trades with, and the belts and pipes between them are
 * routed through corridors. Jobs with fewer machines stay one block.
 */
@Serializable
data class BlocksConfig(
    @SerialName("max_machines") val maxMachines: Int,
    /** Items to prefer cutting at (e.g. plates), on top of the split that minimises traffic between blocks. */
    @SerialName("split_at") val splitAt: List<String> = emptyList(),
    /** Corridor width between blocks in tiles (it widens by itself if the belts between blocks do not fit). */
    val corridor: Int = 4,
    /** Ignored: blocks are floorplanned in any arrangement now (kept so old job files load). */
    val rows: Int? = null,
    /** A link between two blocks above this share of a belt is split over several belts. */
    @SerialName("link_belt_share") val linkBeltShare: Double = 0.9,
    /** Weight of [splitAt] items' links when deciding which machines share a block (1 = like any other item). */
    @SerialName("split_at_weight") val splitAtWeight: Double = 0.1,
    /** Price of a belt between blocks when splitting (a port on each block and a belt through the corridors). */
    @SerialName("link_cost") val linkCost: Double = 1.0,
    /** Price of a block's port on a fluid's pipe tree between blocks (pipes take more corridor than belts). */
    @SerialName("fluid_link_cost") val fluidLinkCost: Double = 3.0,
    /** Price of one more block, in links: fewer, fuller blocks unless splitting saves more links than this. */
    @SerialName("block_cost") val blockCost: Double = 4.0,
    /** Floorplan: how much a factory off the job's box aspect costs (area x weight x (ratio - 1)^2). */
    @SerialName("aspect_weight") val aspectWeight: Double = 0.5,
    /** Floorplan: price of one tile of belt between blocks, in tiles of factory area. */
    @SerialName("wire_weight") val wireWeight: Double = 2.0,
    /** Share of the time budget spent generating blocks; the rest is left for stitching them together. */
    @SerialName("block_time_share") val blockTimeShare: Double = 0.9,
    /**
     * What one block (or the factory edge) sends to several blocks may pass through them in a chain: the nearest
     * keeps its share and passes the rest on, so blocks inside the factory get plates from a neighbour, not each its
     * own belt from the edge. Off by default: on six science (from plates and from ore, 15 machines per block) the
     * belts passing through made blocks bigger than the corridor belts they saved (4.5% and 3.6% larger factories).
     */
    @SerialName("pass_through") val passThrough: Boolean = false,
    /** Two items going between the same two blocks, each fitting a lane, share one belt (one item per lane). */
    @SerialName("lane_pairs") val lanePairs: Boolean = true,
    /** Floorplan: blocks may be turned in quarter turns (their ports turn with them). */
    val rotate: Boolean = true,
    /**
     * Seconds spent compacting the stitched factory (squeezing corridor lines out and floorplanning again, belts
     * growing longer if need be); every smaller valid factory is saved on the way. Default: the time left for
     * stitching by [blockTimeShare], at least half a minute.
     */
    @SerialName("compact_s") val compactS: Double? = null,
    /**
     * Each block is also searched at its aspect times each of these (e.g. [0.6, 1.7]), and the floorplan picks among
     * all its shapes (and their turns): blocks whose shapes fit together leave fewer holes.
     */
    @SerialName("shape_variants") val shapeVariants: List<Double> = emptyList(),
    /** Seconds per shape variant (default: half the block's own share). */
    @SerialName("shape_variant_s") val shapeVariantS: Double? = null,
    /** Floorplans tried when stitching (each from another seed); the smallest valid factory wins. */
    @SerialName("floorplan_tries") val floorplanTries: Int = 3,
    /** Remove rows and columns that only straight belts and pipes cross from the stitched factory. */
    val compact: Boolean = true,
    /**
     * After the first factory: search blocks again at the shapes a floorplan of their real areas asks for, and let
     * the floorplan pick each block's shape among all it has; kept if the factory is smaller ([repackShare] of the
     * time budget).
     */
    val repack: Boolean = false,
    @SerialName("repack_share") val repackShare: Double = 0.25,
    /**
     * Then, until the time budget runs out: merge two neighbouring blocks into one, search it starting
     * from their current placements side by side, and keep it if the factory gets smaller ([mergeShare] of the
     * budget is set aside for it; a merged block has at most [mergeMaxMachines] machines, default twice
     * [maxMachines]).
     */
    val merge: Boolean = false,
    @SerialName("merge_share") val mergeShare: Double = 0.3,
    @SerialName("merge_max_machines") val mergeMaxMachines: Int? = null,
)

@Serializable
data class SinkConfig(
    /** "port": products leave on a belt (or pipe) at the box edge; "chest": an inserter into a chest per producer. */
    val mode: String = "port",
    val chest: String = "steel-chest",
)

@Serializable
data class FluidConfig(
    /** "pipes": route pipes from fluid ports and producers; "infinity-pipe": an infinity pipe on every used
     *  connection (quick tests); "manual": leave the connection tiles free. */
    val mode: String = "pipes",
    /**
     * Recipes that should only use what is left over of a fluid (recipe -> fluid): a storage tank on that fluid's
     * pipes and a red wire make them work only while the tank is more than [surplusLevel] full, when other machines
     * also use the fluid. By default the two cracking recipes. `{}` turns it off.
     */
    @SerialName("surplus_only") val surplusOnly: Map<String, String> = mapOf("heavy-oil-cracking" to "heavy-oil", "light-oil-cracking" to "light-oil"),
    @SerialName("surplus_level") val surplusLevel: Double = 0.5,
)

@Serializable
data class PowerConfig(
    val mode: String = "set-cover",
    val pole: String = "medium-electric-pole",
    @SerialName("allow_outside_ring") val allowOutsideRing: Boolean = true,
)

@Serializable
data class SearchConfig(
    @SerialName("time_budget_s") val timeBudgetS: Double = 300.0,
    @SerialName("seeds_per_size") val seedsPerSize: Int = 3,
    val threads: Int = 4,
    val seed: Long = 12345,
    /** Print a line for every finished trial (how close it came and where), not just one per box size. */
    val verbose: Boolean = true,
)

@Serializable
data class ScoreConfig(
    val belt: Double = 0.3,
    val underground: Double = 1.0,
    val inserter: Double = 0.2,
)
