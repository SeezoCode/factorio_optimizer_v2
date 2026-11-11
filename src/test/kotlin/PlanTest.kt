import config.ConfigSource
import config.DEFAULT_CATEGORIES
import config.ConfigTarget
import config.RunConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plan.ProductionPlan
import plan.RecipeIndex
import plan.bestInserterRate
import plan.Role
import plan.buildPlan
import plan.machineCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanTest {
    private val data get() = TestData.data

    @Test
    fun `a plan read back from its JSON is the same plan (a resumed run reuses it)`() {
        val plan = buildPlan(data, RunConfiguration(targets = listOf(ConfigTarget("logistic-science-pack", 0.5))))
        assertEquals(plan, config.strictJson.decodeFromString(ProductionPlan.serializer(), plan.toJson()))
    }

    @Test
    fun `machine count uses crafting speed`() {
        val circuit = data.recipes.getValue("electronic-circuit")
        // 2 circuits/s: 2 * 0.5 / (1 * 0.75) = 1.33 -> 2 with AM2; / 1.25 = 0.8 -> 1 with AM3.
        assertEquals(2, data.machineCount(2.0, circuit, "assembling-machine-2", "normal"))
        assertEquals(1, data.machineCount(2.0, circuit, "assembling-machine-3", "normal"))
        // Exactly 2.0 machines must not round up to 3.
        val red = data.recipes.getValue("automation-science-pack")
        assertEquals(2, data.machineCount(0.5, red, "assembling-machine-3", "normal"))
    }

    @Test
    fun `recipe index finds recipes by product, not by name`() {
        val config = RunConfiguration(targets = emptyList())
        val index = RecipeIndex(data, DEFAULT_CATEGORIES, machine = config.machine)
        assertEquals("transport-belt", index.recipeFor("transport-belt")?.name)
        assertNull(index.recipeFor("iron-plate"), "smelting is excluded, so plates are raw")
        assertNull(index.recipeFor("plastic-bar"))
        assertFailsWith<IllegalArgumentException> {
            RecipeIndex(data, DEFAULT_CATEGORIES, overrides = mapOf("iron-gear-wheel" to "pipe"))
        }
    }

    @Test
    fun `T2 gives one circuit machine, two cable machines and one cable net`() {
        val plan = buildPlan(data, TestData.job("t2"), handTuning)
        assertEquals(1, plan.machines.count { it.recipe == "electronic-circuit" })
        assertEquals(2, plan.machines.count { it.recipe == "copper-cable" })
        assertEquals(3, plan.machines.size)

        val cableNets = plan.nets.filter { it.item == "copper-cable" }
        assertEquals(1, cableNets.size)
        val cable = plan.netTerminals(cableNets[0])
        assertEquals(2, cable.count { it.role == Role.PRODUCER })
        assertEquals(1, cable.count { it.role == Role.CONSUMER })
        assertEquals(6.0, cableNets[0].flow, 1e-9)

        assertEquals(setOf("iron-plate", "copper-plate"), plan.ports.filter { !it.output }.map { it.item }.toSet())
        assertEquals(listOf("electronic-circuit"), plan.ports.filter { it.output }.map { it.item })
        assertEquals(2.0, plan.rawRates.getValue("iron-plate"), 1e-9)
        assertEquals(3.0, plan.rawRates.getValue("copper-plate"), 1e-9)
        // The product leaves on a belt through its output port.
        val circuit = plan.netTerminals(plan.nets.single { it.item == "electronic-circuit" })
        assertEquals(1, circuit.count { it.role == Role.PRODUCER })
        assertEquals(2.0, circuit.single { it.role == Role.OUTPUT }.rate, 1e-9)
        assertTrue(plan.terminals.none { it.role == Role.SINK })

        // Chest mode: no product net, one chest sink instead.
        val chests = buildPlan(data, TestData.job("t2").copy(sinks = config.SinkConfig("chest")), handTuning)
        assertTrue(chests.nets.none { it.item == "electronic-circuit" }, "the final product has no consumers, so no net")
        val sinks = chests.terminals.filter { it.role == Role.SINK }
        assertEquals(1, sinks.size)
        assertEquals(2.0, sinks[0].rate, 1e-9)
    }

    @Test
    fun `T3 nets match the hand calculation`() {
        val plan = buildPlan(data, TestData.job("t3"), handTuning)
        val expected = Json.parseToJsonElement(TestData.text("jobs/t3_expected.json")).jsonObject

        expected.getValue("machines").jsonObject.forEach { (recipe, n) ->
            assertEquals(n.jsonPrimitive.int, plan.machines.count { it.recipe == recipe }, "machines for $recipe")
        }
        assertEquals(expected.getValue("machines").jsonObject.values.sumOf { it.jsonPrimitive.int }, plan.machines.size)

        val nets = expected.getValue("nets").jsonObject
        assertEquals(nets.keys, plan.nets.map { it.item }.toSet())
        nets.forEach { (item, e) ->
            val net = plan.nets.single { it.item == item }
            val ts = plan.netTerminals(net)
            val o = e.jsonObject
            assertEquals(o.getValue("flow").jsonPrimitive.double, net.flow, 1e-9, "flow of $item")
            assertEquals(o.getValue("producers").jsonPrimitive.int, ts.count { it.role == Role.PRODUCER }, "producers of $item")
            assertEquals(o.getValue("sources").jsonPrimitive.int, ts.count { it.role == Role.SOURCE }, "sources of $item")
            assertEquals(o.getValue("consumers").jsonPrimitive.int, ts.count { it.role == Role.CONSUMER }, "consumers of $item")
            assertEquals(1, net.beltCount)
        }
        expected.getValue("outputs").jsonObject.forEach { (item, n) ->
            assertEquals(n.jsonPrimitive.int, plan.terminals.count { it.role == Role.OUTPUT && it.item == item })
        }
        assertEquals(0, plan.terminals.count { it.role == Role.SINK }, "products leave through ports, not chests")
        expected.getValue("raw").jsonObject.forEach { (item, r) ->
            assertEquals(r.jsonPrimitive.double, plan.rawRates.getValue(item), 1e-9)
        }
    }

    @Test
    fun `T4 takes acid through a fluid port into both processing unit machines`() {
        val plan = buildPlan(data, TestData.job("t4"), handTuning)
        val pu = plan.machines.filter { it.recipe == "processing-unit" }
        assertEquals(2, pu.size)
        assertTrue(pu.all { it.needsFluid == "sulfuric-acid" && it.fluidIn == mapOf("sulfuric-acid" to 1) })
        val acid = plan.nets.single { it.item == "sulfuric-acid" }
        assertTrue(acid.fluid)
        assertEquals(1, plan.netTerminals(acid).count { it.role == Role.SOURCE })
        assertEquals(2, plan.netTerminals(acid).count { it.role == Role.CONSUMER })
        assertTrue(plan.ports.single { it.item == "sulfuric-acid" }.fluid)
        assertEquals(1.0, plan.fluidRates.getValue("sulfuric-acid"), 1e-9)
        assertEquals(4, plan.machines.count { it.recipe == "copper-cable" })
    }

    @Test
    fun `terminal inserter counts cover their rate`() {
        val config = TestData.job("t4")
        val plan = buildPlan(data, config, handTuning)
        plan.terminals.filter { it.machine != null }.forEach { t ->
            val perInserter = plan.bestRate(config, t.role)
            assertTrue(t.k * perInserter >= t.rate - 1e-9, "terminal ${t.id} (${t.item}, ${t.role}) k=${t.k}")
            assertTrue((t.k - 1) * perInserter < t.rate, "terminal ${t.id} uses more inserters than needed")
        }
        // 14.4 cable/s into 2 circuit machines is 7.2/s each; fast inserters from a belt give 2.31 * 0.7.
        val circuitCable = plan.terminals.filter { it.role == Role.CONSUMER && it.item == "copper-cable" && plan.machines[it.machine!!].recipe == "electronic-circuit" }
        assertTrue(circuitCable.all { it.k == 5 }, "k = ${circuitCable.map { it.k }}")
    }

    private fun ProductionPlan.bestRate(config: RunConfiguration, role: Role) =
        bestInserterRate(config, fromBelt = role == Role.CONSUMER)

    @Test
    fun `unknown config keys are an error`() {
        assertFailsWith<Exception> { RunConfiguration.parse("""{ "targets": [], "belt_teir": "fast" }""") }
        assertFailsWith<Exception> { RunConfiguration.parse("""{ "targets": [], "tricks": { "Q9": true } }""") }
        assertFailsWith<Exception> { RunConfiguration.parse("""{ "targets": [], "tricks": { "B8": true } }""") }
        assertFailsWith<Exception> { RunConfiguration.parse("""{ "targets": [], "sinks": { "mode": "output_ports" } }""") }
        val ok = RunConfiguration.parse("""{ "targets": [{ "name": "pipe", "amount": 1 }] }""")
        assertTrue(ok.trick("I3"))
        assertTrue(!ok.trick("B8"))
    }

    @Test
    fun `the old config files still parse`() {
        assertNotNull(RunConfiguration.load("config.json"))
        assertNotNull(RunConfiguration.load("example_config.json"))
    }

    /** Every item and fluid balances: made + supplied = used + leaving. */
    private fun assertBalanced(plan: ProductionPlan) {
        val made = HashMap<String, Double>(); val used = HashMap<String, Double>()
        plan.machines.forEach { m ->
            m.products.forEach { (k, v) -> made.merge(k, v, Double::plus) }
            (m.demand + m.fluidDemand).forEach { (k, v) -> used.merge(k, v, Double::plus) }
        }
        (made.keys + used.keys + plan.rawRates.keys + plan.outputs.keys).forEach { item ->
            val balance = (made[item] ?: 0.0) + (plan.rawRates[item] ?: 0.0) - (used[item] ?: 0.0) - (plan.outputs[item] ?: 0.0)
            assertTrue(kotlin.math.abs(balance) < 1e-6 * maxOf(1.0, made[item] ?: 0.0), "$item off by $balance")
        }
    }

    @Test
    fun `plastic from crude oil balances, with refineries and chemical plants`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("plastic-bar", 5.0)))
        val plan = buildPlan(data, config, handTuning)
        assertBalanced(plan)
        assertTrue(plan.machines.any { it.machine == "oil-refinery" })
        assertTrue(plan.machines.filter { it.recipe == "plastic-bar" }.all { it.machine == "chemical-plant" && it.fluidIn == mapOf("petroleum-gas" to 1) })
        assertEquals(setOf("coal", "crude-oil"), plan.rawRates.keys.intersect(setOf("coal", "crude-oil", "water")).let { it - "water" })
        assertTrue(plan.nets.any { it.item == "petroleum-gas" && it.fluid })
        // Basic oil processing puts crude on input 2 and petroleum on output 3 (fluidbox_index in the data).
        plan.machines.filter { it.recipe == "basic-oil-processing" }.forEach { assertEquals(mapOf("crude-oil" to 2), it.fluidIn); assertEquals(mapOf("petroleum-gas" to 3), it.fluidOut) }
    }

    @Test
    fun `advanced oil processing with cracking leaves no by-products`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("plastic-bar", 5.0)), forbiddenRecipes = listOf("basic-oil-processing"))
        val plan = buildPlan(data, config, handTuning)
        assertBalanced(plan)
        assertTrue(plan.machines.any { it.recipe == "advanced-oil-processing" })
        assertTrue(plan.machines.any { it.recipe == "heavy-oil-cracking" } && plan.machines.any { it.recipe == "light-oil-cracking" })
        assertTrue(plan.outputs.filterKeys { it != "plastic-bar" }.values.all { it < 1e-6 }, "surplus ${plan.outputs}")
    }

    @Test
    fun `by-products without a use leave the block`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("uranium-238", 0.5)), forbiddenRecipes = listOf("kovarex-enrichment-process"))
        val plan = buildPlan(data, config, handTuning)
        assertBalanced(plan)
        assertTrue((plan.outputs["uranium-235"] ?: 0.0) > 0.0, "U-235 must leave the block: ${plan.outputs}")
        assertTrue(plan.machines.all { it.machine == "centrifuge" })
    }

    @Test
    fun `listed sources are not made inside the block`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("electronic-circuit", 1.0)),
            sources = listOf(ConfigSource("iron-plate", edge = "west")))
        val plan = buildPlan(data, config, handTuning)
        assertBalanced(plan)
        assertTrue(plan.machines.none { it.product == "iron-plate" })
        assertTrue(plan.machines.any { it.product == "copper-plate" && it.machine == "electric-furnace" }, "copper is smelted from ore")
        assertTrue("copper-ore" in plan.rawRates)
    }

    @Test
    fun `burner furnaces get coal as an input`() {
        val config = RunConfiguration(targets = listOf(ConfigTarget("iron-plate", 2.0)), machines = listOf("stone-furnace"))
        val plan = buildPlan(data, config, handTuning)
        assertBalanced(plan)
        val f = plan.machines.first()
        assertEquals("stone-furnace", f.machine)
        assertTrue((f.demand["coal"] ?: 0.0) > 0.0, "fuel: ${f.demand}")
    }

    @Test
    fun `plan json round trips`() {
        val plan = buildPlan(data, TestData.job("t3"), handTuning)
        val again = config.strictJson.decodeFromString(ProductionPlan.serializer(), plan.toJson())
        assertEquals(plan, again)
    }

    @Test
    fun `a job's tuning section overrides single knobs and rejects unknown ones`() {
        val job = RunConfiguration.parse("""{"targets": [{"name": "iron-gear-wheel", "amount": 1.0}],
            "tuning": {"route": {"p_mult": 1.3, "endgame_rounds": 5}, "place": {"beta2": 0.5}}}""")
        val base = config.Tuning()
        val t = base.withOverrides(job.tuning)
        assertEquals(1.3, t.route.pMult)
        assertEquals(5, t.route.endgameRounds)
        assertEquals(0.5, t.place.beta2)
        assertEquals(base.route.hFac, t.route.hFac)
        assertEquals(base.place.copy(beta2 = 0.5), t.place)
        assertEquals(base.search, t.search)
        assertFailsWith<IllegalArgumentException> {
            RunConfiguration.parse("""{"targets": [{"name": "iron-gear-wheel", "amount": 1.0}], "tuning": {"route": {"p_mul": 1.3}}}""")
        }
    }
}
