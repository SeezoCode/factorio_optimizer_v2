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
    private fun m(id: Int, ins: List<String>, outs: List<String>) = m(id, ins.associateWith { 1.0 }, outs.associateWith { 1.0 })
    private fun m(id: Int, ins: Map<String, Double>, outs: Map<String, Double>) = plan.MachineInstance(
        id, "r$id", "m", 3, 3, outputRate = outs.values.first(), product = outs.keys.first(), demand = ins, products = outs)

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

    @Test
    fun `a by-product feeding a production loop gets an overflow, one off any loop does not`() {
        val ms = listOf(
            m(0, listOf("tar"), listOf("aromatics", "co2")),          // tar distillation
            m(1, listOf("co2", "fertilizer"), listOf("moss")),         // co2 goes into the loop
            m(2, mapOf("moss" to 1.0), mapOf("fertilizer" to 1.5, "substrate" to 1.0)),   // the loop: fertilizer -> moss -> fertilizer, growing
            m(3, listOf("aromatics", "substrate"), listOf("science")),
            m(4, listOf("ore"), listOf("plate", "slag")),              // off any loop
            m(5, listOf("slag"), listOf("brick")),
        )
        assertEquals(setOf("co2", "fertilizer", "substrate"), plan.overflowItems(ms, setOf("tar", "ore"), setOf("science", "plate", "brick")))
        // No loops: no overflows.
        assertEquals(emptySet(), plan.overflowItems(ms.filter { it.id >= 4 }, setOf("ore"), emptySet()))
    }

    @Test
    fun `a machine of several products keeps one without an overflow to stop it, unless that would stall its users`() {
        // Py's electrolysis: hydroxide (all used) with chlorine and hydrogen (their surplus leaves). An overflow on the
        // hydroxide would let the electrolyser run flat out on the saline water others need.
        val electrolysis = listOf(
            m(0, listOf("brine"), listOf("hydroxide", "chlorine", "hydrogen")),
            m(1, listOf("hydroxide"), listOf("soap")),
            m(2, listOf("chlorine"), listOf("bleach")),
        )
        assertEquals(emptySet(), plan.overflowItems(electrolysis, setOf("brine"), setOf("soap", "bleach", "chlorine", "hydrogen"), coupled = true))
        // A slaughterhouse: meat (its main product) and bones both used. Bones get a way out; the meat stops the
        // slaughterhouse when its users stop taking it (with every product overflowing nothing ever would).
        val slaughter = listOf(
            m(0, listOf("animal"), listOf("meat", "bones")),
            m(1, listOf("meat"), listOf("food")),
            m(2, listOf("bones"), listOf("meal")),
        )
        assertEquals(setOf("bones"), plan.overflowItems(slaughter, setOf("animal"), setOf("food", "meal"), coupled = true))
        // Py science 1's moss farm: moss (main) goes to a lab that waits for flasks made from the farm's coarse. Stopped by
        // its moss, the farm would stop the coarse the lab is waiting for: the coarse stops it instead, the moss overflows.
        val moss = listOf(
            m(0, listOf("water"), listOf("moss", "coarse")),
            m(1, listOf("coarse"), listOf("iron-oxide")),
            m(2, listOf("iron-oxide"), listOf("flask")),
            m(3, listOf("moss", "flask"), listOf("science")),
        )
        assertEquals(setOf("moss"), plan.overflowItems(moss, setOf("water"), setOf("science"), coupled = true))
        // Each needs the other's line: no product can stop the machine safely, both keep a way out.
        val tangled = listOf(
            m(0, listOf("ore"), listOf("a", "b")),
            m(1, listOf("a"), listOf("a2")),
            m(2, listOf("b"), listOf("b2")),
            m(3, listOf("a", "b2"), listOf("x")),
            m(4, listOf("b", "a2"), listOf("y")),
        )
        assertEquals(setOf("a", "b"), plan.overflowItems(tangled, setOf("ore"), setOf("x", "y"), coupled = true))
    }

    @Test
    fun `items a loop only passes round, one for one, get no overflow`() {
        // Py's heat bricks: heated by gas, cooled making hot air; the ring holds as many bricks as were put in.
        val ms = listOf(
            m(0, mapOf("stone-brick" to 0.07, "hot-gas" to 0.35), mapOf("warm-stone-brick" to 0.07, "cool-gas" to 0.35)),
            m(1, mapOf("warm-stone-brick" to 0.07, "air" to 0.5), mapOf("stone-brick" to 0.07, "hot-air" to 0.5)),
            m(2, mapOf("cool-gas" to 0.35, "salt" to 0.2), mapOf("hot-gas" to 0.33, "used-salt" to 0.2)),
            m(3, mapOf("hot-air" to 0.5), mapOf("science" to 0.02)),
        )
        val over = plan.overflowItems(ms, setOf("air", "salt"), setOf("science", "used-salt"))
        assertTrue("stone-brick" !in over && "warm-stone-brick" !in over, "bricks only go round: $over")
        // A brick furnace beside the ring, its bricks all used off the ring (planter boxes): still a ring of carriers.
        val furnace = m(4, mapOf("stone" to 0.2), mapOf("stone-brick" to 0.04))
        val boxes = m(5, mapOf("stone-brick" to 0.04, "wood" to 0.02), mapOf("planter-box" to 0.01))
        val beside = plan.overflowItems(ms + furnace + boxes, setOf("air", "salt", "stone", "wood"), setOf("science", "used-salt", "planter-box"))
        assertTrue("stone-brick" !in beside && "warm-stone-brick" !in beside, "bricks only go round: $beside")
        // One making more than is used off the ring would fill it up: then the bricks need their way out.
        val more = m(4, mapOf("stone" to 0.5), mapOf("stone-brick" to 0.1))
        assertTrue("stone-brick" in plan.overflowItems(ms + more + boxes, setOf("air", "salt", "stone", "wood"), setOf("science", "used-salt", "planter-box")))
    }
}
