import data.AssemblingMachineEntity
import data.BeltData
import data.InserterData
import data.SplitterData
import data.UndergroundData
import data.CraftingEntity
import data.FluidBoxData
import data.GameConstants
import data.PipeConnectionData
import data.Vec
import geom.Dir
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exporter mod's data (recipes.json) against the hand-checked tables in GameConstants. Isolated: two tests here
 * register modded data in GameConstants for a while, which no other test may see.
 */
@Isolated
class ExportedDataTest {
    private val data = TestData.data

    @Test
    fun `exported fluid boxes give the same connections as the hand-written table`() {
        val machines = GameConstants.BASE_MACHINES + GameConstants.SPACE_AGE_MACHINES
        var checked = 0
        for (m in machines) {
            val e = data.entities[m] as? CraftingEntity ?: continue
            val table = GameConstants.tableFluidConnections(m)
            val exported = GameConstants.fluidConnectionsOf(e.width, e.height, e.fluidBoxes.orEmpty())
            assertEquals(table.toSet(), exported.toSet(), m)
            if (table.isNotEmpty()) {
                assertEquals(GameConstants.tableOnlyWithFluidRecipe(m), e.fluidBoxesOffWhenNoFluidRecipe == true, "$m: connections only with a fluid recipe")
                checked++
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun `exported belts, poles and pipes match the built-in tables`() {
        val lg = data.logistics!!
        GameConstants.BASE_BELT_TIERS.values.forEach { t ->
            assertEquals(t.throughput, lg.belts!!.first { it.name == t.belt }.itemsPerSecond, 1e-9, t.belt)
            assertEquals(t.maxUndergroundGap, lg.undergrounds!!.first { it.name == t.underground }.maxDistance - 1, t.underground)
            assertTrue(lg.splitters!!.any { it.name == t.splitter }, t.splitter)
        }
        listOf("small-electric-pole", "medium-electric-pole", "big-electric-pole", "substation").forEach { n ->
            val p = GameConstants.BASE_POLES.getValue(n); val x = lg.poles!!.first { it.name == n }
            assertEquals(p.size, x.width, n); assertEquals(p.supplyHalf, x.supplyAreaDistance, 1e-9, n); assertEquals(p.wireReach, x.maxWireDistance, 1e-9, n)
        }
        assertEquals(GameConstants.PIPE_TO_GROUND_GAP, lg.pipesToGround!!.first { it.name == GameConstants.PIPE_TO_GROUND }.maxDistance!! - 1)
        GameConstants.BASE_INSERTERS.values.forEach { i ->
            val x = lg.inserters!!.first { it.name == i.name }
            assertEquals(i.reach, kotlin.math.abs(x.pickup.y).toInt(), i.name)
            // The table's rates were measured; one swing there and back per hand is within a few percent of them.
            assertEquals(i.baseRate, x.rotationSpeed * 60, 0.1, i.name)
        }
    }

    @Test
    fun `a modded machine's fluid boxes become connections`() {
        // A 4 x 3 machine (facing north): an input box on the west side, two outputs on the south side, one
        // box without a plain connection (skipped), and an input box whose pipe goes underground (skipped too).
        fun box(i: Int, type: String, dir: String?, x: Double, y: Double, kind: String = "normal") =
            FluidBoxData(i, type, connections = listOf(PipeConnectionData(type, kind, dir, listOf(Vec(x, y)))))
        val boxes = listOf(
            box(1, "input", "W", -1.5, 0.0),
            box(2, "output", "S", -0.5, 1.0),
            box(3, "output", "S", 1.5, 1.0),
            box(4, "input", "N", 0.5, -1.0, kind = "underground"),
        )
        val c = GameConstants.fluidConnectionsOf(4, 3, boxes)
        assertEquals(listOf(
            GameConstants.FluidConnection(GameConstants.Flow.INPUT, 1, -1, 1, Dir.W),
            GameConstants.FluidConnection(GameConstants.Flow.OUTPUT, 1, 1, 3, Dir.S),
            GameConstants.FluidConnection(GameConstants.Flow.OUTPUT, 2, 3, 3, Dir.S),
        ), c)
    }

    @Test
    fun `a mod's belts, inserters and poles are registered, and what it changes of the base game wins`() {
        val lg = data.logistics!!
        // As Krastorio does: a faster belt tier of its own, express undergrounds reaching 20, a bigger substation.
        val modded = data.copy(logistics = lg.copy(
            belts = lg.belts!! + BeltData("mod-belt", 0.1875, 90.0, "mod-underground"),
            undergrounds = lg.undergrounds!!.map { if (it.name == "express-underground-belt") it.copy(maxDistance = 20) else it } +
                UndergroundData("mod-underground", 0.1875, 40),
            splitters = lg.splitters!! + SplitterData("mod-splitter", 0.1875, 2, 1),
            inserters = lg.inserters!! + InserterData("mod-inserter", 0.06, 0.15, Vec(0.0, -1.0), Vec(0.0, 1.2), bulk = true, filterCount = 5),
            poles = lg.poles!!.map { if (it.name == "substation") it.copy(supplyAreaDistance = 10.0) else it },
        ))
        try {
            GameConstants.register(modded)
            assertEquals(GameConstants.BeltTier("mod-belt", "mod-belt", "mod-underground", "mod-splitter", 39, 90.0), GameConstants.beltTier("mod-belt"))
            assertEquals(19, GameConstants.beltTier("express").maxUndergroundGap)
            val ins = GameConstants.inserter("mod-inserter")
            assertEquals(1, ins.reach); assertEquals(3.6, ins.baseRate, 1e-9); assertTrue(ins.bulk && ins.filterable)
            // The base game's measured rate stays (the swing speed is unchanged).
            assertEquals(2.31, GameConstants.inserter("fast-inserter").baseRate, 1e-9)
            assertEquals(10.0, GameConstants.pole("substation").supplyHalf, 1e-9)
        } finally {
            GameConstants.register(data)
        }
        assertEquals(8, GameConstants.beltTier("express").maxUndergroundGap)
        assertTrue("mod-belt" !in GameConstants.BELT_TIERS)
    }

    @Test
    fun `a machine that puts its products in front of it keeps that tile free`() {
        // Py's wood processing unit, 6 x 6, places what it makes 3.3 tiles south of its centre (facing north).
        val am3 = data.entities.getValue("assembling-machine-3") as AssemblingMachineEntity
        val wpu = am3.copy(name = "test-wpu", width = 6, height = 6, vectorToPlaceResult = Vec(-0.5, 3.3))
        try {
            GameConstants.register(data.copy(entities = data.entities + ("test-wpu" to wpu)))
            // Facing north at (10, 10): the tile below its bottom row, left of centre.
            assertEquals(geom.Tile(12, 16), GameConstants.resultTile("test-wpu", 6, 6, 10, 10, 0, false))
            // Facing south: the tile above its top row, right of centre (Py science 3's wpu at (600, 140), its
            // yotoi leaves landing on the underground entrance at (600, 136)).
            assertEquals(geom.Tile(600, 136), GameConstants.resultTile("test-wpu", 6, 6, 597, 137, 2, false))
            assertEquals(null, GameConstants.resultTile("assembling-machine-3", 3, 3, 0, 0, 0, false))
            // A belt there is an error; an inserter is not.
            fun errs(on: geom.PlacedEntity) = validate.validate(geom.Layout(30, 30, listOf(
                geom.PlacedEntity("test-wpu", geom.Kind.MACHINE, 10, 10, geom.Dir.N, recipe = "iron-gear-wheel", w = 6, h = 6), on)),
                validate.ValidationSpec(requirePower = false, checkRates = false, recipeInfo = validate.recipeInfoFrom(data)))
                .filter { it.type == validate.ErrorType.UNSUPPORTED && "in front of it" in it.message }
            assertEquals(1, errs(geom.PlacedEntity("transport-belt", geom.Kind.BELT, 12, 16, geom.Dir.E)).size)
            assertEquals(0, errs(geom.PlacedEntity("inserter", geom.Kind.INSERTER, 12, 16, geom.Dir.N)).size)
        } finally {
            GameConstants.register(data)
        }
    }

    @Test
    fun `a furnace a mod turns into an assembling machine gets its recipe in the blueprint`() {
        assertTrue(!GameConstants.takesRecipe("electric-furnace"))
        // Krastorio 2 makes the base furnaces assembling machines: they accept nothing until a recipe is set.
        val am3 = data.entities.getValue("assembling-machine-3") as AssemblingMachineEntity
        val modded = data.copy(entities = data.entities + ("electric-furnace" to am3.copy(name = "electric-furnace")))
        try {
            GameConstants.register(modded)
            assertTrue(GameConstants.takesRecipe("electric-furnace"))
        } finally {
            GameConstants.register(data)
        }
        assertTrue(!GameConstants.takesRecipe("electric-furnace"))
    }

    @Test
    fun `machines of the loaded data beyond the built-in ones are usable`() {
        // Space Age's captive biter spawner and the like come from the data, not the built-in lists.
        val extra = data.entities.values.filterIsInstance<AssemblingMachineEntity>().filter { it.hidden != true && it.name !in GameConstants.BASE_MACHINES + GameConstants.SPACE_AGE_MACHINES }
        assertTrue(extra.isNotEmpty())
        extra.forEach { e ->
            assertTrue(e.name in GameConstants.SUPPORTED_MACHINES, e.name)
            assertEquals(e.width to e.height, GameConstants.MACHINE_SIZES[e.name], e.name)
        }
    }
}
