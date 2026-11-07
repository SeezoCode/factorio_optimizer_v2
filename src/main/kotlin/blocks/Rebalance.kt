package blocks

import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import plan.ProductionPlan
import plan.Role

/**
 * The plan with each machine's crafting rate chosen so blocks trade as little as possible. The planner gives every
 * machine of a recipe the same share, but machine counts are rounded up, so most machines have room to spare: a
 * block with two of nine cable assemblers can make exactly the cable it uses instead of a sliver more, which would
 * otherwise cost a belt and two ports for 0.06 cable/s. A linear program keeps each recipe's total, keeps every
 * machine between a quarter of its planned rate and what its crafting speed and planned inserters allow
 * ([plan.MachineInstance.maxCrafts], times [maxUtilization]), and minimises the sum over blocks and items of |made - used| (each item
 * relative to its total flow). Solutions of such programs sit on vertices, so most balances come out exactly zero.
 * Returns [plan] itself if nothing changes.
 */
fun rebalance(plan: ProductionPlan, blocks: List<List<Int>>, maxUtilization: Double = 1.0): ProductionPlan =
    if (blocks.size < 2) plan else rebalanceWith(plan, blocks, maxUtilization, emptyList()) ?: plan

/** What one block makes of one net (made minus used, items/s) must be at least [min] and at most [max]. */
data class BalanceBound(val net: Int, val block: Int, val min: Double = Double.NEGATIVE_INFINITY, val max: Double = Double.POSITIVE_INFINITY)

/**
 * [rebalance] with [bounds] on some blocks' balances (a sliver link handed to another supplier: that block makes
 * that much more, the first supplier that much less). Every machine's rate, and with it what it takes in, follows.
 * Null if the bounds cannot be met (or the solver fails).
 */
fun rebalanceWith(plan: ProductionPlan, blocks: List<List<Int>>, maxUtilization: Double, bounds: List<BalanceBound>): ProductionPlan? {
    Loader.loadNativeLibraries()
    val solver = MPSolver.createSolver("GLOP") ?: return null
    val ms = plan.machines
    val c = ms.map { m -> solver.makeNumVar(0.25 * m.crafts, maxOf(m.crafts, (plan.utilization[m.recipe] ?: maxUtilization) * m.maxCrafts), "c${m.id}") }
    ms.groupBy { it.recipe }.forEach { (r, group) ->
        val total = group.sumOf { it.crafts }
        val row = solver.makeConstraint(total, total, "r_$r")
        group.forEach { row.setCoefficient(c[it.id], 1.0) }
    }
    val blockOf = IntArray(ms.size).also { a -> blocks.forEachIndexed { b, ids -> ids.forEach { a[it] = b } } }
    val obj = solver.objective()
    plan.nets.forEach { net ->
        // Net rate per craft of each machine on this item.
        val perCraft = HashMap<Int, Double>()
        plan.netTerminals(net).forEach { t ->
            val m = t.machine ?: return@forEach
            val sign = when (t.role) { Role.PRODUCER -> 1.0; Role.CONSUMER -> -1.0; else -> return@forEach }
            perCraft.merge(m, sign * t.rate / ms[m].crafts, Double::plus)
        }
        perCraft.keys.groupBy { blockOf[it] }.forEach { (b, inBlock) ->
            val t = solver.makeNumVar(0.0, MPSolver.infinity(), "t_${net.id}_$b")
            val up = solver.makeConstraint(0.0, MPSolver.infinity())
            val down = solver.makeConstraint(0.0, MPSolver.infinity())
            up.setCoefficient(t, 1.0); down.setCoefficient(t, 1.0)
            inBlock.forEach { m -> up.setCoefficient(c[m], -perCraft.getValue(m)); down.setCoefficient(c[m], perCraft.getValue(m)) }
            obj.setCoefficient(t, 1.0 / maxOf(net.flow, 1e-6))
        }
    }
    bounds.forEach { bd ->
        val net = plan.nets[bd.net]
        val row = solver.makeConstraint(bd.min, bd.max, "bound_${bd.net}_${bd.block}")
        plan.netTerminals(net).forEach { t ->
            val m = t.machine ?: return@forEach
            if (blockOf[m] != bd.block) return@forEach
            val sign = when (t.role) { Role.PRODUCER -> 1.0; Role.CONSUMER -> -1.0; else -> return@forEach }
            row.setCoefficient(c[m], row.getCoefficient(c[m]) + sign * t.rate / ms[m].crafts)
        }
    }
    obj.setMinimization()
    val status = solver.solve()
    if (status != MPSolver.ResultStatus.OPTIMAL) return null
    val factor = DoubleArray(ms.size) { i -> c[i].solutionValue() / ms[i].crafts }
    if (factor.all { kotlin.math.abs(it - 1) < 1e-9 }) return plan
    val machines = ms.map { m ->
        val f = factor[m.id]
        m.copy(outputRate = m.outputRate * f, demand = m.demand.mapValues { it.value * f }, products = m.products.mapValues { it.value * f },
            fluidDemand = m.fluidDemand.mapValues { it.value * f }, crafts = m.crafts * f)
    }
    // Inserter counts stay: every machine runs at most at what its planned inserters move.
    val terminals = plan.terminals.map { t -> t.machine?.let { t.copy(rate = t.rate * factor[it]) } ?: t }
    val nets = plan.nets.map { n -> n.copy(flow = n.terminals.map { terminals[it] }.filter { it.role == Role.CONSUMER || it.role == Role.OUTPUT }.sumOf { it.rate }) }
    return plan.copy(machines = machines, terminals = terminals, nets = nets)
}
