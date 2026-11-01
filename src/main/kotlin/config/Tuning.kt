package config

import data.readResourceOrFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Experiment knobs. Defaults come from tuning.json (the measured values); a job may override any of them in
 * its own `tuning` section.
 */
@Serializable
data class Tuning(
    val planner: PlannerTuning = PlannerTuning(),
    val net: NetTuning = NetTuning(),
    val place: PlaceTuning = PlaceTuning(),
    val route: RouteTuning = RouteTuning(),
    val search: SearchTuning = SearchTuning(),
) {
    /** This tuning with [overrides] (same layout as tuning.json, any subset of keys) merged over it. */
    fun withOverrides(overrides: JsonObject?): Tuning {
        if (overrides == null || overrides.isEmpty()) return this
        fun merge(a: JsonElement, b: JsonElement): JsonElement =
            if (a is JsonObject && b is JsonObject) JsonObject(a + b.mapValues { (k, v) -> a[k]?.let { merge(it, v) } ?: v })
            else b
        val merged = merge(strictJson.encodeToJsonElement(serializer(), this), overrides)
        return try {
            strictJson.decodeFromJsonElement(serializer(), merged)
        } catch (e: Exception) {
            throw IllegalArgumentException("Bad tuning override (keys and layout as in tuning.json): ${e.message}", e)
        }
    }

    fun toJson(): String = strictJson.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): Tuning = strictJson.decodeFromString(serializer(), text)
        fun load(name: String): Tuning = parse(readResourceOrFile(name))
        /** tuning.json on the classpath when present (the measured defaults), else the built-in ones. */
        fun defaults(): Tuning = runCatching { load("tuning.json") }.getOrElse { Tuning() }
    }
}

/** Linear-program weights. Machines cost their area in assembler units (3 x 3 = 1) per machine. */
@Serializable
data class PlannerTuning(
    /** Per item/s supplied through a port. */
    @SerialName("supply_cost_item") val supplyCostItem: Double = 0.001,
    /** Per fluid unit/s supplied. */
    @SerialName("supply_cost_fluid") val supplyCostFluid: Double = 0.0001,
    /** Per item/s of a by-product leaving the block unused (cracking and recycling are usually cheaper). */
    @SerialName("surplus_cost_item") val surplusCostItem: Double = 10.0,
    @SerialName("surplus_cost_fluid") val surplusCostFluid: Double = 0.1,
    /**
     * Share of the tiles around a machine its belt inserters may take. A recipe whose machines would need more
     * gets more machines, each with a smaller share (fast machines with productivity need this most).
     */
    @SerialName("access_share") val accessShare: Double = 0.75,
)

@Serializable
data class NetTuning(
    /** Target belt utilisation when splitting an item over several belts. */
    @SerialName("belt_utilisation") val beltUtilisation: Double = 0.9,
)

@Serializable
data class PlaceTuning(
    val alpha: Double = 1.0,
    /** RUDY overflow weight. The plan started at 0.5; 20 measured best on T3/T4 (see TUNING.md). */
    val beta: Double = 20.0,
    /** Weight of the router's congestion map in the placer's cost after a failed route (1 measured best, NIGHT_LOG). */
    val beta2: Double = 1.0,
    /** Pin-access weight (plan start 4; 12 measured best). */
    val gamma: Double = 12.0,
    /** Direct-insertion bonus per belt inserter a fitting pair saves, in units of one belt tile. */
    val delta: Double = 6.0,
    /** Weight of the squared beacon shortfall per machine (beacons in lattice mode, see BeaconConfig). */
    @SerialName("beacon_weight") val beaconWeight: Double = 15.0,
    @SerialName("rudy_bin") val rudyBin: Int = 4,
    /**
     * Belt-access demand around machines, added to the RUDY bins: per belt terminal, its inserters plus
     * access_belt_tiles belt tiles, spread over a band access_range tiles wide around the machine and scaled
     * by access_demand (0 turns it off). Many-input machines then keep room for the belts that pass them.
     */
    @SerialName("access_demand") val accessDemand: Double = 1.0,
    @SerialName("access_belt_tiles") val accessBeltTiles: Double = 3.0,
    @SerialName("access_range") val accessRange: Int = 2,
    /** VPR crossing-count correction q(k): (terminal count, factor) points, interpolated linearly. */
    @SerialName("q_table") val qTable: List<List<Double>> = listOf(
        listOf(1.0, 1.0), listOf(3.0, 1.0), listOf(4.0, 1.0828), listOf(5.0, 1.1536), listOf(6.0, 1.2206),
        listOf(7.0, 1.2823), listOf(8.0, 1.3385), listOf(9.0, 1.3991), listOf(10.0, 1.4493), listOf(15.0, 1.6899),
        listOf(20.0, 1.8924), listOf(25.0, 2.0743), listOf(30.0, 2.2334), listOf(35.0, 2.3895),
        listOf(40.0, 2.5356), listOf(45.0, 2.6625), listOf(50.0, 2.7933),
    ),
    @SerialName("shift_share") val shiftShare: Double = 0.6,
    @SerialName("swap_share") val swapShare: Double = 0.3,
    @SerialName("t0_factor") val t0Factor: Double = 20.0,
    @SerialName("moves_per_temp_factor") val movesPerTempFactor: Double = 10.0,
    @SerialName("stop_factor") val stopFactor: Double = 0.005,
    @SerialName("target_acceptance") val targetAcceptance: Double = 0.44,
    @SerialName("max_moves") val maxMoves: Long = 2_000_000,
    /** Anneal budget per machine (the budget is the smaller of this times the machine count and max_moves). */
    @SerialName("moves_per_machine") val movesPerMachine: Long = 20_000,
    @SerialName("feedback_rounds") val feedbackRounds: Int = 3,
    /**
     * A trial whose best routing round ended within this many conflicts goes back to that round's placement
     * and retries up to near_miss_attempts times: each retry shifts one machine next to a remaining conflict
     * by a tile or two and routes again with a larger endgame (different shuffles each time).
     */
    @SerialName("near_miss_max") val nearMissMax: Int = 4,
    @SerialName("near_miss_attempts") val nearMissAttempts: Int = 4,
    /** Endgame budget multiplier (rounds and time) for near-miss retries. */
    @SerialName("near_miss_endgame_factor") val nearMissEndgameFactor: Double = 3.0,
    /**
     * A trial whose best round ended with more conflicts than a near miss but at most spread_max moves the
     * machines within spread_radius tiles of each conflict cluster one tile outward and routes again, up to
     * spread_attempts times (each building on the last if it got closer). Off by default (0): it did not raise
     * success rates and costs time (NIGHT_LOG sweep 4).
     */
    @SerialName("spread_max") val spreadMax: Int = 0,
    @SerialName("spread_attempts") val spreadAttempts: Int = 3,
    @SerialName("spread_radius") val spreadRadius: Int = 3,
    @SerialName("feedback_t0_divisor") val feedbackT0Divisor: Double = 20.0,
    /**
     * How a net's belt length is estimated: "hpwl" (bounding box of its machines times q(k)) or "path" (the
     * bounding box plus path_weight times the gaps a greedy path leaves between the machines' inserter rings,
     * so machines along one corridor cost no more than its length and scattered ones pay for every detour).
     */
    @SerialName("belt_cost") val beltCost: String = "hpwl",
    @SerialName("path_weight") val pathWeight: Double = 1.0,
    /**
     * Starting placement: "random", "columns" (machines of a recipe stacked flush in columns, column_gap tiles
     * of corridor between columns, refined at a low temperature so the structure stays), or "mixed" (columns on
     * even seeds, random on odd ones).
     */
    val initial: String = "random",
    @SerialName("column_gap") val columnGap: Int = 3,
    /** Free tiles between machines within a column (0: flush; 1 leaves a lane for belts crossing the column). */
    @SerialName("column_spacing") val columnSpacing: Int = 1,
    /** Share of annealing moves that shift or swap a whole column of machines (random starts). */
    @SerialName("column_share") val columnShare: Double = 0.1,
    /**
     * From a column start, only moves that keep columns: shift or swap whole columns, swap two machines, slide
     * a machine up or down its column, rotate a fluid machine in place. False (measured better on chemical
     * science, equal on job 9) lets single machines leave their column once annealing starts.
     */
    @SerialName("column_moves_only") val columnMovesOnly: Boolean = false,
    /** With column moves only: whether two machines of different columns may swap (mixing the columns' recipes). */
    @SerialName("column_swap_across") val columnSwapAcross: Boolean = true,
    /** Starting temperature when refining a column start, as a share of the sampled one. */
    @SerialName("column_t0_scale") val columnT0Scale: Double = 0.05,
    /** Largest shift of the first refinement moves from a column start, in tiles. */
    @SerialName("column_start_range") val columnStartRange: Int = 3,
) {
    /** Crossing-count correction q(k), interpolated linearly in the table, extended linearly past its end. */
    fun q(k: Int): Double {
        val t = qTable
        if (k <= t.first()[0]) return t.first()[1]
        for (i in 1 until t.size) {
            if (k <= t[i][0]) {
                val (k0, q0) = t[i - 1]; val (k1, q1) = t[i]
                return q0 + (q1 - q0) * (k - k0) / (k1 - k0)
            }
        }
        val (kl, ql) = t.last()
        return ql + (k - kl) * 0.02616
    }
}

@Serializable
data class RouteTuning(
    @SerialName("belt_base") val beltBase: Double = 1.0,
    @SerialName("turn_penalty") val turnPenalty: Double = 0.2,
    @SerialName("ug_penalty") val ugPenalty: Double = 2.0,
    @SerialName("ug_penalty_per_tile") val ugPenaltyPerTile: Double = 0.1,
    @SerialName("inserter_base") val inserterBase: Double = 0.5,
    @SerialName("long_inserter_base") val longInserterBase: Double = 0.6,
    @SerialName("chest_base") val chestBase: Double = 0.5,
    @SerialName("pin_reservation") val pinReservation: Double = 1.5,
    /** Two chains share a belt only if their consumer machines overlap at least this much (shared / all). */
    @SerialName("lane_share_overlap") val laneShareOverlap: Double = 0.5,
    /** Extra cost of an underground pair of the weaving tier over one of the main tier (B5). */
    @SerialName("weave_penalty") val weavePenalty: Double = 1.0,
    /** Extra cost of a splitter that starts a belt branch (B9). */
    @SerialName("splitter_cost") val splitterCost: Double = 2.0,
    /** While visiting producers, extra cost on the access tiles of the same net's consumers. */
    @SerialName("own_consumer_reservation") val ownConsumerReservation: Double = 3.0,
    /** Start producers tried per net and iteration before the net counts as unrouted. */
    @SerialName("start_attempts") val startAttempts: Int = 3,
    /** Leg undo-and-retry steps per net attempt when the chain gets trapped. */
    @SerialName("max_backtracks") val maxBacktracks: Int = 12,
    /** Nets needing more inserter visits than this are split into several belt chains. */
    @SerialName("max_chain_visits") val maxChainVisits: Int = 12,
    /** A* reruns per leg after forbidding a state whose path crossed itself. */
    @SerialName("repair_restarts") val repairRestarts: Int = 40,
    @SerialName("dead_end_conflict") val deadEndConflict: Double = 1.0,
    @SerialName("p_fac_first") val pFacFirst: Double = 0.5,
    @SerialName("p_mult") val pMult: Double = 1.1,
    /** Cap on the present-congestion factor (VPR's max_pres_fac). */
    @SerialName("p_fac_max") val pFacMax: Double = 1000.0,
    @SerialName("h_fac") val hFac: Double = 0.5,
    @SerialName("max_iterations") val maxIterations: Int = 120,
    @SerialName("stagnation_stop") val stagnationStop: Int = 40,
    @SerialName("full_reroute_iterations") val fullRerouteIterations: Int = 5,
    /** Weighted A* factor; 1.0 keeps the heuristic admissible. */
    @SerialName("heuristic_weight") val heuristicWeight: Double = 1.2,
    /** Reroutes first try the chain's previous terminal order. */
    @SerialName("keep_chain_order") val keepChainOrder: Boolean = false,
    /** Log a per-iteration overuse breakdown. */
    @SerialName("trace_detail") val traceDetail: Boolean = false,
    /** Allow a belt directly after an underground exit to turn. VERIFY in game. */
    @SerialName("turn_after_ug_exit") val turnAfterUgExit: Boolean = true,
    /** Endgame (LNS) after negotiation stalls: rounds of ripping up and rerouting everything near the conflicts. */
    @SerialName("endgame_rounds") val endgameRounds: Int = 30,
    /** Half-width of the square ripped up around each conflict; grows by 2 every 4 rounds. */
    @SerialName("endgame_radius") val endgameRadius: Int = 3,
    /** Negotiation iterations among the rerouted nets per endgame round. */
    @SerialName("endgame_iterations") val endgameIterations: Int = 4,
    /** The endgame runs only when negotiation ends this close to a solution. */
    @SerialName("endgame_max_overuse") val endgameMaxOveruse: Int = 150,
    /** ... or within this many conflicts per belt chain, whichever is larger (big blocks have more). */
    @SerialName("endgame_overuse_per_chain") val endgameOverusePerChain: Double = 2.0,
    @SerialName("endgame_max_unrouted") val endgameMaxUnrouted: Int = 4,
    /** Weight of each conflict left at the end in the congestion map handed back to the placer. */
    @SerialName("feedback_overuse_weight") val feedbackOveruseWeight: Double = 2.0,
    /** The endgame stops starting rounds after this multiple of the negotiation's own time. */
    @SerialName("endgame_time_factor") val endgameTimeFactor: Double = 2.0,
    /** A net's flow above this share of a belt is split over several belt chains. */
    @SerialName("chain_belt_share") val chainBeltShare: Double = 0.9,
    /** A chain's flow may use up to this share of the weaving (second) underground tier's throughput. */
    @SerialName("weave_belt_share") val weaveBeltShare: Double = 0.98,
    /** How many tile conflicts one unrouted chain counts as when comparing routing iterations and rounds. */
    @SerialName("unrouted_penalty") val unroutedPenalty: Int = 10,
    /** Search states one path search (belt or pipe) may expand before it gives up. */
    @SerialName("max_expansions") val maxExpansions: Int = 3_000_000,
)

@Serializable
data class SearchTuning(
    /** s_lo = sqrt(machine area * factor). */
    @SerialName("area_factor") val areaFactor: Double = 1.8,
    @SerialName("warm_t0_divisor") val warmT0Divisor: Double = 10.0,
    /** Extra seed rounds at the smallest feasible size (and the one below) once the interval closes. */
    @SerialName("extra_rounds") val extraRounds: Int = 1000,
    /** Until a first success the box grows by this factor (a failure is usually a routing dead end, not a lack of room). */
    @SerialName("grow_factor") val growFactor: Double = 1.25,
    /** Seed rounds at each size before growing the box. */
    @SerialName("grow_rounds") val growRounds: Int = 2,
)
