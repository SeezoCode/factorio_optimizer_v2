import emit.decodeBlueprint
import emit.decodeToJson
import emit.toBlueprint
import emit.toBlueprintString
import emit.toJson
import emit.toLayout
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.dropTile
import geom.pickupTile
import geom.Tile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

object Fixtures {
    fun e(name: String, x: Int, y: Int, dir: Dir = Dir.N, recipe: String? = null, item: String? = null, fluid: String? = null, ug: String? = null) =
        PlacedEntity(name, PlacedEntity.kindOf(name, ug), x, y, dir, recipe, item, fluid)

    /** Hand-written T1 (milestone M1), shared with the in-game verify kit. */
    val t1Hand = emit.VerifyKit.t1Hand
}

class BlueprintTest {
    private fun allTypes(): Layout {
        val e = Fixtures::e
        val list = mutableListOf<PlacedEntity>()
        list += e("assembling-machine-3", 0, 0, Dir.E, "processing-unit", null, null, null)
        list += e("assembling-machine-2", 4, 0, Dir.N, "iron-gear-wheel", null, null, null)
        listOf("basic", "fast", "express", "turbo").forEachIndexed { i, tier ->
            val t = data.GameConstants.beltTier(tier)
            Dir.entries.forEachIndexed { j, d -> list += e(t.belt, j, 4 + i, d, null, null, null, null) }
            list += e(t.underground, 5, 4 + i, Dir.E, null, null, null, "input")
            list += e(t.underground, 7, 4 + i, Dir.E, null, null, null, "output")
            list += e(t.splitter, 9, 4 + i, Dir.N, null, null, null, null)
            list += e(t.splitter, 12, 2 * i, Dir.E, null, null, null, null)
        }
        data.GameConstants.INSERTERS.keys.forEachIndexed { i, name ->
            Dir.entries.forEachIndexed { j, d -> list += e(name, 14 + j, i, d, null, null, null, null) }
        }
        data.GameConstants.CHESTS.filter { it != "infinity-chest" }.forEachIndexed { i, name -> list += e(name, i, 9, Dir.N, null, null, null, null) }
        list += e("steel-chest", 0, 10, Dir.N, null, "iron-gear-wheel", null, null)
        list += e("infinity-chest", 1, 10, Dir.N, null, "iron-plate", null, null)
        list += e("infinity-pipe", 2, 10, Dir.N, null, null, "sulfuric-acid", null)
        val poleStart = list.size
        list += e("small-electric-pole", 0, 12, Dir.N, null, null, null, null)
        list += e("medium-electric-pole", 5, 12, Dir.N, null, null, null, null)
        list += e("big-electric-pole", 10, 12, Dir.N, null, null, null, null)
        list += e("substation", 14, 12, Dir.N, null, null, null, null)
        val wires = listOf(poleStart to poleStart + 1, poleStart + 2 to poleStart + 1, poleStart + 3 to poleStart + 2)
        return Layout(20, 15, list, wires, "all types")
    }

    @Test
    fun `round trip keeps every entity type`() {
        val layout = allTypes()
        val decoded = decodeBlueprint(layout.toBlueprintString()).toLayout(layout.w, layout.h)
        assertEquals(layout.entities.map { it.stripped() }, decoded.entities.map { it.stripped() })
        assertEquals(layout.wires.map { setOf(it.first, it.second) }, decoded.wires.map { setOf(it.first, it.second) })
        assertEquals(layout.label, decoded.label)
    }

    @Test
    fun `json uses 2_0 conventions`() {
        val json = Json.parseToJsonElement(decodeToJson(Fixtures.t1Hand.toBlueprintString())).jsonObject.getValue("blueprint").jsonObject
        assertEquals(562949954076673L, json.getValue("version").jsonPrimitive.content.toLong())
        val ents = json.getValue("entities").jsonArray.map { it.jsonObject }
        val machine = ents[0]
        assertEquals(4.5, machine.getValue("position").jsonObject.getValue("x").jsonPrimitive.content.toDouble())
        assertEquals(2.5, machine.getValue("position").jsonObject.getValue("y").jsonPrimitive.content.toDouble())
        assertEquals("iron-gear-wheel", machine.getValue("recipe").jsonPrimitive.content)
        assertNull(machine["direction"], "north is omitted")
        assertEquals(4, ents[1].getValue("direction").jsonPrimitive.int, "east belt")
        assertEquals("input", ents[2].getValue("type").jsonPrimitive.content)
        assertEquals("output", ents[3].getValue("type").jsonPrimitive.content)
        assertEquals(8, ents[6].getValue("direction").jsonPrimitive.int, "inserter picking from the south")
        assertEquals(12, ents[8].getValue("direction").jsonPrimitive.int)
        val icon = json.getValue("icons").jsonArray[0].jsonObject.getValue("signal").jsonObject
        assertEquals("iron-gear-wheel", icon.getValue("name").jsonPrimitive.content)
        assertNull(icon["type"], "2.0 item signals have no type")
    }

    @Test
    fun `wires are copper tuples with the lower entity first`() {
        val l = Layout(10, 3, listOf(Fixtures.e("medium-electric-pole", 0, 0), Fixtures.e("medium-electric-pole", 6, 0)), listOf(1 to 0))
        assertEquals(listOf(listOf(1, 5, 2, 5)), l.toBlueprint().wires)
    }

    @Test
    fun `a furnace's planned recipe goes along as a tag`() {
        val l = Layout(4, 4, listOf(PlacedEntity("stone-furnace", Kind.MACHINE, 0, 0, Dir.N, recipe = "iron-plate", w = 2, h = 2)))
        val e = l.toBlueprint().entities[0]
        assertNull(e.recipe)
        assertEquals("iron-plate", e.tags!!["recipe"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inserter filters carry the game's item names`() {
        // A raw resource made as a by-product is "stone#byproduct" in a plan; the game only knows stone.
        val l = Layout(6, 3, listOf(
            PlacedEntity("fast-inserter", Kind.INSERTER, 0, 0, Dir.N, item = "stone#byproduct"),
            PlacedEntity("fast-inserter", Kind.INSERTER, 2, 0, Dir.N, blacklist = listOf("iron-ore#byproduct", "meat")),
        ))
        val bp = l.toBlueprint()
        assertEquals(listOf("stone"), bp.entities[0].filters!!.map { it.name })
        assertEquals(listOf("iron-ore", "meat"), bp.entities[1].filters!!.map { it.name })
    }

    @Test
    fun `inserter pickup and drop follow the direction`() {
        val ins = Fixtures.e("long-handed-inserter", 5, 5, Dir.N)
        assertEquals(Tile(5, 3), ins.pickupTile)
        assertEquals(Tile(5, 7), ins.dropTile)
        assertEquals(Kind.INSERTER, ins.kind)
    }
}

class VerifyKitTest {
    @Test
    fun `verify kit layouts have no overlaps and round trip`() {
        emit.VerifyKit.cases.forEach { c ->
            val errs = validate.validate(c.layout, validate.ValidationSpec(requirePower = false, checkRates = false,
                recipeInfo = validate.recipeInfoFrom(TestData.data), outsideAllowed = c.layout.entities.flatMap { it.rect.tiles() }.toSet()))
            kotlin.test.assertTrue(errs.none { it.type == validate.ErrorType.OVERLAP || it.type == validate.ErrorType.INSERTER_BAD_PICKUP }, "${c.id}: $errs")
            val back = decodeBlueprint(c.layout.toBlueprintString()).toLayout(c.layout.w, c.layout.h)
            assertEquals(c.layout.entities.map { it.stripped() }, back.entities.map { it.stripped() }, c.id)
        }
    }

    @Test
    fun `a requester chest keeps its request and a roboport round trips`() {
        val l = Layout(10, 10, listOf(
            PlacedEntity("requester-chest", geom.Kind.CHEST, 1, 1, item = "iron-plate", request = 30),
            PlacedEntity("passive-provider-chest", geom.Kind.CHEST, 2, 1, bar = 2),
            PlacedEntity("roboport", geom.Kind.ROBOPORT, 4, 4),
        ))
        val back = emit.decodeBlueprint(l.toBlueprintString()).toLayout(10, 10)
        assertEquals(l.entities.map { it.stripped() }, back.entities.map { it.stripped() })
        val json = l.toBlueprint().toJson()
        kotlin.test.assertTrue("request_filters" in json && "\"count\":30" in json, json)
        // A provider limited to two slots (blocks.bot_provider_slots).
        kotlin.test.assertTrue("\"bar\":2" in json, json)
    }
}
