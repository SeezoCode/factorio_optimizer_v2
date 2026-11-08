package config

import data.GameConstants
import data.readResourceOrFile
import data.loadRootData as loadGameData
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
    // transport-belt and a few other plates-only items are "pressing" in 2.0 data (2.1 lists such recipes under
    // several categories instead, "crafting" among them; a recipe is allowed when any of its categories is).
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
    // B14: dead-end splitter pickups (an inserter takes items off a splitter half that leads nowhere).
    put("B14", true)
}

/** In [RunConfiguration.machines]: the game data's best machine for every recipe category not covered. */
const val AUTO_MACHINES = "auto"

@Serializable
data class RunConfiguration(
    val engine: String = "sa",
    /**
     * The game data this job uses: a file written by the exporter mod (tools/factorio-exporter), e.g. with mods
     * loaded. Default: the built-in recipes.json. Its machines, belts, inserters and poles become known as the job
     * is read (see GameConstants.register).
     */
    val data: String? = null,
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
     *  electric-furnace, chemical-plant, oil-refinery, centrifuge. "auto" (anywhere in the list) adds, for every
     *  recipe category no listed machine crafts, the game data's best machine for it (see [machineTier]): electric
     *  machines and farms before burners; none with a fixed recipe or matching [skipRecipes]. For modded games. */
    val machines: List<String>? = null,
    /** Which machine "auto" picks per category: "fastest" (default) or "slowest" (the first tier of each building). */
    @SerialName("machine_tier") val machineTier: String = "fastest",
    /**
     * Recipes (and, for "auto", machines) whose name contains one of these are not used unless a recipe_override
     * names them: Pyanodon's upgrade variants ("-turd", unlocked only by its tech upgrade choices) and voiding
     * recipes ("pyvoid"). Hidden recipes (but recycling) are never used.
     */
    @SerialName("skip_recipes") val skipRecipes: List<String> = listOf("-turd", "pyvoid"),
    /**
     * A raw resource a recipe makes besides other products (Py's copper screening gives stone): "leave" (default)
     * sends it out of the block as surplus and feeds what needs the resource raw, since a by-product that shares its
     * consumers with raw supply backs up and stops the machine making it; "use" lets the planner use it.
     */
    @SerialName("raw_byproducts") val rawByproducts: String = "leave",
    /**
     * By-products going into or coming out of production loops (Py's farms) also get a 0/s output that takes what
     * their users leave (fluids through an overflow valve, when the data has one: Py's py-overflow-valve), so their
     * machine does not stop while a loop is not running yet. Only plans with loops; items only in port mode.
     * "fluids" (default: a valve on the pipe), "all" (items too: a belt past their users to a port of their own, which
     * in a packed block of a factory often cannot be routed), "off".
     */
    @SerialName("byproduct_overflow") val byproductOverflow: String = "fluids",
    /** Fuel for burner machines (stone and steel furnaces). */
    val fuel: String = "coal",
    /** Fuel fluid for machines that burn one (Py's glassworks); default: the raw fluid that holds the most energy. */
    @SerialName("fluid_fuel") val fluidFuel: String? = null,
    @SerialName("belt_tier") val beltTier: String = "fast",
    /**
     * Space Age belt stacking: items stack this high on belts (1: off; up to 4 with the belt capacity research). Every
     * inserter dropping onto a belt is then a stack inserter and the items coming in are taken as stacked, so a
     * lane carries this many times its items; items dropped by any other inserter take a whole slot each.
     */
    @SerialName("belt_stack_size") val beltStackSize: Int = 1,
    /** Making items of a better quality (see [QualityProductionConfig]); null: everything normal. */
    @SerialName("quality_production") val qualityProduction: QualityProductionConfig? = null,
    /** Gleba spoilage: where items that spoil leave what they spoil into (see [SpoilageConfig]). */
    val spoilage: SpoilageConfig = SpoilageConfig(),
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
    /** Recycling recipes (recyclers): off by default; see [RecyclingConfig]. */
    val recycling: RecyclingConfig = RecyclingConfig(),
    /** Aquilo heating with heat pipes: off by default; see [HeatConfig]. */
    val heat: HeatConfig = HeatConfig(),
    /** Surplus items that leave together on one belt (a sushi belt), see [MixedOutput]. */
    @SerialName("mixed_outputs") val mixedOutputs: List<MixedOutput> = emptyList(),
    /**
     * Every item that only leaves (not a target, not used inside: Py's dozens of by-products at a few hundredths per
     * second) goes out on one mixed belt, after the [mixedOutputs] groups, instead of a port and belt each.
     */
    @SerialName("byproducts_mixed") val byproductsMixed: Boolean = false,
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
    /** Modules in the machines and beacons around them (see [BeaconConfig]); null for none. */
    val beacons: BeaconConfig? = null,

    val search: SearchConfig = SearchConfig(),
    val score: ScoreConfig = ScoreConfig(),
    @SerialName("test_mode") val testMode: Boolean = false,
    /**
     * A job in one block whose machines a ready-made column holds (one recipe, see Templates.kt) starts with that
     * column as its best layout; the search then looks for smaller ones only. Factories of blocks: [BlocksConfig.templates].
     */
    val templates: Boolean = true,
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

    /** Quality of the machines, beacons and modules a blueprint of this job places ([quality] is the machines'). */
    fun buildQuality() = emit.BuildQuality(quality, beacons?.quality ?: "normal", moduleQuality(), fluids.pipe, fluids.pipeToGround)

    /** Quality of every module the job places. */
    fun moduleQuality(): String = qualityProduction?.moduleQuality ?: beacons?.moduleQuality ?: "normal"

    /** The underground tier used for weaving, or null when weaving is off. */
    fun weave(): GameConstants.BeltTier? = when {
        !trick("B5") || weaveTier == "none" -> null
        weaveTier == "auto" -> GameConstants.beltTier(when (beltTier) { "basic" -> "fast"; "fast" -> "express"; "express" -> "fast"; else -> "express" })
        else -> GameConstants.beltTier(weaveTier)
    }?.let { GameConstants.beltTier(it.key, beltStackSize) }

    fun validate() {
        // The CP-SAT engine was removed in v2; it lives on in github.com/SeezoCode/factorio_optimizer.
        require(engine == "sa") { "engine must be 'sa' (the CP-SAT engine is in github.com/SeezoCode/factorio_optimizer), got '$engine'" }
        val unknownTricks = tricks.keys - DEFAULT_TRICKS.keys
        require(unknownTricks.isEmpty()) { "Unknown tricks: $unknownTricks" }
        val notYet = tricks.filter { it.value && it.key in UNIMPLEMENTED_TRICKS }.keys
        require(notYet.isEmpty()) { "Tricks $notYet are planned for v2/v3 and not implemented yet" }
        GameConstants.beltTier(beltTier)
        (machines ?: listOf(machine)).filter { it != AUTO_MACHINES }.forEach { require(it in GameConstants.SUPPORTED_MACHINES) { "Unknown or unsupported machine '$it' (supported: ${GameConstants.SUPPORTED_MACHINES})" } }
        if (weaveTier != "auto" && weaveTier != "none") { require(weaveTier != beltTier) { "weave_tier must differ from belt_tier" }; GameConstants.beltTier(weaveTier) }
        inserters.allowed.forEach { GameConstants.inserter(it) }
        require(research.inserterCapacity in 0..7) { "research.inserter_capacity must be 0..7" }
        require(recycling.mode in setOf("off", "void", "full")) { "recycling.mode must be 'off', 'void' or 'full'" }
        require(heat.mode in setOf("off", "pipes")) { "heat.mode must be 'off' or 'pipes'" }
        require(heat.edge in setOf("any", "north", "east", "south", "west")) { "heat.edge must be north, east, south, west or any" }
        heat.lattice?.let { require(it >= 4) { "heat.lattice must be at least 4" } }
        mixedOutputs.flatMap { it.items }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.let { require(it.isEmpty()) { "items in more than one mixed output: $it" } }
        require(inserters.allowed.isNotEmpty()) { "inserters.allowed is empty" }
        require(beltStackSize in 1..GameConstants.MAX_BELT_STACK) { "belt_stack_size must be 1..${GameConstants.MAX_BELT_STACK}" }
        require(spoilage.mode in setOf("off", "drain")) { "spoilage.mode must be 'off' or 'drain'" }
        qualityProduction?.let { qp ->
            require(qp.leftovers in setOf("north", "east", "south", "west", "any")) { "quality_production.leftovers must be an edge" }
        }
        (listOf(quality) + listOfNotNull(beacons?.quality, beacons?.moduleQuality, qualityProduction?.moduleQuality)).forEach {
            require(it in GameConstants.QUALITIES) { "Unknown quality '$it' (known: ${GameConstants.QUALITIES})" }
        }
        require(spoilage.chest in GameConstants.CHESTS) { "Unknown chest '${spoilage.chest}'" }
        GameConstants.pole(power.pole)
        box.startSize?.let { require(it >= 3) { "box.start_size must be at least 3" } }
        box.maxSize?.let { require(it >= 3) { "box.max_size must be at least 3" } }
        if (box.startSize != null && box.maxSize != null) require(box.startSize <= box.maxSize) { "box.start_size must not exceed box.max_size" }
        require(sinks.mode in setOf("port", "chest")) { "sinks.mode must be 'port' or 'chest'" }
        require(sinks.chest in GameConstants.CHESTS) { "Unknown chest '${sinks.chest}'" }
        require(fluids.mode in setOf("pipes", "infinity-pipe", "manual")) { "fluids.mode must be 'pipes', 'infinity-pipe' or 'manual'" }
        require(power.mode in setOf("set-cover", "lattice", "none")) { "power.mode must be 'set-cover', 'lattice' or 'none'" }
        beacons?.let { b ->
            require(b.mode in setOf("lattice", "set-cover")) { "beacons.mode must be 'lattice' or 'set-cover'" }
            require(b.perMachine in 0..16) { "beacons.per_machine must be 0..16" }
            require(b.pitch == null || b.pitch >= 6) { "beacons.pitch must be at least 6" }
        }
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
            require(b.sizeBy in setOf("weight", "machines")) { "blocks.size_by must be 'weight' or 'machines'" }
            require(b.lanes == null || b.lanes >= 1) { "blocks.lanes must be at least 1" }
            require(b.parkAfter >= 1 && b.restitchShare >= 0) { "blocks.park_after must be at least 1, restitch_share not negative" }
            require(b.overflowCost >= 0 && b.hardMax >= 1.0) { "blocks.overflow_cost must not be negative, hard_max at least 1" }
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
        fun parse(text: String): RunConfiguration = strictJson.decodeFromString(serializer(), text).also { c ->
            // A job on exported (modded) data knows that data's machines before it is checked.
            c.data?.let { loadGameData(it) }
            c.validate()
        }
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

/**
 * Items that leave the block together on one belt (a sushi belt), each lane carrying any of them, instead of a belt
 * each: Fulgora's recyclers make a dozen products, and what the block does not use leaves this way. Only surplus
 * items no machine of the block uses go on it; each machine making some of them puts all of those on the belt with
 * one inserter (filtered to leave out its other products), and the belt ends at an output port on [edge]. Whatever
 * takes the belt must take all of it, or the machines feeding it stop.
 */
@Serializable
data class MixedOutput(val items: List<String>, val edge: String = "any")

/**
 * Aquilo heating. With mode "pipes" every entity that freezes (everything but chests and, unless [poles], electric
 * poles) must share an edge with a heat pipe, and every heat pipe must join the heat port on [edge] (one tile
 * outside the box, like an item port) through other heat pipes. Heat pipes are laid after routing on free tiles
 * (see power/Heat.kt); where belts wall an area off, the layout is routed again with room kept around what stayed
 * cold. Heat comes in through the ports (at most [ports], listed in the run's notes): bring it from your reactors or
 * heating towers.
 */
@Serializable
data class HeatConfig(
    val mode: String = "off",
    val edge: String = "any",
    val poles: Boolean = false,
    /** Most heat ports (each a heat pipe on the ring where you bring heat in): more reach areas belts wall off. */
    val ports: Int = 4,
    /**
     * Rows of heat pipe across the box every this many rows (null: none), laid before placement as on Aquilo: machines
     * stay off them, belts pass under them, and everything else branches off them, so belts wall off less.
     */
    val lattice: Int? = null,
)

/**
 * Recyclers. "off" (default): the planner never recycles. "void": only items in [voidItems] are recycled, into a
 * quarter of themselves (a recycler fed its own output destroys the item), and they may not leave the block: what is
 * left over of them is destroyed instead. "full": every recycling recipe (scrap on Fulgora, taking items back apart
 * into a quarter of their ingredients) plus the voiding of [voidItems]. Nothing else is ever destroyed, so a
 * surplus not listed still leaves through an output port. The recycler joins the job's machines when needed.
 */
@Serializable
data class RecyclingConfig(
    val mode: String = "off",
    @SerialName("void_items") val voidItems: List<String> = emptyList(),
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
    /**
     * Target block size. With [sizeBy] "weight" (default) a target, not a cap: machines count by how hard they are
     * to connect (inserters and pipe connections; a furnace with one in and one out counts least), a block aims at
     * max_machines average machines of the plan, may grow past that at a price ([overflowCost]) and never past
     * [hardMax] times it. With "machines", at most max_machines machines, whatever they are.
     */
    @SerialName("max_machines") val maxMachines: Int,
    @SerialName("size_by") val sizeBy: String = "weight",
    /**
     * Blocks searched side by side, each on its share of search.threads (default half the threads, at least 1).
     * Blocks are searched in turns: first one layout each and a preview factory, then smaller boxes, blocks that
     * set the factory's size first, blocks that stop improving parked.
     */
    val lanes: Int? = null,
    /** A block is parked after this many turns without a smaller or better layout (it keeps a fifth of its share of the time). */
    @SerialName("park_after") val parkAfter: Int = 4,
    /** Stitch a new preview once the blocks' total area has dropped by this share since the last one (and at most every 2 minutes). */
    @SerialName("restitch_share") val restitchShare: Double = 0.03,
    /** Price of a block over its target: this many ports for 20 % over, growing with the square. */
    @SerialName("overflow_cost") val overflowCost: Double = 1.0,
    /** No block weighs more than this times the target (size_by weight). */
    @SerialName("hard_max") val hardMax: Double = 1.5,
    /** Items to prefer cutting at (e.g. plates), on top of the split that minimises traffic between blocks. */
    @SerialName("split_at") val splitAt: List<String> = emptyList(),
    /** Corridor width between blocks in tiles (it widens by itself if the belts between blocks do not fit). */
    val corridor: Int = 4,
    /** Ignored: blocks are floorplanned in any arrangement now (kept so old job files load). */
    val rows: Int? = null,
    /** A link between two blocks above this share of a belt is split over several belts. */
    @SerialName("link_belt_share") val linkBeltShare: Double = 0.9,
    /**
     * With [sizeBy] "weight": a machine also weighs this much per belt's worth of items it takes or makes, so blocks of
     * machines that fill belts (foundries, beaconed assemblers) come out smaller, with fewer belts to route.
     */
    @SerialName("belt_weight") val beltWeight: Double = 0.0,
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
    /**
     * Whether the blocks' pole networks must be wired to each other. Off (for now): a factory whose blocks are powered
     * each on their own is kept, with a note to wire them by hand; wide corridors leave poles out of reach.
     */
    @SerialName("wire_blocks") val wireBlocks: Boolean = false,
    /** With search.stop_after: seconds blocks keep refining after each has a first layout, before the stitch (60). */
    @SerialName("refine_s") val refineS: Double? = null,
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
    /** Search time a shape variant gets at the same share as its block; then it is parked (a fifth of the share). Null: no cap. */
    @SerialName("shape_variant_s") val shapeVariantS: Double? = null,
    /**
     * Ready-made layouts for simple blocks (see Templates.kt; e.g. a column of furnaces with two belts down the middle),
     * offered to the floorplan next to the searched layouts: it picks whichever packs best, so nothing is forced.
     */
    val templates: Boolean = true,
    /**
     * Recipes with up to this many ingredients (1 to 4) that a machine column can hold are kept in blocks of their
     * own, so that those blocks get a column; recipes with more get one only where a block happens to hold them alone.
     */
    @SerialName("template_ingredients") val templateIngredients: Int = 4,
    /** ...and with at least this many machines (fewer gain little from a block of their own, which costs links). */
    @SerialName("template_min_machines") val templateMinMachines: Int = 4,
    /**
     * Columns side by side the split sizes such a recipe's blocks for (1 or 2): with 1 each block fits one column's
     * belts; with 2 a block may hold what a double column carries (fewer, larger blocks), which needs ports that
     * divide between the two columns, else the block is searched.
     */
    @SerialName("template_columns") val templateColumns: Int = 1,
    /**
     * Links of one item leaving a block on one side for other blocks share one output port (two or three, up to a belt's
     * worth): splitters right outside it give each link its own belt, and the block has fewer ports to reach.
     */
    @SerialName("shared_outputs") val sharedOutputs: Boolean = false,
    /** Search share of a block that has a template (it has a layout already, usually smaller than the search finds). */
    @SerialName("template_share") val templateShare: Double = 0.25,
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
     * budget is set aside for it). Pairs that share recipes or trade with each other go first, lightest first; a
     * merged block weighs at most twice the target ([mergeMaxMachines] machines with size_by machines, default
     * twice [maxMachines]).
     */
    val merge: Boolean = false,
    @SerialName("merge_share") val mergeShare: Double = 0.3,
    @SerialName("merge_max_machines") val mergeMaxMachines: Int? = null,
)

/**
 * Gleba spoilage. "off" (default): spoilage is not modelled. "drain": what spoils on a belt rides to its end, where a
 * filter inserter takes it into a [chest]; a machine whose ingredients, fuel or products spoil hands its output to a
 * belt through an inserter that takes the spoilage too (never straight into another machine, which would not take
 * it), and no dead-end splitter stub holds a spoiling item.
 */
@Serializable
data class SpoilageConfig(
    val mode: String = "off",
    val chest: String = "steel-chest",
)

/**
 * Quality production: targets may name a quality ("iron-gear-wheel@legendary"). Every machine making what they need
 * takes [modules] (quality modules), so its products come out of that quality or better by chance; with [recycle],
 * recyclers with [recyclerModules] take what is not wanted back to ingredients of the same quality or better, and the
 * planner loops items through them to climb. What leaves and is not a target (the other qualities) shares one mixed
 * belt on [leftovers]. See plan/Quality.kt.
 */
@Serializable
data class QualityProductionConfig(
    val modules: List<String> = List(4) { "quality-module-3" },
    val recycle: Boolean = true,
    @SerialName("recycler_modules") val recyclerModules: List<String> = List(4) { "quality-module-3" },
    @SerialName("module_quality") val moduleQuality: String = "normal",
    val leftovers: String = "east",
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
    /**
     * The pipe and pipe-to-ground the blueprint builds (routing plans with the base game's reach either way). A fluid
     * network stops working past its pipes' max_pipeline_extent: Pyanodon holds plain pipes to 320 tiles, so a big
     * factory's pipes between blocks need its "ht-pipes" / "ht-pipes-to-ground" (1280; they join plain pipes and machines).
     */
    val pipe: String = "pipe",
    @SerialName("pipe_to_ground") val pipeToGround: String = "pipe-to-ground",
)

@Serializable
data class PowerConfig(
    val mode: String = "set-cover",
    val pole: String = "medium-electric-pole",
    @SerialName("allow_outside_ring") val allowOutsideRing: Boolean = true,
)

/**
 * Modules and beacons, placed like electricity poles. Every machine that takes modules gets [machineModules] in
 * its own slots (one the recipe does not allow, such as productivity in a recipe that allows none, is replaced by
 * [fallbackModule] or left out), and machines of [recipes] (all that take beacon effects when null) are planned
 * with [perMachine] beacons of [modules] each, so fewer machines make the same rate. In "lattice" mode rows of
 * beacon spots are kept free before placement, every [pitch] tiles (from the machine size and [perMachine] when
 * null), and the placer pulls the machines next to them; after routing, beacons that serve nobody go, and any
 * machine still short gets beacons on free tiles, like set-cover poles. "set-cover" places them after routing
 * only, rerouting around machines left short, and falls back to the lattice.
 */
@Serializable
data class BeaconConfig(
    val mode: String = "lattice",
    val beacon: String = "beacon",
    val modules: List<String> = listOf("speed-module-3", "speed-module-3"),
    @SerialName("per_machine") val perMachine: Int = 2,
    /** Quality of the beacons: a better one passes on more (distribution effectivity +0.2 a level). */
    val quality: String = "normal",
    /** Quality of every module, in machines and beacons: a better one has larger bonuses, the same penalties. */
    @SerialName("module_quality") val moduleQuality: String = "normal",
    @SerialName("machine_modules") val machineModules: List<String> = emptyList(),
    @SerialName("fallback_module") val fallbackModule: String? = null,
    val recipes: List<String>? = null,
    val pitch: Int? = null,
)

@Serializable
data class SearchConfig(
    @SerialName("time_budget_s") val timeBudgetS: Double = 300.0,
    @SerialName("seeds_per_size") val seedsPerSize: Int = 3,
    val threads: Int = 4,
    val seed: Long = 12345,
    /** Print a line for every finished trial (how close it came and where), not just one per box size. */
    val verbose: Boolean = true,
    /** Stop after this many valid layouts (a block's improvements, or a factory's saves) instead of using the whole budget; 0: the budget. */
    @SerialName("stop_after") val stopAfter: Int = 0,
)

@Serializable
data class ScoreConfig(
    val belt: Double = 0.3,
    val underground: Double = 1.0,
    val inserter: Double = 0.2,
)
