import blocks.turnLayout
import config.Tuning
import geom.Kind
import geom.PlacedEntity
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

    @Test
    fun `a turned block keeps its requester's request, its chest's bar and its read belt`() {
        // The built logistic science factory stood still in game: 260 of its 380 requester chests asked for nothing,
        // as the turn rebuilt each entity without those fields.
        val plan = buildPlan(TestData.data, plasticJob)
        val r = (1L..8L).asSequence().map { runTrial(TestData.data, plan, plasticJob, Tuning(), 24, 24, it) }.first { it.success }
        val extra = listOf(
            PlacedEntity("requester-chest", Kind.CHEST, -1, 0, item = "iron-plate", request = 12, bar = 3),
            PlacedEntity("transport-belt", Kind.BELT, -1, 1, readBelt = true),
        )
        val l = r.layout!!
        val with = search.TrialResult(true, r.w, r.h, r.seed, geom.Layout(l.w, l.h, l.entities + extra, l.wires, l.label), r.placement,
            r.errors, null, r.score, r.stage, r.annealT0, r.millis, r.notes, sources = r.sources, outputs = r.outputs)
        val t = turnLayout(with, 1)
        val chest = t.layout!!.entities.single { it.name == "requester-chest" }
        assertEquals(12, chest.request)
        assertEquals(3, chest.bar)
        assertTrue(t.layout!!.entities.any { it.kind == Kind.BELT && it.readBelt })
    }

    @Test
    fun `a turned block's local fluid sink stays beside its port`() {
        val plan = buildPlan(TestData.data, plasticJob)
        val r = (1L..8L).asSequence().map { runTrial(TestData.data, plan, plasticJob, Tuning(), 24, 24, it) }.first { it.success }
        val port = geom.Tile(5, 0)
        val outs = listOf(validate.OutputSpec(port, "water", 1.0, fluid = true, local = true, sink = geom.Tile(5, -1)))
        val with = search.TrialResult(true, r.w, r.h, r.seed, r.layout, r.placement, r.errors, null, r.score, r.stage, r.annealT0, r.millis, r.notes,
            sources = r.sources, outputs = outs)
        for (q in 1..3) {
            val o = turnLayout(with, q).outputs.single()
            assertEquals(1, kotlin.math.abs(o.tile.x - o.sink!!.x) + kotlin.math.abs(o.tile.y - o.sink!!.y), "turned $q: ${o.tile} and ${o.sink}")
        }
    }
}
