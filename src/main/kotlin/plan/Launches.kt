package plan

import data.Ingredient
import data.Product
import data.RocketSiloEntity
import data.RootData
import data.TEMPERATURE_MARK

/** Marks a rocket part recipe that also launches a payload ("rocket-part#launch-capsule"); the game's name is before it. */
const val LAUNCH_MARK = "${TEMPERATURE_MARK}launch-"

/** Whether [recipe] is a rocket part recipe with a payload (see [withLaunchProducts]). */
fun isLaunch(recipe: String): Boolean = LAUNCH_MARK in recipe

/**
 * Rocket launch products. Pyanodon's production, utility and space science need what comes back from launching an item
 * in a rocket (a capsule brings 500 filled tholins vessels, a satellite destabilized toxirus). For each rocket silo's
 * part recipe and each item with launch products, a variant of the part recipe that also takes 1 / rocket_parts_required
 * of the item (inserted into the silo, it rides the rocket) and gives 1 / rocket_parts_required of what comes back (in
 * the silo's result slots, taken out like any product). Its parts are not counted as launched; the blueprint sets the
 * game's part recipe.
 */
fun withLaunchProducts(data: RootData): RootData {
    val payloads = data.items.values.mapNotNull { item ->
        val lp = item.rocketLaunchProducts?.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
        val products = lp.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.get("name")?.asString ?: return@mapNotNull null
            val amount = o.get("amount")?.asDouble ?: ((o.get("amount_min")?.asDouble ?: 0.0) + (o.get("amount_max")?.asDouble ?: 0.0)) / 2
            Product(type = o.get("type")?.asString ?: "item", name = name, amount = amount * (o.get("probability")?.asDouble ?: 1.0))
        }.filter { it.amount > 0 }
        if (products.isEmpty()) null else item.name to products
    }
    if (payloads.isEmpty()) return data
    val recipes = LinkedHashMap(data.recipes)
    data.entities.values.filterIsInstance<RocketSiloEntity>().filter { it.rocketPartsRequired > 1 }.forEach { silo ->
        val part = silo.fixedRecipe?.let { data.recipes[it] } ?: return@forEach
        val n = silo.rocketPartsRequired.toDouble()
        payloads.forEach { (payload, back) ->
            val name = part.name + LAUNCH_MARK + payload
            val products = back.map { p -> p.copy(amount = p.amount / n, item = data.items[p.name]) }
            recipes[name] = part.copy(
                name = name,
                ingredients = part.ingredients + Ingredient(type = "item", item = data.items[payload], name = payload, amount = 1 / n),
                products = products, mainProduct = products.first(),
                // The game hides its part recipe (a silo is set to it, never chosen); a payload's variant is a choice.
                hidden = false,
            )
        }
    }
    return data.copy(recipes = recipes)
}
