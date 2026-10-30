import blocks.turnLayout
import config.Tuning
import plan.buildPlan
import search.runTrial
import search.validationSpec
import validate.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotateTest {
    @Test
    fun `a turned block layout is still valid, and four turns give it back`() {
        // Plastic: refineries and chemical plants, so pipes, pipe-to-ground and fluid connections turn too.
        val plan = buildPlan(TestData.data, plasticJob)
        val r = (1L..8L).asSequence().map { runTrial(TestData.data, plan, plasticJob, Tuning(), 24, 24, it) }.first { it.success }
        for (q in 1..3) {
            val t = turnLayout(r, q)
            val layout = t.layout!!
            val outside = layout.entities.flatMap { it.rect.tiles() }.filter { it.x !in 0 until t.w || it.y !in 0 until t.h }.toSet()
            val spec = validationSpec(TestData.data, plan, plasticJob, t.placement!!, t.sources, requirePower = true, outsideAllowed = outside, outputs = t.outputs)
            val errors = validate(layout, spec)
            assertTrue(errors.isEmpty(), "turned $q: ${errors.take(5)}")
        }
        val back = turnLayout(turnLayout(turnLayout(turnLayout(r, 1), 1), 1), 1)
        assertEquals(r.layout!!.entities.toSet(), back.layout!!.entities.toSet())
        assertEquals(r.sources.map { it.tile }, back.sources.map { it.tile })
    }
}
