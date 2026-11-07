package data

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

val recipeGson: Gson = GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .registerTypeAdapter(Entity::class.java, EntityDeserializer())
    .registerTypeAdapterFactory(ListOrObjectAdapterFactory())
    .create()

/** Reads a classpath resource (leading slash = classpath root), or a file path if no such resource exists. */
fun readResourceOrFile(name: String): String {
    RootData::class.java.getResourceAsStream(if (name.startsWith("/")) name else "/$name")
        ?.bufferedReader()?.use { return it.readText() }
    val file = File(name)
    if (file.exists()) return file.readText()
    throw RuntimeException("Could not find $name on the classpath or on disk")
}

private val loaded = java.util.concurrent.ConcurrentHashMap<String, RootData>()

/**
 * Loads game data (recipes.json, or a file the exporter mod wrote) and links products and ingredients to items and
 * fluids; its machines, belts, inserters and poles set GameConstants' tables ([GameConstants.register]): one game's
 * data is in use at a time, the one loaded last. Each file is read once.
 */
fun loadRootData(name: String = "recipes.json"): RootData = loaded.getOrPut(name) {
    // Fluids made at several temperatures become fluids of their own (see Temperatures.kt).
    val data = withFluidTemperatures(recipeGson.fromJson(readResourceOrFile(name), RootData::class.java))
    interlinkProductsWithItems(data)
    data
}.also { GameConstants.register(it) }   // the tables follow the data loaded last

/** Crafting speed of a machine at a quality, from recipes.json. */
fun RootData.craftingSpeed(machine: String, quality: String): Double {
    val entity = entities[machine] ?: error("Unknown machine '$machine'")
    val speeds = when (entity) {
        is AssemblingMachineEntity -> entity.craftingSpeed
        is FurnaceEntity -> entity.craftingSpeed
        is RocketSiloEntity -> entity.craftingSpeed
        else -> error("'$machine' is not a crafting machine")
    }
    return speeds[quality] ?: error("Machine '$machine' has no crafting speed for quality '$quality'")
}

fun RootData.craftingCategories(machine: String): List<String> = when (val entity = entities[machine]) {
    is AssemblingMachineEntity -> entity.craftingCategories
    is FurnaceEntity -> entity.craftingCategories
    is RocketSiloEntity -> entity.craftingCategories
    else -> emptyList()
}
