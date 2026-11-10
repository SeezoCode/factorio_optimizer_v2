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
import blocks.linkMismatches
import blocks.splitPlan
import blocks.Link
import blocks.Split
import blocks.withLocalSinks
import blocks.withBotLinks
import blocks.sharedOutputs
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
        val plan = buildPlan(TestData.data, TestData.job("t4"), handTuning)
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
        val plan = buildPlan(TestData.data, job, handTuning)
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
    fun `small by-products end in chests, off the sides their block keeps to the factory's edge`() {
        val plan = buildPlan(TestData.data, plasticJob, handTuning)
        val base = splitPlan(plan, BlocksConfig(maxMachines = 3, sizeBy = "machines"), 30.0)
        val p = base.plan.copy(outputs = mapOf("plastic-bar" to 6.0, "iron-plate" to 5.0, "stone" to 0.01, "wood" to 0.01, "coal" to 0.02))
        val links = listOf(
            Link(0, "plastic-bar", 6.0, 0, EDGE, false, fromSide = "E"),            // the target: straight out east
            Link(1, "stone", 0.01, 0, EDGE, false, fromSide = "E"),                 // small, on that edge side: moves west
            Link(2, "wood", 0.01, 0, EDGE, false, fromSide = "N"),                  // small, on a free side: stays
            Link(3, "coal", 0.02, 0, EDGE, false, fromSide = "S", pair = 4),        // shares a belt with a big one: leaves
            Link(4, "iron-plate", 5.0, 0, EDGE, false, fromSide = "S", pair = 3),
            Link(5, "petroleum-gas", 0.01, 0, EDGE, true, fromSide = "E"),          // a fluid: leaves
        )
        val split = withLocalSinks(Split(base.blocks, links, base.warnings, base.geometry, p, base.partition, base.ports), 0.1, setOf("plastic-bar"))
        val byItem = split.links.associateBy { it.item }
        assertEquals(setOf("stone", "wood"), split.links.filter { it.local }.map { it.item }.toSet())
        assertTrue(split.links.filter { it.local }.all { it.routed && it.throughCorridor })
        assertEquals("W", byItem.getValue("stone").fromSide)
        assertEquals("N", byItem.getValue("wood").fromSide)
        assertEquals(setOf(Dir.E, Dir.S), split.touch[0])
        // A fluid something destroys (Py's sinkhole): local too, and off the edge side it shared with the target.
        val voided = withLocalSinks(Split(base.blocks, links, base.warnings, base.geometry, p, base.partition, base.ports), 0.1, setOf("plastic-bar")) { it == "petroleum-gas" }
        val gas = voided.links.single { it.item == "petroleum-gas" }
        assertTrue(gas.local && gas.routed, "$gas")
        assertEquals("W", gas.fromSide)
        // Off: nothing changes.
        assertEquals(links, withLocalSinks(Split(base.blocks, links, base.warnings, base.geometry, p, base.partition, base.ports), null, emptySet()).links)
    }

    @Test
    fun `machines joined by pipes may go to different blocks, joined by one pipe tree per fluid`() {
        // Plastic from crude oil: refineries pipe petroleum gas to chemical plants. Three machines per block forces cuts.
        val plan = buildPlan(TestData.data, plasticJob, handTuning)
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
        val plan = buildPlan(TestData.data, job, handTuning)
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

    @Test
    fun `sliver links handed to another supplier keep every block's links equal to what its machines make`() {
        // Six science at 2/s from ore, 64 blocks: many slivers. Before, a moved sliver left the receiving block's machines
        // at their old rates, so the block had to send more than it made and never got a valid layout.
        val job = config.RunConfiguration.load("docs/examples/two_science_per_second.json")
        val tun = config.Tuning.defaults().withOverrides(job.tuning)
        val plan = buildPlan(TestData.data, job, tun)
        val split = splitPlan(plan, job.blocks!!, 30.0, maxUtilization = tun.planner.maxUtilization)
        assertEquals(emptyList(), linkMismatches(split.plan, split.blocks, split.links))
        assertTrue(split.warnings.none { it.startsWith("SPLIT MISMATCH") }, split.warnings.joinToString("\n"))
        println("slivers: " + split.warnings.filter { "sliver" in it })
    }

    @Test
    fun `small links between blocks go by robots, off the sides their blocks keep to the factory's edge`() {
        val plan = buildPlan(TestData.data, plasticJob, handTuning)
        val base = splitPlan(plan, BlocksConfig(maxMachines = 3, sizeBy = "machines"), 30.0)
        val links = listOf(
            Link(0, "plastic-bar", 6.0, 0, EDGE, false, fromSide = "E"),          // to the edge: block 0 keeps east
            Link(1, "coal", 0.02, 0, 1, false, fromSide = "E", toSide = "W"),       // small, between blocks: robots, off east
            Link(2, "iron-plate", 5.0, 0, 1, false, fromSide = "S", toSide = "N"),  // big: a belt
            Link(3, "petroleum-gas", 0.01, 0, 1, true, fromSide = "S", toSide = "N"), // a fluid: a pipe
        )
        val split = withBotLinks(Split(base.blocks, links, base.warnings, base.geometry, base.plan, base.partition, base.ports), 0.1)
        assertEquals(listOf("coal"), split.links.filter { it.bot }.map { it.item })
        assertTrue(split.links.single { it.item == "coal" }.fromSide != "E")
        assertEquals(links, withBotLinks(Split(base.blocks, links, base.warnings, base.geometry, base.plan, base.partition, base.ports), null).links)
        // Robot links share no output port.
        assertTrue(sharedOutputs(base.plan, split.links, 0, handTuning).none { g -> g.any { it.bot } })
    }

    @Test
    fun `roboports cover the robot chests as one network`() {
        // Two chests 300 tiles apart in an empty 400 x 60 box: each covered, and roboports in between join them.
        val chests = listOf(geom.Tile(10, 30), geom.Tile(310, 30))
        val r = power.placeRoboports(emptyList(), chests, 400, 60)
        assertEquals(emptyList(), r.notes)
        val radius = data.GameConstants.ROBOPORT_LOGISTIC_RADIUS
        fun covers(p: geom.PlacedEntity, t: geom.Tile) = kotlin.math.abs(t.x + 0.5 - (p.x + 2.0)) <= radius && kotlin.math.abs(t.y + 0.5 - (p.y + 2.0)) <= radius
        assertTrue(chests.all { c -> r.roboports.any { covers(it, c) } })
        // Sorted along x, each within reach of the next.
        val xs = r.roboports.sortedBy { it.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> kotlin.math.abs(a.x - b.x) <= 2 * radius && kotlin.math.abs(a.y - b.y) <= 2 * radius }, "$xs")
    }
}
