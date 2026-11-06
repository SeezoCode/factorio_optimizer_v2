package plan

import config.ConfigSource
import config.RunConfiguration
import config.Tuning
import config.strictJson
import data.GameConstants
import data.RootData
import data.craftingSpeed
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.ceil

/** SINK: inserter into a chest; OUTPUT: leaves the block at a port on the edge (belt or pipe). */
enum class Role { PRODUCER, CONSUMER, SINK, SOURCE, OUTPUT }

@Serializable
data class MachineInstance(
    val id: Int,
    val recipe: String,
    val machine: String,
    val w: Int,
    val h: Int,
    /** Fluid ingredient this machine needs on its fluid input, or null (v1 infinity-pipe mode). */
    val needsFluid: String? = null,
    /** Items/s of the main product this instance makes. */
    val outputRate: Double,
    val product: String,
    /** Items/s this instance consumes, per solid ingredient (fuel included). */
    val demand: Map<String, Double>,
    /** Every product per second, items and fluids. */
    val products: Map<String, Double> = mapOf(product to outputRate),
    /** Fluid ingredients per second. */
    val fluidDemand: Map<String, Double> = emptyMap(),
    /** Fluid -> 1-based input or output connection it uses (recipe fluidbox_index, else order). */
    val fluidIn: Map<String, Int> = emptyMap(),
    val fluidOut: Map<String, Int> = emptyMap(),
    val crafts: Double = 0.0,
    /**
     * Crafts/s this instance could run at: its crafting speed, and what its inserters (as many as [crafts] needs)
     * can move. Machines of a recipe in different blocks of a factory may run at different rates up to this.
     */
    val maxCrafts: Double = 0.0,
    /** Beacons this machine was planned with (0 without beacons). */
    val beacons: Int = 0,
    /** Crafts/s its crafting speed allows with 0, 1, ... [beacons] beacons in range (empty without beacons). */
    val beaconCrafts: List<Double> = emptyList(),
) {
    val label: String get() = "$recipe#$id"
    /** Items (not fluids) this machine makes. */
    val itemProducts: Map<String, Double> get() = products.filterKeys { it !in fluidOut }
}

/** Where an input enters (or an output leaves): the union of the configured fixed tiles and edge segments. */
@Serializable
data class Port(
    val id: Int,
    val item: String,
    val specs: List<ConfigSource>,
    val fluid: Boolean = false,
    /** Items leave the block here instead of entering. */
    val output: Boolean = false,
    /** Exactly one belt (a link between blocks of a split factory, so its two ends pair up). */
    val single: Boolean = false,
    /** Another input port whose item should arrive on the same belt, one item per lane (-1: none). */
    val pairWith: Int = -1,
)

@Serializable
data class Terminal(
    val id: Int,
    val role: Role,
    val item: String,
    /** Items/s through this terminal (fluids: units/s). */
    val rate: Double,
    /** Inserters this terminal needs (1 for ports and fluids). */
    val k: Int,
    val machine: Int? = null,
    val port: Int? = null,
    val fluid: Boolean = false,
)

/** All producers and consumers of one item (or fluid) in the block. Routing decides how many belts carry it. */
@Serializable
data class Net(
    val id: Int,
    val item: String,
    val flow: Double,
    val terminals: List<Int>,
    /** Belts this item needs at the configured tier. */
    val beltCount: Int = 1,
    val lanePair: Int? = null,
    val fluid: Boolean = false,
)

@Serializable
data class ProductionPlan(
    val machines: List<MachineInstance>,
    val ports: List<Port>,
    val terminals: List<Terminal>,
    val nets: List<Net>,
    /** Supplied through ports per second (items and fluids). */
    val rawRates: Map<String, Double>,
    /** The fluid part of [rawRates]. */
    val fluidRates: Map<String, Double>,
    val recipeRates: Map<String, Double>,
    val beltTier: String,
    val warnings: List<String>,
    /** Leaving the block per second: targets plus surplus by-products. */
    val outputs: Map<String, Double> = emptyMap(),
    /** Belt stack size (Space Age belt stacking; 1 without). */
    val beltStack: Int = 1,
    /**
     * Items that leave on a mixed belt (sushi), each to the name of its belt ("mixed-1"...): that name is the item of the
     * belt's port, net and terminals; machines making them put them all on it with one inserter.
     */
    val mixed: Map<String, String> = emptyMap(),
) {
    /** Nets carried on belts (fluid nets are piped). */
    val beltNets: List<Net> get() = nets.filter { !it.fluid }
    fun terminal(id: Int): Terminal = terminals[id]
    fun netTerminals(net: Net): List<Terminal> = net.terminals.map { terminals[it] }
    fun machineTerminals(machine: Int): List<Terminal> = terminals.filter { it.machine == machine }
    /** Machine footprints, plus a share of the beacons their rates need (each beacon serving about four machines). */
    fun totalMachineArea(): Int = machines.sumOf { it.w * it.h + beaconNeed(it.crafts, it.beaconCrafts) * 2 }

    fun toJson(): String = strictJson.encodeToString(serializer(), this)
    fun write(path: String) = File(path).also { it.parentFile?.mkdirs() }.writeText(toJson())
}

/** Effective items/s of one inserter of [name], given hand size research and whether it picks from a belt. */
fun inserterRate(name: String, config: RunConfiguration, fromBelt: Boolean): Double {
    val spec = GameConstants.inserter(name)
    val (normal, bulk) = GameConstants.handSizes(config.research.inserterCapacity)
    val hand = if (spec.bulk) bulk else normal
    return spec.baseRate * hand * (if (fromBelt) config.inserters.beltPickupDerate else 1.0)
}

/** Best rate among the allowed inserters that sit at d = 1 (long-handed only serves trick options). */
fun bestInserterRate(config: RunConfiguration, fromBelt: Boolean): Double =
    config.inserters.allowed.maxOf { inserterRate(it, config, fromBelt) }

/**
 * Best rate of an inserter dropping onto a belt: it drops onto one lane, which holds half the belt (a bulk
 * inserter at hand size 12 moves more than a fast belt's lane). The router's lane check keeps the same margin.
 */
fun beltDropRate(config: RunConfiguration): Double =
    minOf(bestInserterRate(config, fromBelt = false), GameConstants.beltTier(config.beltTier, config.beltStackSize).throughput / 2 * 0.98)

fun inserterCount(rate: Double, perInserter: Double): Int = maxOf(1, ceil(rate / perInserter - 1e-9).toInt())

/**
 * Stage 1: targets -> recipe rates (linear program) -> machine instances, ports, terminals and nets.
 * Runs once per job.
 */
fun buildPlan(data: RootData, config: RunConfiguration, tuning: Tuning = Tuning()): ProductionPlan {
    val warnings = mutableListOf<String>()
    val warn: (String) -> Unit = { warnings += it; println("WARNING: $it") }
    val lp = solveRates(data, config, tuning.planner, warn)
    // Mixed outputs: surplus items no recipe of the plan uses leave together on one belt per group.
    val used = lp.crafts.keys.flatMap { c -> c.recipe.ingredients.map { it.name } }.toSet()
    val mixed = LinkedHashMap<String, String>()
    config.mixedOutputs.forEachIndexed { k, g ->
        g.items.forEach { item ->
            when {
                (lp.output[item] ?: 0.0) <= 1e-9 -> {}
                item in lp.targets -> warn("mixed output: $item is a target; it leaves on a belt of its own")
                item in used -> warn("mixed output: $item is also used inside the block; it leaves on a belt of its own")
                item in lp.fluids -> warn("mixed output: $item is a fluid; it leaves through a pipe")
                else -> mixed[item] = "mixed-${k + 1}"
            }
        }
    }
    val beltTier = GameConstants.beltTier(config.beltTier, config.beltStackSize)
    fun isFluid(name: String) = name in lp.fluids

    // 1. Machine instances: ceil(machine-seconds), each with an equal share of its recipe's crafts; more when one
    //    machine would need more inserters than fit around it (a foundry or electromagnetic plant is fast).
    val machines = mutableListOf<MachineInstance>()
    val pickRate0 = bestInserterRate(config, fromBelt = true)
    val dropRate0 = beltDropRate(config)
    val crafts = lp.crafts.entries.sortedBy { it.key.recipe.name }
    // Direct-insertion clusters (planner.di_consumers_per_producer): producers of an item used only inside the block,
    // at least one per that many consumer machines. Counts are the rate's, as below, before any extra for inserters.
    val extraFor = HashMap<String, Int>()
    tuning.planner.diConsumersPerProducer.takeIf { it > 0 }?.let { per ->
        // Counts with the extra machines so far: more consumers above want more producers below (until stable).
        val count = crafts.associate { (c, x) -> c.recipe.name to maxOf(1, ceil(x * c.machineTime - 1e-6).toInt()) }.toMutableMap()
        repeat(crafts.size) { crafts.forEach { (c, _) ->
            val main = c.recipe.mainProduct?.name ?: return@forEach
            if (isFluid(main) || main in lp.targets || (lp.output[main] ?: 0.0) > 1e-9 || c.recipe.products.size > 1) return@forEach
            val consumers = crafts.filter { (d, _) -> d.recipe.ingredients.any { it.name == main && !it.isFluid } }.sumOf { (d, _) -> count.getValue(d.recipe.name) }
            var want = ceil(consumers.toDouble() / per - 1e-9).toInt()
            // A producer that cannot feed two consumers (an engine for chemical science) gets one consumer to itself:
            // shared, it would also put its spare on a belt for another, and that belt's inserter takes from the
            // machine whenever the belt has room, starving the consumer beside it.
            val users = crafts.filter { (d, _) -> d.recipe.ingredients.any { it.name == main && !it.isFluid } }
            if (users.size == 1) {
                val (d, xd) = users[0]
                val nd = count.getValue(d.recipe.name)
                val perConsumer = xd / nd * d.recipe.ingredients.filter { it.name == main }.sumOf { it.amount }
                val full = (productsPerCraft(data, c, config)[main] ?: 0.0) / c.machineTime
                if (perConsumer > full / 2 + 1e-9 && perConsumer <= full + 1e-9) want = maxOf(want, nd)
            }
            if (want > count.getValue(c.recipe.name)) { extraFor[c.recipe.name] = want; count[c.recipe.name] = want }
        } }
    }
    crafts.forEach { (c, x) ->
        val r = c.recipe
        val e = data.entities.getValue(c.machine)
        val fluidIngredients = r.ingredients.filter { it.isFluid }
        val fluidProducts = r.products.filter { it.type == "fluid" }
        val fuelItem = fuelItem(data, c.machine, config)
        val fuel = fuelPerCraft(data, c, fuelItem)
        val main = r.mainProduct?.name ?: r.products.first().name
        val productsPer = productsPerCraft(data, c, config)
        val demandPer = r.ingredients.filter { !it.isFluid }.groupBy { it.name }.mapValues { (_, ings) -> ings.sumOf { it.amount } }
            .let { if (fuel > 0) it + (fuelItem!! to (it[fuelItem] ?: 0.0) + fuel) else it }
        val itemOut0 = productsPer.filterKeys { p -> fluidProducts.none { it.name == p } }.filterValues { it > 1e-12 }
        // Products that go on one mixed belt leave through one inserter (count them as one).
        val itemOut = itemOut0.entries.groupBy { mixed[it.key] ?: it.key }.mapValues { (_, es) -> es.sumOf { it.value } }
        fun inserters(n: Int) = demandPer.values.sumOf { inserterCount(x * it / n, pickRate0) } + itemOut.values.sumOf { inserterCount(x * it / n, dropRate0) }
        // A beaconed machine in the lattice has one side against its beacon row: no belt-fed inserter goes there.
        val beaconSide = if (tuning.planner.beaconSide && config.beacons?.mode == "lattice" && plannedBeacons(data, config, r, c.machine) > 0)
            maxOf(e.width, e.height) else 0
        val room = maxOf(demandPer.size + itemOut.size,
            (tuning.planner.accessShare * (2 * (e.width + e.height) - beaconSide - fluidIngredients.size - fluidProducts.size)).toInt())
        val base = maxOf(1, ceil(x * c.machineTime - 1e-6).toInt())
        val di = maxOf(base, extraFor[r.name] ?: 0)
        if (di > base) warn("${r.name}: ${di - base} more ${c.machine} than its rate needs, one per ${tuning.planner.diConsumersPerProducer} consumers for direct insertion")
        var n = di
        while (inserters(n) > room && n < base * 20) n++
        if (n > di) warn("${r.name}: ${n - di} more ${c.machine} than its rate needs, so each one's inserters fit around it")
        val crafts = x / n
        val insCap = demandPer.values.filter { it > 1e-12 }.map { a -> inserterCount(crafts * a, pickRate0) * pickRate0 / a } +
            itemOut.values.map { a -> inserterCount(crafts * a, dropRate0) * dropRate0 / a }
        val maxCrafts = maxOf(crafts, minOf(1.0 / c.machineTime, insCap.minOrNull() ?: Double.MAX_VALUE))
        val products = productsPer.mapValues { crafts * it.value }
        val demand = demandPer.mapValues { crafts * it.value }
        val beacons = plannedBeacons(data, config, r, c.machine)
        val beaconCrafts = if (beacons == 0) emptyList() else (0..beacons).map { k -> moduleSpeed(data, config, r, c.machine, k) / r.energy }
        repeat(n) {
            machines += MachineInstance(
                id = machines.size,
                recipe = r.name,
                machine = c.machine,
                w = e.width,
                h = e.height,
                needsFluid = fluidIngredients.firstOrNull()?.name,
                outputRate = products.getValue(main),
                product = main,
                demand = demand,
                products = products,
                fluidDemand = fluidIngredients.groupBy { it.name }.mapValues { (_, ings) -> crafts * ings.sumOf { it.amount } },
                fluidIn = fluidIngredients.mapIndexed { i, ing -> ing.name to (ing.fluidboxIndex ?: (i + 1)) }.toMap(),
                fluidOut = fluidProducts.mapIndexed { i, p -> p.name to (p.fluidboxIndex ?: (i + 1)) }.toMap(),
                crafts = crafts,
                maxCrafts = maxCrafts,
                beacons = beacons,
                beaconCrafts = beaconCrafts,
            )
        }
    }

    // 2. Ports: one per supplied item or fluid, the union of its configured sources (whole perimeter if none);
    //    and, in port mode, one per item or fluid leaving the block (fluids always leave through a port).
    val ports = ArrayList<Port>()
    lp.supply.keys.sorted().forEach { item ->
        val specs = config.sources.filter { it.name == item }
        if (specs.isEmpty()) warn("no source configured for input '$item'; it may enter anywhere on the box edge")
        ports += Port(ports.size, item, specs.ifEmpty { listOf(ConfigSource(item, edge = "any")) }, fluid = isFluid(item))
    }
    val portMode = config.sinks.mode == "port"
    lp.output.keys.sorted().filter { (portMode || isFluid(it)) && it !in mixed }.forEach { item ->
        val specs = config.outputs.filter { it.name == item }
        ports += Port(ports.size, item, specs.ifEmpty { listOf(ConfigSource(item, edge = "any")) }, fluid = isFluid(item), output = true)
    }
    mixed.values.distinct().forEach { name ->
        val k = name.removePrefix("mixed-").toInt() - 1
        ports += Port(ports.size, name, listOf(ConfigSource(name, edge = config.mixedOutputs[k].edge)), output = true)
    }
    config.sources.map { it.name }.distinct().filter { it !in lp.supply }.forEach {
        warn("source '$it' is configured but not an input of this job")
    }

    // 3. Terminals and nets per item and fluid.
    val terminals = mutableListOf<Terminal>()
    val nets = mutableListOf<Net>()
    val dropRate = beltDropRate(config)
    val pickRate = bestInserterRate(config, fromBelt = true)

    fun addTerminal(role: Role, item: String, rate: Double, k: Int, machine: Int? = null, port: Int? = null) =
        Terminal(terminals.size, role, item, rate, k, machine, port, isFluid(item)).also { terminals += it }

    val items = (machines.flatMap { it.products.keys } + machines.flatMap { it.demand.keys + it.fluidDemand.keys } + lp.supply.keys).distinct()
        .filter { it !in mixed }
    items.forEach { item ->
        val fluid = isFluid(item)
        val producers = machines.filter { (it.products[item] ?: 0.0) > 1e-12 }
        val consumers = machines.filter { (it.demand[item] ?: it.fluidDemand[item] ?: 0.0) > 1e-12 }
        val outTotal = lp.output[item] ?: 0.0
        val netTerminals = mutableListOf<Terminal>()
        val produced = producers.sumOf { it.products.getValue(item) }

        val outPort = ports.firstOrNull { it.item == item && it.output }
        producers.forEach { m ->
            val made = m.products.getValue(item)
            // Chest mode: what leaves the block is split over producers in proportion to what they make.
            val chestShare = if (outPort == null && produced > 0) outTotal * made / produced else 0.0
            if (chestShare > 1e-9) addTerminal(Role.SINK, item, chestShare, inserterCount(chestShare, dropRate), machine = m.id)
            val beltShare = made - chestShare
            if ((consumers.isNotEmpty() || outPort != null) && beltShare > 1e-9) {
                netTerminals += addTerminal(Role.PRODUCER, item, beltShare, if (fluid) 1 else inserterCount(beltShare, dropRate), machine = m.id)
            }
        }
        ports.firstOrNull { it.item == item && !it.output }?.let { port ->
            netTerminals += addTerminal(Role.SOURCE, item, lp.supply.getValue(item), 1, port = port.id)
        }
        if (outPort != null && outTotal > 1e-9) netTerminals += addTerminal(Role.OUTPUT, item, outTotal, 1, port = outPort.id)
        consumers.forEach { m ->
            val need = m.demand[item] ?: m.fluidDemand.getValue(item)
            netTerminals += addTerminal(Role.CONSUMER, item, need, if (fluid) 1 else inserterCount(need, pickRate), machine = m.id)
        }

        val takers = netTerminals.any { it.role == Role.CONSUMER || it.role == Role.OUTPUT }
        if (netTerminals.size >= 2 && takers) {
            val flow = netTerminals.filter { it.role == Role.CONSUMER || it.role == Role.OUTPUT }.sumOf { it.rate }
            val belts = if (fluid) 1 else maxOf(1, ceil(flow / (tuning.net.beltUtilisation * beltTier.throughput) - 1e-9).toInt())
            nets += Net(nets.size, item, flow, netTerminals.map { it.id }, belts, fluid = fluid)
        }
    }

    // Each mixed belt: every machine making some of its items puts them all on it; it ends at its port.
    mixed.values.distinct().forEach { name ->
        val netTerminals = mutableListOf<Terminal>()
        machines.forEach { m ->
            val made = m.products.filterKeys { mixed[it] == name }.values.sum()
            if (made > 1e-9) netTerminals += addTerminal(Role.PRODUCER, name, made, inserterCount(made, dropRate), machine = m.id)
        }
        val total = mixed.filterValues { it == name }.keys.sumOf { lp.output[it] ?: 0.0 }
        netTerminals += addTerminal(Role.OUTPUT, name, total, 1, port = ports.first { it.item == name }.id)
        val belts = maxOf(1, ceil(total / (tuning.net.beltUtilisation * beltTier.throughput) - 1e-9).toInt())
        nets += Net(nets.size, name, total, netTerminals.map { it.id }, belts)
    }

    return ProductionPlan(
        machines = machines,
        ports = ports,
        terminals = terminals,
        nets = nets,
        rawRates = lp.supply,
        fluidRates = lp.supply.filterKeys { isFluid(it) },
        recipeRates = lp.crafts.entries.associate { (c, x) -> c.recipe.name to x * (c.recipe.mainProduct?.expectedAmount ?: 1.0) },
        beltTier = config.beltTier,
        beltStack = config.beltStackSize,
        warnings = warnings,
        outputs = lp.output,
        mixed = mixed,
    )
}
