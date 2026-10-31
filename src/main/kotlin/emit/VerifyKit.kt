package emit

import geom.Dir
import geom.Layout
import geom.PlacedEntity
import java.io.File

/**
 * Small blueprints that each test one fact from the plan's verify list in game (editor or sandbox).
 * Power is not included: build an electric-energy-interface (editor) next to the medium pole in each one.
 */
object VerifyKit {
    private fun e(name: String, x: Int, y: Int, dir: Dir = Dir.N, recipe: String? = null, item: String? = null, fluid: String? = null, ug: String? = null) =
        PlacedEntity(name, PlacedEntity.kindOf(name, ug), x, y, dir, recipe, item, fluid)

    private fun pole(x: Int, y: Int) = e("medium-electric-pole", x, y)

    data class Case(val id: String, val title: String, val check: String, val layout: Layout)

    val cases: List<Case> = listOf(
        Case(
            "v1-inserter-direction", "Inserter direction points at the pickup side",
            "Plates must move from the infinity chest (west) into the steel chest (east). If the steel chest stays empty " +
                "and the inserter tries to take from it, the direction convention is reversed.",
            Layout(4, 3, listOf(
                e("infinity-chest", 0, 0, item = "iron-plate"),
                e("inserter", 1, 0, Dir.W),
                e("steel-chest", 2, 0),
                pole(1, 1),
            )),
        ),
        Case(
            "v2-inserter-lanes", "Which lane an inserter drops on",
            "Row 0: belt runs north, inserter drops from the west (perpendicular): expected far (east) lane. " +
                "Row 1: belt runs east, away from the inserter (parallel). Row 2: belt runs west, toward the inserter. " +
                "Row 3: inserter drops onto a left turn from behind it: expected the inside (west) lane. Note the lane each row fills.",
            Layout(10, 16, listOf(
                // Perpendicular: belt heading N at x=3, inserter at x=2 dropping east.
                e("infinity-chest", 0, 1, item = "iron-plate"), e("inserter", 1, 1, Dir.W), e("transport-belt", 2, 1, Dir.N),
                e("transport-belt", 2, 0, Dir.N),
                // Parallel, belt heading away (east) from the inserter.
                e("infinity-chest", 0, 4, item = "iron-plate"), e("inserter", 1, 4, Dir.W),
                e("transport-belt", 2, 4, Dir.E), e("transport-belt", 3, 4, Dir.E), e("transport-belt", 4, 4, Dir.E),
                // Parallel, belt heading toward the inserter (west), ending in a dead end at x=2.
                e("infinity-chest", 0, 7, item = "iron-plate"), e("inserter", 1, 7, Dir.W),
                e("transport-belt", 2, 7, Dir.W), e("transport-belt", 3, 7, Dir.W), e("transport-belt", 4, 7, Dir.W),
                // Curve: belt from (2,11) heading E turns north at (3,11); inserter drops onto the curve from the south.
                e("transport-belt", 2, 11, Dir.E), e("transport-belt", 3, 11, Dir.N), e("transport-belt", 3, 10, Dir.N),
                e("infinity-chest", 3, 13, item = "iron-plate"), e("inserter", 3, 12, Dir.S),
                pole(5, 2), pole(5, 8), pole(5, 12),
            )),
        ),
        Case(
            "v3-underground-pairing", "Underground pairing with an opposite-facing pair in between",
            "Pair A runs east from x=1 to x=6. Pair B (same tier) runs west from x=5 to x=3, inside A's span. " +
                "Check whether plates still reach the chest at x=9 through A, or whether B breaks A's pairing.",
            Layout(10, 4, listOf(
                e("infinity-chest", 0, 0, item = "iron-plate"), e("inserter", 0, 1, Dir.N),
                e("transport-belt", 0, 2, Dir.E),
                e("underground-belt", 1, 2, Dir.E, ug = "input"),
                e("underground-belt", 5, 2, Dir.W, ug = "input"),
                e("underground-belt", 3, 2, Dir.W, ug = "output"),
                e("underground-belt", 6, 2, Dir.E, ug = "output"),
                e("transport-belt", 7, 2, Dir.E),
                e("inserter", 8, 2, Dir.W), e("steel-chest", 9, 2),
                pole(4, 0),
            )),
        ),
        Case(
            "v4-sideload-underground", "Sideloading into an underground entrance keeps one lane",
            "Items arrive from the side onto the entrance at x=3. Note which lane comes out at the exit.",
            Layout(10, 4, listOf(
                e("infinity-chest", 3, 3, item = "iron-plate"), e("inserter", 3, 2, Dir.S),
                e("transport-belt", 3, 1, Dir.N),
                e("underground-belt", 3, 0, Dir.E, ug = "input"),
                e("underground-belt", 6, 0, Dir.E, ug = "output"),
                e("transport-belt", 7, 0, Dir.E), e("transport-belt", 8, 0, Dir.E),
                pole(5, 2),
            )),
        ),
        Case(
            "v5-poles-without-wires", "Do poles in a blueprint connect when it has no wires?",
            "Two medium poles 6 tiles apart and no wires array. After building, hover a pole: are they connected?",
            Layout(8, 1, listOf(pole(0, 0), pole(6, 0))),
        ),
        Case(
            "v6-infinity-settings", "Infinity pipe and chest settings import",
            "The pipe should be set to sulfuric acid (at least, 100%), the chest to iron plates (exactly 50).",
            Layout(3, 1, listOf(e("infinity-pipe", 0, 0, fluid = "sulfuric-acid"), e("infinity-chest", 2, 0, item = "iron-plate"))),
        ),
        Case(
            "v7-underground-directions", "Underground type and direction in all four directions",
            "Four underground pairs (N, E, S, W), each fed by an inserter from an infinity chest. Items must come out of every exit.",
            Layout(14, 14, listOf(
                // East-going pair.
                e("infinity-chest", 0, 1, item = "iron-plate"), e("inserter", 0, 2, Dir.N),
                e("transport-belt", 0, 3, Dir.E), e("underground-belt", 1, 3, Dir.E, ug = "input"),
                e("underground-belt", 4, 3, Dir.E, ug = "output"), e("transport-belt", 5, 3, Dir.E),
                // West-going pair.
                e("infinity-chest", 12, 5, item = "copper-plate"), e("inserter", 12, 6, Dir.N),
                e("transport-belt", 12, 7, Dir.W), e("underground-belt", 11, 7, Dir.W, ug = "input"),
                e("underground-belt", 8, 7, Dir.W, ug = "output"), e("transport-belt", 7, 7, Dir.W),
                // South-going pair.
                e("infinity-chest", 1, 9, item = "iron-plate"), e("inserter", 2, 9, Dir.W),
                e("transport-belt", 3, 9, Dir.S), e("underground-belt", 3, 10, Dir.S, ug = "input"),
                e("underground-belt", 3, 13, Dir.S, ug = "output"),
                // North-going pair.
                e("infinity-chest", 11, 13, item = "copper-plate"), e("inserter", 10, 13, Dir.E),
                e("transport-belt", 9, 13, Dir.N), e("underground-belt", 9, 12, Dir.N, ug = "input"),
                e("underground-belt", 9, 9, Dir.N, ug = "output"), e("transport-belt", 9, 8, Dir.N),
                pole(1, 5), pole(6, 5), pole(6, 11), pole(11, 10),
            )),
        ),
        Case(
            "v8-inserter-rates", "Inserter throughput, chest to chest",
            "Each row moves plates from an infinity chest to a steel chest. After 60 s, divide each chest's count by 60 and " +
                "compare with GameConstants (inserter 0.83, long-handed 1.2, fast 2.31, bulk 2.31 x hand size).",
            Layout(6, 8, listOf(
                e("infinity-chest", 0, 0, item = "iron-plate"), e("inserter", 1, 0, Dir.W), e("steel-chest", 2, 0),
                e("infinity-chest", 0, 2, item = "iron-plate"), e("long-handed-inserter", 2, 2, Dir.W), e("steel-chest", 4, 2),
                e("infinity-chest", 0, 4, item = "iron-plate"), e("fast-inserter", 1, 4, Dir.W), e("steel-chest", 2, 4),
                e("infinity-chest", 0, 6, item = "iron-plate"), e("bulk-inserter", 1, 6, Dir.W), e("steel-chest", 2, 6),
                pole(3, 1), pole(3, 5),
            )),
        ),
        Case(
            "v9-fluid-input-side", "Assembler fluid input follows its direction",
            "Four processing-unit assemblers facing N, E, S, W, each with an infinity pipe of sulfuric acid on the middle " +
                "tile of the side it faces. Every machine should show acid in its input.",
            Layout(14, 14, listOf(
                e("assembling-machine-3", 1, 1, Dir.N, recipe = "processing-unit"), e("infinity-pipe", 2, 0, fluid = "sulfuric-acid"),
                e("assembling-machine-3", 8, 1, Dir.E, recipe = "processing-unit"), e("infinity-pipe", 11, 2, fluid = "sulfuric-acid"),
                e("assembling-machine-3", 1, 8, Dir.S, recipe = "processing-unit"), e("infinity-pipe", 2, 11, fluid = "sulfuric-acid"),
                e("assembling-machine-3", 8, 8, Dir.W, recipe = "processing-unit"), e("infinity-pipe", 7, 9, fluid = "sulfuric-acid"),
                pole(5, 5),
            )),
        ),
        Case(
            "v10-chemical-plant-connections", "Chemical plant fluid connections in all four directions",
            "Four lubricant plants facing N, E, S, W. Heavy oil is supplied at the position the generator thinks is " +
                "input 1 and lubricant drained at output 1. Every plant should run; one that does not shows a wrong " +
                "connection table or rotation.",
            fluidMachines("chemical-plant", "lubricant", listOf("heavy-oil"), listOf("lubricant"), 13),
        ),
        Case(
            "v11-refinery-connections", "Oil refinery connections, rotation and mirroring",
            "Three refineries on advanced oil processing: facing north, facing north mirrored, facing east. Water " +
                "on input 1, crude on input 2, heavy/light/petroleum drained on outputs 1/2/3. All three should run.",
            refineries(),
        ),
        Case(
            "v12-pipe-to-ground-span", "Pipe-to-ground reach and direction",
            "Top row: two pipe-to-ground ends with 9 tiles between them feed a lubricant plant. Bottom row: 10 tiles " +
                "between. If only the top plant runs, the gap limit is 9. Also check each end's direction points to " +
                "its above-ground pipe.",
            pipeToGround(),
        ),
        Case(
            "v13-splitter-lanes", "A splitter keeps lanes",
            "Iron is dropped on the south lane and copper on the north lane of a belt into a splitter. Both splitter " +
                "outputs should carry iron on the south lane and copper on the north lane, like the input.",
            Layout(9, 5, listOf(
                e("infinity-chest", 1, 0, item = "iron-plate"), e("inserter", 1, 1, Dir.N),
                e("infinity-chest", 2, 4, item = "copper-plate"), e("inserter", 2, 3, Dir.S),
                e("transport-belt", 0, 2, Dir.E), e("transport-belt", 1, 2, Dir.E), e("transport-belt", 2, 2, Dir.E), e("transport-belt", 3, 2, Dir.E),
                e("splitter", 4, 2, Dir.E),
                e("transport-belt", 5, 2, Dir.E), e("transport-belt", 6, 2, Dir.E), e("transport-belt", 7, 2, Dir.E), e("transport-belt", 8, 2, Dir.E),
                e("transport-belt", 5, 3, Dir.E), e("transport-belt", 6, 3, Dir.E), e("transport-belt", 7, 3, Dir.E), e("transport-belt", 8, 3, Dir.E),
                pole(0, 4),
            )),
        ),
        Case(
            "v15-void-chest", "An infinity chest without filters deletes what it is given",
            "Test mode empties output belts into infinity chests set to remove unfiltered items. Iron runs along the " +
                "belt and an inserter puts it into such a chest: the chest should stay empty while the inserter keeps working.",
            Layout(5, 5, listOf(
                e("infinity-chest", 0, 0, item = "iron-plate"), e("inserter", 0, 1, Dir.N),
                e("transport-belt", 0, 2, Dir.E), e("transport-belt", 1, 2, Dir.E), e("transport-belt", 2, 2, Dir.E), e("transport-belt", 3, 2, Dir.E),
                e("inserter", 3, 3, Dir.N), e("infinity-chest", 3, 4),
                pole(1, 4),
            )),
        ),
        Case(
            "v16-underground-weaving", "Undergrounds of different tiers do not pair (weaving)",
            "Iron runs under row 0 in a fast underground from x 0 to x 5 and should come out down the belt at x 6. " +
                "Copper runs under the same row in an express underground from x 1 to x 7, across the fast pair's span, " +
                "and should come out east of x 7. If either stops or comes out of the other's exit, undergrounds of " +
                "different tiers interfere: set weave_tier to \"none\".",
            Layout(10, 4, listOf(
                e("infinity-chest", 0, 2, item = "iron-plate"), e("inserter", 0, 1, Dir.S),
                e("fast-underground-belt", 0, 0, Dir.E, ug = "input"), e("fast-underground-belt", 5, 0, Dir.E, ug = "output"),
                e("fast-transport-belt", 6, 0, Dir.S), e("fast-transport-belt", 6, 1, Dir.S), e("fast-transport-belt", 6, 2, Dir.S),
                e("infinity-chest", 1, 3, item = "copper-plate"), e("inserter", 1, 2, Dir.S), e("fast-transport-belt", 1, 1, Dir.N),
                e("express-underground-belt", 1, 0, Dir.E, ug = "input"), e("express-underground-belt", 7, 0, Dir.E, ug = "output"),
                e("fast-transport-belt", 8, 0, Dir.E), e("fast-transport-belt", 9, 0, Dir.E),
                pole(3, 2),
            )),
        ),
        Case(
            "v14-assembler-fluid-sides", "Does an assembler take fluid on the side opposite its input?",
            "Two electric-engine assemblers facing north. Left: lubricant on the north middle (the input per the " +
                "table). Right: lubricant on the south middle (the output side). If the right one also fills, " +
                "assemblers accept fluid on either connection and the table should say so.",
            Layout(10, 6, listOf(
                e("assembling-machine-3", 1, 1, Dir.N, recipe = "electric-engine-unit"), e("infinity-pipe", 2, 0, fluid = "lubricant"),
                e("assembling-machine-3", 6, 1, Dir.N, recipe = "electric-engine-unit"), e("infinity-pipe", 7, 4, fluid = "lubricant"),
                pole(5, 2),
            )),
        ),
        Case(
            "v17-space-age-fluid-connections", "Space Age machine fluid connections in all four directions",
            "Rows, each machine facing N, E, S, W: foundry casting low density structures (molten iron on input 1, " +
                "molten copper on input 2); foundry melting lava (lava in, molten iron drained on output 1); " +
                "electromagnetic plant making electrolyte (heavy oil, holmium solution in, electrolyte drained); " +
                "cryogenic plant cooling fluoroketone (hot in, cold drained); cryogenic plant making sulfur (water, " +
                "petroleum gas in); biochamber cracking heavy oil (water, heavy oil in, light oil drained). Every pipe " +
                "should connect and each fluid should show in the machine's matching box (hover it); the cryogenic " +
                "plants run, the others lack items or nutrients. A pipe that does not connect, or a fluid in the " +
                "wrong box, means the table in GameConstants is wrong for that machine.",
            stack(
                fluidMachines("foundry", "casting-low-density-structure", listOf("molten-iron", "molten-copper"), emptyList(), 10),
                fluidMachines("foundry", "molten-iron-from-lava", listOf("lava"), listOf("molten-iron"), 10),
                fluidMachines("electromagnetic-plant", "electrolyte", listOf("heavy-oil", "holmium-solution"), listOf("electrolyte"), 9),
                fluidMachines("cryogenic-plant", "fluoroketone-cooling", listOf("fluoroketone-hot"), listOf("fluoroketone-cold"), 10),
                fluidMachines("cryogenic-plant", "sulfur", listOf("water", "petroleum-gas"), emptyList(), 10),
                fluidMachines("biochamber", "heavy-oil-cracking", listOf("water", "heavy-oil"), listOf("light-oil"), 8),
            ),
        ),
        Case(
            "v19-cracking-on-surplus", "A storage tank on heavy oil, wired to a cracking plant that works on a condition",
            "A heavy oil cracking plant (water from an infinity pipe, light oil drained) gets heavy oil through a storage " +
                "tank filled by an infinity pipe, and has a red wire from the tank with the condition heavy oil > 12500. " +
                "Check: the tank fills (its corner connection meets the pipe), the wire is there and red, the plant shows " +
                "the condition and runs. Set the heavy oil infinity pipe to 40 %: the tank drops below half and the plant " +
                "stops; back to 100 % and it runs. A missing wire or condition means the wire ids or control_behavior " +
                "format in GameConstants / Blueprint.kt are wrong; a tank that stays empty, its connection table.",
            surplusControl(),
        ),
        Case(
            "v18-filtered-output-inserters", "Filtered inserters split a machine's products",
            "A recycler (2 x 4) takes electronic circuits and returns iron plates and copper cable. The top output " +
                "inserter is filtered to iron plates, the bottom one to copper cable: each chest should get only its " +
                "item. If an inserter has no filter or takes both, the blueprint filter format (use_filters, filters " +
                "without quality) is wrong.",
            Layout(6, 5, listOf(
                e("infinity-chest", 0, 1, item = "electronic-circuit"), e("inserter", 1, 1, Dir.W),
                e("recycler", 2, 0),
                e("fast-inserter", 4, 0, Dir.W, item = "iron-plate"), e("steel-chest", 5, 0),
                e("fast-inserter", 4, 3, Dir.W, item = "copper-cable"), e("steel-chest", 5, 3),
                pole(4, 1),
            )),
        ),
    )

    /** Layouts one below the other, a tile apart. */
    private fun stack(vararg parts: Layout): Layout {
        val list = ArrayList<PlacedEntity>()
        var y = 0
        parts.forEach { p -> p.entities.forEach { list += it.copy(y = it.y + y) }; y += p.h + 1 }
        return Layout(parts.maxOf { it.w }, y - 1, list)
    }

    /** One machine per direction, fluids at the connections the table predicts. */
    private fun fluidMachines(machine: String, recipe: String, inputs: List<String>, outputs: List<String>, pitch: Int): Layout {
        val size = data.GameConstants.MACHINE_SIZES.getValue(machine).first
        val conns = fluidTable(machine)
        val list = ArrayList<PlacedEntity>()
        Dir.entries.forEachIndexed { i, d ->
            val ox = 2 + i * pitch; val oy = 2
            list += e(machine, ox, oy, d, recipe = recipe)
            fun pipe(flow: data.GameConstants.Flow, idx: Int, fluid: String, drain: Boolean) {
                val c = conns.first { it.flow == flow && it.index == idx }
                val (dx, dy) = data.GameConstants.placeConnection(c, size, size, d.ordinal, false)
                list += e("infinity-pipe", ox + dx, oy + dy, fluid = fluid).copy(drain = drain)
            }
            inputs.forEachIndexed { k, f -> pipe(data.GameConstants.Flow.INPUT, k + 1, f, false) }
            outputs.forEachIndexed { k, f -> pipe(data.GameConstants.Flow.OUTPUT, k + 1, f, true) }
            list += pole(ox + size, oy + size)
        }
        return Layout(4 * pitch + 4, size + 6, list)
    }

    private fun refineries(): Layout {
        val conns = fluidTable("oil-refinery")
        val list = ArrayList<PlacedEntity>()
        listOf(Triple(Dir.N, false, 2), Triple(Dir.N, true, 11), Triple(Dir.E, false, 20)).forEach { (d, mirror, ox) ->
            val oy = 2
            list += e("oil-refinery", ox, oy, d, recipe = "advanced-oil-processing").copy(mirror = mirror)
            val fluids = listOf(data.GameConstants.Flow.INPUT to listOf("water", "crude-oil"),
                data.GameConstants.Flow.OUTPUT to listOf("heavy-oil", "light-oil", "petroleum-gas"))
            fluids.forEach { (flow, names) ->
                names.forEachIndexed { k, f ->
                    val c = conns.first { it.flow == flow && it.index == k + 1 }
                    val (dx, dy) = data.GameConstants.placeConnection(c, 5, 5, d.ordinal, mirror)
                    list += e("infinity-pipe", ox + dx, oy + dy, fluid = f).copy(drain = flow == data.GameConstants.Flow.OUTPUT)
                }
            }
            list += pole(ox + 5, oy + 5)
        }
        return Layout(28, 10, list)
    }

    private fun pipeToGround(): Layout {
        val list = ArrayList<PlacedEntity>()
        listOf(0 to 9, 8 to 10).forEach { (y, gap) ->
            val far = 1 + gap + 1
            list += e("infinity-pipe", 0, y, fluid = "heavy-oil")
            list += e("pipe-to-ground", 1, y, Dir.W)
            list += e("pipe-to-ground", far, y, Dir.E)
            list += e("pipe", far + 1, y)
            // Chemical plant facing north with input 1 on the tile right after the far end.
            list += e("chemical-plant", far + 1, y + 1, Dir.N, recipe = "lubricant")
            list += e("infinity-pipe", far + 1, y + 4, fluid = "lubricant").copy(drain = true)
            list += pole(far + 4, y + 2)
        }
        return Layout(18, 14, list)
    }

    /** A heavy oil cracking plant fed through a storage tank, wired to it with "heavy oil > 12500". */
    private fun surplusControl(): Layout {
        val conns = fluidTable("chemical-plant")
        val px = 6; val py = 6
        val list = ArrayList<PlacedEntity>()
        val plant = e("chemical-plant", px, py, Dir.N, recipe = "heavy-oil-cracking")
            .copy(circuit = geom.CircuitCondition("heavy-oil", ">", (data.GameConstants.STORAGE_TANK_CAPACITY / 2).toInt()))
        list += plant
        fun at(flow: data.GameConstants.Flow, idx: Int): geom.Tile {
            val (dx, dy) = data.GameConstants.placeConnection(conns.first { it.flow == flow && it.index == idx }, 3, 3, Dir.N.ordinal, false)
            return geom.Tile(px + dx, py + dy)
        }
        val water = at(data.GameConstants.Flow.INPUT, 1); val heavy = at(data.GameConstants.Flow.INPUT, 2); val light = at(data.GameConstants.Flow.OUTPUT, 1)
        list += e("infinity-pipe", water.x, water.y, fluid = "water")
        list += e("infinity-pipe", light.x, light.y, fluid = "light-oil").copy(drain = true)
        list += e("pipe", heavy.x, heavy.y)
        // The tank: a corner connection onto the heavy oil pipe, clear of the plant and the other pipes; an infinity
        // pipe of heavy oil on one of its other connections.
        val taken = (list.flatMap { it.rect.tiles() } + geom.Rect(px - 1, py - 1, 5, 5).tiles()).toSet()
        for (d in listOf(Dir.N, Dir.E)) for ((off, side) in data.GameConstants.storageTankConnections(d)) {
            val x0 = heavy.x - side.dx - off.x; val y0 = heavy.y - side.dy - off.y
            val rect = geom.Rect(x0, y0, 3, 3)
            if (x0 < 0 || y0 < 0 || rect.tiles().any { it in taken }) continue
            val feed = data.GameConstants.storageTankConnections(d).map { (o, s2) -> geom.Tile(x0 + o.x, y0 + o.y) + s2 }
                .firstOrNull { it != heavy && it !in taken && it.x >= 0 && it.y >= 0 } ?: continue
            list += PlacedEntity(data.GameConstants.STORAGE_TANK, geom.Kind.TANK, x0, y0, d)
            list += e("infinity-pipe", feed.x, feed.y, fluid = "heavy-oil")
            list += pole(px + 3, py + 3)
            return Layout(14, 14, list, circuitWires = listOf(Triple(list.indexOfFirst { it.kind == geom.Kind.TANK }, 0, data.GameConstants.WIRE_RED)))
        }
        error("no room for the tank in the v19 verify case")
    }

    private fun fluidTable(machine: String): List<data.GameConstants.FluidConnection> =
        data.GameConstants.baseFluidConnections(machine)

    /** Milestone M1: one gear machine, an input belt with an underground, two input inserters, output inserter, chest, pole. */
    val t1Hand = Layout(
        8, 6, listOf(
            e("assembling-machine-3", 3, 1, recipe = "iron-gear-wheel"),
            e("fast-transport-belt", 0, 5, Dir.E),
            e("fast-underground-belt", 1, 5, Dir.E, ug = "input"),
            e("fast-underground-belt", 3, 5, Dir.E, ug = "output"),
            e("fast-transport-belt", 4, 5, Dir.E),
            e("fast-transport-belt", 5, 5, Dir.E),    // dead end
            e("fast-inserter", 4, 4, Dir.S),          // picks from the belt at (4,5), drops into the machine
            e("fast-inserter", 5, 4, Dir.S),
            e("fast-inserter", 6, 2, Dir.W),          // picks from the machine at (5,2), drops into the chest
            e("steel-chest", 7, 2),
            pole(6, 4),
        ), label = "T1 hand layout"
    )

    /** Writes one blueprint string per case, plus the hand-written T1 layout. */
    fun write(dir: File) {
        dir.mkdirs()
        cases.forEach { c -> File(dir, "${c.id}.txt").writeText(c.layout.copy(label = c.title).toBlueprintString()) }
        File(dir, "t1-hand.txt").writeText(t1Hand.toBlueprintString())
    }
}
