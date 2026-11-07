package data

/**
 * Fluid temperatures. In the game steam at 500° and steam at 250° are the same fluid at different temperatures, and a
 * recipe may take only steam of at least 500° (Pyanodon's `cool-steam-500-to-250` takes 500° steam and gives 250°
 * steam). To the planner, router and validator a fluid is its name, so such a recipe would take and give the same
 * fluid on one pipe network. Here a fluid that recipes make at more than one temperature becomes fluids of their own:
 * at its default temperature it keeps its plain name (so jobs and the base game are unchanged), at any other it is
 * named "name#temperature" ("steam#500"). A product takes its temperature's name; an ingredient that accepts a range
 * becomes recipe variants, one per temperature in its range that something makes ("cool-steam-500-to-250#1000"
 * takes steam#1000), or keeps the recipe's name when there is just one. Names go back to the game's with [gameName]
 * (blueprints, where an infinity pipe also gets the temperature, [temperatureOf]).
 */
const val TEMPERATURE_MARK = '#'

/** The game's name of a fluid or recipe named here with a temperature ("steam#500" -> "steam"). */
fun gameName(name: String): String = name.substringBefore(TEMPERATURE_MARK)

/** The temperature in a fluid's name ("steam#500" -> 500.0), null for a plain name. */
fun temperatureOf(name: String): Double? = name.substringAfter(TEMPERATURE_MARK, "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()

private fun fmt(t: Double): String = if (t == Math.floor(t) && kotlin.math.abs(t) < 1e12) t.toLong().toString() else t.toString()

/** [name] at temperature [t] (its default temperature keeps the plain name). */
fun fluidAt(name: String, t: Double, default: Double): String = if (t == default) name else "$name$TEMPERATURE_MARK${fmt(t)}"

fun withFluidTemperatures(data: RootData): RootData {
    fun default(f: String) = data.fluids[f]?.defaultTemperature ?: 15.0
    // Temperatures each fluid is made at (products without one come out at the fluid's default).
    val made = HashMap<String, MutableSet<Double>>()
    data.recipes.values.forEach { r ->
        r.products.filter { it.type == "fluid" }.forEach { p -> made.getOrPut(p.name) { sortedSetOf() } += (p.temperature ?: default(p.name)) }
    }
    // Pumped or mined fluids come out at the resource's temperature (Py's geothermal water at 3000°).
    data.resources?.values?.forEach { res ->
        res.products.orEmpty().filter { it.type == "fluid" }.forEach { p -> made.getOrPut(p.name) { sortedSetOf() } += (p.temperature ?: default(p.name)) }
    }
    // Only fluids whose data gives a default temperature (older data files have none: left as they are).
    val split = made.filter { (f, ts) -> ts.size > 1 && data.fluids[f]?.defaultTemperature != null }.keys
    if (split.isEmpty()) return data
    // Raw fluids (pumped or mined, or from no recipe) are at their default temperature.
    split.forEach { made.getValue(it) += default(it) }

    fun accepts(ing: Ingredient, t: Double): Boolean {
        ing.temperature?.let { return t == it }
        return t >= (ing.minimumTemperature ?: Double.NEGATIVE_INFINITY) && t <= (ing.maximumTemperature ?: Double.POSITIVE_INFINITY)
    }
    val recipes = LinkedHashMap<String, Recipe>()
    data.recipes.values.forEach { r ->
        val products = r.products.map { p ->
            if (p.type == "fluid" && p.name in split) p.copy(name = fluidAt(p.name, p.temperature ?: default(p.name), default(p.name))) else p
        }
        val main = r.mainProduct?.let { m -> products.firstOrNull { gameName(it.name) == m.name && it.type == m.type } ?: m }
        // Per ingredient of a split fluid: the temperatures it may take.
        val choices = r.ingredients.map { ing ->
            if (!ing.isFluid || ing.name !in split) listOf(ing)
            else made.getValue(ing.name).filter { accepts(ing, it) }.map { t -> ing.copy(name = fluidAt(ing.name, t, default(ing.name))) }
                // Nothing made in range: a temperature nothing makes, so the recipe is never planned without a source.
                .ifEmpty { listOf(ing.copy(name = fluidAt(ing.name, ing.temperature ?: ing.minimumTemperature ?: default(ing.name), default(ing.name)))) }
        }
        var combos = listOf(emptyList<Ingredient>())
        choices.forEach { opts -> combos = combos.flatMap { c -> opts.map { c + it } }.take(16) }
        combos.forEach { ings ->
            val temps = ings.filter { it.isFluid }.mapNotNull { temperatureOf(it.name) }
            val name = if (combos.size == 1 || temps.isEmpty()) r.name else r.name + TEMPERATURE_MARK + temps.joinToString(TEMPERATURE_MARK.toString()) { fmt(it) }
            recipes[name] = r.copy(name = name, ingredients = ings, products = products, mainProduct = main)
        }
    }
    val fluids = LinkedHashMap(data.fluids)
    split.forEach { f ->
        val base = data.fluids[f] ?: return@forEach
        made.getValue(f).forEach { t ->
            val n = fluidAt(f, t, default(f))
            if (n != f) fluids[n] = base.copy(name = n, translatedName = "${base.translatedName} ${fmt(t)}°")
        }
    }
    // Resources name their fluids the same way, so raw inputs from them match.
    val resources = data.resources?.mapValues { (_, res) ->
        res.copy(products = res.products?.map { p ->
            if (p.type == "fluid" && p.name in split) p.copy(name = fluidAt(p.name, p.temperature ?: default(p.name), default(p.name))) else p
        })
    }
    return data.copy(recipes = recipes, fluids = fluids, resources = resources)
}
