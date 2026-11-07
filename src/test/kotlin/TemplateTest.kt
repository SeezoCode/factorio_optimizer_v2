import blocks.Templates
import config.ConfigSource
import config.ConfigTarget
import config.ResearchConfig
import config.RunConfiguration
import config.SearchConfig
import config.Tuning
import geom.Kind
import plan.Port
import plan.Role
import plan.Terminal
import plan.buildPlan
import search.validationSpec
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateTest {
    private fun job(item: String, rate: Double, input: String, inEdge: String, outEdge: String) = RunConfiguration(
        targets = listOf(ConfigTarget(item, rate)),
        sources = listOf(ConfigSource(input, edge = inEdge)),
        outputs = listOf(ConfigSource(item, edge = outEdge)),
    )

    private fun checkAll(name: String, config: RunConfiguration, plan: plan.ProductionPlan): List<search.TrialResult> {
        val notes = ArrayList<String>()
        val ts = Templates.layouts(TestData.data, config, plan) { notes += it }
        ts.forEachIndexed { i, t ->
            renderLayout(t.layout!!, File("build/renders/templates/${name}_$i.png"), RenderOptions(title = "$name ${t.w}x${t.h}", ports = viz.portLabels(t.sources, t.outputs)))
            // Valid on its own (the template validated itself; check again from the outside).
            val spec = validationSpec(TestData.data, plan, config, t.placement!!, t.sources, requirePower = true,
                outsideAllowed = (t.sources.map { it.tile } + t.outputs.map { it.tile }).toSet(), outputs = t.outputs)
            assertEquals(emptyList(), validate(t.layout, spec), "$name template $i")
            assertEquals(plan.machines.size, t.layout.entities.count { it.kind == Kind.MACHINE })
        }
        assertTrue(ts.isNotEmpty(), "$name: no template: $notes")
        return ts
    }

    @Test
    fun `a smelting block becomes a furnace column with ports on any sides`() {
        for ((inEdge, outEdge) in listOf("west" to "east", "north" to "north", "south" to "west", "east" to "east")) {
            val config = job("iron-plate", 12.5, "iron-ore", inEdge, outEdge)
            val plan = buildPlan(TestData.data, config, handTuning)
            assertEquals(20, plan.machines.size)
            val ts = checkAll("iron_${inEdge}_$outEdge", config, plan)
            // Ten wide and three tiles per pair of furnaces, plus at most a few header rows (or the same lying down).
            val best = ts.minBy { it.area }
            assertEquals(10, minOf(best.w, best.h), "${best.w} x ${best.h}")
            assertTrue(maxOf(best.w, best.h) <= 30 + 4, "${best.w} x ${best.h}")
            // Both sides of the template are offered: upright and lying.
            assertTrue(ts.any { it.w < it.h } && ts.any { it.w > it.h }, ts.map { "${it.w}x${it.h}" }.toString())
        }
    }

    @Test
    fun `what the furnaces leave of the ore passes on through an output port`() {
        val config = job("copper-plate", 10.0, "copper-ore", "north", "east")
        val base = buildPlan(TestData.data, config, handTuning)
        // Six more ore per second come in and leave on the south side, as a pass-through link of a factory would.
        val inPort = base.ports.first { !it.output }
        val pass = Port(base.ports.size, "copper-ore", listOf(ConfigSource("copper-ore", edge = "south")), output = true)
        val terminals = base.terminals.map { if (it.port == inPort.id) it.copy(rate = it.rate + 6.0) else it } +
            Terminal(base.terminals.size, Role.OUTPUT, "copper-ore", 6.0, 1, port = pass.id)
        val net = base.nets.first { it.item == "copper-ore" }
        val plan = base.copy(ports = base.ports + pass, terminals = terminals,
            nets = base.nets.map { if (it.id == net.id) it.copy(terminals = it.terminals + terminals.last().id, flow = it.flow + 6.0) else it })
        val ts = checkAll("copper_pass", config, plan)
        assertTrue(ts.all { t -> t.outputs.any { it.item == "copper-ore" } })
    }

    /** [base] with one more port of [item] on [edge] taking [rate] of what its first port of that item carries. */
    private fun withSplitPort(base: plan.ProductionPlan, item: String, output: Boolean, edge: String, rate: Double): plan.ProductionPlan {
        val first = base.ports.first { it.item == item && it.output == output }
        val extra = Port(base.ports.size, item, listOf(ConfigSource(item, edge = edge)), output = output)
        val t = Terminal(base.terminals.size, if (output) Role.OUTPUT else Role.SOURCE, item, rate, 1, port = extra.id)
        val terminals = base.terminals.map { if (it.port == first.id) it.copy(rate = it.rate - rate) else it } + t
        return base.copy(ports = base.ports + extra, terminals = terminals,
            nets = base.nets.map { if (it.item == item) it.copy(terminals = it.terminals + t.id) else it })
    }

    @Test
    fun `two product ports leave through a splitter, two input ports join through one`() {
        val config = job("iron-plate", 12.5, "iron-ore", "west", "north")
        val base = buildPlan(TestData.data, config, handTuning)
        val twoOut = withSplitPort(base, "iron-plate", true, "east", 5.0)
        assertTrue(checkAll("iron_two_out", config, twoOut).all { t -> t.layout!!.entities.any { it.kind == Kind.SPLITTER } })
        val twoIn = withSplitPort(base, "iron-ore", false, "south", 6.0)
        checkAll("iron_two_in", config, twoIn)
        checkAll("iron_two_in_two_out", config, withSplitPort(twoIn, "iron-plate", true, "east", 5.0))
        // Plates to three blocks: ore from the west, plates out east and north.
        val three0 = job("iron-plate", 18.0, "iron-ore", "west", "east")
        var many = buildPlan(TestData.data, three0, handTuning)
        listOf("north" to 3.0, "east" to 2.5).forEach { (e, r) -> many = withSplitPort(many, "iron-plate", true, e, r) }
        checkAll("iron_three_out", three0, many)
        // Three input ports on one side, three outputs on two others (a steel block fed by three smelting blocks).
        val steel = job("steel-plate", 2.5, "iron-plate", "east", "west")
        var three = buildPlan(TestData.data, steel, handTuning)
        listOf("east" to 4.0, "east" to 3.0).forEach { (e, r) -> three = withSplitPort(three, "iron-plate", false, e, r) }
        listOf("south" to 0.8, "west" to 0.5).forEach { (e, r) -> three = withSplitPort(three, "steel-plate", true, e, r) }
        checkAll("steel_three_in", steel, three)
        // Ports on both ends of the column: a bypass lane along it carries belts past the machines.
        val ends = withSplitPort(withSplitPort(base, "iron-plate", true, "south", 2.0), "iron-plate", true, "north", 1.0)
        checkAll("iron_both_ends", config, ends)
    }

    @Test
    fun `gears come from an assembler column too, once inserters keep up`() {
        // An assembler 3 makes 2.5 gears/s from 5 plates/s: no inserter takes that from a belt without research.
        val slow = job("iron-gear-wheel", 5.0, "iron-plate", "west", "south")
        val notes = ArrayList<String>()
        assertTrue(Templates.layouts(TestData.data, slow, buildPlan(TestData.data, slow, handTuning)) { notes += it }.isEmpty())
        assertTrue(notes.any { "no inserter takes" in it }, "$notes")
        // Assembler 1 at full inserter capacity research: long-handed inserters move 1.2 x 3 a second.
        val config = slow.copy(machine = "assembling-machine-1", machines = listOf("assembling-machine-1", "electric-furnace"),
            research = ResearchConfig(inserterCapacity = 7))
        checkAll("gears", config, buildPlan(TestData.data, config, handTuning))
    }

    @Test
    fun `beaconed furnaces get a column with beacons along both sides`() {
        for ((inEdge, outEdge) in listOf("west" to "east", "north" to "south")) {
            // Beaconed furnaces take 1.67 ore/s each: inserters keep up with capacity research.
            val config = job("iron-plate", 24.0, "iron-ore", inEdge, outEdge).copy(research = ResearchConfig(inserterCapacity = 7),
                beacons = config.BeaconConfig(machineModules = listOf("productivity-module-3", "productivity-module-3")))
            val plan = buildPlan(TestData.data, config, handTuning)
            assertTrue(plan.machines.all { it.beacons == 2 }, "${plan.machines.map { it.beacons }}")
            val ts = checkAll("iron_beacons_${inEdge}_$outEdge", config, plan)
            // (Double columns, two side by side, are offered too.)
            ts.filter { t -> t.notes.none { "columns" in it } }.also { assertTrue(it.isNotEmpty()) }.forEach { t ->
                val l = t.layout!!
                // Three tiles of beacons outside each column of machines: 16 wide (17 with a bypass lane).
                assertTrue(minOf(t.w, t.h) in 16..17, "${t.w} x ${t.h}")
                assertTrue(l.entities.count { it.kind == Kind.BEACON } >= plan.machines.size / 2)
                assertTrue(l.entities.filter { it.kind == Kind.MACHINE }.all { it.modules == listOf("productivity-module-3", "productivity-module-3") })
            }
        }
    }

    /** A job making [item] from [inputs] (item to edge) in assembler 3s, the inputs raw. */
    private fun assembly(item: String, rate: Double, inputs: List<Pair<String, String>>, outEdge: String) = RunConfiguration(
        targets = listOf(ConfigTarget(item, rate)), machines = listOf("assembling-machine-3"), rawResources = inputs.map { it.first },
        sources = inputs.map { (i, e) -> ConfigSource(i, edge = e) }, outputs = listOf(ConfigSource(item, edge = outEdge)),
    )

    @Test
    fun `a two-ingredient recipe gets the narrow column, an ingredient a lane`() {
        val config = assembly("automation-science-pack", 2.0, listOf("copper-plate" to "west", "iron-gear-wheel" to "north"), "east")
        val plan = buildPlan(TestData.data, config, handTuning)
        assertEquals(setOf("automation-science-pack"), plan.machines.map { it.recipe }.toSet())
        val ts = checkAll("red_science", config, plan)
        ts.forEach { t ->
            // Ten wide (eleven with a bypass lane), or the same lying down.
            assertTrue(minOf(t.w, t.h) in 10..11 && (t.w <= 11 || t.h <= 11), "${t.w} x ${t.h}")
            assertTrue(t.notes.any { "template: machine column" in it }, "${t.notes}")
        }
        // The same with each ingredient from two ports.
        var two = withSplitPort(plan, "copper-plate", false, "south", 0.8)
        two = withSplitPort(two, "iron-gear-wheel", false, "west", 0.7)
        checkAll("red_science_four_in", config, two)
    }

    @Test
    fun `two ingredients coming on one belt, a lane each, run straight into the column`() {
        val config = assembly("automation-science-pack", 2.0, listOf("copper-plate" to "west", "iron-gear-wheel" to "west"), "east")
        val base = buildPlan(TestData.data, config, handTuning)
        // A factory's lane pair: the two ports pair up (one belt into the block, an item a lane).
        val cu = base.ports.first { it.item == "copper-plate" }; val gear = base.ports.first { it.item == "iron-gear-wheel" }
        val plan = base.copy(ports = base.ports.map { p -> when (p.id) { cu.id -> p.copy(pairWith = gear.id); gear.id -> p.copy(pairWith = cu.id); else -> p } })
        checkAll("red_science_pair", config, plan).forEach { t ->
            val srcs = t.sources.filter { it.item in setOf("copper-plate", "iron-gear-wheel") }
            assertEquals(1, srcs.map { it.tile }.distinct().size, "$srcs")
            assertEquals(setOf(0, 1), srcs.map { it.lane }.toSet())
            // No junction: the belt runs in as it is, with no sideload onto it.
            assertTrue(t.layout!!.entities.none { it.kind == Kind.SPLITTER })
        }
    }

    @Test
    fun `an input port carrying more than a lane runs straight in, the other merging into it`() {
        val steel = job("steel-plate", 4.4, "iron-plate", "west", "east")
        val base = buildPlan(TestData.data, steel, handTuning)
        val plan = withSplitPort(base, "iron-plate", false, "south", 2.0)
        assertTrue(plan.terminals.any { it.role == Role.SOURCE && it.rate > 15.0 }, "${plan.terminals.filter { it.role == Role.SOURCE }}")
        checkAll("steel_big_port", steel, plan).forEach { t -> assertTrue(t.layout!!.entities.any { it.kind == Kind.SPLITTER }) }
    }

    @Test
    fun `three and four ingredients get the wide column, products out of the outer sides`() {
        val engine = assembly("engine-unit", 0.5, listOf("steel-plate" to "west", "iron-gear-wheel" to "west", "pipe" to "north"), "east")
        val plan = buildPlan(TestData.data, engine, handTuning)
        assertEquals(setOf("engine-unit"), plan.machines.map { it.recipe }.toSet())
        // (Small blocks are also offered one-sided: machines on one side of the belts only.)
        checkAll("engines", engine, plan).filter { t -> t.notes.none { "one-sided" in it } }.forEach { t ->
            assertTrue(t.w in 14..15 || t.h in 14..15, "${t.w} x ${t.h}")
            assertTrue(t.notes.any { "wide machine column" in it }, "${t.notes}")
        }
        // Engines out on two sides, one per product belt.
        checkAll("engines_two_out", engine, withSplitPort(plan, "engine-unit", true, "south", 0.2))
        val frames = assembly("flying-robot-frame", 0.5,
            listOf("electric-engine-unit" to "west", "battery" to "west", "steel-plate" to "north", "electronic-circuit" to "east"), "south")
        val fp = buildPlan(TestData.data, frames, handTuning)
        assertEquals(setOf("flying-robot-frame"), fp.machines.map { it.recipe }.toSet())
        checkAll("robot_frames", frames, fp)
    }

    @Test
    fun `two ingredients too many for a lane each take a belt each`() {
        // Copper and gears at 8/s each: over a yellow lane (7.5/s), so each gets a belt of its own in the wide column.
        val config = assembly("automation-science-pack", 8.0, listOf("copper-plate" to "west", "iron-gear-wheel" to "west"), "east")
            .copy(beltTier = "basic")
        val plan = buildPlan(TestData.data, config, handTuning)
        // Or two narrow columns side by side, each machine's share a lane each.
        checkAll("red_science_wide", config, plan).forEach { t -> assertTrue(t.notes.any { "wide machine column" in it || "2 columns" in it }, "${t.notes}") }
    }

    @Test
    fun `a single block starts from its column and searches smaller boxes only`() {
        val config = job("iron-plate", 12.5, "iron-ore", "west", "east").copy(
            search = SearchConfig(timeBudgetS = 4.0, seedsPerSize = 2, threads = 2, seed = 3))
        val plan = buildPlan(TestData.data, config, handTuning)
        val column = Templates.layouts(TestData.data, config, plan).minBy { it.area }
        val out = File("build/search/template_single")
        out.deleteRecursively()
        val r = search.Search(TestData.data, config, Tuning.defaults(), plan, out, templates = true).run()
        val best = r.best!!
        assertTrue(best.area <= column.area, "${best.w}x${best.h} vs ${column.w}x${column.h}")
        assertTrue(r.log.any { "template: machine column" in it }, r.log.take(5).toString())
        // Improvement #1 is the column; every box tried after it is smaller.
        assertTrue(File(out, "allSolves").list()!!.any { it.startsWith("001 - ${column.w}x${column.h}") }, File(out, "allSolves").list()!!.toList().toString())
        File(out, "attempts.tsv").takeIf { it.exists() }?.readLines()?.drop(1)?.forEach { line ->
            val f = line.split("\t")
            assertTrue(f[1].toInt() * f[2].toInt() < column.area, line)
        }
        // Without templates the search starts from scratch.
        val off = search.Search(TestData.data, config.copy(templates = false), Tuning.defaults(), plan, File("build/search/template_single_off"), templates = false)
        assertTrue(off.run().log.none { "template:" in it })
    }

    @Test
    fun `stone furnaces, two by two with coal, get a split column`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("iron-plate", 6.0)), machines = listOf("stone-furnace"),
            sources = listOf(ConfigSource("iron-ore", edge = "west"), ConfigSource("coal", edge = "north")),
            outputs = listOf(ConfigSource("iron-plate", edge = "east")))
        val plan = buildPlan(TestData.data, config, handTuning)
        assertTrue(plan.machines.all { it.machine == "stone-furnace" && it.w == 2 && it.demand.size == 2 }, "${plan.machines.take(2)}")
        val ts = checkAll("stone_furnaces", config, plan)
        // Ore and coal a lane each down the middle, plates out of both outer sides: 11 wide, two rows a furnace pair.
        val split = ts.filter { t -> t.notes.any { "template: split machine column" in it && "columns" !in it && "one-sided" !in it } }
        assertTrue(split.isNotEmpty(), ts.map { it.notes }.toString())
        split.forEach { t -> assertTrue(minOf(t.w, t.h) in 11..12 && maxOf(t.w, t.h) <= 2 * ((plan.machines.size + 1) / 2) + 6, "${t.w} x ${t.h}") }
    }

    @Test
    fun `electromagnetic plants, four by four, get a wide column`() {
        // (Electronic circuits in them are no good: one takes 12 cable/s, most of a fast lane.)
        val config = RunConfiguration(targets = listOf(ConfigTarget("advanced-circuit", 4.0)), machines = listOf("electromagnetic-plant"),
            rawResources = listOf("plastic-bar", "copper-cable", "electronic-circuit"), research = ResearchConfig(inserterCapacity = 7),
            sources = listOf(ConfigSource("plastic-bar", edge = "west"), ConfigSource("copper-cable", edge = "west"), ConfigSource("electronic-circuit", edge = "north")),
            outputs = listOf(ConfigSource("advanced-circuit", edge = "east")))
        val plan = buildPlan(TestData.data, config, handTuning)
        assertTrue(plan.machines.all { it.machine == "electromagnetic-plant" && it.w == 4 }, "${plan.machines.take(2)}")
        // Four rows a machine, 16 wide (two 4 x 4 machines, their tracks and four belts).
        checkAll("em_plants", config, plan).filter { t -> t.notes.none { "one-sided" in it || "columns" in it } }.forEach { t ->
            assertTrue(t.notes.any { "wide machine column" in it }, "${t.notes}")
            assertTrue(minOf(t.w, t.h) in 16..17, "${t.w} x ${t.h}")
        }
    }

    @Test
    fun `an ingredient sharing a belt passes on through a splitter off its input belt`() {
        val config = assembly("automation-science-pack", 2.0, listOf("copper-plate" to "west", "iron-gear-wheel" to "north"), "east")
        val base = buildPlan(TestData.data, config, handTuning)
        // Three more gears per second come in and leave on the south side, as a pass-through link of a factory would.
        val inPort = base.ports.first { !it.output && it.item == "iron-gear-wheel" }
        val pass = Port(base.ports.size, "iron-gear-wheel", listOf(ConfigSource("iron-gear-wheel", edge = "south")), output = true)
        val terminals = base.terminals.map { if (it.port == inPort.id) it.copy(rate = it.rate + 3.0) else it } +
            Terminal(base.terminals.size, Role.OUTPUT, "iron-gear-wheel", 3.0, 1, port = pass.id)
        val net = base.nets.first { it.item == "iron-gear-wheel" }
        val plan = base.copy(ports = base.ports + pass, terminals = terminals,
            nets = base.nets.map { if (it.id == net.id) it.copy(terminals = it.terminals + terminals.last().id, flow = it.flow + 3.0) else it })
        checkAll("red_science_pass", config, plan).forEach { t ->
            assertTrue(t.outputs.any { it.item == "iron-gear-wheel" })
            assertTrue(t.layout!!.entities.any { it.kind == Kind.SPLITTER && it.net == net.id })
        }
    }

    @Test
    fun `without long-handed inserters a split column takes over`() {
        val config = job("iron-plate", 12.5, "iron-ore", "west", "east").copy(inserters = config.InserterConfig(allowed = listOf("inserter", "fast-inserter")))
        val plan = buildPlan(TestData.data, config, handTuning)
        checkAll("iron_short_only", config, plan).forEach { t ->
            assertTrue(t.notes.any { "split machine column" in it }, "${t.notes}")
            assertTrue(t.layout!!.entities.none { it.name == "long-handed-inserter" })
        }
    }

    @Test
    fun `foundries casting plates get a casting column, molten iron piped along the outside`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("iron-plate", 20.0)), machines = listOf("foundry"),
            rawResources = listOf("molten-iron"), research = ResearchConfig(inserterCapacity = 7),
            inserters = config.InserterConfig(allowed = listOf("inserter", "long-handed-inserter", "fast-inserter", "bulk-inserter")),
            sources = listOf(ConfigSource("molten-iron", edge = "west")), outputs = listOf(ConfigSource("iron-plate", edge = "east")))
        val plan = buildPlan(TestData.data, config, handTuning)
        assertTrue(plan.machines.all { it.machine == "foundry" && it.fluidDemand.keys == setOf("molten-iron") && it.demand.isEmpty() }, "${plan.machines.take(2)}")
        checkAll("foundry_cast", config, plan).forEach { t ->
            assertTrue(t.notes.any { "casting column" in it }, "${t.notes}")
            assertTrue(t.layout!!.entities.any { it.kind == Kind.PIPE })
        }
    }

    @Test
    fun `chemical plants making plastic get a column with the gas piped along the outside`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("plastic-bar", 6.0)), machines = listOf("chemical-plant"),
            rawResources = listOf("petroleum-gas", "coal"),
            sources = listOf(ConfigSource("petroleum-gas", edge = "north"), ConfigSource("coal", edge = "west")),
            outputs = listOf(ConfigSource("plastic-bar", edge = "east")))
        val plan = buildPlan(TestData.data, config, handTuning)
        assertTrue(plan.machines.all { it.machine == "chemical-plant" && it.fluidDemand.keys == setOf("petroleum-gas") }, "${plan.machines.take(2)}")
        checkAll("plastic", config, plan).forEach { t -> assertTrue(t.notes.any { "machine column" in it }, "${t.notes}") }
    }

    @Test
    fun `mixed blocks get no template`() {
        val plan = buildPlan(TestData.data, TestData.job("t3"), handTuning)
        val notes = ArrayList<String>()
        assertTrue(Templates.layouts(TestData.data, TestData.job("t3"), plan) { notes += it }.isEmpty())
        assertTrue(notes.any { "more than one recipe" in it }, "$notes")
    }
}
