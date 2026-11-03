import blocks.Templates
import config.ConfigSource
import config.ConfigTarget
import config.ResearchConfig
import config.RunConfiguration
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
            val plan = buildPlan(TestData.data, config)
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
        val base = buildPlan(TestData.data, config)
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
        val base = buildPlan(TestData.data, config)
        val twoOut = withSplitPort(base, "iron-plate", true, "east", 5.0)
        assertTrue(checkAll("iron_two_out", config, twoOut).all { t -> t.layout!!.entities.any { it.kind == Kind.SPLITTER } })
        val twoIn = withSplitPort(base, "iron-ore", false, "south", 6.0)
        checkAll("iron_two_in", config, twoIn)
        checkAll("iron_two_in_two_out", config, withSplitPort(twoIn, "iron-plate", true, "east", 5.0))
    }

    @Test
    fun `gears come from an assembler column too, once inserters keep up`() {
        // An assembler 3 makes 2.5 gears/s from 5 plates/s: no inserter takes that from a belt without research.
        val slow = job("iron-gear-wheel", 5.0, "iron-plate", "west", "south")
        val notes = ArrayList<String>()
        assertTrue(Templates.layouts(TestData.data, slow, buildPlan(TestData.data, slow)) { notes += it }.isEmpty())
        assertTrue(notes.any { "no inserter takes" in it }, "$notes")
        // Assembler 1 at full inserter capacity research: long-handed inserters move 1.2 x 3 a second.
        val config = slow.copy(machine = "assembling-machine-1", machines = listOf("assembling-machine-1", "electric-furnace"),
            research = ResearchConfig(inserterCapacity = 7))
        checkAll("gears", config, buildPlan(TestData.data, config))
    }

    @Test
    fun `mixed blocks get no template`() {
        val plan = buildPlan(TestData.data, TestData.job("t3"))
        val notes = ArrayList<String>()
        assertTrue(Templates.layouts(TestData.data, TestData.job("t3"), plan) { notes += it }.isEmpty())
        assertTrue(notes.any { "more than one recipe" in it }, "$notes")
    }
}
