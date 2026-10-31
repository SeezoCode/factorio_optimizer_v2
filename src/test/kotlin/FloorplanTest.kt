import blocks.EDGE
import blocks.FpBlock
import blocks.FpNet
import blocks.FpParams
import blocks.FpShape
import blocks.FpState
import blocks.Floorplanner
import blocks.HUB
import blocks.BlockSize
import blocks.PartitionModel
import blocks.machineWeights
import blocks.partitionPlan
import blocks.rebalance
import blocks.splitPlan
import config.BlocksConfig
import geom.Dir
import geom.Rect
import plan.Role
import plan.buildPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val plasticJob = config.RunConfiguration(targets = listOf(config.ConfigTarget("plastic-bar", 6.0)),
    sources = listOf(config.ConfigSource("crude-oil", edge = "west"), config.ConfigSource("coal", edge = "north")))

class FloorplanTest {
    private fun padded(r: Rect, p: IntArray) = Rect(r.x - p[3], r.y - p[0], r.w + p[1] + p[3], r.h + p[0] + p[2])

    @Test
    fun `six equal blocks pack about square, not in a row, without overlapping`() {
        val blocks = List(6) { FpBlock(listOf(FpShape(20, 20)), IntArray(4) { 1 }) }
        val fp = Floorplanner(blocks, emptyList(), FpParams(seed = 3)).optimise()
        val boxes = fp.boxes.mapIndexed { i, b -> padded(b, blocks[i].pad) }
        for (a in boxes.indices) for (b in a + 1 until boxes.size) assertTrue(!boxes[a].overlaps(boxes[b]), "${boxes[a]} ${boxes[b]}")
        // 6 x 22 x 22 = 2904 tiles; a row would be 132 x 22. Two rows of three or three of two: 66 x 44.
        assertTrue(fp.w * fp.h <= 66 * 44, "${fp.w} x ${fp.h}")
        assertTrue(maxOf(fp.w, fp.h) <= 2 * minOf(fp.w, fp.h), "${fp.w} x ${fp.h}")
    }

    @Test
    fun `a single row start is improved on and every touch constraint holds`() {
        val rnd = java.util.Random(5)
        val blocks = List(8) { i ->
            val w = 8 + rnd.nextInt(20); val h = 8 + rnd.nextInt(20)
            val touch = when (i) { 0 -> setOf(Dir.W); 1 -> setOf(Dir.E); 2 -> setOf(Dir.N, Dir.E); else -> emptySet() }
            FpBlock(listOf(FpShape(w, h), FpShape(h, w)), IntArray(4) { d -> if (Dir.entries[d] in touch) 0 else 2 }, touch)
        }
        val nets = (0 until 7).map { FpNet(listOf(it to -1, it + 1 to -1)) }
        val planner = Floorplanner(blocks, nets, FpParams(seed = 11))
        val row = planner.pack(FpState(IntArray(8) { it }, IntArray(8) { it }, IntArray(8)))
        val fp = planner.optimise()
        assertEquals(0, fp.violations)
        assertTrue(fp.cost < row.cost, "${fp.cost} vs row ${row.cost}")
        assertEquals(0, fp.boxes[0].x)
        assertEquals(fp.w, fp.boxes[1].x2 + 1)
        assertEquals(0, fp.boxes[2].y); assertEquals(fp.w, fp.boxes[2].x2 + 1)
        val boxes = fp.boxes.mapIndexed { i, b -> padded(b, blocks[i].pad) }
        for (a in boxes.indices) for (b in a + 1 until boxes.size) assertTrue(!boxes[a].overlaps(boxes[b]), "${boxes[a]} ${boxes[b]}")
        boxes.forEach { assertTrue(it.x >= 0 && it.y >= 0 && it.x2 < fp.w && it.y2 < fp.h, "$it in ${fp.w} x ${fp.h}") }
    }

    @Test
    fun `the partition cuts fewer links than the greedy split it starts from and respects the size limit`() {
        val plan = buildPlan(TestData.data, TestData.job("t4"))
        val cfg = BlocksConfig(maxMachines = 6, sizeBy = "machines")
        val p = partitionPlan(plan, cfg, 30.0, seed = 3)
        assertTrue(p.blocks.all { it.size <= 6 }, "${p.blocks}")
        assertEquals(plan.machines.size, p.blocks.sumOf { it.size })
        assertEquals(plan.machines.indices.toSet(), p.blocks.flatten().toSet())
        assertTrue(p.cost <= p.greedyCost + 1e-9, "${p.cost} vs greedy ${p.greedyCost}")
    }

    @Test
    fun `blocks are sized by weight - simple machines weigh least, no block passes the hard limit`() {
        // Red + green + chemical science from ore: many furnaces (one in, one out) and many-input assemblers.
        val job = config.RunConfiguration(
            targets = listOf(config.ConfigTarget("automation-science-pack", 1.0), config.ConfigTarget("logistic-science-pack", 1.0)),
            sources = listOf(config.ConfigSource("iron-ore", edge = "west"), config.ConfigSource("copper-ore", edge = "west")),
        )
        val plan = buildPlan(TestData.data, job)
        val cfg = BlocksConfig(maxMachines = 8)
        val w = machineWeights(plan, cfg)
        val furnace = plan.machines.indices.filter { plan.machines[it].recipe == "iron-plate" }
        val science = plan.machines.indices.filter { plan.machines[it].recipe == "logistic-science-pack" }
        assertTrue(furnace.all { w[it] == 1.0 }, "${furnace.map { w[it] }}")
        assertTrue(science.all { w[it] > 1.0 }, "${science.map { w[it] }}")
        val size = BlockSize.of(plan, w, cfg)
        val p = partitionPlan(plan, cfg, 30.0, seed = 3)
        assertTrue(p.blocks.all { b -> b.sumOf { w[it] } <= size.hard + 1e-9 }, "${p.blocks.map { b -> b.sumOf { w[it] } }} > ${size.hard}")
        assertEquals(plan.machines.indices.toSet(), p.blocks.flatten().toSet())
        // A block of nothing but furnaces holds more machines than the target count; overflow is priced.
        assertTrue(p.blocks.any { b -> b.all { it in furnace } && b.size > 8 } || furnace.size <= 8, "${p.blocks}")
        assertEquals(0.0, size.overflow(size.target))
        assertEquals(cfg.overflowCost, size.overflow(size.target * 1.2), 1e-9)
        // size_by machines is the old cap.
        val old = partitionPlan(plan, cfg.copy(sizeBy = "machines"), 30.0, seed = 3)
        assertTrue(old.blocks.all { it.size <= 8 })
    }

    @Test
    fun `machines joined by pipes may go to different blocks, joined by one pipe tree per fluid`() {
        // Plastic from crude oil: refineries pipe petroleum gas to chemical plants. Three machines per block forces cuts.
        val plan = buildPlan(TestData.data, plasticJob)
        val split = splitPlan(plan, BlocksConfig(maxMachines = 3, sizeBy = "machines"), 30.0)
        assertTrue(split.blocks.all { it.size <= 3 })
        val hub = split.links.filter { it.hub }
        assertTrue(hub.isNotEmpty(), "${split.links}")
        // One port per block and fluid on a tree, and every tree is fed.
        hub.groupBy { it.item }.forEach { (item, ls) ->
            val blocks = ls.flatMap { listOf(it.from, it.to) }.filter { it >= 0 }
            assertEquals(blocks.size, blocks.toSet().size, "$item: $ls")
            assertTrue(ls.any { it.to == HUB }, "$item has no supply: $ls")
        }
        // Rates still balance per block and item, fluids included.
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
        // Every port is on a side, and ports that leave the factory straight out are on edges the block touches.
        split.links.forEach { l ->
            if (l.from >= 0) assertTrue(l.fromSide != null, "$l")
            if (l.to >= 0) assertTrue(l.toSide != null, "$l")
            if (!l.throughCorridor) {
                val b = if (l.from == EDGE) l.to else l.from
                assertTrue(Dir.fromName(l.side(b)!!) in split.geometry.touches[b], "$l")
            }
        }
    }

    @Test
    fun `rebalancing machine rates keeps every recipe's total and machine limits and never adds ports`() {
        val job = TestData.job("t4")
        val plan = buildPlan(TestData.data, job)
        val cfg = BlocksConfig(maxMachines = 4, sizeBy = "machines")
        val blocks = partitionPlan(plan, cfg, 30.0, seed = 3).blocks
        val r = rebalance(plan, blocks)
        plan.machines.groupBy { it.recipe }.forEach { (recipe, ms) ->
            assertEquals(ms.sumOf { it.crafts }, r.machines.filter { it.recipe == recipe }.sumOf { it.crafts }, 1e-9, recipe)
        }
        r.machines.forEach { m -> assertTrue(m.crafts <= maxOf(plan.machines[m.id].crafts, m.maxCrafts) + 1e-9, "${m.label} ${m.crafts} > ${m.maxCrafts}") }
        val blockOf = IntArray(plan.machines.size).also { a -> blocks.forEachIndexed { b, ms -> ms.forEach { a[it] = b } } }
        val before = PartitionModel(plan, cfg, 30.0).ports(blockOf, blocks.size)
        val after = PartitionModel(r, cfg, 30.0).ports(blockOf, blocks.size)
        assertTrue(after <= before, "$after ports after rebalancing, $before before")
    }
}
