package plan

import data.Recipe
import data.RootData
import data.craftingCategories
import data.craftingSpeed
import kotlin.math.ceil

/**
 * item -> the recipe that makes it. Only recipes in the allowed categories that the machine can craft
 * count; recipe names need not equal item names. When several recipes make an item, [overrides] picks,
 * then the recipe named like the item, then the alphabetically first one.
 */
class RecipeIndex(
    data: RootData,
    allowedCategories: Collection<String>,
    forbiddenRecipes: Collection<String> = emptyList(),
    private val overrides: Map<String, String> = emptyMap(),
    machine: String? = null,
    private val warn: (String) -> Unit = { println("WARNING: $it") },
) {
    private val byItem: Map<String, List<Recipe>>

    init {
        val machineCategories = machine?.let { data.craftingCategories(it).toSet() }
        byItem = data.recipes.values
            .filter { it.inAny(allowedCategories) && it.name !in forbiddenRecipes }
            .filter { machineCategories == null || it.inAny(machineCategories) }
            .filter { it.mainProduct != null }
            .groupBy { it.mainProduct!!.name }
            .mapValues { (_, list) -> list.sortedBy { it.name } }
        overrides.forEach { (item, recipe) ->
            require(byItem[item]?.any { it.name == recipe } == true) {
                "recipe_overrides: '$recipe' is not an allowed recipe making '$item'"
            }
        }
    }

    private val warned = mutableSetOf<String>()

    fun recipeFor(item: String): Recipe? {
        val options = byItem[item] ?: return null
        if (options.size == 1) return options[0]
        overrides[item]?.let { name -> return options.first { it.name == name } }
        val pick = options.firstOrNull { it.name == item } ?: options[0]
        if (warned.add(item)) warn("several recipes make '$item' (${options.map { it.name }}); using '${pick.name}'. Set recipe_overrides to choose.")
        return pick
    }
}

/** Items per second of every recipe's main product, and of every raw input. */
data class Rates(
    val recipeOutput: Map<Recipe, Double>,
    val raw: Map<String, Double>,
    val fluids: Map<String, Double>,
)

/**
 * Expands targets (main product items/s) into recipe output rates and raw-input rates.
 * Raw = no allowed recipe makes it. Fluid ingredients are collected separately (they get infinity pipes).
 */
fun computeRates(targets: List<Pair<String, Double>>, index: RecipeIndex, warn: (String) -> Unit = { println("WARNING: $it") }): Rates {
    val recipeOutput = LinkedHashMap<Recipe, Double>()
    val raw = LinkedHashMap<String, Double>()
    val fluids = LinkedHashMap<String, Double>()

    fun demand(item: String, rate: Double, depth: Int) {
        require(depth < 64) { "Recipe loop while expanding '$item'" }
        val recipe = index.recipeFor(item)
        if (recipe == null) {
            raw.merge(item, rate, Double::plus)
            return
        }
        recipeOutput.merge(recipe, rate, Double::plus)
        val crafts = rate / recipe.mainProduct!!.expectedAmount
        recipe.ingredients.forEach { ing ->
            if (ing.isFluid) fluids.merge(ing.name, crafts * ing.amount, Double::plus)
            else demand(ing.name, crafts * ing.amount, depth + 1)
        }
    }

    targets.forEach { (item, amount) ->
        if (index.recipeFor(item) == null) warn("target '$item' has no allowed recipe; it would be a raw input, skipped")
        else demand(item, amount, 0)
    }
    return Rates(recipeOutput, raw, fluids)
}

/** Machines needed: ceil(r * e / (a * s)), with a small tolerance so 2.0000001 does not become 3. */
fun machineCount(outputRate: Double, recipe: Recipe, craftingSpeed: Double): Int {
    val exact = outputRate * recipe.energy / (recipe.mainProduct!!.expectedAmount * craftingSpeed)
    return maxOf(1, ceil(exact - 1e-9).toInt())
}

fun RootData.machineCount(outputRate: Double, recipe: Recipe, machine: String, quality: String): Int =
    machineCount(outputRate, recipe, craftingSpeed(machine, quality))
