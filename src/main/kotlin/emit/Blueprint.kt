package emit

import data.GameConstants
import geom.CircuitCondition
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.floor

/** Factorio 2.0 blueprint JSON. Nulls are omitted; unknown keys are tolerated when reading game exports. */
val blueprintJson = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class BlueprintRoot(val blueprint: Blueprint)

@Serializable
data class Blueprint(
    val item: String = "blueprint",
    val label: String,
    val icons: List<BpIcon> = emptyList(),
    val entities: List<BpEntity> = emptyList(),
    /** [entity_a, connector_a, entity_b, connector_b]; 5 is a pole's copper connector. Top level in 2.0. VERIFY */
    val wires: List<List<Int>> = emptyList(),
    /** Floor tiles; omitted when there are none. */
    val tiles: List<BpTile>? = null,
    val version: Long = GameConstants.BLUEPRINT_VERSION,
)

/** A floor tile; its position is the tile's top-left corner (entity positions are centres). */
@Serializable
data class BpTile(val name: String, val position: BpTilePosition)

@Serializable
data class BpTilePosition(val x: Int, val y: Int)

/** 2.0 signals have no "type" for items. */
@Serializable
data class BpSignal(val name: String, val type: String? = null)

@Serializable
data class BpIcon(val signal: BpSignal, val index: Int)

@Serializable
data class BpPosition(val x: Double, val y: Double)

@Serializable
data class BpItemFilter(val index: Int, val name: String, val quality: String? = null, val comparator: String? = null)

@Serializable
data class BpEntity(
    @SerialName("entity_number") val entityNumber: Int,
    val name: String,
    val position: BpPosition,
    /** 16-way; omitted when north. */
    val direction: Int? = null,
    /** "input" or "output" for underground belts (required for them). */
    val type: String? = null,
    val recipe: String? = null,
    @SerialName("recipe_quality") val recipeQuality: String? = null,
    @SerialName("infinity_settings") val infinitySettings: JsonObject? = null,
    @SerialName("use_filters") val useFilters: Boolean? = null,
    val filters: List<BpItemFilter>? = null,
    @SerialName("filter_mode") val filterMode: String? = null,
    val bar: Int? = null,
    val mirror: Boolean? = null,
    /** Circuit settings; for a crafting machine its enable condition. VERIFY (v19) */
    @SerialName("control_behavior") val controlBehavior: JsonObject? = null,
    /**
     * Item requests: modules into slots, [{id: {name}, items: {in_inventory: [{inventory, stack}]}}], stacks
     * 0-based; inventory 1 for a beacon, 4 for a crafting machine. VERIFY (v20)
     */
    val items: kotlinx.serialization.json.JsonArray? = null,
)

/** Layout -> 2.0 blueprint. Entity numbers are layout index + 1. */
fun Layout.toBlueprint(quality: String = "normal"): Blueprint {
    val bpEntities = entities.mapIndexed { i, e ->
        BpEntity(
            entityNumber = i + 1,
            name = e.name,
            position = BpPosition(e.x + e.w / 2.0, e.y + e.h / 2.0),
            // A storage tank has two orientations: north (as south) and east (as west).
            direction = if (e.kind == Kind.TANK) (if (e.dir.axis == 0) Dir.E.value16 else null) else e.dir.value16.takeIf { it != 0 && e.kind.hasDirection },
            type = when (e.kind) { Kind.UG_IN -> "input"; Kind.UG_OUT -> "output"; else -> null },
            recipe = e.recipe?.takeIf { GameConstants.takesRecipe(e.name) },
            recipeQuality = if (e.recipe != null && GameConstants.takesRecipe(e.name)) quality else null,
            mirror = if (e.mirror) true else null,
            infinitySettings = when (e.kind) {
                // Without an item the chest deletes whatever it is given (test-mode output void).
                Kind.INFINITY_CHEST -> if (e.item == null) buildJsonObject { put("remove_unfiltered_items", true) } else e.item.let { item ->
                    buildJsonObject {
                        put("remove_unfiltered_items", true)
                        put("filters", buildJsonArray {
                            add(buildJsonObject {
                                put("index", 1); put("name", item); put("count", 50); put("mode", "exactly")
                            })
                        })
                    }
                }
                Kind.INFINITY_PIPE -> e.fluid?.let { fluid ->
                    buildJsonObject {
                        put("name", fluid)
                        if (e.drain) { put("percentage", 0.0); put("mode", "exactly") } else { put("percentage", 1.0); put("mode", "at-least") }
                    }
                }
                else -> null
            },
            // Filters leave out the quality, so they match an item of any quality.
            useFilters = if (e.kind == Kind.INSERTER && e.item != null) true else null,
            filters = if ((e.kind == Kind.CHEST || e.kind == Kind.INSERTER) && e.item != null) listOf(BpItemFilter(1, e.item)) else null,
            items = e.modules?.takeIf { it.isNotEmpty() }?.let { ms ->
                val inv = if (e.kind == Kind.BEACON) GameConstants.BEACON_MODULE_INVENTORY else GameConstants.MACHINE_MODULE_INVENTORY
                buildJsonArray {
                    ms.withIndex().groupBy({ it.value }, { it.index }).forEach { (module, stacks) ->
                        add(buildJsonObject {
                            put("id", buildJsonObject { put("name", module) })
                            put("items", buildJsonObject {
                                put("in_inventory", buildJsonArray {
                                    stacks.forEach { st -> add(buildJsonObject { put("inventory", inv); put("stack", st) }) }
                                })
                            })
                        })
                    }
                }
            },
            controlBehavior = e.circuit?.let { c ->
                buildJsonObject {
                    put("circuit_enable_disable", true)
                    put("circuit_condition", buildJsonObject {
                        put("first_signal", buildJsonObject { if (c.fluid) put("type", "fluid"); put("name", c.signal) })
                        put("constant", c.constant)
                        put("comparator", c.comparator)
                    })
                }
            },
        )
    }
    val icon = entities.firstOrNull { it.kind == Kind.MACHINE }?.recipe ?: entities.firstOrNull()?.name
    return Blueprint(
        label = label,
        icons = listOfNotNull(icon?.let { BpIcon(BpSignal(it), 1) }),
        entities = bpEntities,
        wires = wires.map { (a, b) -> listOf(minOf(a, b) + 1, GameConstants.WIRE_POLE_COPPER, maxOf(a, b) + 1, GameConstants.WIRE_POLE_COPPER) } +
            circuitWires.map { (a, b, c) -> listOf(minOf(a, b) + 1, c, maxOf(a, b) + 1, c) },
        tiles = tiles.takeIf { it.isNotEmpty() }?.map { BpTile(it.name, BpTilePosition(it.x, it.y)) },
    )
}

private val Kind.hasDirection: Boolean
    get() = this != Kind.CHEST && this != Kind.POLE && this != Kind.BEACON && this != Kind.INFINITY_CHEST && this != Kind.INFINITY_PIPE && this != Kind.PIPE

/** 2.0 blueprint -> layout. Inverse of [toBlueprint] for the entity types this tool emits. */
fun Blueprint.toLayout(w: Int, h: Int): Layout {
    val list = entities.sortedBy { it.entityNumber }.map { b ->
        val kind = PlacedEntity.kindOf(b.name, b.type)
        val dir = Dir.from16(b.direction ?: 0)
        val (ew, eh) = PlacedEntity.defaultSize(b.name, kind, dir)
        PlacedEntity(
            name = b.name,
            kind = kind,
            x = floor(b.position.x - ew / 2.0 + 1e-6).toInt(),
            y = floor(b.position.y - eh / 2.0 + 1e-6).toInt(),
            dir = dir,
            recipe = b.recipe,
            item = when (kind) {
                Kind.INFINITY_CHEST -> b.infinitySettings?.get("filters")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("name")?.jsonPrimitive?.content
                Kind.CHEST -> b.filters?.firstOrNull()?.name
                Kind.INSERTER -> if (b.useFilters == true && b.filterMode != "blacklist") b.filters?.firstOrNull()?.name else null
                else -> null
            },
            fluid = if (kind == Kind.INFINITY_PIPE) b.infinitySettings?.get("name")?.jsonPrimitive?.content else null,
            drain = kind == Kind.INFINITY_PIPE && b.infinitySettings?.get("mode")?.jsonPrimitive?.content == "exactly" &&
                b.infinitySettings["percentage"]?.jsonPrimitive?.content?.toDoubleOrNull() == 0.0,
            mirror = b.mirror == true,
            w = ew,
            h = eh,
            circuit = b.controlBehavior?.get("circuit_condition")?.jsonObject?.let { c ->
                val sig = c["first_signal"]?.jsonObject ?: return@let null
                CircuitCondition(sig["name"]?.jsonPrimitive?.content ?: return@let null, c["comparator"]?.jsonPrimitive?.content ?: "<",
                    c["constant"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0, sig["type"]?.jsonPrimitive?.content == "fluid")
            },
            modules = b.items?.let { reqs ->
                val slots = sortedMapOf<Int, String>()
                reqs.forEach { r ->
                    val o = r.jsonObject
                    val name = o["id"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: return@forEach
                    o["items"]?.jsonObject?.get("in_inventory")?.jsonArray?.forEach { st ->
                        st.jsonObject["stack"]?.jsonPrimitive?.content?.toIntOrNull()?.let { slots[it] = name }
                    }
                }
                slots.values.toList().takeIf { it.isNotEmpty() }
            },
        )
    }
    val numberToIndex = entities.sortedBy { it.entityNumber }.mapIndexed { i, b -> b.entityNumber to i }.toMap()
    val wirePairs = wires.filter { it.size == 4 && it[1] == GameConstants.WIRE_POLE_COPPER && it[3] == GameConstants.WIRE_POLE_COPPER }
        .map { numberToIndex.getValue(it[0]) to numberToIndex.getValue(it[2]) }
    val circuit = wires.filter { it.size == 4 && it[1] == it[3] && (it[1] == GameConstants.WIRE_RED || it[1] == GameConstants.WIRE_GREEN) }
        .map { Triple(numberToIndex.getValue(it[0]), numberToIndex.getValue(it[2]), it[1]) }
    return Layout(w, h, list, wirePairs, label, tiles?.map { geom.GroundTile(it.name, it.position.x, it.position.y) } ?: emptyList(), circuit)
}

fun Blueprint.toJson(): String = blueprintJson.encodeToString(BlueprintRoot.serializer(), BlueprintRoot(this))

fun encodeBlueprint(bp: Blueprint): String = compressAndEncode(bp.toJson())

fun decodeBlueprint(s: String): Blueprint =
    blueprintJson.decodeFromString(BlueprintRoot.serializer(), decodeToJson(s)).blueprint

fun Layout.toBlueprintString(quality: String = "normal"): String = encodeBlueprint(toBlueprint(quality))
