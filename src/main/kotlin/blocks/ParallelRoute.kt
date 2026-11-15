package blocks

import geom.Kind
import geom.PlacedEntity
import geom.Tile
import geom.outputTile
import plan.ProductionPlan
import route.RouteProblem
import route.RouteResult
import route.RouteStats
import route.route
import java.util.concurrent.Executors

/** Fewer belt links than this are routed in one go: groups would only add a second pass. */
const val PARALLEL_MIN_LINKS = 24

/**
 * A factory's corridors routed on several threads (docs/STITCH_REWRITE.md, step 3). The belt links are split into up to
 * [threads] groups by where their global routes run (the factory halved along its longer side until there are enough),
 * and each group is routed on its own thread as if the others were not there: they keep to their own corridors, so
 * most never meet. Then the belts of different groups that do meet (on one tile, one pointing into the other, an
 * underground between another's pair on its axis), the links that did not route, and every pipe are routed together
 * around the belts that stay as they were. Routing everything in one go took 10 to 18 minutes on Py production
 * science, on one core. Without global routes (blocks.stitch "router"), or with few belt links, it is one go as before.
 */
fun routeCorridors(problem: RouteProblem, threads: Int, say: (String) -> Unit = {}): RouteResult {
    val plan = problem.plan
    val byId = plan.nets.associateBy { it.id }
    val belts = plan.nets.filter { !it.fluid && !problem.netGuides[it.id].isNullOrEmpty() }.map { it.id }
    if (threads < 2 || belts.size < PARALLEL_MIN_LINKS) return route(problem)
    val t0 = System.currentTimeMillis()
    val groups = splitByGuides(belts, problem.netGuides, threads)
    val groupOf = HashMap<Int, Int>().also { m -> groups.forEachIndexed { g, ids -> ids.forEach { m[it] = g } } }
    // Each net's fixed port tiles and the tile straight out from each (its way in): a group keeps off the other nets'
    // (pipes' among them, routed only later), or a kept belt may wall a port in.
    val portTiles = plan.nets.associate { n ->
        n.id to n.terminals.mapNotNull { t -> plan.terminals.getOrNull(t)?.port?.let { plan.ports.getOrNull(it) } }
            .flatMap { p -> p.specs.flatMap { s ->
                if (s.x == null || s.y == null) emptyList()
                else listOfNotNull(Tile(s.x, s.y), s.dir?.let { d -> runCatching { geom.Dir.fromName(d) }.getOrNull() }?.let { d -> Tile(s.x - d.dx, s.y - d.dy) })
            } }
    }
    // The groups get a third of the time left: what they leave clashing is routed again afterwards anyway.
    val third = (minOf(problem.deadlineMs, t0 + 6 * 3_600_000L) - t0) / 3
    val budget = Deadlines(t0 + third, t0 + third / 3, t0 + third * 2 / 3)
    val pool = Executors.newFixedThreadPool(groups.size) { r -> Thread(r).apply { isDaemon = true } }
    val parts = try {
        groups.map { ids ->
            val others = plan.nets.filter { it.id !in ids.toSet() }.flatMap { portTiles[it.id].orEmpty() }
            pool.submit<Pair<List<Int>, RouteResult>> { ids to route(subProblem(problem, ids, obstacles = problem.obstacles + others, deadlines = budget)) }
        }.map { it.get() }
    } finally { pool.shutdownNow() }
    // Each net's entities, by its id in the whole problem.
    val ents = HashMap<Int, List<PlacedEntity>>()
    val failed = HashSet<Int>()
    for ((ids, r) in parts) {
        r.entities.groupBy { it.net }.forEach { (n, es) -> if (n != null && n in ids.indices) ents[ids[n]] = es.map { it.copy(net = ids[n]) } }
        r.failedNets.forEach { failed += ids[it] }
    }
    val clash = clashes(ents.filterKeys { it !in failed }.flatMap { it.value }).first
    var again = (plan.nets.map { it.id }.filter { it !in groupOf || it in failed || it in clash }).toSet()
    var kept = belts.filter { it !in again }
    val keptAtFirst = kept.size
    // Where the groups found congestion, the second pass knows from the start (their histories added up).
    var warm = parts.map { it.second.rawHistory }.filter { it.isNotEmpty() }.takeIf { hs -> hs.isNotEmpty() && hs.all { it.size == hs[0].size } }
        ?.let { hs -> FloatArray(hs[0].size) { i -> hs.sumOf { it[i].toDouble() }.toFloat() } } ?: problem.warmHistory
    // Each port's source or output as last routed.
    val srcByPort = HashMap<Int, route.RouteResult>()
    parts.forEach { (_, r) -> (r.sources.map { it.port } + r.outputs.map { it.port }).forEach { srcByPort[it] = r } }
    val passes = ArrayList<RouteResult>()
    var last: RouteResult? = null
    var againIds = emptyList<Int>()
    var lastEnts = emptyList<PlacedEntity>()
    var keptEnts = emptyList<PlacedEntity>()
    var still = emptySet<Int>(); var stillTiles = emptyList<Tile>()
    val why = ArrayList<String>()
    val notes = ArrayList<String>()
    // Up to three passes: the clashing links routed again around the kept belts; where some still fail, the kept belts
    // near the trouble are freed and routed again with them.
    for (round in 0 until 3) {
        againIds = again.sorted()
        keptEnts = kept.flatMap { ents[it].orEmpty() }
        if (againIds.isEmpty()) { last = null; lastEnts = emptyList(); break }
        // What the kept belts take and point into; no underground passes under their undergrounds' ends, and none
        // comes up between a pair of theirs (it would pair with them; pipes-to-ground are another kind).
        val keptTiles = keptEnts.flatMap { it.rect.tiles() }.toSet()
        val pointed = keptEnts.flatMap { e -> fedFrom(e) }.toSet()
        val spans = undergroundSpans(keptEnts).flatMap { it.second }.toSet()
        val ugEnds = keptEnts.filter { it.kind == Kind.UG_IN || it.kind == Kind.UG_OUT }.map { it.tile }.toSet()
        val now = System.currentTimeMillis()
        val share = (minOf(problem.deadlineMs, t0 + 6 * 3_600_000L) - now) / (3 - round)
        last = route(subProblem(problem, againIds, obstacles = problem.obstacles + keptTiles + pointed,
            noBeltTunnel = problem.noBeltTunnel + ugEnds, noBeltUgEnds = problem.noBeltUgEnds + spans,
            deadlines = Deadlines(now + share, now + share / 3, now + share * 2 / 3), warm = warm))
        passes += last
        (last.sources.map { it.port } + last.outputs.map { it.port }).forEach { srcByPort[it] = last }
        lastEnts = last.entities.map { e -> e.copy(net = e.net?.let { againIds.getOrNull(it) }) }
        why.clear()
        clashes(keptEnts + lastEnts, why).let { (n, t) -> still = n; stillTiles = t }
        val failedHere = (last.failedNets.mapNotNull { againIds.getOrNull(it) } + still).toSet()
        notes += "pass ${round + 1}: ${againIds.size} links around ${kept.size} (${last.millis / 1000} s): " +
            if (last.success && still.isEmpty()) "all routed" else "${last.failedNets.size} unrouted, ${last.conflicts.size} conflicts, ${still.size} clashing"
        if (last.success && still.isEmpty()) break
        if (System.currentTimeMillis() > problem.deadlineMs - 60_000) break
        // Next pass: what failed or clashed, and the kept belts near it (within 8 tiles of its trouble or its ports).
        val trouble = HashSet<Tile>()
        (last.conflicts + stillTiles + failedHere.flatMap { portTiles[it].orEmpty() }).forEach { c ->
            for (dy in -8..8) for (dx in -8..8) trouble += Tile(c.x + dx, c.y + dy)
        }
        val near = kept.filter { id -> ents[id].orEmpty().any { e -> e.rect.tiles().any { it in trouble } } }.toSet()
        // The links this pass routed cleanly stay as routed; the trouble's are routed again.
        lastEnts.groupBy { it.net }.forEach { (n, es) -> if (n != null) ents[n] = es }
        val conflictNets = clashes(lastEnts).first
        val clean = againIds.filter { it !in failedHere && it !in conflictNets && !byId.getValue(it).fluid }
        val nextAgain = failedHere + conflictNets + near + againIds.filter { byId.getValue(it).fluid }
        if (near.isEmpty() && nextAgain.toSet() == again) break
        kept = (kept.filter { it !in near } + clean).distinct()
        again = nextAgain
        warm = last.rawHistory.takeIf { it.isNotEmpty() } ?: warm
    }
    val failedAll = ((last?.failedNets?.mapNotNull { againIds.getOrNull(it) } ?: emptyList()) + still).distinct()
    val conflicts = (last?.conflicts ?: emptyList()) + stillTiles
    val all = parts.map { it.second } + passes
    val ports = plan.nets.flatMap { n -> n.terminals.mapNotNull { t -> plan.terminals.getOrNull(t)?.port } }.toSet()
    val sources = ports.flatMap { p -> srcByPort[p]?.sources?.filter { it.port == p }.orEmpty() }
    val outputs = ports.flatMap { p -> srcByPort[p]?.outputs?.filter { it.port == p }.orEmpty() }
    say("corridors in ${groups.size} groups side by side (${groups.joinToString("/") { it.size.toString() }} belt links, " +
        "${parts.maxOf { it.second.millis } / 1000} s), $keptAtFirst kept; ${notes.joinToString("; ")}" +
        "${if (still.isNotEmpty()) "; still clashing: ${why.take(3).joinToString("; ")}" else ""}; ${(System.currentTimeMillis() - t0) / 1000} s")
    return RouteResult(
        success = (last?.success ?: true) && still.isEmpty(),
        entities = keptEnts + lastEnts,
        iterations = all.maxOf { it.iterations },
        overuse = (last?.overuse ?: 0) + stillTiles.size,
        history = last?.history ?: emptyMap(),
        rawHistory = last?.rawHistory ?: FloatArray(0),
        failedNets = failedAll,
        sources = sources, outputs = outputs,
        conflicts = conflicts,
        unreached = last?.unreached ?: emptyList(),
        conflictNets = last?.conflictNets?.mapValues { (_, ns) -> ns.mapNotNull { againIds.getOrNull(it) }.toSet() } ?: emptyMap(),
        di = emptyList(),
        stats = RouteStats(all.sumOf { it.stats.belts }, all.sumOf { it.stats.undergrounds }, all.sumOf { it.stats.inserters }),
        chains = all.sumOf { it.chains },
        chainStats = all.flatMap { it.chainStats },
        millis = System.currentTimeMillis() - t0,
        log = parts.flatMapIndexed { g, p -> p.second.log.takeLast(5).map { "group ${g + 1}: $it" } } + (last?.log ?: emptyList()),
    )
}

/**
 * [ids] in up to [k] groups of about equal size, by the middle of each one's coarse route: the set halved along the
 * longer side of where those middles lie, again and again.
 */
fun splitByGuides(ids: List<Int>, guides: Map<Int, List<Tile>>, k: Int): List<List<Int>> {
    val mid = ids.associateWith { id -> guides[id].orEmpty().let { cs -> if (cs.isEmpty()) 0.0 to 0.0 else cs.sumOf { it.x }.toDouble() / cs.size to cs.sumOf { it.y }.toDouble() / cs.size } }
    fun halve(set: List<Int>, k: Int): List<List<Int>> {
        if (k <= 1 || set.size < 2) return listOf(set)
        val xs = set.map { mid.getValue(it).first }; val ys = set.map { mid.getValue(it).second }
        val alongX = xs.max() - xs.min() >= ys.max() - ys.min()
        val sorted = set.sortedBy { if (alongX) mid.getValue(it).first else mid.getValue(it).second }
        val ka = k / 2
        val cut = (sorted.size * ka / k).coerceIn(1, sorted.size - 1)
        return halve(sorted.take(cut), ka) + halve(sorted.drop(cut), k - ka)
    }
    return halve(ids, k).filter { it.isNotEmpty() }
}

/** When a routing stops: at [hard], negotiating at [negotiation], and once past [soft] when it no longer converges. */
private class Deadlines(val hard: Long, val soft: Long, val negotiation: Long)

/** [problem] for the nets [ids] alone (numbered 0 on in that order), with [obstacles] and the belt underground limits given. */
private fun subProblem(problem: RouteProblem, ids: List<Int>, obstacles: Set<Tile> = problem.obstacles,
                       noBeltTunnel: Set<Tile> = problem.noBeltTunnel, noBeltUgEnds: Set<Tile> = problem.noBeltUgEnds,
                       deadlines: Deadlines? = null, warm: FloatArray? = problem.warmHistory): RouteProblem {
    val byId = problem.plan.nets.associateBy { it.id }
    val plan: ProductionPlan = problem.plan.copy(nets = ids.mapIndexed { i, id -> byId.getValue(id).copy(id = i) })
    val guides = ids.withIndex().mapNotNull { (i, id) -> problem.netGuides[id]?.let { i to it } }.toMap()
    return RouteProblem(plan, problem.config, problem.tuning, problem.placement, obstacles = obstacles, tileBias = problem.tileBias,
        beltBias = problem.beltBias, warmHistory = warm, debugDir = null, endgameSeed = problem.endgameSeed,
        noTunnel = problem.noTunnel, pipeObstacles = problem.pipeObstacles, noBeltTunnel = noBeltTunnel, noBeltUgEnds = noBeltUgEnds, deadlineMs = deadlines?.hard ?: problem.deadlineMs, softDeadlineMs = deadlines?.soft ?: problem.softDeadlineMs,
        negotiationDeadlineMs = deadlines?.negotiation ?: problem.negotiationDeadlineMs, netGuides = guides, guideCell = problem.guideCell,
        guidePenalty = problem.guidePenalty, guideBound = problem.guideBound, heuristicScale = problem.heuristicScale)
}

/** The tiles [e] puts its items onto: in front of a belt, an underground's exit, or both halves of a splitter. */
private fun fedFrom(e: PlacedEntity): List<Tile> = when (e.kind) {
    Kind.SPLITTER -> e.rect.tiles().map { it + e.dir }
    else -> listOfNotNull(e.outputTile)
}

/** Each underground belt pair among [es] (by net): its entrance and the tiles it passes under. */
private fun undergroundSpans(es: List<PlacedEntity>): List<Pair<PlacedEntity, List<Tile>>> {
    val at = HashMap<Tile, PlacedEntity>()
    es.filter { it.kind == Kind.UG_OUT }.forEach { at[it.tile] = it }
    return es.filter { it.kind == Kind.UG_IN }.mapNotNull { e ->
        (1..12).firstOrNull { i -> at[e.tile.step(e.dir, i)]?.let { it.net == e.net && it.dir == e.dir } == true }
            ?.let { j -> e to (1 until j).map { e.tile.step(e.dir, it) } }
    }
}

/**
 * Nets among [es] that meet another net, and where: two on one tile, one putting its items onto the other's belt, or
 * an underground of one between another's pair on its axis (it would pair with them).
 */
internal fun clashes(es: List<PlacedEntity>, why: MutableList<String>? = null): Pair<Set<Int>, List<Tile>> {
    val nets = HashSet<Int>(); val tiles = LinkedHashSet<Tile>()
    val on = HashMap<Tile, PlacedEntity>()
    fun meet(a: PlacedEntity, b: PlacedEntity, t: Tile, how: String) {
        if (a.net == b.net) return
        a.net?.let { nets += it }; b.net?.let { nets += it }; tiles += t
        if (why != null && why.size < 10) why += "$how at $t: ${a.kind} ${a.dir} of ${a.net}, ${b.kind} ${b.dir} of ${b.net}"
    }
    for (e in es) for (t in e.rect.tiles()) { on[t]?.let { meet(it, e, t, "one tile") } ?: on.put(t, e) }
    for (e in es) if (e.kind.beltFamily) for (t in fedFrom(e)) on[t]?.let { b -> if (b.kind.beltFamily) meet(e, b, t, "feeds") }
    // An underground of the same direction and tier between a pair's two ends takes the place of one of them.
    for ((e, span) in undergroundSpans(es)) for (t in span) on[t]?.let { b ->
        if ((b.kind == Kind.UG_IN || b.kind == Kind.UG_OUT) && b.dir == e.dir && b.name == e.name) meet(e, b, t, "under")
    }
    return nets to tiles.toList()
}
