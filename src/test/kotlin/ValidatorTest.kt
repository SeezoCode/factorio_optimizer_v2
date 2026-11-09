import Fixtures.e
import geom.Dir
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import geom.pickupTile
import validate.ErrorType
import validate.MachineSpec
import validate.OutputSpec
import validate.SourceSpec
import validate.ValidationError
import validate.ValidationSpec
import validate.recipeInfoFrom
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorTest {
    private val base = ValidationSpec(recipeInfo = recipeInfoFrom(TestData.data), requirePower = false, checkRates = false)

    private fun belt(x: Int, y: Int, d: Dir, net: Int) = e("fast-transport-belt", x, y, d).copy(net = net)

    private fun check(name: String, layout: Layout, spec: ValidationSpec): List<ValidationError> {
        val errors = validate(layout, spec)
        renderLayout(layout, File("build/renders/validator/$name.png"), RenderOptions(errors = errors, title = name, showSupply = true))
        return errors
    }

    private fun assertHas(errors: List<ValidationError>, type: ErrorType, tile: Tile) {
        assertTrue(errors.any { it.type == type && it.tile == tile }, "expected $type at $tile, got $errors")
    }

    @Test
    fun `the hand-written T1 layout passes with rates and power`() {
        val spec = base.copy(
            requirePower = true, checkRates = true,
            machines = mapOf(Tile(3, 1) to MachineSpec("iron-gear-wheel", setOf("iron-plate"), 1.0, 1.0, mapOf("iron-plate" to 2.0))),
            sources = listOf(SourceSpec(Tile(0, 5), "iron-plate", 2.0)),
        )
        val errors = check("t1_hand", Fixtures.t1Hand, spec)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an inserter with more filters than slots is caught`() {
        // Py logistic science: the game left out a long-handed inserter given a 7-item blacklist (it has 3 slots there).
        val many = listOf("bones", "brain", "cage", "guts", "meat", "mukmoux-fat", "skin")
        val layout = Layout(8, 8, listOf(
            PlacedEntity("long-handed-inserter", geom.Kind.INSERTER, 2, 2, Dir.N, blacklist = many),
            PlacedEntity("fast-inserter", geom.Kind.INSERTER, 5, 2, Dir.N, blacklist = many.take(2)),
        ))
        val errors = check("filter_slots", layout, base)
        assertTrue(errors.any { it.type == ErrorType.UNSUPPORTED && it.tile == Tile(2, 2) && "filter slots" in it.message }, "$errors")
        assertTrue(errors.none { it.type == ErrorType.UNSUPPORTED && it.tile == Tile(5, 2) }, "$errors")
    }

    @Test
    fun `a filter of the one product a machine sends to the mixed belt takes the belt's share`() {
        // Py logistic science: the slaughterhouse's pineal glands (and crushers' stone#byproduct) leave on the mixed belt;
        // the plan counts them as the belt's product, the inserter is filtered to the item itself (by the game's name).
        val machine = e("assembling-machine-3", 2, 0, recipe = "iron-gear-wheel")
        val out = listOf(Dir.N, Dir.S, Dir.E, Dir.W).map { e("fast-inserter", 3, 3, it) }.first { it.pickupTile.y in 0..2 && it.pickupTile.x in 2..4 }
        val mi = MachineSpec("iron-gear-wheel", setOf("iron-plate"), products = mapOf("iron-gear-wheel" to 1.0, "mixed-1" to 0.5),
            filterAliases = mapOf("stone#byproduct" to "mixed-1", "stone" to "mixed-1"))
        val layout = Layout(8, 8, listOf(machine, out.copy(item = "stone")))
        val errors = check("mixed_filter", layout, base.copy(machines = mapOf(Tile(2, 0) to mi)))
        assertTrue(errors.none { it.type == ErrorType.INSERTER_WRONG_ITEM }, "$errors")
        // Without the alias the filter names an item the machine does not make.
        val bad = check("mixed_filter_bad", layout, base.copy(machines = mapOf(Tile(2, 0) to mi.copy(filterAliases = emptyMap()))))
        assertTrue(bad.any { it.type == ErrorType.INSERTER_WRONG_ITEM }, "$bad")
    }

    @Test
    fun `a dead end into another net's belt contaminates it`() {
        val layout = Layout(8, 8, listOf(
            belt(3, 0, Dir.S, 1), belt(3, 1, Dir.S, 1), belt(3, 2, Dir.S, 1), belt(3, 3, Dir.S, 1),
            belt(0, 1, Dir.E, 2), belt(1, 1, Dir.E, 2), belt(2, 1, Dir.E, 2),  // dead end points into (3,1)
        ))
        val spec = base.copy(sources = listOf(SourceSpec(Tile(3, 0), "copper-plate", 1.0), SourceSpec(Tile(0, 1), "iron-plate", 1.0)))
        val errors = check("dead_end_contamination", layout, spec)
        assertHas(errors, ErrorType.CONTAMINATION, Tile(3, 1))
    }

    @Test
    fun `a dead end that turns a straight belt into a curve is caught`() {
        val layout = Layout(8, 8, listOf(
            belt(3, 1, Dir.S, 1), belt(3, 2, Dir.S, 1), belt(3, 3, Dir.S, 1),  // starts at (3,1), no feeder
            belt(1, 1, Dir.E, 2), belt(2, 1, Dir.E, 2),                         // dead end into (3,1) from the side
        ))
        val spec = base.copy(sources = listOf(SourceSpec(Tile(3, 1), "copper-plate", 1.0), SourceSpec(Tile(1, 1), "iron-plate", 1.0)))
        val errors = check("curve_contamination", layout, spec)
        assertHas(errors, ErrorType.SHAPE_CHANGED, Tile(3, 1))
        assertHas(errors, ErrorType.CONTAMINATION, Tile(3, 1))
    }

    @Test
    fun `two items share a belt on separate lanes but not on one lane`() {
        // Iron on the left lane, cable on the right, past a circuit assembler that takes both.
        val layout = Layout(8, 6, listOf(
            e("assembling-machine-3", 2, 0, recipe = "electronic-circuit"),
            e("fast-inserter", 3, 3, Dir.S),
            belt(0, 4, Dir.E, 1), belt(1, 4, Dir.E, 1), belt(2, 4, Dir.E, 1), belt(3, 4, Dir.E, 1), belt(4, 4, Dir.E, 1),
        ))
        val shared = base.copy(sources = listOf(SourceSpec(Tile(0, 4), "iron-plate", 1.0, lane = 0), SourceSpec(Tile(0, 4), "copper-cable", 3.0, lane = 1)))
        assertEquals(emptyList(), check("shared_lanes", layout, shared))
        // Both on the left lane: contamination.
        val oneLane = base.copy(sources = listOf(SourceSpec(Tile(0, 4), "iron-plate", 1.0, lane = 0), SourceSpec(Tile(0, 4), "copper-cable", 3.0, lane = 0)))
        assertHas(check("shared_one_lane", layout, oneLane), ErrorType.CONTAMINATION, Tile(0, 4))
        // An inserter dropping cable onto the iron lane is caught; onto the cable lane it is fine.
        val ironOnly = base.copy(sources = listOf(SourceSpec(Tile(0, 4), "iron-plate", 1.0, lane = 0)))
        fun withDrop(fromNorthSide: Boolean) = layout.copy(entities = layout.entities + listOf(
            e("infinity-chest", 1, if (fromNorthSide) 2 else 6, item = "copper-cable"),
            e("fast-inserter", 1, if (fromNorthSide) 3 else 5, if (fromNorthSide) Dir.N else Dir.S),
        )).let { it.copy(h = 7) }
        // From the north side (the belt's left) an inserter drops on the far, right lane: no clash with iron.
        assertTrue(check("drop_far_lane", withDrop(true), ironOnly).none { it.type == ErrorType.INSERTER_WRONG_ITEM || it.type == ErrorType.CONTAMINATION })
        assertHas(check("drop_iron_lane", withDrop(false), ironOnly), ErrorType.INSERTER_WRONG_ITEM, Tile(1, 5))
    }

    @Test
    fun `a belt fed into the side of an underground entrance passes only the lane on its open half`() {
        // A belt heading north runs into the side of an east-facing entrance at (1,1): only its left (west) lane
        // gets in, onto the entrance's right (south) lane; the exit at (4,1) leads out of the box at the east edge.
        fun layout() = Layout(6, 4, listOf(
            belt(1, 3, Dir.N, 1), belt(1, 2, Dir.N, 1),
            e("fast-underground-belt", 1, 1, Dir.E, ug = "input").copy(net = 1),
            e("fast-underground-belt", 4, 1, Dir.E, ug = "output").copy(net = 1),
            belt(5, 1, Dir.E, 1),
        ))
        val rates = base.copy(checkRates = true)
        fun spec(item: String, lane: Int) = rates.copy(
            sources = listOf(SourceSpec(Tile(1, 3), item, 1.0, lane = lane)),
            outputs = listOf(validate.OutputSpec(Tile(5, 1), item, 1.0)),
        )
        // The left lane passes.
        assertEquals(emptyList(), check("ug_side_left_lane", layout(), spec("iron-plate", 0)))
        // The right lane is blocked by the hood: nothing arrives, and the blocked lane is reported.
        val blocked = check("ug_side_right_lane", layout(), spec("copper-plate", 1))
        assertHas(blocked, ErrorType.OUTPUT_MISSING, Tile(5, 1))
        assertHas(blocked, ErrorType.LANE_BLOCKED, Tile(1, 1))
        // Both lanes: iron on the left passes, copper on the right does not.
        val both = rates.copy(
            sources = listOf(SourceSpec(Tile(1, 3), "iron-plate", 1.0, lane = 0), SourceSpec(Tile(1, 3), "copper-plate", 1.0, lane = 1)),
            outputs = listOf(validate.OutputSpec(Tile(5, 1), "iron-plate", 1.0)),
        )
        val errs = check("ug_side_both_lanes", layout(), both)
        assertTrue(errs.none { it.type == ErrorType.CONTAMINATION }, "$errs")
        assertHas(errs, ErrorType.LANE_BLOCKED, Tile(1, 1))
    }

    @Test
    fun `an inserter picks from a dead-end splitter half`() {
        // Iron runs east along y=4; the splitter at x=3 covers (3,4) and (3,5); its south half leads nowhere. The
        // gear assembler below takes iron from that half; a second assembler picks from the end-of-belt splitter at
        // x=7, which leads nowhere on both halves.
        val layout = Layout(12, 11, listOf(
            belt(0, 4, Dir.E, 1), belt(1, 4, Dir.E, 1), belt(2, 4, Dir.E, 1),
            e("fast-splitter", 3, 4, Dir.E).copy(net = 1),
            belt(4, 4, Dir.E, 1), belt(5, 4, Dir.E, 1), belt(6, 4, Dir.E, 1),
            e("fast-splitter", 7, 4, Dir.E).copy(net = 1),
            e("assembling-machine-3", 2, 7, recipe = "iron-gear-wheel"),
            e("fast-inserter", 3, 6, Dir.N),
            e("fast-inserter", 5, 8, Dir.W), e("steel-chest", 6, 8),
            e("assembling-machine-3", 7, 0, recipe = "iron-gear-wheel"),
            e("fast-inserter", 8, 3, Dir.S),     // picks from (8,4), east of the end splitter: nothing there
            e("fast-inserter", 7, 3, Dir.S),     // picks from (7,4), the end splitter's north half
            e("fast-inserter", 10, 1, Dir.W), e("steel-chest", 11, 1),
        ))
        val gear = MachineSpec("iron-gear-wheel", setOf("iron-plate"), 0.5, 0.5, mapOf("iron-plate" to 1.0))
        val spec = base.copy(
            checkRates = true,
            machines = mapOf(Tile(2, 7) to gear, Tile(7, 0) to gear),
            sources = listOf(SourceSpec(Tile(0, 4), "iron-plate", 2.0)),
        )
        val errors = check("splitter_stub", layout.copy(entities = layout.entities.filter { !(it.x == 8 && it.y == 3) }), spec)
        assertEquals(emptyList(), errors)
        // An inserter picking from the empty tile beside the end splitter has nothing to pick from.
        assertHas(check("splitter_stub_bad", layout, spec), ErrorType.INSERTER_BAD_PICKUP, Tile(8, 3))
    }

    @Test
    fun `an entrance pairs with the nearest same-tier underground on its axis`() {
        fun ug(x: Int, type: String, net: Int) = e("fast-underground-belt", x, 4, Dir.E, ug = type).copy(net = net)
        val layout = Layout(10, 8, listOf(
            belt(0, 4, Dir.E, 1), ug(1, "input", 1), ug(6, "output", 1), belt(7, 4, Dir.E, 1),
            ug(2, "input", 2), ug(4, "output", 2),
        ))
        val errors = check("wrong_ug_pairing", layout, base)
        assertHas(errors, ErrorType.UG_UNPAIRED, Tile(1, 4))
        assertHas(errors, ErrorType.UG_UNPAIRED, Tile(6, 4))
        assertTrue(errors.none { it.tile == Tile(2, 4) })
        // Different tiers ignore each other: making net 2 basic tier fixes net 1.
        val woven = layout.copy(entities = layout.entities.map {
            if (it.net == 2) it.copy(name = "underground-belt") else it
        })
        assertTrue(validate(woven, base).none { it.type == ErrorType.UG_UNPAIRED }, "${validate(woven, base)}")
    }

    @Test
    fun `an inserter dropping onto another net's belt is caught`() {
        val layout = Layout(10, 8, listOf(
            e("assembling-machine-3", 2, 0, recipe = "iron-gear-wheel"),
            belt(0, 4, Dir.E, 1), belt(1, 4, Dir.E, 1), belt(2, 4, Dir.E, 1), belt(3, 4, Dir.E, 1), belt(4, 4, Dir.E, 1),
            e("inserter", 3, 3, Dir.N),   // picks gears from the machine, drops onto the copper belt at (3,4)
        ))
        val spec = base.copy(sources = listOf(SourceSpec(Tile(0, 4), "copper-plate", 1.0)))
        val errors = check("wrong_net_drop", layout, spec)
        assertHas(errors, ErrorType.INSERTER_WRONG_ITEM, Tile(3, 3))
        assertHas(errors, ErrorType.CONTAMINATION, Tile(3, 4))
    }

    @Test
    fun `an inserter outside every supply area is unpowered`() {
        val layout = Fixtures.t1Hand.copy(entities = Fixtures.t1Hand.entities.map {
            if (it.name == "medium-electric-pole") it.copy(x = 0, y = 2) else it
        })
        val errors = check("uncovered_inserter", layout, base.copy(requirePower = true))
        // Pole at (0,2): supply x in (-3, 4), so the inserters at x = 4, 5 and 6 are outside.
        assertHas(errors, ErrorType.UNPOWERED, Tile(6, 2))
        assertHas(errors, ErrorType.UNPOWERED, Tile(5, 4))
        assertTrue(errors.none { it.type == ErrorType.UNPOWERED && it.tile == Tile(3, 1) }, "the machine overlaps the area")
    }

    @Test
    fun `removing the only pole fails the power check`() {
        val layout = Fixtures.t1Hand.copy(entities = Fixtures.t1Hand.entities.filter { it.name != "medium-electric-pole" })
        assertTrue(validate(layout, base.copy(requirePower = true)).any { it.type == ErrorType.UNPOWERED })
    }

    @Test
    fun `touching pipes of two fluids are caught`() {
        val layout = Layout(10, 8, listOf(
            e("assembling-machine-3", 0, 1, Dir.N, recipe = "processing-unit"),
            e("assembling-machine-3", 4, 1, Dir.N, recipe = "processing-unit"),
            e("infinity-pipe", 1, 0, fluid = "sulfuric-acid"),
            e("infinity-pipe", 2, 0, fluid = "lubricant"),       // joins the acid pipe next to it
            e("infinity-pipe", 5, 0, fluid = "sulfuric-acid"),
        ))
        val errors = check("adjacent_pipes", layout, base)
        assertHas(errors, ErrorType.FLUID_MIX, Tile(1, 0))
        assertTrue(errors.none { it.type == ErrorType.FLUID_MISSING && it.tile == Tile(5, 0) }, "$errors")
    }

    @Test
    fun `pipes carry a fluid from a refinery to a chemical plant`() {
        // Refinery facing north at (0,2): basic oil takes crude on input 2 at (3,7), petroleum leaves output 3 at (4,1).
        // Chemical plant facing north at (10,2) makes plastic from petroleum on input 1 at (10,1).
        fun layout(vararg line: geom.PlacedEntity) = Layout(14, 10, listOf(
            e("oil-refinery", 0, 2, Dir.N, recipe = "basic-oil-processing"),
            e("infinity-pipe", 3, 7, fluid = "crude-oil"),
            e("chemical-plant", 10, 2, Dir.N, recipe = "plastic-bar"),
        ) + line)
        val pipes = layout(*(4..10).map { e("pipe", it, 1) }.toTypedArray())
        assertTrue(check("refinery_pipes", pipes, base).none { it.type.name.startsWith("FLUID") }, "${validate(pipes, base)}")

        val underground = layout(e("pipe", 4, 1), e("pipe-to-ground", 5, 1, Dir.W), e("pipe-to-ground", 9, 1, Dir.E), e("pipe", 10, 1))
        assertTrue(check("refinery_ptg", underground, base).none { it.type.name.startsWith("FLUID") }, "${validate(underground, base)}")

        // Another pipe-to-ground facing the wrong way inside the span breaks the pair.
        val broken = underground.copy(entities = underground.entities + e("pipe-to-ground", 7, 1, Dir.W))
        val be = check("refinery_ptg_broken", broken, base)
        assertTrue(be.any { it.type == ErrorType.FLUID_MISSING } && be.any { it.type == ErrorType.FLUID_STUCK }, "$be")

        // A lubricant pipe touching the petroleum line mixes them.
        val mixed = pipes.copy(entities = pipes.entities + e("infinity-pipe", 6, 0, fluid = "lubricant"))
        assertTrue(check("refinery_mixed", mixed, base).any { it.type == ErrorType.FLUID_MIX })

        // Without the plant, the refinery's petroleum has nowhere to go.
        val stuck = pipes.copy(entities = pipes.entities.filter { it.name != "chemical-plant" })
        assertHas(check("refinery_stuck", stuck, base), ErrorType.FLUID_STUCK, Tile(4, 1))

        // A pipe on the refinery's unused heavy-oil output leaks.
        val leak = pipes.copy(entities = pipes.entities + e("pipe", 0, 1))
        assertHas(check("refinery_leak", leak, base), ErrorType.FLUID_LEAK, Tile(0, 1))
    }

    @Test
    fun `a splitter branch feeds consumers on both sides`() {
        val gear = MachineSpec("iron-gear-wheel", setOf("iron-plate"), 0.0, 0.0, mapOf("iron-plate" to 1.5))
        val layout = Layout(10, 10, listOf(
            belt(0, 4, Dir.E, 1), belt(1, 4, Dir.E, 1),
            e("fast-splitter", 2, 4, Dir.E).copy(net = 1),
            belt(3, 4, Dir.E, 1), belt(4, 4, Dir.E, 1), belt(5, 4, Dir.E, 1), belt(6, 4, Dir.E, 1), belt(7, 4, Dir.E, 1),
            belt(3, 5, Dir.E, 1), belt(4, 5, Dir.E, 1), belt(5, 5, Dir.E, 1), belt(6, 5, Dir.E, 1), belt(7, 5, Dir.E, 1),
            e("assembling-machine-3", 5, 0, recipe = "iron-gear-wheel"), e("fast-inserter", 6, 3, Dir.S),
            e("assembling-machine-3", 5, 7, recipe = "iron-gear-wheel"), e("fast-inserter", 6, 6, Dir.N),
        ))
        val spec = base.copy(checkRates = true, sources = listOf(SourceSpec(Tile(0, 4), "iron-plate", 3.0)),
            machines = mapOf(Tile(5, 0) to gear, Tile(5, 7) to gear))
        assertEquals(emptyList(), check("splitter_branch", layout, spec))
        // Without the splitter the lower belt gets nothing.
        val noSplit = layout.copy(entities = layout.entities.map { if (it.kind == geom.Kind.SPLITTER) belt(2, 4, Dir.E, 1) else it })
        assertTrue(check("splitter_missing", noSplit, spec).any { it.type == ErrorType.UNREACHABLE || it.type == ErrorType.MISSING_INPUT })
    }

    @Test
    fun `a splitter sends a supply where the flow takes it, not by all the demand below each side`() {
        // A (10/s) splits: 3/s leave on top, the rest side-loads onto C's left lane; B (7/s) side-loads onto its right
        // lane; 14/s leave along C. A sends C 7/s (B gives the other 7), within a yellow lane's 7.5/s. Split by the
        // demand below each side (3 against 14), A would send C 8.2/s, over a lane.
        fun yb(x: Int, y: Int, d: Dir) = e("transport-belt", x, y, d).copy(net = 1)
        val layout = Layout(8, 7, listOf(
            yb(0, 0, Dir.E), yb(1, 0, Dir.E), e("splitter", 2, 0, Dir.E).copy(net = 1),
            yb(3, 0, Dir.E), yb(4, 0, Dir.E), yb(5, 0, Dir.E), yb(6, 0, Dir.E), yb(7, 0, Dir.E),
            yb(3, 1, Dir.E), yb(4, 1, Dir.S), yb(4, 2, Dir.S),
            yb(4, 3, Dir.E), yb(5, 3, Dir.E), yb(6, 3, Dir.E), yb(7, 3, Dir.E),
            yb(4, 5, Dir.N), yb(4, 4, Dir.N),
        ))
        val spec = base.copy(checkRates = true,
            sources = listOf(SourceSpec(Tile(0, 0), "iron-plate", 10.0), SourceSpec(Tile(4, 5), "iron-plate", 7.0)),
            outputs = listOf(validate.OutputSpec(Tile(7, 0), "iron-plate", 3.0), validate.OutputSpec(Tile(7, 3), "iron-plate", 14.0)))
        assertEquals(emptyList(), check("splitter_by_flow", layout, spec))
        // B gives only 5/s: A must send C 9/s, more than its left lane takes.
        val short = spec.copy(sources = listOf(SourceSpec(Tile(0, 0), "iron-plate", 12.0), SourceSpec(Tile(4, 5), "iron-plate", 5.0)))
        assertHas(check("splitter_by_flow_over", layout, short), ErrorType.BELT_OVER_CAPACITY, Tile(4, 3))
    }

    @Test
    fun `one lane overflows when every producer drops from the same side`() {
        fun basic(x: Int) = e("transport-belt", x, 2, Dir.E).copy(net = 1)
        val belts = (0..6).map { basic(it) }
        fun fromNorth(x: Int) = listOf(e("infinity-chest", x, 0, item = "iron-plate"), e("fast-inserter", x, 1, Dir.N))
        fun fromSouth(x: Int) = listOf(e("infinity-chest", x, 4, item = "iron-plate"), e("fast-inserter", x, 3, Dir.S))
        val spec = base.copy(checkRates = true)
        val oneSide = Layout(8, 5, belts + (1..4).flatMap { fromNorth(it) })
        assertTrue(check("lane_one_side", oneSide, spec).any { it.type == ErrorType.BELT_OVER_CAPACITY }, "${validate(oneSide, spec)}")
        val bothSides = Layout(8, 5, belts + fromNorth(1) + fromNorth(2) + fromSouth(3) + fromSouth(4))
        assertTrue(check("lane_both_sides", bothSides, spec).none { it.type == ErrorType.BELT_OVER_CAPACITY }, "${validate(bothSides, spec)}")
    }

    @Test
    fun `a missing infinity pipe and overlaps are reported`() {
        val layout = Layout(10, 8, listOf(
            e("assembling-machine-3", 0, 1, Dir.E, recipe = "processing-unit"),
            e("steel-chest", 2, 2),
        ))
        val errors = validate(layout, base)
        assertHas(errors, ErrorType.FLUID_MISSING, Tile(3, 2))
        assertHas(errors, ErrorType.OVERLAP, Tile(2, 2))
    }

    @Test
    fun `a consumer upstream of every producer is unreachable`() {
        val layout = Layout(12, 8, listOf(
            e("assembling-machine-3", 1, 5, recipe = "automation-science-pack"),
            e("assembling-machine-3", 6, 5, recipe = "iron-gear-wheel"),
            belt(0, 3, Dir.E, 1), belt(1, 3, Dir.E, 1), belt(2, 3, Dir.E, 1), belt(3, 3, Dir.E, 1),
            belt(4, 3, Dir.E, 1), belt(5, 3, Dir.E, 1), belt(6, 3, Dir.E, 1), belt(7, 3, Dir.E, 1),
            e("fast-inserter", 2, 4, Dir.N),   // belt -> science, before any gear is on the belt
            e("fast-inserter", 7, 4, Dir.S),   // gears -> belt
        ))
        val spec = base.copy(
            checkRates = true,
            machines = mapOf(
                Tile(1, 5) to MachineSpec("automation-science-pack", setOf("copper-plate", "iron-gear-wheel"), 0.0, 0.0, mapOf("iron-gear-wheel" to 0.5)),
                Tile(6, 5) to MachineSpec("iron-gear-wheel", setOf("iron-plate"), 0.5, 0.0, emptyMap()),
            ),
        )
        val errors = check("unreachable", layout, spec)
        assertHas(errors, ErrorType.UNREACHABLE, Tile(2, 3))
        assertHas(errors, ErrorType.MISSING_INPUT, Tile(1, 5))
    }

    @Test
    fun `a consumer after the supply runs out is starved`() {
        val layout = Layout(13, 8, listOf(
            e("assembling-machine-3", 1, 5, recipe = "iron-gear-wheel"),
            e("assembling-machine-3", 5, 5, recipe = "automation-science-pack"),
            e("assembling-machine-3", 9, 5, recipe = "automation-science-pack"),
            e("fast-inserter", 2, 4, Dir.S),   // gears -> belt
            e("fast-inserter", 6, 4, Dir.N),   // belt -> science A
            e("fast-inserter", 10, 4, Dir.N),  // belt -> science B
        ) + (0..11).map { belt(it, 3, Dir.E, 1) })
        val science = MachineSpec("automation-science-pack", setOf("copper-plate", "iron-gear-wheel"), 0.0, 0.0, mapOf("iron-gear-wheel" to 0.5))
        val spec = base.copy(
            checkRates = true,
            machines = mapOf(
                Tile(1, 5) to MachineSpec("iron-gear-wheel", setOf("iron-plate"), 0.5, 0.0, emptyMap()),
                Tile(5, 5) to science, Tile(9, 5) to science,
            ),
        )
        val errors = check("starved", layout, spec)
        assertHas(errors, ErrorType.STARVED, Tile(10, 3))
        assertTrue(errors.none { it.type == ErrorType.STARVED && it.tile == Tile(6, 3) }, "$errors")
    }

    /**
     * Gear assemblers above two separate belts: the top belt (y 3) leaves the box at (12,3) as an output port, the
     * bottom one (y 9) feeds a science assembler at (9,11). [extra] adds machines and inserters.
     */
    private fun twoBelts(vararg extra: PlacedEntity) = Layout(13, 14, listOf(
        e("assembling-machine-3", 9, 11, recipe = "automation-science-pack"),
        e("fast-inserter", 10, 10, Dir.N),   // bottom belt -> science
    ) + extra + (0..12).flatMap { listOf(belt(it, 3, Dir.E, 1), belt(it, 9, Dir.E, 2)) })

    private val science = MachineSpec("automation-science-pack", setOf("copper-plate", "iron-gear-wheel"), 0.0, 0.0, mapOf("iron-gear-wheel" to 0.1))
    private fun gears(rate: Double, full: Double = rate) =
        MachineSpec("iron-gear-wheel", setOf("iron-plate"), rate, 0.0, emptyMap(), fullRates = mapOf("iron-gear-wheel" to full))

    @Test
    fun `a machine dropping onto two belts divides its output as the game does, not as planned`() {
        // B is planned to send 1.0/s up to the port and 0.1/s down to the science. In the game each of its
        // inserters takes whenever its belt has room. Upstream of C on the bottom belt, B fills the gaps the science
        // leaves first (C waits) and sends 0.1/s down: fine. Downstream of C, C fills the bottom belt and B sends
        // the rest up. But with C downstream and only able to supply part, the bottom belt pulls more of B than
        // planned and the port comes up short.
        fun layout(cAt: Int) = twoBelts(
            e("assembling-machine-3", 1, 5, recipe = "iron-gear-wheel"), e("fast-inserter", 2, 4, Dir.S),
            e("assembling-machine-3", 4, 5, recipe = "iron-gear-wheel"), e("fast-inserter", 5, 4, Dir.S), e("fast-inserter", 5, 8, Dir.N),
            e("assembling-machine-3", cAt, 11, recipe = "iron-gear-wheel"), e("fast-inserter", cAt + 1, 10, Dir.S),
        )
        val spec = base.copy(
            checkRates = true,
            machines = mapOf(Tile(1, 5) to gears(0.5), Tile(4, 5) to gears(1.1), Tile(1, 11) to gears(0.4), Tile(5, 11) to gears(0.4),
                Tile(9, 11) to science.copy(demand = mapOf("iron-gear-wheel" to 0.5))),
            outputs = listOf(OutputSpec(Tile(12, 3), "iron-gear-wheel", 1.5)),
        )
        assertEquals(emptyList(), check("split_output_after", layout(cAt = 1), spec))
        assertHas(check("split_output_before", layout(cAt = 5), spec), ErrorType.SPLIT_OUTPUT, Tile(4, 5))
    }

    @Test
    fun `machines making one item share its work up to full speed`() {
        // A and B (1.4/s planned) feed the port's 1.5/s; C below makes 0.2/s for a science that takes 0.1/s. In the
        // game C's belt backs up and A runs faster than planned (it has spare speed), so nothing starves.
        val layout = twoBelts(
            e("assembling-machine-3", 1, 5, recipe = "iron-gear-wheel"), e("fast-inserter", 2, 4, Dir.S),
            e("assembling-machine-3", 6, 5, recipe = "iron-gear-wheel"), e("fast-inserter", 7, 4, Dir.S),
            e("assembling-machine-3", 1, 11, recipe = "iron-gear-wheel"), e("fast-inserter", 2, 10, Dir.S),
        )
        fun spec(fullA: Double) = base.copy(
            checkRates = true,
            machines = mapOf(Tile(1, 5) to gears(0.4, fullA), Tile(6, 5) to gears(1.0), Tile(1, 11) to gears(0.2), Tile(9, 11) to science),
            outputs = listOf(OutputSpec(Tile(12, 3), "iron-gear-wheel", 1.5)),
        )
        assertEquals(emptyList(), check("shared_work", layout, spec(fullA = 2.5)))
        // Without spare speed the port comes up short.
        assertHas(check("shared_work_no_spare", layout, spec(fullA = 0.4)), ErrorType.STARVED, Tile(12, 3))
    }

    @Suppress("unused")
    private fun List<PlacedEntity>.at(x: Int, y: Int) = first { it.x == x && it.y == y }

    @Test
    fun `an inserter drops onto the far side of a curve`() {
        // Straight belts: far lane, or the right lane in line with the belt.
        assertEquals(1, data.GameConstants.dropLane(Dir.E, Dir.N, Dir.N))
        assertEquals(0, data.GameConstants.dropLane(Dir.W, Dir.N, Dir.N))
        assertEquals(data.GameConstants.PARALLEL_DROP_LANE, data.GameConstants.dropLane(Dir.N, Dir.N, Dir.N))
        // Left turn (east, then north): from outside the bend (south or east of it) onto the inside lane, the left.
        assertEquals(0, data.GameConstants.dropLane(Dir.N, Dir.E, Dir.N))
        assertEquals(0, data.GameConstants.dropLane(Dir.W, Dir.E, Dir.N))
        // From inside the bend (long-handed, across the belt before or after it) onto the outside lane.
        assertEquals(1, data.GameConstants.dropLane(Dir.E, Dir.E, Dir.N))
        assertEquals(1, data.GameConstants.dropLane(Dir.S, Dir.E, Dir.N))
        // Right turn (west, then north): the inside lane is the right one.
        assertEquals(1, data.GameConstants.dropLane(Dir.N, Dir.W, Dir.N))
        assertEquals(1, data.GameConstants.dropLane(Dir.E, Dir.W, Dir.N))
    }

    @Test
    fun `cable dropped onto a left turn from behind lands on the inside lane`() {
        // Iron on one lane runs east and turns north at (2,3); a cable assembler below drops cable onto the curve
        // from the south, onto the inside (left) lane.
        fun layout() = Layout(6, 8, listOf(
            belt(0, 3, Dir.E, 1), belt(1, 3, Dir.E, 1), belt(2, 3, Dir.N, 1), belt(2, 2, Dir.N, 1), belt(2, 1, Dir.N, 1),
            e("fast-inserter", 2, 4, Dir.S),
            e("assembling-machine-3", 1, 5, recipe = "copper-cable"),
        ))
        val left = check("curve_drop_inside", layout(), base.copy(sources = listOf(SourceSpec(Tile(0, 3), "iron-plate", 1.0, lane = 0))))
        assertHas(left, ErrorType.INSERTER_WRONG_ITEM, Tile(2, 4))
        val right = check("curve_drop_free", layout(), base.copy(sources = listOf(SourceSpec(Tile(0, 3), "iron-plate", 1.0, lane = 1))))
        assertTrue(right.none { it.type == ErrorType.INSERTER_WRONG_ITEM }, "$right")
    }
}
