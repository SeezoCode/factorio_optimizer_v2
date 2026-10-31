import config.Tuning
import emit.decodeBlueprint
import emit.toBlueprintString
import emit.toLayout
import geom.Kind
import plan.buildPlan
import search.runTrial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurplusControlTest {
    /** Plastic and lubricant from crude oil: heavy oil is cracked, and lubricant needs it too. */
    private val job = config.RunConfiguration(
        targets = listOf(config.ConfigTarget("plastic-bar", 4.0), config.ConfigTarget("lubricant", 10.0)),
        sources = listOf(config.ConfigSource("crude-oil", edge = "west"), config.ConfigSource("coal", edge = "north")),
    )

    @Test
    fun `heavy oil crackers work only on what lubricant leaves, through a wired tank`() {
        val plan = buildPlan(TestData.data, job)
        println("plan: ${plan.machines.groupingBy { it.recipe }.eachCount()}")
        assertTrue(plan.machines.any { it.recipe == "heavy-oil-cracking" } && plan.machines.any { it.recipe == "lubricant" })
        val r = (1L..8L).asSequence().map { runTrial(TestData.data, plan, job, Tuning(), 28, 28, it) }.firstOrNull { it.success }
        assertTrue(r != null, "no seed made a valid layout")
        val layout = r.layout!!
        viz.renderLayout(layout, java.io.File("build/renders/surplus_control.png"), viz.RenderOptions(title = "surplus control"))
        val tanks = layout.entities.filter { it.kind == Kind.TANK }
        val crackers = layout.entities.filter { it.recipe == "heavy-oil-cracking" }
        assertTrue(tanks.isNotEmpty(), "no tank (${r.notes})")
        assertTrue(crackers.all { it.circuit?.signal == "heavy-oil" && it.circuit?.comparator == ">" }, "${crackers.map { it.circuit }} ${r.notes}")
        assertTrue(layout.circuitWires.size >= crackers.size)
        // The validator checked the tank is on the crackers' heavy oil pipes and wired to each; the blueprint keeps it.
        val back = decodeBlueprint(layout.toBlueprintString()).toLayout(layout.w, layout.h)
        assertEquals(layout.circuitWires.map { setOf(it.first, it.second) to it.third }.toSet(), back.circuitWires.map { setOf(it.first, it.second) to it.third }.toSet())
        assertEquals(crackers.map { it.circuit }, back.entities.filter { it.recipe == "heavy-oil-cracking" }.map { it.circuit })
        assertEquals(tanks.size, back.entities.count { it.kind == Kind.TANK })
    }
}
