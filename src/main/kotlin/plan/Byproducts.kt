package plan

import config.RunConfiguration
import data.Item
import data.RootData
import data.TEMPERATURE_MARK

/** Suffix of a raw resource made as a by-product, which leaves instead of being used ("stone#byproduct"). */
const val BYPRODUCT_SUFFIX = "${TEMPERATURE_MARK}byproduct"

/**
 * Raw resources made as by-products ([RunConfiguration.rawByproducts] "leave", the default). Pyanodon's copper
 * screener and crusher give stone besides copper; if that stone went to the machines that also take raw stone, they
 * would take the raw stone (a full belt, a full pipe) as readily, and in a game the by-product backs up whenever they
 * need less than both bring: then the screener stops, and copper with it. (A 57-machine Py factory stood at 3% of
 * its plan this way: block 2 took raw stone, block 3's crushers could not hand theirs on, small parts stopped, and with
 * them the science that block 2's planter boxes were waiting to go into.)
 *
 * So a raw resource (the job's raw_resources and sources, or the data's) that a recipe with other products makes, and
 * does not take, is made as an item of its own, "name#byproduct", that nothing uses: it leaves the block like any
 * surplus, and what needs the resource takes it raw. A machine making only the resource is left as it is (when raw
 * supply fills its belt, only it waits). Names go back to the game's with [data.gameName], as temperatures do.
 */
fun withRawByproducts(data: RootData, config: RunConfiguration): RootData {
    if (config.rawByproducts != "leave") return data
    val raw = (config.rawResources?.toSet() ?: defaultRaw(data)) + config.sources.map { it.name }
    val targets = config.targets.map { it.name }.toSet()
    val renamed = HashSet<String>()
    val recipes = data.recipes.mapValues { (_, r) ->
        val made = r.products.filter { it.expectedAmount > 0 }.map { it.name }.toSet()
        if (made.size < 2) return@mapValues r
        val taken = r.ingredients.map { it.name }.toSet()
        val leave = made.filter { it in raw && it !in taken && it !in targets }.toSet()
        if (leave.isEmpty() || leave.size == made.size) return@mapValues r
        renamed += leave
        r.copy(products = r.products.map { p -> if (p.name in leave) p.copy(name = p.name + BYPRODUCT_SUFFIX, item = null) else p },
            mainProduct = r.mainProduct?.let { m -> if (m.name in leave) m.copy(name = m.name + BYPRODUCT_SUFFIX, item = null) else m })
    }
    if (renamed.isEmpty()) return data
    val items = LinkedHashMap<String, Item>(data.items)
    val fluids = LinkedHashMap(data.fluids)
    renamed.forEach { n ->
        data.items[n]?.let { items[n + BYPRODUCT_SUFFIX] = it.copy(name = n + BYPRODUCT_SUFFIX) }
        data.fluids[n]?.let { fluids[n + BYPRODUCT_SUFFIX] = it.copy(name = n + BYPRODUCT_SUFFIX) }
    }
    val out = data.copy(recipes = recipes, items = items, fluids = fluids)
    // Products point at their items (stack sizes and the like).
    out.recipes.values.forEach { r ->
        r.products.forEach { p -> if (p.item == null) p.item = items[p.name] }
        r.mainProduct?.let { m -> if (m.item == null) m.item = items[m.name] }
    }
    return out
}
