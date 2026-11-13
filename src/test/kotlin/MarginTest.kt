import config.RunConfiguration
import config.ConfigTarget
import config.Tuning
import plan.buildPlan
import plan.solveRates
import kotlin.test.Test
import kotlin.test.assertTrue

/** planner.margin: every item made and used inside is in surplus with makers slow and users fast by the margin. */
class MarginTest {
    private fun job() = RunConfiguration(targets = listOf(ConfigTarget("chemical-science-pack", 1.0)), machines = listOf("assembling-machine-3", "electric-furnace", "chemical-plant", "oil-refinery"))

    @Test
    fun surplusOfEverythingInside() {
        val exact = solveRates(TestData.data, job(), Tuning.defaults().planner) {}
        val d = 0.02
        val notes = ArrayList<String>()
        val lp = solveRates(TestData.data, job(), Tuning.defaults().planner.copy(margin = d)) { notes += it }
        assertTrue(notes.any { "margin" in it }, "the margin stage ran: $notes")
        // No recipe below its exact rate, the same recipes.
        assertTrue(lp.crafts.keys.map { it.recipe.name }.toSet() == exact.crafts.keys.map { it.recipe.name }.toSet())
        exact.crafts.forEach { (c, v) -> assertTrue(lp.crafts.entries.first { it.key.recipe.name == c.recipe.name }.value >= v - 1e-9, "${c.recipe.name} below its exact rate") }
        // Makers at (1 - d), users at (1 + d): still enough of everything made and used inside.
        val madeAt = HashMap<String, Double>(); val usedAt = HashMap<String, Double>()
        lp.crafts.forEach { (c, x) ->
            c.recipe.products.forEach { p -> madeAt.merge(p.name, p.expectedAmount * x, Double::plus) }
            c.recipe.ingredients.forEach { i -> usedAt.merge(i.name, i.amount * x, Double::plus) }
        }
        val inside = madeAt.keys.intersect(usedAt.keys) - lp.rings - lp.supply.keys
        assertTrue(inside.isNotEmpty())
        inside.forEach { i ->
            // (Productivity and catalysts aside: the test job has neither, so gross amounts are the net ones.)
            assertTrue(madeAt.getValue(i) * (1 - d) >= usedAt.getValue(i) * (1 + d) - 1e-6, "$i: made ${madeAt[i]} against used ${usedAt[i]}")
        }
        // The target still comes out with its makers slow.
        assertTrue(madeAt.getValue("chemical-science-pack") * (1 - d) >= 1.0 - 1e-6)
        // And the plan builds: machines sized for the raised rates, the surplus leaving as by-products.
        val plan = buildPlan(TestData.data, job(), Tuning.defaults().let { it.copy(planner = it.planner.copy(margin = d)) })
        assertTrue(plan.margin == d && plan.machines.isNotEmpty())
    }
}
