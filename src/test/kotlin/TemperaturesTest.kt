import data.RootData
import data.gameName
import data.recipeGson
import data.temperatureOf
import data.withFluidTemperatures
import data.fluidBoxes
import emit.splitQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemperaturesTest {
    private fun recipe(name: String, ings: String, prods: String) = """
        "$name": {"name": "$name", "category": "chemistry", "ingredients": [$ings], "products": [$prods],
          "allowed_effects": {"consumption": true, "speed": true, "productivity": true, "pollution": true, "quality": true},
          "maximum_productivity": 3, "energy": 1, "order": "a", "group": "g", "subgroup": "s", "enabled": true,
          "productivity_bonus": 0, "translated_name": "$name"}"""

    private fun game(defaults: Boolean): RootData {
        val t = if (defaults) ", \"default_temperature\": 15" else ""
        val json = """{"game_version": "2.1", "groups": {}, "quality": {}, "quality_names": ["normal"], "items": {}, "entities": {},
            "fluids": {"steam": {"name": "steam", "order": "a", "group": "g", "subgroup": "s", "fuel_value": 0, "translated_name": "Steam"$t},
                       "water": {"name": "water", "order": "a", "group": "g", "subgroup": "s", "fuel_value": 0, "translated_name": "Water"$t}},
            "recipes": {${listOf(
                recipe("boil", """{"type": "fluid", "name": "water", "amount": 10}""", """{"type": "fluid", "name": "steam", "amount": 10, "temperature": 500}"""),
                recipe("boil-hot", """{"type": "fluid", "name": "water", "amount": 10}""", """{"type": "fluid", "name": "steam", "amount": 10, "temperature": 1000}"""),
                recipe("cool", """{"type": "fluid", "name": "steam", "amount": 10, "minimum_temperature": 500, "maximum_temperature": 3000}""",
                    """{"type": "fluid", "name": "steam", "amount": 20, "temperature": 250}"""),
                recipe("use-warm", """{"type": "fluid", "name": "steam", "amount": 10, "minimum_temperature": 200, "maximum_temperature": 300}""",
                    """{"type": "fluid", "name": "water", "amount": 10}"""),
            ).joinToString(",")}}}"""
        return withFluidTemperatures(recipeGson.fromJson(json, RootData::class.java))
    }

    @Test
    fun `a fluid made at several temperatures becomes fluids of their own, and ranges become recipe variants`() {
        val d = game(defaults = true)
        // Steam at 500, 1000 and 250 (and 15, its default, for raw steam) are fluids of their own.
        assertTrue(listOf("steam#500", "steam#1000", "steam#250").all { it in d.fluids }, "${d.fluids.keys}")
        assertEquals("steam#500", d.recipes.getValue("boil").products.single().name)
        // "cool" takes 500° or more: one variant per temperature made in that range, each giving 250° steam.
        assertEquals(setOf("cool#500", "cool#1000"), d.recipes.keys.filter { it.startsWith("cool") }.toSet())
        assertEquals("steam#1000", d.recipes.getValue("cool#1000").ingredients.single().name)
        assertEquals("steam#250", d.recipes.getValue("cool#1000").products.single().name)
        // "use-warm" accepts 200..300: only 250° steam, so it keeps its name.
        assertEquals("steam#250", d.recipes.getValue("use-warm").ingredients.single().name)
        // Water is only made at its default temperature: unchanged.
        assertEquals("water", d.recipes.getValue("use-warm").products.single().name)
        // Back to the game's names (and the quality split still works on a variant of a recipe).
        assertEquals("cool", gameName("cool#1000"))
        assertEquals(1000.0, temperatureOf("steam#1000"))
        assertNull(temperatureOf("steam"))
        assertEquals("cool" to "rare", splitQuality("cool#1000@rare"))
    }

    @Test
    fun `data without default temperatures is left as it is`() {
        val d = game(defaults = false)
        assertEquals(setOf("boil", "boil-hot", "cool", "use-warm"), d.recipes.keys)
        assertEquals("steam", d.recipes.getValue("cool").ingredients.single().name)
    }

    @Test
    fun `fluids go to the boxes the game measured, else fluidbox_index, else in order`() {
        val json = """{"game_version": "2.1", "groups": {}, "quality": {}, "quality_names": ["normal"], "items": {}, "entities": {}, "fluids": {},
            "recipes": {${recipe("formamide", """{"type": "fluid", "name": "carbon-dioxide", "amount": 100}, {"type": "fluid", "name": "methanol", "amount": 100},
                {"type": "fluid", "name": "ammonia", "amount": 100, "fluidbox_index": 2}""",
                """{"type": "fluid", "name": "formamide", "amount": 100}, {"type": "fluid", "name": "methanol", "amount": 100}""")}},
            "fluid_box_assignment": {"formamide": {"fbreactor-mk01": {"in:methanol": 3, "out:methanol": 3}}}}"""
        val d = recipeGson.fromJson(json, RootData::class.java)
        val r = d.recipes.getValue("formamide")
        val (ins, outs) = d.fluidBoxes(r, "fbreactor-mk01")
        assertEquals(mapOf("carbon-dioxide" to 1, "methanol" to 3, "ammonia" to 2), ins)
        assertEquals(mapOf("formamide" to 1, "methanol" to 3), outs)
        // Another machine: no measurement, so in order.
        assertEquals(mapOf("carbon-dioxide" to 1, "methanol" to 2, "ammonia" to 2), d.fluidBoxes(r, "chemical-plant").first)
    }
}
