import config.RouteTuning
import config.RunConfiguration
import geom.Kind
import place.PlacementFixture
import place.Placement
import place.staticObstacles
import plan.ProductionPlan
import plan.buildPlan
import route.RouteProblem
import route.RouteResult
import route.route
import search.layoutOf
import search.validationSpec
import validate.ValidationError
import validate.validate
import viz.RenderOptions
import viz.renderLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class Routed(val plan: ProductionPlan, val placement: Placement, val result: RouteResult, val errors: List<ValidationError>)

object RouteSupport {
    fun routeAndValidate(
        name: String, config: RunConfiguration, plan: ProductionPlan, placement: Placement,
        tuning: RouteTuning = RouteTuning(), render: Boolean = true, debug: Boolean = false,
    ): Routed {
        val result = route(RouteProblem(plan, config, tuning, placement, staticObstacles(config, plan, placement.w, placement.h),
            debugDir = if (debug) File("build/renders/router/$name-debug") else null))
        val layout = layoutOf(placement, result.entities)
        val spec = validationSpec(TestData.data, plan, config, placement, result.sources, requirePower = false, outputs = result.outputs)
        val errors = if (result.success) validate(layout, spec) else emptyList()
        if (render) {
            renderLayout(layout, File("build/renders/router/$name.png"), RenderOptions(errors = errors,
                title = "$name: success=${result.success} iter=${result.iterations} overuse=${result.overuse} ${result.stats} ${result.millis}ms failed=${result.failedNets}"))
        }
        return Routed(plan, placement, result, errors)
    }

    fun fixture(name: String, debug: Boolean = false): Routed {
        val fx = PlacementFixture.load("placements/$name.json")
        val config = RunConfiguration.load(fx.job)
        val plan = buildPlan(TestData.data, config, handTuning)
        return routeAndValidate(name, config, plan, fx.toPlacement(plan), debug = debug)
    }
}

class RouterTest {
    private fun assertRoutedClean(r: Routed) {
        assertTrue(r.result.success, "routing failed: ${r.result.log.takeLast(10)} failed nets ${r.result.failedNets}")
        assertEquals(emptyList(), r.errors)
    }

    @Test
    fun `T1 hand placement routes and validates`() {
        val r = RouteSupport.fixture("t1")
        assertRoutedClean(r)
        assertEquals(3, r.result.entities.count { it.kind == Kind.INSERTER && it.net != null }, "two input inserters for 2 iron/s, one onto the gear belt")
        assertEquals(0, r.result.entities.count { it.kind == Kind.CHEST }, "gears leave through an output port")
        assertEquals(listOf("iron-gear-wheel"), r.result.outputs.map { it.item })
    }

    @Test
    fun `T2 with adjacent machines uses direct insertion for cable`() {
        val r = RouteSupport.fixture("t2_di")
        assertRoutedClean(r)
        assertEquals(2, r.result.di.size, "both cable machines feed the circuit machine directly")
        assertTrue(r.result.entities.none { it.net != null && r.plan.nets[it.net!!].item == "copper-cable" }, "no cable belt is needed")
    }

    @Test
    fun `T2 with spread machines routes a cable belt`() {
        val r = RouteSupport.fixture("t2_belt")
        assertRoutedClean(r)
        assertTrue(r.result.entities.any { it.net != null && r.plan.nets[it.net!!].item == "copper-cable" && it.kind == Kind.BELT })
    }

    @Test
    fun `T3 hand placement routes and validates`() {
        val r = RouteSupport.fixture("t3")
        assertRoutedClean(r)
    }

    @Test
    fun `two nets that must cross succeed through negotiation with an underground`() {
        val fx = PlacementFixture.load("placements/cross.json")
        val config = RunConfiguration.load(fx.job)
        val plan = buildPlan(TestData.data, config, handTuning)
        val placement = fx.toPlacement(plan)
        // Expensive undergrounds and the fast 1.5 present-cost schedule, under which a detour does not win.
        val tuning = RouteTuning(ugPenalty = 8.0, pMult = 1.5, maxIterations = 60, stagnationStop = 25)
        // Plain A*: one pass, other nets cost nothing and no endgame, so the two shortest paths overlap.
        val plain = RouteSupport.routeAndValidate("cross_plain", config, plan, placement, tuning.copy(pFacFirst = 0.0, maxIterations = 1, endgameRounds = 0))
        assertTrue(!plain.result.success, "plain A* should overlap")
        val r = RouteSupport.routeAndValidate("cross", config, plan, placement, tuning)
        assertRoutedClean(r)
        assertTrue(r.result.entities.any { it.kind == Kind.UG_IN }, "an underground carries one net under the other")
        assertTrue(r.result.iterations > 1, "negotiation needed more than one pass")
    }

    @Test
    fun `consumers far apart are reached by a splitter branch`() {
        val r = RouteSupport.fixture("fork")
        assertRoutedClean(r)
        val splitters = r.result.entities.count { it.kind == Kind.SPLITTER }
        println("fork: ${r.result.stats}, splitters $splitters")
        assertTrue(splitters >= 1, "expected a branch")
        // Without B9 the same fixture still routes, as one longer chain.
        val fx = PlacementFixture.load("placements/fork.json")
        val config = RunConfiguration.load(fx.job).let { it.copy(tricks = it.tricks + ("B9" to false)) }
        val plan = buildPlan(TestData.data, config, handTuning)
        val chain = RouteSupport.routeAndValidate("fork_chain", config, plan, fx.toPlacement(plan))
        assertRoutedClean(chain)
        assertTrue(chain.result.entities.none { it.kind == Kind.SPLITTER })
        println("fork as chain: ${chain.result.stats}")
    }

    @Test
    fun `sources start on their configured edge tiles`() {
        val r = RouteSupport.fixture("t3")
        r.result.sources.forEach { assertEquals(0, it.tile.x, "west edge source ${it.item} at ${it.tile}") }
        assertTrue(r.result.sources.map { it.item }.toSet() == setOf("iron-plate", "copper-plate"))
    }
}
