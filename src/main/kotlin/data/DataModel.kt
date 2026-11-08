package data

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Type

data class RootData(
    @SerializedName("game_version") val gameVersion: String,
    val groups: Map<String, Group>,
    val quality: Map<String, Quality>,
    @SerializedName("quality_names") val qualityNames: List<String>,
    var recipes: Map<String, Recipe>,
    val items: Map<String, Item>,
    val fluids: Map<String, Fluid>,
    val entities: Map<String, Entity>,
    /** Belts, inserters, poles and pipes of the game (exporter mod data; the built-in recipes.json may lack it). */
    val logistics: LogisticsData? = null,
    /** What can be mined or pumped, with what mining gives (exporter data; null in older files). */
    val resources: Map<String, ResourceData>? = null,
    /** 2.0 mod-data prototypes as the mods wrote them (e.g. Pyanodon's farm buildings), by name. */
    val modData: Map<String, ModData>? = null,
    /**
     * Where the game puts a recipe's fluids in a machine, when that is not the n-th fluid in the n-th box of its kind
     * (or its fluidbox_index): recipe -> machine -> "in:fluid" / "out:fluid" -> box number among its kind (exporter
     * probes it in game; see [fluidBoxes]).
     */
    val fluidBoxAssignment: Map<String, Map<String, Map<String, Int>>>? = null,
)

/**
 * Which input and output box (1-based among boxes of its kind) each fluid of [recipe] uses in [machine]: the recipe's
 * fluidbox_index, else the game's own choice as the exporter measured it, else the n-th fluid in the n-th box.
 */
fun RootData.fluidBoxes(recipe: Recipe, machine: String?): Pair<Map<String, Int>, Map<String, Int>> {
    val game = machine?.let { fluidBoxAssignment?.get(gameName(recipe.name))?.get(it) }
    val ins = recipe.ingredients.filter { it.isFluid }.mapIndexed { i, ing ->
        ing.name to (ing.fluidboxIndex ?: game?.get("in:" + gameName(ing.name)) ?: (i + 1))
    }.toMap()
    val outs = recipe.products.filter { it.type == "fluid" }.mapIndexed { i, p ->
        p.name to (p.fluidboxIndex ?: game?.get("out:" + gameName(p.name)) ?: (i + 1))
    }.toMap()
    return ins to outs
}

/** A resource on the map: what mining it gives, and the fluid mining it needs (Py's borax takes syngas). */
data class ResourceData(
    val name: String, val category: String? = null, val infinite: Boolean? = null,
    val products: List<Product>? = null, val requiredFluid: String? = null, val fluidAmount: Double? = null,
)

data class ModData(val dataType: String? = null, val data: com.google.gson.JsonElement? = null)

/** A fluid box of a crafting machine as the exporter writes it (machine facing north). */
data class FluidBoxData(val index: Int, val productionType: String, val filter: String? = null, val connections: List<PipeConnectionData>? = null)

/**
 * One pipe connection of a fluid box: [positions] (north, east, south, west orientation) of the tile inside the
 * machine, from the machine's centre; the pipe goes on the tile next to it in [direction] (for north).
 */
data class PipeConnectionData(
    val flowDirection: String, val connectionType: String, val direction: String?, val positions: List<Vec>,
    val maxUndergroundDistance: Int? = null,
)

data class Vec(val x: Double, val y: Double)

data class LogisticsData(
    val belts: List<BeltData>? = null, val undergrounds: List<UndergroundData>? = null, val splitters: List<SplitterData>? = null,
    val inserters: List<InserterData>? = null, val poles: List<PoleData>? = null,
    val pipesToGround: List<PipeToGroundData>? = null,
)
data class BeltData(val name: String, val speed: Double, val itemsPerSecond: Double, val underground: String? = null, val hidden: Boolean? = null)
data class UndergroundData(val name: String, val speed: Double, val maxDistance: Int, val hidden: Boolean? = null)
data class SplitterData(val name: String, val speed: Double, val width: Int, val height: Int, val hidden: Boolean? = null)
data class InserterData(
    val name: String, val rotationSpeed: Double, val extensionSpeed: Double, val pickup: Vec, val drop: Vec,
    val bulk: Boolean? = null, val filterCount: Int? = null, val stackSizeBonus: Int? = null, val energySource: String? = null, val hidden: Boolean? = null,
)
data class PoleData(val name: String, val width: Int, val height: Int, val supplyAreaDistance: Double, val maxWireDistance: Double, val hidden: Boolean? = null)
data class PipeToGroundData(val name: String, val maxDistance: Int? = null, val hidden: Boolean? = null)

data class Group(
    val name: String,
    val type: String,
    val order: String,
    val orderInRecipe: String
)

data class Quality(
    val name: String,
    val level: Int,
    val nextProbability: Double,
    val beaconPowerUsageMultiplier: Double,
    val miningDrillResourceDrainMultiplier: Double,
    val group: String,
    val subgroup: String,
    val next: String? = null,
    val translatedName: String,
    /**
     * Factorio 2.1: the chance to go on to the tier after the next once a craft got one tier better
     * ([nextProbability] is then only the first step's factor, 1). 2.0 data has none: [nextProbability] is both.
     */
    val chainProbability: Double? = null,
) {
    /** Chance a craft that reached this tier goes one further. */
    val chain: Double get() = chainProbability ?: nextProbability
}

data class Recipe(
    val name: String,
    val category: String,
    val ingredients: List<Ingredient> = emptyList(),
    val products: List<Product> = emptyList(),
    val mainProduct: Product? = null,
    val allowedEffects: AllowedEffects,
    val maximumProductivity: Int,
    val energy: Double,
    val order: String,
    val group: String,
    val subgroup: String,
    val enabled: Boolean,
    val productivityBonus: Int,
    val translatedName: String,
    /** Factorio 2.1: every category the recipe is in (a machine of any of them makes it); 2.0 data has [category] only. */
    val categories: List<String>? = null,
    /** Hidden from the player (exporter data): placeholders, recycling, mod internals. */
    val hidden: Boolean? = null,
) {
    /** Every category of the recipe: [categories], or the single [category] of 2.0 data. */
    val allCategories: List<String> get() = categories ?: listOf(category)
    /** The recipe is in at least one of [cats]. */
    fun inAny(cats: Collection<String>): Boolean = allCategories.any { it in cats }
}

/** The part of a product's shared random roll that yields it (Factorio 2.1). */
data class ProbabilityRange(val min: Double = 0.0, val max: Double = 1.0)

data class Ingredient(
    val type: String,
    var item: Item? = null,
    var fluid: Fluid? = null,
    val name: String,
    val amount: Double,
    val fluidboxMultiplier: Int? = null,
    val ignoredByStats: Double? = null,
    val fluidboxIndex: Int? = null,
    /** A fluid ingredient's accepted temperatures: exactly [temperature], or [minimumTemperature]..[maximumTemperature]. */
    val temperature: Double? = null,
    val minimumTemperature: Double? = null,
    val maximumTemperature: Double? = null,
) {
    val isFluid: Boolean get() = type == "fluid"
}

data class Product(
    val type: String,
    var item: Item? = null,
    val name: String,
    val amount: Double,
    val probability: Double? = null,
    val temperature: Double? = null,
    val extraCountFraction: Double? = null,
    val fluidboxIndex: Int? = null,
    val fluidboxMultiplier: Int? = null,
    val percentSpoiled: Double? = null,
    val ignoredByStats: Double? = null,
    val ignoredByProductivity: Double? = null,
    /** Factorio 2.1: [probability] became an independent chance and a share of a roll common to the recipe's products. */
    val independentProbability: Double? = null,
    val sharedProbability: ProbabilityRange? = null,
    /** A random amount between these instead of [amount] (mods: Krastorio's sand from crushing). */
    val amountMin: Double? = null,
    val amountMax: Double? = null,
) {
    /** Items per successful craft: [amount], or the middle of [amountMin]..[amountMax]. */
    val meanAmount: Double get() = if (amountMin != null && amountMax != null && amount == 0.0) (amountMin + amountMax) / 2 else amount
    /** The chance a craft yields this product. */
    val chance: Double get() = (independentProbability ?: probability ?: 1.0) * (sharedProbability?.let { it.max - it.min } ?: 1.0)
    /** Expected items per craft, counting probability and extra fraction. */
    val expectedAmount: Double get() = meanAmount * chance + (extraCountFraction ?: 0.0)
}

data class AllowedEffects(
    val consumption: Boolean,
    val speed: Boolean,
    val productivity: Boolean,
    val pollution: Boolean,
    val quality: Boolean
)

data class Item(
    val name: String,
    val type: String,
    val order: String,
    val group: String,
    val subgroup: String,
    val stackSize: Int,
    val weight: Double,
    val fuelValue: Long,
    val rocketLaunchProducts: com.google.gson.JsonElement? = null,
    val translatedName: String,
    val fuelCategory: String? = null,
    val flags: List<String>? = null,
    val moduleEffects: ModuleEffects? = null,
    /** Factorio 2.1: an item may be fuel of several categories; 2.0 data has [fuelCategory] only. */
    val fuelCategories: List<String>? = null,
    /** What is left after burning it (Py: ash from chemical fuels). */
    val burntResult: String? = null,
    /** A module item's category: which machines take it ([AssemblingMachineEntity.allowedModuleCategories]). */
    val category: String? = null,
) {
    /** Every fuel category of the item (empty if it is no fuel). */
    val allFuelCategories: List<String> get() = fuelCategories ?: listOfNotNull(fuelCategory)
}

data class ModuleEffects(
    val consumption: Double? = null,
    val speed: Double? = null,
    val productivity: Double? = null,
    val pollution: Double? = null,
    val quality: Double? = null
)

data class Fluid(
    val name: String,
    val order: String,
    val group: String,
    val subgroup: String,
    val fuelValue: Long,
    val translatedName: String,
    /** Temperature it has when nothing gives it another (exporter data). */
    val defaultTemperature: Double? = null,
    val maxTemperature: Double? = null,
)

interface Entity {
    val name: String; val type: String; val order: String; val group: String
    val subgroup: String; val moduleInventorySize: Int; val width: Int
    val height: Int; val flags: List<String>; val translatedName: String
}

/** A machine that crafts recipes; exporter data adds its fluid boxes. */
interface CraftingEntity : Entity {
    val fluidBoxes: List<FluidBoxData>?
    /** Its fluid connections exist only while it is set to a recipe with fluids. */
    val fluidBoxesOffWhenNoFluidRecipe: Boolean?
    val hidden: Boolean?
}
data class BoilerEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, override val moduleInventorySize: Int, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
/** A machine powered by burning a fuel fluid (energy_source "fluid"): its own fluid box for the fuel. */
data class FluidEnergyData(val fluidBox: FluidBoxData, val burnsFluid: Boolean? = null, val effectivity: Double? = null,
    val fluidUsagePerTick: Double? = null, val scaleFluidUsage: Boolean? = null)
data class EffectReceiver(val baseEffect: Map<String, Double>, val usesModuleEffects: Boolean, val usesBeaconEffects: Boolean, val usesSurfaceEffects: Boolean)
data class FurnaceEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val energyConsumption: Double, val drain: Double, val energySource: String, val fuelCategories: List<String>? = null, val allowedModuleCategories: List<String>? = null, val fluidEnergy: FluidEnergyData? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String, override val fluidBoxes: List<FluidBoxData>? = null, override val fluidBoxesOffWhenNoFluidRecipe: Boolean? = null, override val hidden: Boolean? = null) : CraftingEntity
data class AssemblingMachineEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val energyConsumption: Double, val drain: Double, val energySource: String, val fuelCategories: List<String>? = null, val allowedModuleCategories: List<String>? = null, val fluidEnergy: FluidEnergyData? = null, val fixedRecipe: String? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String, override val fluidBoxes: List<FluidBoxData>? = null, override val fluidBoxesOffWhenNoFluidRecipe: Boolean? = null, override val hidden: Boolean? = null) : CraftingEntity
data class BeaconEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val allowedEffects: List<String>, override val moduleInventorySize: Int, val distributionEffectivity: Double, val distributionEffectivityBonusPerQualityLevel: Double, val supplyAreaDistance: Map<String, Int>, val energyConsumption: Double, val drain: Double, val energySource: String, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
data class RocketSiloEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val rocketPartsRequired: Int, val energyConsumption: Double, val drain: Double, val energySource: String, val fixedRecipe: String? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String, override val fluidBoxes: List<FluidBoxData>? = null, override val fluidBoxesOffWhenNoFluidRecipe: Boolean? = null, override val hidden: Boolean? = null) : CraftingEntity


class EntityDeserializer : JsonDeserializer<Entity> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Entity {
        val jsonObject = json.asJsonObject
        val type = jsonObject.get("type").asString
        val clazz = when (type) {
            "boiler" -> BoilerEntity::class.java
            "furnace" -> FurnaceEntity::class.java
            "assembling-machine" -> AssemblingMachineEntity::class.java
            "beacon" -> BeaconEntity::class.java
            "rocket-silo" -> RocketSiloEntity::class.java
            else -> throw JsonParseException("Unknown entity type: $type")
        }
        return context.deserialize(jsonObject, clazz)
    }
}

// --- NEW AND IMPROVED: A TypeAdapterFactory to handle the {} vs [] problem ---
class ListOrObjectAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
        // We are only interested in handling List types
        if (!List::class.java.isAssignableFrom(typeToken.rawType)) {
            return null // Let Gson's default factories handle it
        }

        // Get the default adapter for this list type from Gson.
        // This is the crucial part: we delegate the actual array parsing.
        val delegateAdapter = gson.getDelegateAdapter(this, typeToken)

        // Return our custom adapter that intercepts the JSON
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                // We only care about reading, so just delegate writing
                delegateAdapter.write(out, value)
            }

            override fun read(input: JsonReader): T? {
                // Check the next token without consuming it
                if (input.peek() == JsonToken.BEGIN_OBJECT) {
                    input.skipValue() // Safely consume and discard the empty {} object
                    // Since we know T is a List, we can cast an empty list to it
                    @Suppress("UNCHECKED_CAST")
                    return emptyList<Any?>() as T
                }
                // If it's not an object, it must be an array or null.
                // Let the default delegate adapter handle it.
                return delegateAdapter.read(input)
            }
        }.nullSafe()
    }
}

/** Links products and ingredients to their item, and fluid ingredients to their fluid. */
fun interlinkProductsWithItems(data: RootData) = data.recipes.forEach {
    it.value.products.forEach { it.item = data.items[it.name] }
    it.value.mainProduct?.item = data.items[it.value.mainProduct!!.name]
    it.value.ingredients.forEach {
        it.item = data.items[it.name]
        if (it.isFluid) it.fluid = data.fluids[it.name]
    }
}

fun String.formatToLength(length: Int, padChar: Char = ' '): String {
    return this.padEnd(length, padChar).take(length)
}
