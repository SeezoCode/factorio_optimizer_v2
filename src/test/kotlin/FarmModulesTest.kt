import config.RunConfiguration
import data.RootData
import data.recipeGson
import plan.farmModule
import plan.machineModules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Farms (a base speed effect of -100%: Pyanodon's farms and reproductive complexes) are filled with a module the
 * recipe takes too: Py's breeding recipes take their own animal only, and the game refuses any other.
 */
class FarmModulesTest {
    private fun recipe(name: String, cats: String?) = """
        "$name": {"name": "$name", "category": "rc", "ingredients": [], "products": [{"type": "item", "name": "cub", "amount": 1}],
          "allowed_effects": {"consumption": true, "speed": true, "productivity": true, "pollution": true, "quality": true},
          "maximum_productivity": 3, "energy": 5, "order": "a", "group": "g", "subgroup": "s", "enabled": true,
          "productivity_bonus": 0, "translated_name": "$name"${cats?.let { ", \"allowed_module_categories\": [$it]" } ?: ""}}"""

    private fun module(n: String, speed: Double) = """"$n": {"name": "$n", "type": "module", "order": "$n", "group": "g", "subgroup": "s",
        "stack_size": 50, "module_effects": {"speed": $speed}, "category": "$n", "translated_name": "$n"}"""

    private val game: RootData = recipeGson.fromJson("""{"game_version": "2.0", "groups": {}, "quality": {}, "quality_names": ["normal"],
        "items": {${module("kmauts", 0.25)}, ${module("mukmoux", 1.0)}, "cub": {"name": "cub", "type": "item", "order": "a", "group": "g",
          "subgroup": "s", "stack_size": 50, "translated_name": "cub"}},
        "fluids": {},
        "entities": {"rc": {"name": "rc", "type": "assembling-machine", "order": "a", "group": "g", "subgroup": "s",
          "crafting_speed": {"normal": 0.5}, "crafting_categories": ["rc"], "allowed_effects": ["speed", "consumption", "pollution"],
          "module_inventory_size": 8, "allowed_module_categories": ["kmauts", "mukmoux"],
          "effect_receiver": {"base_effect": {"speed": -1}, "uses_module_effects": true, "uses_beacon_effects": true, "uses_surface_effects": true},
          "energy_consumption": 1000, "drain": 10, "energy_source": "electric", "width": 22, "height": 22, "flags": [], "translated_name": "rc"}},
        "recipes": {${recipe("mukmoux-calf", "\"mukmoux\"")}, ${recipe("any-cub", null)}}}""", RootData::class.java)
        .also { data.interlinkProductsWithItems(it) }

    private val job = RunConfiguration.parse("""{"targets": [{"name": "cub", "amount": 1}]}""")

    @Test
    fun `a breeding recipe that takes one animal gets that animal`() {
        val r = game.recipes.getValue("mukmoux-calf")
        assertEquals("mukmoux", farmModule(game, "rc", r))
        assertEquals(List(8) { "mukmoux" }, machineModules(game, job, r, "rc"))
    }

    @Test
    fun `a recipe with no limit gets the plainest module the machine takes`() {
        assertEquals("kmauts", farmModule(game, "rc", game.recipes.getValue("any-cub")))
        assertEquals("kmauts", farmModule(game, "rc"))
    }
}
