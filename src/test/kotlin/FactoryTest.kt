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
        val split = splitPlan(plan, BlocksConfig(maxMachines = 5), 30.0)
        assertTrue(split.blocks.size >= 2)
        assertTrue(split.blocks.all { it.size <= 5 }, "${split.blocks}")
        assertEquals(plan.machines.size, split.blocks.sumOf { it.size })
        // Each block's links in and out balance what its machines make and use.
        plan.nets.forEach { net ->
            split.blocks.indices.forEach { b ->
                val ts = plan.netTerminals(net).filter { it.machine != null && it.machine in split.blocks[b] }
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
            val config = TestData.job("t3").copy(blocks = BlocksConfig(maxMachines = 5), testMode = test,
                search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7))
            val plan = buildPlan(TestData.data, config)
            val out = File("build/factory/t3${if (test) "_test" else ""}")
            out.deleteRecursively()
            val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
            assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
            assertTrue(File(out, "best_output_string.txt").exists() && File(out, "ports.json").exists())
            assertTrue(File(out, "block_1/best_output_string.txt").exists() && File(out, "block_2/best_output_string.txt").exists())
        }
    }

    @Test
    fun `edge links leave straight out of blocks on an allowed edge, the rest run through the corridors`() {
        val west = buildPlan(TestData.data, TestData.job("t3"))
        val split = splitPlan(west, BlocksConfig(maxMachines = 5), 30.0)
        val inputs = split.links.filter { it.from == EDGE }
        assertTrue(inputs.isNotEmpty())
        inputs.forEach { l -> assertEquals(Dir.W !in split.geometry.touches[l.to], l.routed, "$l on ${split.geometry.boxes}") }
        assertTrue(inputs.filter { !it.routed }.all { it.toSide == "W" }, "$inputs")
        assertTrue(split.links.filter { it.routed && !it.fluid }.all { it.rate <= 0.9 * 30.0 + 1e-9 })
        // Any edge (the default): two blocks both touch some edge, so every edge link leaves its block directly.
        val any = TestData.job("t3").let { c -> c.copy(sources = c.sources.map { ConfigSource(it.name, edge = "any") }) }
        assertTrue(splitPlan(buildPlan(TestData.data, any), BlocksConfig(maxMachines = 5), 30.0).links.none { it.routed })
    }

    @Test
    fun `a factory takes its inputs and outputs on the edges the job names, fluids included`() {
        val base = TestData.job("t4")
        val config = base.copy(
            outputs = listOf(ConfigSource("processing-unit", edge = "east")), blocks = BlocksConfig(maxMachines = 6),
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
            outputs = listOf(ConfigSource("sulfuric-acid", edge = "east")), blocks = BlocksConfig(maxMachines = 2),
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
        val config = TestData.job("t3").copy(blocks = BlocksConfig(maxMachines = 5), laneSharing = true,
            search = SearchConfig(timeBudgetS = 20.0, seedsPerSize = 2, threads = 4, seed = 7))
        val out = File("build/factory/t3_shared")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), buildPlan(TestData.data, config), out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
    }

    @Test
    fun `machines joined by pipes may sit in different blocks, joined by a pipe tree through the corridors`() {
        val config = plasticJob.copy(blocks = BlocksConfig(maxMachines = 3),
            search = SearchConfig(timeBudgetS = 40.0, seedsPerSize = 2, threads = 4, seed = 7))
        val plan = buildPlan(TestData.data, config)
        val out = File("build/factory/plastic_split")
        out.deleteRecursively()
        val f = assertNotNull(runFactory(TestData.data, config, Tuning.defaults(), plan, out))
        assertTrue(f.success, "${f.errors.take(5)} ${f.notes}")
        assertTrue(File(out, "factory.txt").readText().contains("pipes:"), "no fluid crosses between blocks")
    }
}
