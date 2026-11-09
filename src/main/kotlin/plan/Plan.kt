package plan

import config.ConfigSource
import config.MixedOutput
import config.RunConfiguration
import config.Tuning
import config.strictJson
import data.GameConstants
import data.RootData
import data.craftingSpeed
import data.fluidBoxes
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
    /**
     * An input of 0/s for a catalyst (a machine that takes and gives back the same item or fluid, Py's formamide and
     * its methanol): the loop between its output and input runs by itself once filled, but starts empty in a new
     * build, so this is where to put some in once.
     */
    val priming: Boolean = false,
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
    /** The most of its full speed each recipe's machines are planned to run (planner.max_utilization, lower up the chain). */
    val utilization: Map<String, Double> = emptyMap(),
    /**
     * The most of its full speed a machine making an intermediate may run to fill a belt, as the validator sees it
     * (planner.max_utilization with planner.headroom_check, else 1): a belt that only producers flat out can fill
     * comes up short in game as soon as anything upstream of it does. Machines making targets may run flat out.
     */
    val headroom: Double = 1.0,
    /** Items launched in rocket silos per second (rocket parts; a rocket is rocket_parts_required of them). */
    val launched: Map<String, Double> = emptyMap(),
    /** Items (and fluids) to put in by hand once so that the plan's production loops can start (see [loopSeeds]). */
    val seeds: List<String> = emptyList(),
    /**
     * Of [seeds], those their loop only passes round one for one (heat bricks, barrels, cages): put in once, never
     * more (more would fill the loop until nothing moves). The others are used up, and a loop is fed them until it runs.
     */
    val seedCarriers: List<String> = emptyList(),
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

/**
 * Effective items/s of one inserter of [name], given hand size research, whether it picks from a belt and whether it
 * drops onto one. A bulk inserter picking from a belt also waits for its hand to fill from the items passing: one
 * swing plus [hand] items at [BULK_PICKUP_SHARE] of the belt's throughput per cycle. One dropping onto a belt lays its
 * hand down an item at a time, at [BULK_DROP_SHARE] of the belt's throughput (the Space Age stack inserter drops
 * stacks instead).
 */
fun inserterRate(name: String, config: RunConfiguration, fromBelt: Boolean, toBelt: Boolean = false): Double {
    val spec = GameConstants.inserter(name)
    val (normal, bulk) = GameConstants.handSizes(config.research.inserterCapacity)
    val hand = if (spec.bulk) bulk else normal
    var rate = spec.baseRate * hand * (if (fromBelt) config.inserters.beltPickupDerate else 1.0)
    if (!spec.bulk || hand <= 1) return rate
    val belt = runCatching { GameConstants.beltTier(config.beltTier, config.beltStackSize).throughput }.getOrNull() ?: return rate
    if (fromBelt) rate = minOf(rate, hand / (1.0 / spec.baseRate + hand / (BULK_PICKUP_SHARE * belt)))
    if (toBelt && name != GameConstants.STACK_INSERTER) rate = minOf(rate, hand / (1.0 / spec.baseRate + hand / (BULK_DROP_SHARE * belt)))
    return rate
}

/**
 * Share of a belt's throughput a bulk inserter's hand fills at when it picks from the belt. Measured in game: one bulk
 * inserter at hand size 12 took 13.5 iron/s from a full express belt into a beaconed gear assembler that wanted more
 * (19.4/s with the plain belt derate): 12 / (1 / 2.31 + 12 / (0.58 x 45)).
 */
const val BULK_PICKUP_SHARE = 0.58

/**
 * Share of a belt's throughput a bulk inserter lays its hand onto the belt at. Measured in game (2.1, hand size 12,
 * from an infinity chest): 13.75 items/s onto an express belt and 11.0 onto a fast one, either 12 / (1 / 2.31 + 12 /
 * (0.607 x belt)); two of them on one lane 20.65, on both lanes 27.5. A fast inserter (hand 3) kept its full 6.9.
 * A Py ash furnace planned at 18 ash/s through one bulk inserter stood half the time with full output at 13.75.
 */
const val BULK_DROP_SHARE = 0.607

/** Best rate among the allowed inserters that sit at d = 1 (long-handed only serves trick options). */
fun bestInserterRate(config: RunConfiguration, fromBelt: Boolean, toBelt: Boolean = false): Double =
    config.inserters.allowed.maxOf { inserterRate(it, config, fromBelt, toBelt) }

/**
 * Best rate of an inserter dropping onto a belt: it drops onto one lane, which holds half the belt (a bulk
 * inserter at hand size 12 moves more than a fast belt's lane). The router's lane check keeps the same margin.
 */
fun beltDropRate(config: RunConfiguration): Double =
    minOf(bestInserterRate(config, fromBelt = false, toBelt = true), GameConstants.beltTier(config.beltTier, config.beltStackSize).throughput / 2 * 0.98)

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
    // (Fuel too: a burner's fuel is no ingredient. Py science 2's fuel rods, burnt by a bhoddos culture, went onto the
    // mixed belt as a leftover and the culture got none.)
    val used = (lp.crafts.keys.flatMap { c -> c.recipe.ingredients.map { it.name } } +
        lp.crafts.keys.mapNotNull { c -> fuelItem(data, c.machine, config) }).toSet()
    val mixed = LinkedHashMap<String, String>()
    // byproducts_mixed: one more group with every item that only leaves.
    val listed = config.mixedOutputs.flatMap { it.items }.toSet()
    val mixedGroups = config.mixedOutputs + (if (!config.byproductsMixed) emptyList() else listOf(MixedOutput(
        lp.output.filter { (item, v) -> v > 1e-9 && item !in lp.targets && item !in used && item !in lp.fluids && item !in listed }.keys.sorted())))
        .filter { it.items.isNotEmpty() }
    mixedGroups.forEachIndexed { k, g ->
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
    // Utilization per recipe: max_utilization for what makes the targets, times upstream_utilization per level further
    // up the chain (a recipe's level is its longest way down to a target), so a supplier flat out more than covers
    // its consumers flat out.
    val level = HashMap<String, Int>()
    run {
        val makes = crafts.associate { (c, _) -> c.recipe.name to c.recipe.products.map { it.name }.toSet() }
        val uses = crafts.associate { (c, _) -> c.recipe.name to c.recipe.ingredients.map { it.name }.toSet() }
        crafts.forEach { (c, _) -> if (makes.getValue(c.recipe.name).any { it in lp.targets }) level[c.recipe.name] = 0 }
        repeat(12) {
            crafts.forEach { (c, _) ->
                val r = c.recipe.name
                val down = crafts.mapNotNull { (d, _) -> level[d.recipe.name]?.takeIf { makes.getValue(r).any { it in uses.getValue(d.recipe.name) } } }
                down.maxOrNull()?.let { l -> if ((level[r] ?: -1) < minOf(l + 1, 12)) level[r] = minOf(l + 1, 12) }
            }
        }
    }
    fun utilOf(recipe: String): Double = tuning.planner.maxUtilization * Math.pow(tuning.planner.upstreamUtilization, (level[recipe] ?: 0).toDouble())
    val extraFor = HashMap<String, Int>()
    val duos = tuning.place.duos && config.beacons == null && config.heat.mode == "off"
    tuning.planner.diConsumersPerProducer.takeIf { it > 0 || duos }?.let { per ->
        // Counts with the extra machines so far: more consumers above want more producers below (until stable).
        val count = crafts.associate { (c, x) -> c.recipe.name to maxOf(1, ceil(x * c.machineTime / utilOf(c.recipe.name) - 1e-6).toInt()) }.toMutableMap()
        repeat(crafts.size) { crafts.forEach { (c, _) ->
            val main = c.recipe.mainProduct?.name ?: return@forEach
            if (isFluid(main) || main in lp.targets || (lp.output[main] ?: 0.0) > 1e-9 || c.recipe.products.size > 1) return@forEach
            val consumers = crafts.filter { (d, _) -> d.recipe.ingredients.any { it.name == main && !it.isFluid } }.sumOf { (d, _) -> count.getValue(d.recipe.name) }
            var want = if (per > 0) ceil(consumers.toDouble() / per - 1e-9).toInt() else 0
            // A producer that cannot feed two consumers (an engine for chemical science) gets one consumer to itself:
            // shared, it would also put its spare on a belt for another, and that belt's inserter takes from the
            // machine whenever the belt has room, starving the consumer beside it.
            val users = crafts.filter { (d, _) -> d.recipe.ingredients.any { it.name == main && !it.isFluid } }
            if (users.size == 1) {
                val (d, xd) = users[0]
                val nd = count.getValue(d.recipe.name)
                val perConsumer = xd / nd * d.recipe.ingredients.filter { it.name == main }.sumOf { it.amount }
                val full = (productsPerCraft(data, c, config)[main] ?: 0.0) / c.machineTime
                if (per > 0 && perConsumer > full / 2 + 1e-9 && perConsumer <= full + 1e-9) want = maxOf(want, nd)
                // A consumer that needs more than one producer's full output (a circuit assembler needs 1.5 cable
                // assemblers) gets two of its own, one on each side: cable, circuit, cable.
                else if (perConsumer > full + 1e-9 && perConsumer <= 2 * full + 1e-9) want = maxOf(want, 2 * nd)
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
        // A burner's fuel leaves its burnt result (Py: ash), taken out like any product.
        val productsPer = productsPerCraft(data, c, config).let { ps ->
            burntPerCraft(data, fuelItem, fuel)?.let { (b, n) -> ps + (b to (ps[b] ?: 0.0) + n) } ?: ps
        }
        // A fuel fluid (a machine that burns one) comes by pipe into its own box; fuel items by inserter.
        val fuelFluid = fuelItem?.takeIf { fuel > 0 && isFluid(it) }
        val demandPer = r.ingredients.filter { !it.isFluid }.groupBy { it.name }.mapValues { (_, ings) -> ings.sumOf { it.amount } }
            .let { if (fuel > 0 && fuelFluid == null) it + (fuelItem!! to (it[fuelItem] ?: 0.0) + fuel) else it }
        // A rocket silo launches what it builds: no product leaves it.
        // A silo's parts are launched; a payload's launch products come back out (see withLaunchProducts).
        val launched = GameConstants.launches(c.machine) && !isLaunch(c.recipe.name)
        val itemOut0 = if (launched) emptyMap() else productsPer.filterKeys { p -> fluidProducts.none { it.name == p } }.filterValues { it > 1e-12 }
        // Products that go on one mixed belt leave through one inserter (count them as one).
        val itemOut = itemOut0.entries.groupBy { mixed[it.key] ?: it.key }.mapValues { (_, es) -> es.sumOf { it.value } }
        fun inserters(n: Int) = demandPer.values.sumOf { inserterCount(x * it / n, pickRate0) } + itemOut.values.sumOf { inserterCount(x * it / n, dropRate0) }
        // A beaconed machine in the lattice has one side against its beacon row: no belt-fed inserter goes there.
        val beaconSide = if (tuning.planner.beaconSide && config.beacons?.mode == "lattice" && plannedBeacons(data, config, r, c.machine) > 0)
            maxOf(e.width, e.height) else 0
        val room = maxOf(demandPer.size + itemOut.size,
            (tuning.planner.accessShare * (2 * (e.width + e.height) - beaconSide - fluidIngredients.size - fluidProducts.size)).toInt())
        val base = maxOf(1, ceil(x * c.machineTime / utilOf(c.recipe.name) - 1e-6).toInt())
        val di = maxOf(base, extraFor[r.name] ?: 0)
        if (di > base) warn("${r.name}: ${di - base} more ${c.machine} than its rate needs, for direct insertion")
        var n = di
        while (inserters(n) > room && n < base * 20) n++
        if (n > di) warn("${r.name}: ${n - di} more ${c.machine} than its rate needs, so each one's inserters fit around it")
        val crafts = x / n
        val insCap = demandPer.values.filter { it > 1e-12 }.map { a -> inserterCount(crafts * a, pickRate0) * pickRate0 / a } +
            itemOut.values.map { a -> inserterCount(crafts * a, dropRate0) * dropRate0 / a }
        val maxCrafts = maxOf(crafts, minOf(1.0 / c.machineTime, insCap.minOrNull() ?: Double.MAX_VALUE))
        val products = if (launched) emptyMap() else productsPer.mapValues { crafts * it.value }
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
                outputRate = products[main] ?: 0.0,
                product = main,
                demand = demand,
                products = products,
                fluidDemand = fluidIngredients.groupBy { it.name }.mapValues { (_, ings) -> crafts * ings.sumOf { it.amount } }
                    .let { fd -> if (fuelFluid == null) fd else fd + (fuelFluid to (fd[fuelFluid] ?: 0.0) + crafts * fuel) },
                fluidIn = data.fluidBoxes(r, c.machine).first.let { fi ->
                    val box = GameConstants.fuelInputIndex(c.machine)
                    if (fuelFluid == null || box == null) fi else fi + (fuelFluid to box)
                },
                fluidOut = data.fluidBoxes(r, c.machine).second,
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
    // Launched in rocket silos (rocket parts): counted, but nothing to carry.
    val launched = crafts.filter { (c, _) -> GameConstants.launches(c.machine) && !isLaunch(c.recipe.name) }
        .flatMap { (c, _) -> c.recipe.products.map { it.name } }.associateWith { lp.output[it] ?: 0.0 }.filterValues { it > 1e-12 }
    val outputs = lp.output.filterKeys { it !in launched }
    outputs.keys.sorted().filter { (portMode || isFluid(it)) && it !in mixed }.forEach { item ->
        val specs = config.outputs.filter { it.name == item }
        ports += Port(ports.size, item, specs.ifEmpty { listOf(ConfigSource(item, edge = "any")) }, fluid = isFluid(item), output = true)
    }
    mixed.values.distinct().forEach { name ->
        val k = name.removePrefix("mixed-").toInt() - 1
        ports += Port(ports.size, name, listOf(ConfigSource(name, edge = mixedGroups[k].edge)), output = true)
    }
    // Overflows: a 0/s output for by-products of production loops (see [overflowItems]).
    val overflow = if (config.byproductOverflow == "off") emptySet()
        else overflowItems(machines, lp.supply.keys, outputs.keys + mixed.keys, coupled = config.byproductOverflow == "all").filter {
            if (isFluid(it)) data.items.containsKey(GameConstants.OVERFLOW_VALVE) else portMode && config.byproductOverflow == "all"
        }.toSet()
    overflow.sorted().forEach { item ->
        val specs = config.outputs.filter { it.name == item }
        ports += Port(ports.size, item, specs.ifEmpty { listOf(ConfigSource(item, edge = "any")) }, fluid = isFluid(item), output = true)
    }
    if (overflow.isNotEmpty()) warn("overflow outputs (0/s; what their users do not take leaves${if (overflow.any { isFluid(it) }) ", fluids through an overflow valve" else ""}): ${overflow.sorted().joinToString(", ")}")
    // Catalysts nobody else makes or brings: a 0/s input to fill the loop once (see [Port.priming]).
    run {
        val made = machines.flatMap { it.products.keys }.toSet()
        made.filter { item -> item !in lp.supply && item !in outputs && item !in mixed }.sorted().forEach { item ->
            val producers = machines.filter { (it.products[item] ?: 0.0) > 1e-12 }
            if (producers.all { (it.demand[item] ?: it.fluidDemand[item] ?: 0.0) > 1e-12 }) {
                val specs = config.sources.filter { it.name == item }
                ports += Port(ports.size, item, specs.ifEmpty { listOf(ConfigSource(item, edge = "any")) }, fluid = isFluid(item), priming = true)
                warn("$item is a catalyst nothing else makes: fill its loop once through its 0/s input")
            }
        }
    }
    config.sources.map { it.name }.distinct().filter { it !in lp.supply && ports.none { p -> p.item == it } }.forEach {
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
        val outTotal = outputs[item] ?: 0.0
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
            netTerminals += addTerminal(Role.SOURCE, item, lp.supply[item] ?: 0.0, 1, port = port.id)
        }
        if (outPort != null && (outTotal > 1e-9 || item in overflow)) netTerminals += addTerminal(Role.OUTPUT, item, outTotal, 1, port = outPort.id)
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

    val seeds = loopSeeds(machines, lp.supply.keys) { m -> !GameConstants.takesRecipe(m) }
    if (seeds.isNotEmpty()) warn("production loops start only with some ${seeds.joinToString(", ")} in them: put a few in by hand once")

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
        outputs = outputs,
        launched = launched,
        mixed = mixed,
        utilization = crafts.associate { (c, _) -> c.recipe.name to utilOf(c.recipe.name) },
        headroom = if (tuning.planner.headroomCheck) tuning.planner.maxUtilization else 1.0,
        seeds = seeds,
        seedCarriers = seeds.filter { it in loopCarriers(machines, lp.supply.keys) },
    )
}

/** Items of production loops that their loop only passes round one for one (see [carrier]). */
fun loopCarriers(machines: List<MachineInstance>, supplied: Set<String>): Set<String> {
    val (made, next) = loopGraph(machines, supplied)
    val loopOf = HashMap<String, Set<String>>().also { m -> stronglyConnected(made, next).forEach { s -> s.forEach { m[it] = s } } }
    return loopOf.keys.filter { carrier(it, machines, loopOf) }.toSet()
}

/**
 * Seeds for production loops. Pyanodon's farms feed each other: fertilizer needs bones, bones come from zipir
 * carcasses, carcasses need fawogae substrate, its moss needs limestone from seaweed, and seaweed needs fertilizer. A
 * new build of such a loop has none of it anywhere and never starts (a 58-machine Py science 1 layout stood with every
 * machine idle). Items made inside, by several recipes in turn, form loops (strongly connected parts of the graph
 * ingredient -> product over the [machines], leaving out what also comes in through [supplied] and a recipe's own
 * catalysts, which get priming inputs instead); per loop, items are taken out, the one most loops run through first,
 * until no loop is left: a few of each of those, put in once, start every loop.
 */
fun loopSeeds(machines: List<MachineInstance>, supplied: Set<String>, recipeLess: (String) -> Boolean = { false }): List<String> {
    val (made, next) = loopGraph(machines, supplied)
    fun loops(nodes: Set<String>) = stronglyConnected(nodes, next)
    // An item only machines without a set recipe take (Py's barrel machine is a furnace: it picks its recipe from what
    // it is given) cannot be put in by hand or by the in-game check: another item of the loop is the seed.
    val takers = HashMap<String, MutableList<MachineInstance>>()
    machines.forEach { m -> (m.demand.keys + m.fluidDemand.keys).forEach { takers.getOrPut(it) { ArrayList() } += m } }
    fun placeable(item: String) = takers[item].orEmpty().any { !recipeLess(it.machine) }
    val seeds = sortedSetOf<String>()
    val todo = ArrayDeque(loops(made))
    while (todo.isNotEmpty()) {
        val part = todo.removeFirst()
        val seed = part.maxWith(compareBy<String>({ placeable(it) }, { v ->
            val inDeg = part.count { u -> v in next[u].orEmpty() }
            val outDeg = next[v].orEmpty().count { it in part }
            inDeg * outDeg
        }, { it }))
        seeds += seed
        todo += loops(part - seed)
    }
    return seeds.toList()
}

/** Items made inside (less [supplied]) and the graph ingredient -> product over them (a recipe's own catalysts left out). */
private fun loopGraph(machines: List<MachineInstance>, supplied: Set<String>): Pair<Set<String>, Map<String, Set<String>>> {
    val made = machines.flatMap { m -> m.products.filterValues { it > 1e-12 }.keys }.toSet() - supplied
    val next = HashMap<String, MutableSet<String>>()
    machines.forEach { m ->
        val ins = (m.demand.keys + m.fluidDemand.keys).filter { it in made }
        val outs = m.products.filterValues { it > 1e-12 }.keys.filter { it in made }
        ins.forEach { i -> outs.forEach { o -> if (o != i) next.getOrPut(i) { HashSet() } += o } }
    }
    return made to next
}

/**
 * Overflows. A machine with several products stops when any of them backs up. Where one of them goes into a
 * production loop (or comes out of one) its users may not take it for a long while: the loop has not started yet, or
 * runs slower for a time. Pyanodon's tar distillation gives carbon dioxide besides the aromatics a loop needs; the CO2's
 * users were in loops not yet running, so its pipe filled, tar distillation stopped, and so did every loop. Such an
 * item also gets an output of 0/s that takes only what its users leave (a belt past them that ends at the port; for a
 * fluid, an overflow valve at the port, which lets fluid out only above 80% full). Items a machine also takes back (its
 * catalysts) and items already leaving are left out. With [coupled], also every product of a machine whose other
 * products have users, loops or not: Py science 1's moss farm makes moss and coarse in one craft; its moss backed up
 * behind a science lab waiting for flasks, and the coarse it then stopped making was where the flasks' iron oxide came
 * from.
 */
fun overflowItems(machines: List<MachineInstance>, supplied: Set<String>, leaving: Set<String>, coupled: Boolean = false): Set<String> {
    val (made, next) = loopGraph(machines, supplied)
    val loops = stronglyConnected(made, next)
    val inLoops = loops.flatten().toSet()
    if (inLoops.isEmpty() && !coupled) return emptySet()
    fun takes(m: MachineInstance) = m.demand.keys + m.fluidDemand.keys
    fun inLoop(m: MachineInstance) = m.products.keys.any { it in inLoops } && takes(m).any { it in inLoops }
    val loopOf = HashMap<String, Set<String>>().also { m -> loops.forEach { s -> s.forEach { m[it] = s } } }
    val out = sortedSetOf<String>()
    machines.forEach { m ->
        val products = m.products.filterValues { it > 1e-12 }.keys
        if (products.size < 2) return@forEach
        products.forEach { p ->
            if (p in takes(m) || p in leaving || p in supplied || carrier(p, machines, loopOf)) return@forEach
            val users = machines.filter { (it.demand[p] ?: it.fluidDemand[p] ?: 0.0) > 1e-12 }
            val othersUsed = coupled && products.any { o -> o != p && machines.any { (it.demand[o] ?: it.fluidDemand[o] ?: 0.0) > 1e-12 } }
            if (users.isNotEmpty() && (p in inLoops || inLoop(m) || users.any { inLoop(it) } || othersUsed)) out += p
        }
    }
    return out
}

/**
 * Whether [p] only goes round its loop: every machine that makes it turns another item of the same loop into it one
 * for one (Py's heat bricks: stone -> warm -> warmer -> hot -> stone; barrels filled and emptied; cages). The loop
 * holds as many as were put in, so an overflow would only drain it: the belt runs on past its users to the edge,
 * and whatever passes them while they are full is gone for good (Py science 2's brick ring starved that way).
 */
private fun carrier(p: String, machines: List<MachineInstance>, loopOf: Map<String, Set<String>>): Boolean {
    val loop = loopOf[p] ?: return false
    fun same(a: Double, b: Double) = kotlin.math.abs(a - b) <= 1e-6 * maxOf(a, b) + 1e-12
    val makers = machines.filter { (it.products[p] ?: 0.0) > 1e-12 }
    if (makers.isEmpty() || !makers.all { m ->
            val r = m.products.getValue(p)
            (m.demand + m.fluidDemand).any { (q, rq) -> q != p && q in loop && same(rq, r) }
        }) return false
    // And back round to itself through one-for-one steps at one rate: a machine taking x at that rate and giving y at
    // it (a sap tree mulched gives one sap seed, but a tree takes five: the loop grows, and its trees are no carrier).
    val rate = makers.sumOf { it.products.getValue(p) }
    val seen = hashSetOf(p); val queue = ArrayDeque(listOf(p))
    while (queue.isNotEmpty()) {
        val x = queue.removeFirst()
        machines.forEach { m ->
            val rx = m.demand[x] ?: m.fluidDemand[x] ?: return@forEach
            m.products.forEach { (y, ry) ->
                if (y in loop && same(rx, ry) && (same(ry, rate) || makers.size > 1)) {
                    if (y == p) return true
                    if (seen.add(y)) queue += y
                }
            }
        }
    }
    return false
}

/** Strongly connected parts of the graph [next] over [nodes] with more than one item (Tarjan). */
private fun stronglyConnected(nodes: Set<String>, next: Map<String, Set<String>>): List<Set<String>> {
    var counter = 0
    val index = HashMap<String, Int>(); val low = HashMap<String, Int>()
    val stack = ArrayDeque<String>(); val on = HashSet<String>()
    val out = ArrayList<Set<String>>()
    fun visit(v: String) {
        index[v] = counter; low[v] = counter; counter++
        stack.addLast(v); on += v
        for (w in next[v].orEmpty()) {
            if (w !in nodes) continue
            if (w !in index) { visit(w); low[v] = minOf(low.getValue(v), low.getValue(w)) }
            else if (w in on) low[v] = minOf(low.getValue(v), index.getValue(w))
        }
        if (low[v] == index[v]) {
            val part = HashSet<String>()
            while (true) { val w = stack.removeLast(); on -= w; part += w; if (w == v) break }
            if (part.size > 1) out += part
        }
    }
    nodes.sorted().forEach { if (it !in index) visit(it) }
    return out
}
