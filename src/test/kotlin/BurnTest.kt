import config.ConfigSource
import config.ConfigTarget
import config.RunConfiguration
import config.SinkConfig
import data.FurnaceEntity
import data.GameConstants
import data.Ingredient
import data.Product
import data.RootData
import plan.buildPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sinks.burn: surplus items end in the game's void building for items (as Py's burner), planned like any machine. */
class BurnTest {
    private val base get() = TestData.data

    /** As Py's burner: a furnace whose recipes, hidden and named "-pyvoid", each turn one item into a fifth of another. */
    private fun modded(): RootData {
        val furnace = base.entities.getValue("electric-furnace") as FurnaceEntity
        val burner = furnace.copy(name = "test-burner", craftingCategories = listOf("py-incineration"))
        val void = base.recipes.getValue("stone-brick").copy(name = "stone-pyvoid", category = "py-incineration", categories = null, hidden = true,
            ingredients = listOf(Ingredient("item", name = "stone", amount = 1.0)),
            products = listOf(Product("item", name = "stone", amount = 1.0, probability = 0.2)), mainProduct = null, energy = 1.5)
        return base.copy(entities = base.entities + ("test-burner" to burner), recipes = base.recipes + ("stone-pyvoid" to void))
    }

    /** Iron from lava on Vulcanus: each foundry craft leaves 10 stone. */
    private fun lava(burn: List<String>) = RunConfiguration(
        targets = listOf(ConfigTarget("iron-plate", 6.0)),
        machines = listOf("foundry", "test-burner"),
        rawResources = listOf("lava", "calcite"),
        sources = listOf(ConfigSource("lava", edge = "west"), ConfigSource("calcite", edge = "west")),
        outputs = listOf(ConfigSource("iron-plate", edge = "east"), ConfigSource("stone", edge = "east")),
        sinks = SinkConfig(burn = burn),
    )

    @Test
    fun `a burned item does not leave and its void recipe takes the surplus, only when the job names it`() {
        val data = modded()
        try {
            GameConstants.register(data)
            // Not named: the void recipe (hidden, on the skip list) is never used, and the stone leaves.
            val plain = buildPlan(data, lava(emptyList()))
            assertTrue(plain.machines.none { it.machine == "test-burner" }, "${plain.machines.map { it.recipe }.toSet()}")
            assertTrue((plain.outputs["stone"] ?: 0.0) > 1.0, "${plain.outputs}")
            for (burn in listOf(listOf("stone"), listOf("*"))) {
                val plan = buildPlan(data, lava(burn))
                val burners = plan.machines.filter { it.machine == "test-burner" }
                assertTrue(burners.isNotEmpty() && burners.all { it.recipe == "stone-pyvoid" }, "$burn: ${plan.machines.map { it.recipe }.toSet()}")
                assertEquals(0.0, plan.outputs["stone"] ?: 0.0, 1e-9)
                // Each craft takes one and gives back a fifth: the burners take all the foundries leave.
                burners.forEach { m -> assertEquals(0.2 * m.demand.getValue("stone"), m.products.getValue("stone"), 1e-9) }
                assertEquals(plain.outputs.getValue("stone"), burners.sumOf { it.demand.getValue("stone") - it.products.getValue("stone") }, 1e-6)
            }
        } finally {
            GameConstants.register(base)
        }
    }
}
