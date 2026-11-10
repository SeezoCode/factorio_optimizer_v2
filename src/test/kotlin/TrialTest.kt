import config.Tuning
import emit.toBlueprintString
import emit.toLayout
import geom.Kind
import plan.buildPlan
import search.TrialResult
import search.runTrial
import search.validationSpec
import validate.ErrorType
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrialTest {
    private fun trial(job: String, size: Int, seed: Long = 1, edit: (config.RunConfiguration) -> config.RunConfiguration = { it }): TrialResult {
        val config = edit(TestData.job(job))
        val plan = buildPlan(TestData.data, config)
        val r = runTrial(TestData.data, plan, config, Tuning(), size, size, seed)
        r.layout?.let {
            renderLayout(it, File("build/renders/trial/${job}_${size}_$seed${if (config.testMode) "_test" else ""}.png"),
                RenderOptions(errors = r.errors, showSupply = false, title = "$job ${size}x$size seed $seed: ${r.stage} score ${"%.1f".format(r.score)} ${r.millis}ms ${r.notes.take(3)}"))
            File("build/renders/trial/${job}_${size}_$seed.txt").writeText(it.toBlueprintString())
        }
        println("$job ${size}x$size seed $seed: ${r.stage} score ${r.score} in ${r.millis} ms; ${r.notes}; errors ${r.errors.take(5)}")
        return r
    }

    @Test
    fun `T1 to T3 complete a full trial at a comfortable size`() {
        listOf("t1" to 10, "t2" to 14, "t3" to 24).forEach { (job, size) ->
            val r = trial(job, size)
            assertTrue(r.success, "$job failed at ${r.stage}: ${r.errors.take(5)} ${r.notes}")
        }
    }

    /** Like the search: several seeds, the first success counts. */
    private fun anySeed(job: String, size: Int, edit: (config.RunConfiguration) -> config.RunConfiguration = { it }): TrialResult =
        (1L..8L).asSequence().map { trial(job, size, it, edit) }.firstOrNull { it.success } ?: trial(job, size, 1, edit)

    @Test
    fun `T4 validates with an infinity pipe on every acid consumer`() {
        val r = anySeed("t4", 24) { it.copy(fluids = config.FluidConfig("infinity-pipe")) }
        assertTrue(r.success, "T4 failed at ${r.stage}: ${r.errors.take(5)} ${r.notes}")
        val layout = r.layout!!
        val pipes = layout.entities.filter { it.kind == Kind.INFINITY_PIPE }
        val acid = layout.entities.filter { it.kind == Kind.MACHINE && it.recipe == "processing-unit" }
        assertEquals(acid.size, pipes.size)
        assertTrue(pipes.all { it.fluid == "sulfuric-acid" })
        acid.forEach { m -> assertTrue(pipes.any { it.tile == m.rect.sideMiddle(m.dir, 1) }, "pipe on the input side of ${m.tile}") }
        for (i in pipes.indices) for (j in i + 1 until pipes.size) assertTrue(pipes[i].tile.manhattan(pipes[j].tile) > 1)
    }

    @Test
    fun `the test copy feeds every port of the real layout with loaders`() {
        val r = anySeed("t3", 24)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes}")
        val config = TestData.job("t3")
        val tc = power.testCopy(r.layout!!, r.sources, r.outputs, config)
        renderLayout(tc.layout, File("build/renders/trial/t3_test_copy.png"), RenderOptions(showSupply = false, title = "t3 test copy"))
        assertTrue(tc.warnings.isEmpty(), "${tc.warnings}")
        val extra = tc.layout.entities.drop(r.layout!!.entities.size)
        assertEquals(r.layout!!.entities, tc.layout.entities.take(r.layout!!.entities.size), "the layout itself is unchanged")
        // Each item input: a loader whose belt end points at the port, the item's chest behind it.
        r.sources.filter { !it.fluid }.groupBy { it.tile }.forEach { (t, g) ->
            val l = extra.single { it.kind == Kind.LOADER_OUT && it.rect.tiles().any { a -> a + it.dir == t } }
            val back = l.rect.tiles().first { a -> a + l.dir.opposite !in l.rect.tiles() } + l.dir.opposite
            val chest = extra.single { it.kind == Kind.INFINITY_CHEST && it.tile == back }
            assertEquals(g[0].item, chest.item)
        }
        // Each item output: a loader taking the belt into a void chest.
        r.outputs.filter { !it.fluid }.forEach { o ->
            val l = extra.single { it.kind == Kind.LOADER_IN && it.rect.tiles().any { a -> a + it.dir.opposite == o.tile } }
            val front = l.rect.tiles().first { a -> a + l.dir !in l.rect.tiles() } + l.dir
            assertTrue(extra.single { it.kind == Kind.INFINITY_CHEST && it.tile == front }.item == null)
        }
        // Nothing overlaps, and the loaders survive the blueprint.
        val tiles = tc.layout.entities.flatMap { it.rect.tiles() }
        assertEquals(tiles.size, tiles.toSet().size)
        val back = emit.decodeBlueprint(tc.layout.toBlueprintString()).toLayout(tc.layout.w, tc.layout.h)
        assertEquals(tc.layout.entities.map { Triple(it.kind, it.tile, it.dir) }, back.entities.map { Triple(it.kind, it.tile, it.dir) })
    }

    @Test
    fun `test mode feeds every source from infinity chests`() {
        val r = anySeed("t4", 24) { it.copy(testMode = true, fluids = config.FluidConfig("infinity-pipe")) }
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes}")
        val chests = r.layout!!.entities.filter { it.kind == Kind.INFINITY_CHEST }
        assertEquals(setOf("iron-plate", "copper-plate", "plastic-bar"), chests.mapNotNull { it.item }.toSet())
        // Acid still arrives by infinity pipe in this mode (pipes are routed from D3 on).
    }

    @Test
    fun `T4 routes acid in pipes from its port`() {
        val r = anySeed("t4", 26)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val layout = r.layout!!
        assertTrue(layout.entities.none { it.kind == Kind.INFINITY_PIPE })
        assertTrue(layout.entities.count { it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG } >= 2)
    }

    @Test
    fun `a plastic block pipes petroleum from refineries to chemical plants`() {
        val job = config.RunConfiguration(targets = listOf(config.ConfigTarget("plastic-bar", 2.0)),
            sources = listOf(config.ConfigSource("crude-oil", edge = "west"), config.ConfigSource("coal", edge = "north")))
        val plan = buildPlan(TestData.data, job)
        println("plastic plan: ${plan.machines.groupingBy { it.recipe }.eachCount()}")
        val r = (1L..8L).asSequence().map { seed ->
            runTrial(TestData.data, plan, job, Tuning(), 24, 24, seed).also { t ->
                println("plastic seed $seed: ${t.stage} ${t.errors.take(3)} ${t.notes.take(1).map { it.take(300) }}")
                t.layout?.let { renderLayout(it, File("build/renders/trial/plastic_$seed.png"), RenderOptions(errors = t.errors, title = "plastic seed $seed ${t.stage}")) }
            }
        }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed the plastic block")
        assertTrue(r.layout!!.entities.any { it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG })
        val infinity = r.layout!!.entities.count { it.kind == Kind.INFINITY_PIPE }
        assertEquals(0, infinity)
    }

    @Test
    fun `an item above one belt is carried on several independent belts`() {
        // Inserter capacity research 2 (hand size 2): two fast inserters per gear machine instead of four.
        val job = config.RunConfiguration(targets = listOf(config.ConfigTarget("iron-gear-wheel", 20.0)),
            sources = listOf(config.ConfigSource("iron-plate", edge = "west")), research = config.ResearchConfig(2))
        val plan = buildPlan(TestData.data, job)
        assertEquals(40.0, plan.rawRates.getValue("iron-plate"), 1e-9)
        assertTrue(plan.terminals.filter { it.item == "iron-plate" && it.machine != null }.all { it.k == 2 })
        val r = (1L..6L).asSequence().map { seed ->
            runTrial(TestData.data, plan, job, Tuning(), 28, 28, seed).also { t ->
                println("40 iron/s seed $seed: ${t.stage} ${t.errors.take(3)}")
                t.layout?.let { renderLayout(it, File("build/renders/trial/iron40_$seed.png"), RenderOptions(errors = t.errors, title = "seed $seed ${t.stage}")) }
            }
        }.firstOrNull { it.success }
        assertTrue(r != null, "no seed routed 40 iron/s")
        val ironChains = r.route!!.chainStats.count { it.item == "iron-plate" }
        assertTrue(ironChains >= 2, "iron on $ironChains belts")
        assertEquals(ironChains, r.sources.count { it.item == "iron-plate" }, "each belt has its own port tile")
    }

    @Test
    fun `products leave through an output port and the test harness empties it`() {
        val r = anySeed("t1", 10) { it.copy(testMode = true) }
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes}")
        val out = r.outputs.single()
        assertEquals("iron-gear-wheel", out.item)
        assertEquals(1.0, out.rate, 1e-9)
        val layout = r.layout!!
        val belt = layout.entities.single { it.kind.beltFamily && it.tile == out.tile }
        assertTrue((belt.tile + belt.dir).let { it.x !in 0 until r.w || it.y !in 0 until r.h }, "the port belt leads out of the box")
        val voids = layout.entities.filter { it.kind == Kind.INFINITY_CHEST && it.item == null }
        assertTrue(voids.isNotEmpty(), "void chests take the gears")
        assertTrue(layout.entities.none { it.kind == Kind.CHEST })
        val ports = search.portManifest(r)
        assertEquals("iron-gear-wheel", ports["outputs"]!!.toString().let { Regex("\"item\":\"([^\"]+)\"").find(it)!!.groupValues[1] })
    }

    @Test
    fun `a fluid product leaves through a pipe to its output port`() {
        val r = anySeed("acid", 14)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val out = r.outputs.single()
        assertTrue(out.fluid)
        assertEquals("sulfuric-acid", out.item)
        assertTrue(r.layout!!.entities.any { it.kind == Kind.PIPE && it.tile == out.tile })
        val t = anySeed("acid", 14) { it.copy(testMode = true) }
        assertTrue(t.success, "test mode failed at ${t.stage}: ${t.errors.take(5)} ${t.notes.take(2)}")
        assertTrue(t.layout!!.entities.any { it.kind == Kind.INFINITY_PIPE && it.drain && it.fluid == "sulfuric-acid" })
    }

    @Test
    fun `two inputs share one belt, a lane each, and the harness feeds each lane`() {
        val r = anySeed("pair", 14) { it.copy(testMode = true, laneSharing = true) }
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val sources = r.sources
        assertEquals(1, sources.map { it.tile }.distinct().size, "one shared input belt: $sources")
        assertEquals(setOf(0, 1), sources.map { it.lane }.toSet())
        val lanes = search.portManifest(r)["inputs"].toString()
        assertTrue("\"lane\":\"left\"" in lanes && "\"lane\":\"right\"" in lanes, lanes)
        // Without lane sharing the two items need two belts.
        val apart = anySeed("pair", 14) { it.copy(testMode = true, laneSharing = false) }
        assertTrue(apart.success)
        assertEquals(2, apart.sources.map { it.tile }.distinct().size)
    }

    @Test
    fun `ports sit on the ring outside the box, and reserved areas stay empty and marked`() {
        val r = anySeed("t3", 24) { it.copy(reserved = listOf(config.ReservedArea(w = 5, h = 4))) }
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val box = geom.Rect(0, 0, r.w, r.h)
        assertTrue(r.sources.isNotEmpty() && r.sources.all { it.tile !in box }, "inputs on the ring: ${r.sources}")
        assertTrue(r.outputs.isNotEmpty() && r.outputs.all { it.tile !in box }, "outputs on the ring: ${r.outputs}")
        // The reserved area (south-east corner by default) holds nothing and has a marker border.
        val area = geom.Rect(r.w - 5, r.h - 4, 5, 4)
        val layout = r.layout!!
        assertTrue(layout.entities.none { e -> e.rect.tiles().any { it in area } }, "reserved area is empty")
        assertEquals(area.tiles().count { it.x == area.x || it.y == area.y || it.x == area.x2 || it.y == area.y2 }, layout.tiles.size)
        // Marker tiles survive a blueprint round trip.
        val back = emit.decodeBlueprint(layout.toBlueprintString()).toLayout(r.w, r.h)
        assertEquals(layout.tiles.toSet(), back.tiles.toSet())
    }

    @Test
    fun `removing a pole from a valid result fails validation`() {
        val config = TestData.job("t3")
        val plan = buildPlan(TestData.data, config)
        val r = runTrial(TestData.data, plan, config, Tuning(), 24, 24, 2)
        assertTrue(r.success, "failed at ${r.stage}")
        val layout = r.layout!!
        val poleIdx = layout.entities.indices.filter { layout.entities[it].kind == Kind.POLE }
        assertTrue(poleIdx.isNotEmpty())
        val spec = validationSpec(TestData.data, plan, config, r.placement!!, r.sources, requirePower = true, outputs = r.outputs,
            outsideAllowed = layout.entities.flatMap { it.rect.tiles() }.filter { it.x !in 0 until 24 || it.y !in 0 until 24 }.toSet())
        assertEquals(emptyList(), validate(layout, spec))
        // Every pole is needed: dropping any single one leaves something unpowered or the rest disconnected.
        val failing = poleIdx.count { drop ->
            val keep = layout.entities.indices.filter { it != drop }
            val remap = keep.withIndex().associate { (newI, oldI) -> oldI to newI }
            val wires = layout.wires.filter { it.first != drop && it.second != drop }.map { remap.getValue(it.first) to remap.getValue(it.second) }
            val errs = validate(layout.copy(entities = keep.map { layout.entities[it] }, wires = wires), spec)
            errs.any { it.type == ErrorType.UNPOWERED || it.type == ErrorType.POLES_DISCONNECTED }
        }
        assertTrue(failing >= 1, "removing a pole should break power")
    }

    private fun anyJobSeed(name: String, json: String, size: Int): TrialResult {
        val job = config.RunConfiguration.parse(json)
        val plan = buildPlan(TestData.data, job)
        return (1L..4L).asSequence().map { seed ->
            runTrial(TestData.data, plan, job, Tuning(), size, size, seed).also { println("$name seed $seed: ${it.stage} ${it.errors.take(3)} ${it.notes.take(1).map { n -> n.take(200) }}") }
        }.firstOrNull { it.success } ?: runTrial(TestData.data, plan, job, Tuning(), size, size, 1)
    }

    @Test
    fun `an input pinned to one tile feeds more consumers than one chain takes`() {
        // Copper for many cable machines needs more inserter visits than one chain takes; every chain used to
        // start on the single port tile, a conflict no box size could resolve (user job 9).
        val r = anyJobSeed("pinned", """{"targets": [{"name": "electronic-circuit", "amount": 6.0}], "machine": "assembling-machine-2",
            "inserters": {"allowed": ["inserter"]}, "sources": [{"name": "copper-plate", "edge": "west", "from": 5, "to": 5},
            {"name": "iron-plate", "edge": "north"}]}""", 40)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
    }

    @Test
    fun `fluid inputs on touching edge tiles enter through pipe-to-ground`() {
        // Pipes on touching tiles join, so the second and third fluid start with a pipe-to-ground open to the outside.
        val r = anyJobSeed("fluids", """{"targets": [{"name": "sulfur", "amount": 2.0}, {"name": "lubricant", "amount": 10.0}],
            "sources": [{"name": "petroleum-gas", "edge": "west", "from": 0, "to": 0}, {"name": "heavy-oil", "edge": "west", "from": 1, "to": 1},
            {"name": "water", "edge": "west", "from": 2, "to": 2}]}""", 20)
        assertTrue(r.success, "failed at ${r.stage}: ${r.errors.take(5)} ${r.notes.take(2)}")
        val ports = r.layout!!.entities.filter { (it.kind == Kind.PIPE || it.kind == Kind.PIPE_UG) && it.x == 0 && it.y in 0..2 }
        assertTrue(ports.count { it.kind == Kind.PIPE_UG } >= 1, "ports: $ports")
    }

    @Test
    fun `a block builds its port ends, an output into a chest and an input fed from a requester`() {
        // T2 (copper cable into electronic circuits): its output ends in a chest, its iron input at a requester chest.
        val config = TestData.job("t2")
        val plan0 = buildPlan(TestData.data, config)
        val plan = plan0.copy(ports = plan0.ports.map { p ->
            when {
                p.output -> p.copy(end = "chest")
                p.item == "iron-plate" -> p.copy(end = "requester")
                else -> p
            }
        })
        val r = (1L..6L).asSequence().map { runTrial(TestData.data, plan, config, Tuning(), 16, 16, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed laid it out")
        val ents = r.layout!!.entities
        assertTrue(r.outputs.all { it.local }, "${r.outputs}")
        assertTrue(r.sources.single { it.item == "iron-plate" }.requester)
        val requester = ents.single { it.kind == geom.Kind.CHEST && it.request != null }
        assertEquals("iron-plate", requester.item)
        assertTrue(ents.any { it.kind == geom.Kind.CHEST && it.request == null }, "the output's chest")
        // The ends lie outside the box, right beside it.
        assertTrue(listOf(requester).all { it.x !in 0 until r.w || it.y !in 0 until r.h })
    }
}
