import blocks.EDGE
import blocks.runFactory
import blocks.splitPlan
import config.BlocksConfig
import config.ConfigSource
import config.SearchConfig
import config.Tuning
import geom.Dir
import plan.Role
import plan.buildPlan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FactoryTest {
    @Test
    fun `the split keeps blocks within the limit and balances every item`() {
        val plan = buildPlan(TestData.data, TestData.job("t3"))
        val split = splitPlan(plan, BlocksConfig(maxMachines = 5, sizeBy = "machines"), 30.0)
        assertTrue(split.blocks.size >= 2)
        assertTrue(split.blocks.all { it.size <= 5 }, "${split.blocks}")
        assertEquals(plan.machines.size, split.blocks.sumOf { it.size })
        // Each block's links in and out balance what its machines make and use.
        // Against the split's own plan: machine rates are rebalanced for the split.
        split.plan.nets.forEach { net ->
            split.blocks.indices.forEach { b ->
                val ts = split.plan.netTerminals(net).filter { it.machine != null && it.machine in split.blocks[b] }
                val made = ts.filter { it.role == Role.PRODUCER }.sumOf { it.rate }
                val used = ts.filter { it.role == Role.CONSUMER }.sumOf { it.rate }
                val inflow = split.links.filter { it.item == net.item && it.to == b }.sumOf { it.rate }
                val outflow = split.links.filter { it.item == net.item && it.from == b }.sumOf { it.rate }
                assertEquals(made + inflow, used + outflow, 1e-6, "${net.item} in block $b")
            }
        }
        assertTrue(split.links.filter { it.inner }.all { it.rate <= 0.9 * 30.0 + 1e-9 })
    }

    @Test
    fun `T3 as two blocks stitches into one valid factory`() {
        listOf(false, true).forEach { test ->
            val config = TestData.job("t3").copy(blocks = BlocksConfig(maxMachines = 5, sizeBy = "machines"), testMode = test,
                search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7))
            val plan = buildPlan(TestData.data, config)
            val out = File("build/factory/t3${if (test) "_test" else ""}")
            out.deleteRecursively()
            val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
            assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
            assertTrue(File(out, "best_output_string.txt").exists() && File(out, "ports.json").exists())
            assertTrue(File(out, "block_1/best_output_string.txt").exists() && File(out, "block_2/best_output_string.txt").exists())
            if (!test) assertTrue(File(out, "test_output_string.txt").exists() && File(out, "test_feeds.txt").readText().startsWith("every port fed"), File(out, "test_feeds.txt").takeIf { it.exists() }?.readText())
            // Its two blocks trade circuits and gears, each under a lane: they share one belt.
            val log = File(out, "factory.txt").readText()
            assertTrue(Regex("block 1 -> block 2: .*electronic-circuit.*iron-gear-wheel").containsMatchIn(log), log)
            val shared = blocks.loadBlock(out, 1)[0].sources.filter { it.lane >= 0 }
            assertEquals(setOf("electronic-circuit", "iron-gear-wheel"), shared.map { it.item }.toSet(), "${shared}")
            assertEquals(1, shared.map { it.tile }.distinct().size)
        }
    }

    @Test
    fun `edge links leave straight out of blocks on an allowed edge, the rest run through the corridors`() {
        val west = buildPlan(TestData.data, TestData.job("t3"))
        val split = splitPlan(west, BlocksConfig(maxMachines = 5, sizeBy = "machines"), 30.0)
        val inputs = split.links.filter { it.from == EDGE }
        assertTrue(inputs.isNotEmpty())
        inputs.forEach { l -> assertEquals(Dir.W !in split.geometry.touches[l.to], l.routed, "$l on ${split.geometry.boxes}") }
        assertTrue(inputs.filter { !it.routed }.all { it.toSide == "W" }, "$inputs")
        assertTrue(split.links.filter { it.routed && !it.fluid }.all { it.rate <= 0.9 * 30.0 + 1e-9 })
        // Any edge (the default): two blocks both touch some edge, so every edge link leaves its block directly.
        val any = TestData.job("t3").let { c -> c.copy(sources = c.sources.map { ConfigSource(it.name, edge = "any") }) }
        assertTrue(splitPlan(buildPlan(TestData.data, any), BlocksConfig(maxMachines = 5, sizeBy = "machines"), 30.0).links.none { it.routed })
    }

    @Test
    fun `a factory takes its inputs and outputs on the edges the job names, fluids included`() {
        val base = TestData.job("t4")
        val config = base.copy(
            outputs = listOf(ConfigSource("processing-unit", edge = "east")), blocks = BlocksConfig(maxMachines = 6, sizeBy = "machines"),
            search = SearchConfig(timeBudgetS = 40.0, seedsPerSize = 2, threads = 4, seed = 7),
        )
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/t4_edges")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        val w = f.layout.w; val h = f.layout.h
        val edgeOf = mapOf("iron-plate" to "west", "copper-plate" to "west", "plastic-bar" to "north", "sulfuric-acid" to "south")
        f.sources.forEach { s ->
            val ok = when (edgeOf.getValue(s.item)) { "west" -> s.tile.x <= 0; "north" -> s.tile.y <= 0; else -> s.tile.y >= h - 1 }
            assertTrue(ok, "${s.item} enters at ${s.tile} of $w x $h")
        }
        assertTrue(f.sources.any { it.fluid && it.item == "sulfuric-acid" })
        assertTrue(f.outputs.filter { it.item == "processing-unit" }.all { it.tile.x >= w - 1 }, "${f.outputs}")
    }

    @Test
    fun `a fluid product leaves the factory by pipe on the edge the job names`() {
        val config = TestData.job("acid").copy(
            outputs = listOf(ConfigSource("sulfuric-acid", edge = "east")), blocks = BlocksConfig(maxMachines = 2, sizeBy = "machines"),
            search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7),
        )
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/acid_east")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        val acid = f.outputs.filter { it.item == "sulfuric-acid" }
        assertTrue(acid.isNotEmpty() && acid.all { it.fluid && it.tile.x >= f.layout.w - 1 }, "${f.outputs}")
    }

    @Test
    fun `blocks share belts inside while links between blocks keep one item each`() {
        val config = TestData.job("t3").copy(blocks = BlocksConfig(maxMachines = 5, sizeBy = "machines"), laneSharing = true,
            search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7))
        val out = File("build/factory/t3_shared")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), buildPlan(TestData.data, config), out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
    }

    @Test
    fun `machines joined by pipes may sit in different blocks, joined by a pipe tree through the corridors`() {
        val config = plasticJob.copy(blocks = BlocksConfig(maxMachines = 3, sizeBy = "machines"),
            search = SearchConfig(timeBudgetS = 40.0, seedsPerSize = 2, threads = 4, seed = 7))
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/plastic_split")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        assertTrue(File(out, "factory.txt").readText().contains("pipes:"), "no fluid crosses between blocks")
    }

    @Test
    fun `with templates the split keeps furnaces in blocks of their own`() {
        val config = config.RunConfiguration(
            targets = listOf(config.ConfigTarget("electronic-circuit", 6.0), config.ConfigTarget("iron-gear-wheel", 2.0)),
            sources = listOf(ConfigSource("iron-ore", edge = "west"), ConfigSource("copper-ore", edge = "west")),
            blocks = BlocksConfig(maxMachines = 20),
        )
        val plan = buildPlan(TestData.data, config)
        val pure = blocks.Templates.columnRecipes(plan, config)
        // Furnaces fit a column (and so do the cable and gear assembler 3s, in split columns of short inserters).
        assertTrue(pure.keys.containsAll(setOf("iron-plate", "copper-plate")), "${pure.keys}")
        val split = splitPlan(plan, config.blocks!!, 30.0, pure = pure)
        split.blocks.forEach { ms ->
            val recipes = ms.map { split.plan.machines[it].recipe }.toSet()
            assertTrue(recipes.size == 1 || recipes.none { it in pure }, "a block mixes $recipes")
        }
        // Without templates the same job mixes them (merging saves a block).
        val mixed = splitPlan(plan, config.blocks!!.copy(templates = false), 30.0)
        assertTrue(mixed.blocks.any { ms -> ms.map { mixed.plan.machines[it].recipe }.toSet().let { it.size > 1 && it.any { r -> r in pure } } })
    }

    @Test
    fun `smelting blocks are offered as furnace columns and the factory uses them`() {
        val config = config.RunConfiguration(
            targets = listOf(config.ConfigTarget("electronic-circuit", 3.0)),
            sources = listOf(ConfigSource("iron-ore", edge = "west"), ConfigSource("copper-ore", edge = "west")),
            outputs = listOf(ConfigSource("electronic-circuit", edge = "east")),
            blocks = BlocksConfig(maxMachines = 8, splitAt = listOf("iron-plate", "copper-plate")),
            search = SearchConfig(timeBudgetS = 60.0, seedsPerSize = 2, threads = 4, seed = 7),
        )
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/circuits_from_ore")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        val log = File(out, "factory.txt").readText()
        assertTrue(Regex("block \\d+: template \\d+ x \\d+").containsMatchIn(log), log)
        assertTrue(f.blockLayouts.any { r -> r.notes.any { it.startsWith("template") } }, "no template in the final factory")
    }

    @Test
    fun `a two-ingredient recipe is kept in a block of its own and offered as a column`() {
        val config = config.RunConfiguration(
            targets = listOf(config.ConfigTarget("automation-science-pack", 1.5)),
            machines = listOf("assembling-machine-3"), rawResources = listOf("iron-plate", "copper-plate"),
            sources = listOf(ConfigSource("iron-plate", edge = "west"), ConfigSource("copper-plate", edge = "west")),
            outputs = listOf(ConfigSource("automation-science-pack", edge = "east")),
            blocks = BlocksConfig(maxMachines = 6, sizeBy = "machines"),
            search = SearchConfig(timeBudgetS = 60.0, seedsPerSize = 2, threads = 4, seed = 7),
        )
        val plan = buildPlan(TestData.data, config)
        // Red science (copper and gears, a lane each) fits a column.
        assertTrue("automation-science-pack" in blocks.Templates.columnRecipes(plan, config))
        val out = File("build/factory/red_science_column")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        val log = File(out, "factory.txt").readText()
        assertTrue(Regex("block \\d+: template [\\d x,]+ \\(template: machine column of \\d+ assembling-machine-3 \\(automation-science-pack\\)").containsMatchIn(log), log)
        // The column (ten wide) is smaller than what the search finds for six assemblers, so the factory uses it.
        assertTrue(f.blockLayouts.any { r -> r.notes.any { it.startsWith("template: machine column") } }, "no column in the final factory")
    }

    @Test
    fun `links of one item leaving a block share one output port and branch off in the corridors`() {
        val config = config.RunConfiguration(
            targets = listOf(config.ConfigTarget("automation-science-pack", 1.0), config.ConfigTarget("logistic-science-pack", 1.0)),
            sources = listOf(ConfigSource("iron-ore", edge = "west"), ConfigSource("copper-ore", edge = "west")),
            outputs = listOf(ConfigSource("automation-science-pack", edge = "east"), ConfigSource("logistic-science-pack", edge = "east")),
            blocks = BlocksConfig(maxMachines = 6, sizeBy = "machines", sharedOutputs = true),
            search = SearchConfig(timeBudgetS = 60.0, seedsPerSize = 2, threads = 4, seed = 7),
        )
        val plan = buildPlan(TestData.data, config)
        val split = splitPlan(plan, config.blocks!!, 30.0)
        val groups = split.blocks.indices.flatMap { blocks.sharedOutputs(split.plan, split.links, it, Tuning.defaults()) }
        assertTrue(groups.isNotEmpty(), "no block sends one item to two others: ${split.links.map { "${it.from}->${it.to} ${it.item}" }}")
        // The producer has one port for the group.
        val g = groups[0]
        val b = g[0].from
        val shared = blocks.blockPlan(split.plan, split, b, Tuning.defaults(), shared = true)
        val apart = blocks.blockPlan(split.plan, split, b, Tuning.defaults())
        assertEquals(apart.plan.ports.size - (g.size - 1), shared.plan.ports.size)
        val out = File("build/factory/shared_outputs")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
    }

    @Test
    fun `a resumed run keeps the saved split and refines the saved blocks`() {
        val config = TestData.job("t3").copy(blocks = BlocksConfig(maxMachines = 5, sizeBy = "machines"),
            search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7))
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/t3_resume")
        out.deleteRecursively()
        val first = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(first.success)
        assertTrue(File(out, "resume/split.json").exists() && File(out, "resume/block_1.json").exists() && File(out, "resume/block_2.json").exists())
        val saved = (0..1).map { blocks.loadBlock(out, it)[0].let { r -> r.w * r.h } }
        val again = assertNotNull(runFactory(TestData.data, blocks.savedJob(out)!!, Tuning.defaults(), plan, out, resume = true))
        assertTrue(again.success, "${again.errors.take(5)}")
        val log = File(out, "factory.txt").readText()
        assertEquals(2, Regex("resumed \\(").findAll(log).count(), log)
        assertTrue(File(out, "block_1/log.txt").readText().contains("refining a "), "blocks did not start from their saved layouts")
        // Refining starts from the saved layouts, so no block gets bigger.
        (0..1).forEach { b -> assertTrue(blocks.loadBlock(out, b)[0].let { r -> r.w * r.h } <= saved[b], "block ${b + 1} grew") }
    }
}
