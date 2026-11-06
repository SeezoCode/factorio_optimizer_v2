import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.Tuning
import plan.Port
import plan.Role
import plan.Terminal
import plan.buildPlan
import search.missingLinkPorts
import search.runTrial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassThroughTest {
    @Test
    fun `two items passing through a block on two one-belt input ports each keep their output port`() {
        // A block of a split factory: gears from plates that come in on two link ports (north 3/s, west 6/s); 4 of
        // the 9 plates/s pass on to two other blocks (1.5/s east, 2.5/s south). Every port is one belt, as links are.
        val config = RunConfiguration(
            targets = listOf(ConfigTarget("iron-gear-wheel", 2.5)),
            sources = listOf(ConfigSource("iron-plate", edge = "north")),
            outputs = listOf(ConfigSource("iron-gear-wheel", edge = "east")),
        )
        val base = buildPlan(TestData.data, config)
        val plates = base.ports.first { it.item == "iron-plate" }
        val west = Port(base.ports.size, "iron-plate", listOf(ConfigSource("iron-plate", edge = "west")), single = true)
        val east = Port(base.ports.size + 1, "iron-plate", listOf(ConfigSource("iron-plate", edge = "east")), output = true, single = true)
        val south = Port(base.ports.size + 2, "iron-plate", listOf(ConfigSource("iron-plate", edge = "south")), output = true, single = true)
        val n = base.terminals.size
        val added = listOf(
            Terminal(n, Role.SOURCE, "iron-plate", 6.0, 1, port = west.id),
            Terminal(n + 1, Role.OUTPUT, "iron-plate", 1.5, 1, port = east.id),
            Terminal(n + 2, Role.OUTPUT, "iron-plate", 2.5, 1, port = south.id),
        )
        // The north port brings 3/s: what the gears need beyond the west port's share.
        val terminals = base.terminals.map { if (it.port == plates.id) it.copy(rate = 3.0) else it } + added
        val plan = base.copy(
            ports = base.ports.map { if (it.id == plates.id) it.copy(single = true) else it } + listOf(west, east, south),
            terminals = terminals,
            rawRates = base.rawRates + ("iron-plate" to 9.0),
            outputs = base.outputs + ("iron-plate" to 4.0),
            nets = base.nets.map { if (it.item == "iron-plate") it.copy(terminals = it.terminals + added.map { t -> t.id }, flow = it.flow + 4.0) else it },
        )
        val tries = listOf(14, 18).flatMap { s -> (1L..4L).map { seed -> runTrial(TestData.data, plan, config, Tuning.defaults(), s, s, seed) } }
        val r = tries.firstOrNull { it.success }
        assertTrue(r != null, "no valid layout: ${tries.map { "${it.stage} ${it.errors.take(2)}" }}")
        // The router used to drop the second output fed from one input port.
        assertEquals(emptyList(), missingLinkPorts(plan, r.sources, r.outputs))
        assertEquals(setOf(east.id, south.id), r.outputs.filter { it.item == "iron-plate" }.map { it.port }.toSet())
        assertEquals(setOf(plates.id, west.id), r.sources.map { it.port }.toSet())
    }
}
