import config.RunConfiguration
import data.RootData
import data.gameName
import data.recipeGson
import plan.BYPRODUCT_SUFFIX
import plan.withRawByproducts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ByproductsTest {
    private fun recipe(name: String, ings: String, prods: String) = """
        "$name": {"name": "$name", "category": "crafting", "ingredients": [$ings], "products": [$prods],
          "allowed_effects": {"consumption": true, "speed": true, "productivity": true, "pollution": true, "quality": true},
          "maximum_productivity": 3, "energy": 1, "order": "a", "group": "g", "subgroup": "s", "enabled": true,
          "productivity_bonus": 0, "translated_name": "$name"}"""

    private fun item(n: String) = """"$n": {"name": "$n", "type": "item", "order": "a", "group": "g", "subgroup": "s", "stack_size": 50, "translated_name": "$n"}"""
    private fun i(n: String, a: Int) = """{"type": "item", "name": "$n", "amount": $a}"""

    private val game: RootData = recipeGson.fromJson("""{"game_version": "2.1", "groups": {}, "quality": {}, "quality_names": ["normal"],
        "items": {${listOf("copper-ore", "grade-2", "stone", "gravel").joinToString(",") { item(it) }}}, "fluids": {}, "entities": {},
        "recipes": {${listOf(
            recipe("screen", i("copper-ore", 5), i("grade-2", 2) + "," + i("stone", 1)),
            recipe("crush", i("stone", 4), i("gravel", 4)),
            recipe("quarry", i("gravel", 4), i("stone", 1)),
            recipe("wash", i("stone", 2) + "," + i("copper-ore", 1), i("stone", 1) + "," + i("grade-2", 1)),
        ).joinToString(",")}}}""", RootData::class.java).also { data.interlinkProductsWithItems(it) }

    private fun job(extra: String = "") = RunConfiguration.parse("""{"targets": [{"name": "gravel", "amount": 1}], "raw_resources": ["copper-ore", "stone"]$extra}""")

    @Test
    fun `a raw resource made besides other products leaves as an item of its own`() {
        val d = withRawByproducts(game, job())
        val screen = d.recipes.getValue("screen")
        assertEquals(listOf("grade-2", "stone$BYPRODUCT_SUFFIX"), screen.products.map { it.name })
        assertEquals("stone", gameName("stone$BYPRODUCT_SUFFIX"))
        assertTrue("stone$BYPRODUCT_SUFFIX" in d.items)
        assertEquals(50, screen.products[1].item?.stackSize)
        // Nothing takes it: the crusher still takes plain (raw) stone.
        assertEquals("stone", d.recipes.getValue("crush").ingredients.single().name)
        // A machine making only the resource, and a recipe taking it back (a catalyst), are left as they are.
        assertEquals("stone", d.recipes.getValue("quarry").products.single().name)
        assertEquals(listOf("stone", "grade-2"), d.recipes.getValue("wash").products.map { it.name })
    }

    @Test
    fun `raw_byproducts use keeps the data as it is`() {
        assertSame(game, withRawByproducts(game, job(""", "raw_byproducts": "use"""")))
    }
}

class LoopSeedsTest {
    private fun m(id: Int, ins: List<String>, outs: List<String>) = plan.MachineInstance(
        id, "r$id", "m", 3, 3, outputRate = 1.0, product = outs.first(),
        demand = ins.associateWith { 1.0 }, products = outs.associateWith { 1.0 })

    @Test
    fun `a loop through several recipes gets one seed, a chain none`() {
        // fertilizer -> seaweed -> moss -> fertilizer, with science off the loop; a plain chain beside it.
        val ms = listOf(
            m(0, listOf("fertilizer"), listOf("seaweed")),
            m(1, listOf("seaweed"), listOf("moss")),
            m(2, listOf("moss"), listOf("fertilizer", "substrate")),
            m(3, listOf("substrate"), listOf("science")),
            m(4, listOf("ore"), listOf("plate")),
        )
        val seeds = plan.loopSeeds(ms, setOf("ore"))
        assertEquals(1, seeds.size)
        assertTrue(seeds.single() in setOf("fertilizer", "seaweed", "moss"))
        // What also comes in from outside starts the loop by itself.
        assertEquals(emptyList(), plan.loopSeeds(ms, setOf("ore", "moss")))
        // A recipe's own catalyst is not a loop here (it gets a priming input).
        assertEquals(emptyList(), plan.loopSeeds(listOf(m(0, listOf("methanol", "ammonia"), listOf("methanol", "formamide"))), emptySet()))
    }
}
