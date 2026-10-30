package search

import config.RunConfiguration
import config.Tuning
import config.reservedRects
import data.GameConstants
import data.RootData
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import geom.borderTiles
import place.Annealer
import place.PlaceModel
import place.PlacedMachine
import place.Placement
import place.staticObstacles
import plan.ProductionPlan
import plan.Role
import plan.inserterRate
import power.buildHarness
import power.placePoles
import power.poleCandidates
import power.poleLattice
import route.RingedPorts
import route.RouteProblem
import route.RouteResult
import route.ringPorts
import route.route
import validate.MachineSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.ValidationError
import validate.ValidationSpec
import validate.recipeInfoFrom
import validate.validate
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the validator should expect of this plan on this placement. */
fun validationSpec(
    data: RootData,
    plan: ProductionPlan,
    config: RunConfiguration,
    placement: Placement,
    sources: List<SourceSpec>,
    requirePower: Boolean,
    outsideAllowed: Set<Tile> = emptySet(),
    outputs: List<OutputSpec> = emptyList(),
): ValidationSpec {
    val recipeInfo = recipeInfoFrom(data)
    val machines = placement.machines.associate { pm ->
        val inst = plan.machines[pm.id]
        val info = recipeInfo(inst.recipe)!!
        // Inserters move only item products; fluids leave through pipes.
        val items = inst.itemProducts
        Tile(pm.x, pm.y) to MachineSpec(
            product = items.maxByOrNull { it.value }?.key ?: inst.product,
            // Fuel counts as an ingredient (coal for stone furnaces, nutrients for biochambers).
            ingredients = info.ingredients + inst.demand.keys,
            outputRate = items.values.sum(),
            sinkRate = plan.terminals.filter { it.machine == pm.id && it.role == Role.SINK }.sumOf { it.rate },
            demand = inst.demand,
            needsFluid = inst.needsFluid,
            fluidIn = inst.fluidIn,
            fluidOut = inst.fluidOut,
            products = items.takeIf { it.size > 1 } ?: emptyMap(),
            sinks = plan.terminals.filter { it.machine == pm.id && it.role == Role.SINK }.groupBy { it.item }.mapValues { (_, ts) -> ts.sumOf { it.rate } },
        )
    }
    val sourceOutside = (sources.map { it.tile } + outputs.map { it.tile }).filter { it.x !in 0 until placement.w || it.y !in 0 until placement.h }
    return ValidationSpec(
        machines = machines,
        sources = sources,
        outputs = outputs,
        poleRing = if (config.power.allowOutsideRing) 1 else 0,
        outsideAllowed = outsideAllowed + sourceOutside,
        requirePower = requirePower,
        fluidMode = config.fluids.mode,
        checkRates = true,
        inserterRate = { name, fromBelt -> inserterRate(name, config, fromBelt) },
        recipeInfo = recipeInfo,
    )
}

fun layoutOf(placement: Placement, entities: List<PlacedEntity>, wires: List<Pair<Int, Int>> = emptyList(), label: String = "factorio-optimizer") =
    Layout(placement.w, placement.h, entities, wires, label)

/** score = W * H + lambda * belts + mu * undergrounds + nu * inserters (lower is better). */
fun score(layout: Layout, config: RunConfiguration): Double =
    layout.w.toDouble() * layout.h +
        config.score.belt * layout.count(Kind.BELT) +
        config.score.underground * (layout.count(Kind.UG_IN) + layout.count(Kind.UG_OUT)) +
        config.score.inserter * layout.count(Kind.INSERTER)

class TrialResult(
    val success: Boolean,
    val w: Int,
    val h: Int,
    val seed: Long,
    val layout: Layout?,
    val placement: Placement?,
    val errors: List<ValidationError>,
    val route: RouteResult?,
    val score: Double,
    /** Where it stopped: "placement", "route", "power", "validate" or "ok". */
    val stage: String,
    val annealT0: Double,
    val millis: Long,
    val notes: List<String>,
    /** Where inputs enter and outputs leave the finished layout (on the ring when ports_on_ring is set). */
    val sources: List<SourceSpec> = emptyList(),
    val outputs: List<OutputSpec> = emptyList(),
    /** How routing went, for progress output. */
    val summary: AttemptSummary? = null,
) {
    val area get() = w * h
}

/** A trial's routing at a glance: how many rounds it routed and what its closest round still had wrong. */
data class AttemptSummary(
    /** Routing runs: placement feedback rounds plus spread and near-miss retries. */
    val rounds: Int,
    /** Tile conflicts left in the closest run (0 when it routed). */
    val overuse: Int,
    /** Plan nets the closest run left unrouted. */
    val failedNets: List<Int>,
    /** The closest run's conflict tiles and the plan nets that meet on each. */
    val hotspots: Map<Tile, Set<Int>>,
)

/**
 * One trial at a box size: place (Stage 2), route with up to N feedback rounds (Stage 3), harness and
 * power (Stage 4), validate. [warm] seeds the placement (scaled from a larger box); [warmT0] then replaces
 * the sampled starting temperature.
 */
fun runTrial(
    data: RootData,
    plan: ProductionPlan,
    config: RunConfiguration,
    tuning: Tuning,
    w: Int,
    h: Int,
    seed: Long,
    warm: Placement? = null,
    warmT0: Double? = null,
    deadline: Long = Long.MAX_VALUE,
    lattice: Boolean = config.power.mode == "lattice",
    /** Per-iteration router renders go here when set. */
    debugDir: java.io.File? = null,
): TrialResult {
    val t0 = System.currentTimeMillis()
    val notes = ArrayList<String>()
    val latticeTiles = if (lattice) poleLattice(w, h, config.power.pole) else emptyList()
    val poleSize = GameConstants.pole(config.power.pole).size
    val latticeCover = latticeTiles.flatMap { t -> (0 until poleSize).flatMap { dy -> (0 until poleSize).map { dx -> Tile(t.x + dx, t.y + dy) } } }
    val obstacles = staticObstacles(config, plan, w, h) + latticeCover
    val model = PlaceModel(plan, config, tuning.place, w, h, obstacles)
    val rnd = SplittableRandom(seed)
    val annealer = Annealer(model, rnd, tuning.place)

    // Every routing run counts towards the summary; the closest one (fewest conflicts, unrouted chains weighted)
    // is what the progress output shows.
    var runs = 0
    var closest: RouteResult? = null
    fun weight(r: RouteResult) = r.overuse + tuning.route.unroutedPenalty * r.failedNets.size
    fun consider(r: RouteResult) { runs++; if (closest.let { it == null || weight(r) < weight(it) }) closest = r }
    fun summary() = closest?.let { c -> AttemptSummary(runs, c.overuse.coerceAtLeast(0), c.failedNets, c.conflictNets) }

    fun fail(stage: String, route: RouteResult? = null, errors: List<ValidationError> = emptyList(), layout: Layout? = null) =
        TrialResult(false, w, h, seed, layout, if (model.placed) model.placement() else null, errors, route, Double.MAX_VALUE, stage,
            annealer.lastT0, System.currentTimeMillis() - t0, notes, summary = summary())

    // Column starts (all seeds, or even ones with "mixed") are refined cool so their structure survives; the
    // others start warm from a larger box's best placement when there is one, else at random.
    val pt = tuning.place
    require(pt.initial in setOf("random", "columns", "mixed")) { "place.initial must be random, columns or mixed" }
    val columns = (pt.initial == "columns" || (pt.initial == "mixed" && seed % 2 == 0L)) && annealer.initialColumns(plan)
    val warmed = !columns && warm != null && annealer.initialFrom(legalize(warm, model))
    val started = columns || warmed || annealer.initialRandom() || annealer.initialRows(plan)
    if (!started) return fail("placement")
    if (columns) notes += "column start"
    val budget = maxMoves(plan, tuning)
    when {
        columns -> annealer.anneal(maxMoves = budget, startRange = pt.columnStartRange, refine = true, t0Scale = pt.columnT0Scale)
        // A given placement without a temperature of its own (merged factory blocks): refine it, keep its shape.
        warmed && warmT0 == null -> annealer.anneal(maxMoves = budget, startRange = 3, refine = true, t0Scale = pt.columnT0Scale)
        else -> annealer.anneal(t0 = if (warmed && warmT0 != null) warmT0 / tuning.search.warmT0Divisor else null, maxMoves = budget, startRange = if (warmed) 4 else null)
    }

    // Routing with feedback to the placer.
    var routed: RouteResult? = null
    var history = HashMap<Tile, Double>()
    // The round that came closest, kept so a near miss is not lost to the next re-anneal.
    var bestPlacement: Placement? = null
    var bestScore = Int.MAX_VALUE
    var bestConflicts: List<Tile> = emptyList()
    for (round in 0..tuning.place.feedbackRounds) {
        if (System.currentTimeMillis() > deadline) return fail("route", routed)
        routed = route(RouteProblem(plan, config, tuning.route, model.placement(), obstacles,
            debugDir = debugDir?.let { java.io.File(it, "round$round") }))
        consider(routed)
        if (routed.success) break
        val sc = routed.overuse + tuning.route.unroutedPenalty * routed.failedNets.size
        if (sc < bestScore) { bestScore = sc; bestPlacement = model.placement(); bestConflicts = routed.conflicts }
        notes += "round $round: overuse ${routed.overuse}, failed nets ${routed.failedNets} ${routed.log.takeLast(3)}"
        if (round == tuning.place.feedbackRounds) break
        routed.history.forEach { (t, v) -> history.merge(t, v, Double::plus) }
        model.setHistory(history, tuning.place.beta2)
        annealer.anneal(t0 = annealer.lastT0 / tuning.place.feedbackT0Divisor, maxMoves = budget / 2, startRange = 3)
    }
    var result = routed!!
    // Not close yet: spread the machines around the remaining conflicts apart and route again.
    if (!result.success && bestPlacement != null && bestScore > tuning.place.nearMissMax && bestScore <= tuning.place.spreadMax) {
        for (attempt in 1..tuning.place.spreadAttempts) {
            if (System.currentTimeMillis() > deadline || !annealer.initialFrom(bestPlacement!!)) break
            val moved = spreadAround(model, bestConflicts, tuning.place.spreadRadius)
            if (moved == 0) break
            val again = route(RouteProblem(plan, config, tuning.route, model.placement(), obstacles, endgameSeed = 100L + attempt))
            consider(again)
            notes += "spread retry $attempt ($bestScore): moved $moved machines, overuse ${again.overuse}, failed nets ${again.failedNets}"
            result = again
            if (again.success) break
            val sc = again.overuse + tuning.route.unroutedPenalty * again.failedNets.size
            if (sc < bestScore) { bestScore = sc; bestPlacement = model.placement(); bestConflicts = again.conflicts }
        }
    }
    if (!result.success && bestScore <= tuning.place.nearMissMax && bestPlacement != null) {
        val f = tuning.place.nearMissEndgameFactor
        val rt = tuning.route.copy(endgameRounds = (tuning.route.endgameRounds * f).toInt(), endgameTimeFactor = tuning.route.endgameTimeFactor * f)
        for (attempt in 1..tuning.place.nearMissAttempts) {
            if (System.currentTimeMillis() > deadline || !annealer.initialFrom(bestPlacement)) break
            val moved = nudgeNear(model, bestConflicts, rnd)
            val again = route(RouteProblem(plan, config, rt, model.placement(), obstacles, endgameSeed = attempt.toLong()))
            consider(again)
            notes += "near miss ($bestScore) retry $attempt${moved?.let { " moving machine $it" } ?: ""}: overuse ${again.overuse}, failed nets ${again.failedNets}"
            result = again
            if (again.success) break
        }
    }
    if (!result.success) return fail("route", result)
    val placement = model.placement()

    // Harness (test mode) and power, with up to two warm reroutes that keep tiles free near unpowered entities.
    var bias = HashMap<Tile, Double>()
    for (attempt in 0..2) {
        val ringed = if (config.portsOnRing) ringPorts(result.entities, result.sources, result.outputs, w, h, GameConstants.beltTier(config.beltTier).belt)
            else RingedPorts(result.entities, result.sources, result.outputs)
        val harness = if (config.testMode) buildHarness(ringed.sources, ringed.entities, w, h, config, ringed.outputs) else null
        harness?.warnings?.let { notes += it }
        val entities = ringed.entities + (harness?.entities ?: emptyList())
        val outside = harness?.tiles ?: emptySet()
        val sources = if (config.testMode) emptyList() else ringed.sources
        val (poles, wires, ok, uncovered) = when (config.power.mode) {
            "none" -> Quad(emptyList(), emptyList(), true, emptyList())
            else -> if (lattice) {
                val ps = placePoles(entities, emptyList(), config.power.pole, fixedPoles = latticeTiles)
                Quad(ps.poles, ps.wires, ps.ok, ps.uncovered.map { it.tile })
            } else {
                val extra = outside.flatMap { t -> (-2..2).flatMap { dy -> (-2..2).map { dx -> Tile(t.x + dx, t.y + dy) } } }
                val keepFree = config.reservedRects(w, h).flatMap { it.tiles() }.toSet()
                val ps = placePoles(entities, poleCandidates(w, h, config.power.allowOutsideRing, extra).filter { it !in keepFree }, config.power.pole)
                Quad(ps.poles, ps.wires, ps.ok, ps.uncovered.map { it.tile })
            }
        }
        if (!ok && config.power.mode == "set-cover" && !lattice && attempt < 2) {
            notes += "power attempt $attempt: ${uncovered.size} uncovered"
            uncovered.forEach { u -> for (dy in -3..3) for (dx in -3..3) bias.merge(Tile(u.x + dx, u.y + dy), 0.5, Double::plus) }
            val again = route(RouteProblem(plan, config, tuning.route, placement, obstacles, tileBias = bias, warmHistory = result.rawHistory))
            if (again.success) result = again
            continue
        }
        if (!ok && config.power.mode == "set-cover" && !lattice) {
            notes += "set cover failed twice; falling back to the pole lattice"
            return runTrial(data, plan, config, tuning, w, h, seed, placement, annealer.lastT0, deadline, lattice = true, debugDir = debugDir)
        }
        val offset = entities.size
        val all = entities + poles
        val marks = if (config.markerTile == "none") emptyList() else config.reservedRects(w, h).flatMap { borderTiles(it, config.markerTile) }
        val layout = Layout(w, h, all, wires.map { (a, b) -> a + offset to b + offset }, "factorio-optimizer ${w}x$h seed $seed", marks)
        // Outside the box: harness, poles in the ring, and ring ports (in test mode the inputs are fed, not listed).
        val outsideAllowed = outside + poles.flatMap { it.rect.tiles() }.filter { it.x !in 0 until w || it.y !in 0 until h } +
            ringed.sources.map { it.tile }
        val spec = validationSpec(data, plan, config, placement, sources, requirePower = config.power.mode != "none", outsideAllowed = outsideAllowed,
            outputs = ringed.outputs)
        // A port that must be one belt (a link between blocks of a factory) is one belt: its other end is one belt.
        val split = (ringed.sources.map { it.port to it.tile } + ringed.outputs.map { it.port to it.tile })
            .filter { (p, _) -> p >= 0 && plan.ports[p].single }.groupBy({ it.first }, { it.second })
            .filter { (_, ts) -> ts.distinct().size > 1 }
            .map { (p, ts) -> ValidationError(validate.ErrorType.UNSUPPORTED, ts.first(), "${plan.ports[p].item} must be one belt at its port, uses ${ts.distinct().size}") }
        val errors = validate(layout, spec) + split
        if (!ok) return fail("power", result, errors, layout)
        if (errors.isNotEmpty()) return fail("validate", result, errors, layout)
        return TrialResult(true, w, h, seed, layout, placement, errors, result, score(layout, config), "ok", annealer.lastT0,
            System.currentTimeMillis() - t0, notes, ringed.sources, ringed.outputs, summary())
    }
    return fail("power", result)
}

/**
 * Moves the machines within [radius] tiles of each cluster of [conflicts] one tile away from the cluster's
 * centre (two tiles, or along the other axis, when that is blocked), outermost first so the inner ones find
 * room. Returns how many moved.
 */
private fun spreadAround(model: PlaceModel, conflicts: List<Tile>, radius: Int): Int {
    if (conflicts.isEmpty()) return 0
    fun dist(m: Int, t: Tile): Int {
        val dx = maxOf(0, model.px[m] - t.x, t.x - (model.px[m] + model.mw[m] - 1))
        val dy = maxOf(0, model.py[m] - t.y, t.y - (model.py[m] + model.mh[m] - 1))
        return dx + dy
    }
    val clusters = ArrayList<MutableList<Tile>>()
    conflicts.forEach { t ->
        val c = clusters.firstOrNull { cl -> cl.any { it.manhattan(t) <= 2 * radius } }
        if (c != null) c += t else clusters += mutableListOf(t)
    }
    val done = HashSet<Int>()
    clusters.forEach { cl ->
        val cx = cl.map { it.x }.average(); val cy = cl.map { it.y }.average()
        fun mx(m: Int) = model.px[m] + model.mw[m] / 2.0
        fun my(m: Int) = model.py[m] + model.mh[m] / 2.0
        (0 until model.nM).filter { m -> m !in done && cl.any { dist(m, it) <= radius } }
            .sortedByDescending { m -> kotlin.math.abs(mx(m) - cx) + kotlin.math.abs(my(m) - cy) }
            .forEach { m ->
                val dx = mx(m) - cx; val dy = my(m) - cy
                val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                val primary = if (horizontal) (if (dx >= 0) Dir.E else Dir.W) else (if (dy >= 0) Dir.S else Dir.N)
                val secondary = if (horizontal) (if (dy >= 0) Dir.S else Dir.N) else (if (dx >= 0) Dir.E else Dir.W)
                for ((d, k) in listOf(primary to 1, primary to 2, secondary to 1)) {
                    val nx = intArrayOf(model.px[m] + d.dx * k); val ny = intArrayOf(model.py[m] + d.dy * k); val nr = intArrayOf(model.rot[m])
                    if (model.legal(intArrayOf(m), nx, ny, nr)) { model.move(intArrayOf(m), nx, ny, nr); done += m; break }
                }
            }
    }
    return done.size
}

/**
 * Shifts one machine next to [conflicts] by one or two tiles (random machine, direction and distance among
 * the legal moves) and returns its id, or null if none can move.
 */
private fun nudgeNear(model: PlaceModel, conflicts: List<Tile>, rnd: SplittableRandom): Int? {
    if (conflicts.isEmpty()) return null
    fun dist(m: Int, t: Tile): Int {
        val dx = maxOf(0, model.px[m] - t.x, t.x - (model.px[m] + model.mw[m] - 1))
        val dy = maxOf(0, model.py[m] - t.y, t.y - (model.py[m] + model.mh[m] - 1))
        return dx + dy
    }
    val near = (0 until model.nM).filter { m -> conflicts.any { dist(m, it) <= 3 } }.toMutableList()
    while (near.isNotEmpty()) {
        val m = near.removeAt(rnd.nextInt(near.size))
        val moves = Dir.entries.flatMap { d -> listOf(1, 2).map { k -> d to k } }.shuffled(java.util.Random(rnd.nextLong()))
        for ((d, k) in moves) {
            val nx = intArrayOf(model.px[m] + d.dx * k); val ny = intArrayOf(model.py[m] + d.dy * k); val nr = intArrayOf(model.rot[m])
            if (model.legal(intArrayOf(m), nx, ny, nr)) { model.move(intArrayOf(m), nx, ny, nr); return m }
        }
    }
    return null
}

private data class Quad(val poles: List<PlacedEntity>, val wires: List<Pair<Int, Int>>, val ok: Boolean, val uncovered: List<Tile>)

private fun maxMoves(plan: ProductionPlan, tuning: Tuning): Long =
    minOf(tuning.place.maxMoves, tuning.place.movesPerMachine * plan.machines.size.coerceAtLeast(5))

/**
 * Scales [p] into [model]'s box and pushes each machine to the nearest legal spot (spiral search),
 * keeping the relative arrangement.
 */
fun legalize(p: Placement, model: PlaceModel): Placement {
    val sx = model.w.toDouble() / p.w
    val sy = model.h.toDouble() / p.h
    model.clear()
    val out = ArrayList<PlacedMachine>()
    val order = p.machines.sortedBy { it.y * 10000 + it.x }
    for (pm in order) {
        val m = pm.id
        val tx = (pm.x * sx).roundToInt().coerceIn(0, maxOf(0, model.w - model.mw[m]))
        val ty = (pm.y * sy).roundToInt().coerceIn(0, maxOf(0, model.h - model.mh[m]))
        var found: PlacedMachine? = null
        search@ for (r in 0..maxOf(model.w, model.h)) {
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dy)) != r) continue
                val code0 = pm.rot.ordinal + if (pm.mirror) 4 else 0
                for (code in if (model.fluid[m]) (listOf(code0) + (0..7)).distinct() else listOf(0)) {
                    val x = tx + dx; val y = ty + dy
                    if (model.legal(intArrayOf(m), intArrayOf(x), intArrayOf(y), intArrayOf(code))) {
                        found = PlacedMachine(m, x, y, geom.Dir.entries[code % 4], code >= 4); break@search
                    }
                }
            }
        }
        val f = found ?: run { model.clear(); return p.copy(w = model.w, h = model.h) }
        model.placeOne(m, f.x, f.y, f.rot.ordinal + if (f.mirror) 4 else 0)
        out += f
    }
    model.clear()
    return Placement(model.w, model.h, out.sortedBy { it.id })
}
