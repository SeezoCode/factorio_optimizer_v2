package viz

import data.GameConstants
import geom.Dir
import geom.Kind
import geom.Layout
import geom.PlacedEntity
import geom.Tile
import geom.dropTile
import geom.pickupTile
import validate.ValidationError
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Per-tile overlay for PathFinder debug renders: history cost as heat, overused tiles in red. */
class HeatOverlay(val values: Map<Tile, Double>, val overused: Set<Tile> = emptySet())

data class RenderOptions(
    val px: Int = 32,
    val showSupply: Boolean = false,
    val errors: List<ValidationError> = emptyList(),
    val heat: HeatOverlay? = null,
    val title: String? = null,
    /** Inputs and outputs to mark (numbered on their tiles, listed under the render). */
    val ports: List<PortLabel> = emptyList(),
)

/**
 * An input ([input]) or output port of a layout: where [item] enters or leaves, at [rate] per second. For an
 * input belt, [lane] says which lane it arrives on, looking along the belt into the block: 0 left, 1 right, -1
 * both (a belt of its own); fluids have none.
 */
data class PortLabel(val tile: Tile, val item: String, val rate: Double, val input: Boolean, val lane: Int = -1, val fluid: Boolean = false)

/** Port labels for a layout's sources and outputs (as the validator and ports.json list them). */
fun portLabels(sources: List<validate.SourceSpec>, outputs: List<validate.OutputSpec>): List<PortLabel> =
    sources.map { PortLabel(it.tile, it.item, it.rate, true, it.lane, it.fluid) } + outputs.map { PortLabel(it.tile, it.item, it.rate, false, fluid = it.fluid) }

private fun fluidColor(fluid: String?): Color = when (fluid) {
    null -> Color(120, 140, 160)
    "water" -> Color(60, 120, 230)
    "crude-oil" -> Color(40, 40, 40)
    "heavy-oil" -> Color(170, 70, 20)
    "light-oil" -> Color(230, 170, 30)
    "petroleum-gas" -> Color(150, 60, 160)
    "sulfuric-acid" -> Color(200, 200, 40)
    "lubricant" -> Color(60, 160, 60)
    else -> Color.getHSBColor(((fluid.hashCode() and 0xffff) / 65535f), 0.7f, 0.7f)
}

/** Belt-family tiles grouped into chains: a belt joins the entity it outputs into; entrances join their exits. */
private fun beltChains(layout: Layout): Map<Tile, Int> {
    val belts = layout.entities.filter { it.kind.beltFamily }
    val at = belts.associateBy { it.tile }
    val parent = HashMap<Tile, Tile>()
    fun find(t: Tile): Tile { var a = t; while (parent.getOrPut(a) { a } != a) a = parent.getValue(a); return a }
    fun join(a: Tile, b: Tile) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
    belts.forEach { e ->
        when (e.kind) {
            Kind.UG_IN -> {
                val reach = (GameConstants.tierOfEntity(e.name)?.maxUndergroundGap ?: 4) + 1
                (1..reach).map { e.tile.step(e.dir, it) }.firstOrNull { at[it]?.let { o -> o.name == e.name && o.dir.axis == e.dir.axis } == true }
                    ?.let { if (at.getValue(it).kind == Kind.UG_OUT && at.getValue(it).dir == e.dir) join(e.tile, it) }
            }
            else -> (e.tile + e.dir).let { t -> at[t]?.let { o -> if (o.dir != e.dir.opposite && o.kind != Kind.UG_OUT) join(e.tile, t) } }
        }
    }
    val ids = HashMap<Tile, Int>()
    return belts.associate { e -> e.tile to ids.getOrPut(find(e.tile)) { ids.size } }
}

/** Headless PNG render: machines as labelled boxes, belts as arrows coloured per net, inserters as small arrows. */
fun renderLayout(layout: Layout, file: File, opt: RenderOptions = RenderOptions()) {
    System.setProperty("java.awt.headless", "true")
    val b = layout.bounds()
    val px = opt.px
    val legendLines = (if (opt.title != null) 1 else 0) + opt.errors.size.coerceAtMost(30) + opt.ports.size
    // Legend text scales with the tile size (14 px lines at 16 px tiles).
    val lineH = (px * 14 / 16).coerceAtLeast(14)
    val img = BufferedImage(maxOf((b.w + 2) * px, if (legendLines > 0) 720 * lineH / 14 else 0), (b.h + 2) * px + legendLines * lineH + 8, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(235, 235, 235)
    g.fillRect(0, 0, img.width, img.height)

    val ox = (1 - b.x) * px
    val oy = (1 - b.y) * px
    fun sx(x: Double) = (ox + x * px).toInt()
    fun sy(y: Double) = (oy + y * px).toInt()

    // Box and grid.
    g.color = Color.WHITE
    g.fillRect(sx(0.0), sy(0.0), layout.w * px, layout.h * px)
    g.color = Color(225, 225, 225)
    for (x in b.x..b.x + b.w) g.drawLine(sx(x.toDouble()), sy(b.y.toDouble()), sx(x.toDouble()), sy((b.y + b.h).toDouble()))
    for (y in b.y..b.y + b.h) g.drawLine(sx(b.x.toDouble()), sy(y.toDouble()), sx((b.x + b.w).toDouble()), sy(y.toDouble()))
    // Floor marker tiles (block borders, reserved areas) under everything else.
    g.color = Color(250, 214, 120)
    layout.tiles.forEach { t -> g.fillRect(sx(t.x.toDouble()) + 1, sy(t.y.toDouble()) + 1, px - 1, px - 1) }
    g.color = Color(80, 80, 80)
    g.stroke = BasicStroke(2f)
    g.drawRect(sx(0.0), sy(0.0), layout.w * px, layout.h * px)

    opt.heat?.let { heat ->
        val max = (heat.values.values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        heat.values.forEach { (t, v) ->
            if (v <= 0) return@forEach
            val a = (40 + 160 * (v / max)).toInt().coerceIn(0, 200)
            g.color = Color(255, 140, 0, a)
            g.fillRect(sx(t.x.toDouble()), sy(t.y.toDouble()), px, px)
        }
        heat.overused.forEach { t ->
            g.color = Color(230, 0, 0, 170)
            g.fillRect(sx(t.x.toDouble()), sy(t.y.toDouble()), px, px)
        }
    }

    g.stroke = BasicStroke(1f)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, (px * 0.55).toInt().coerceAtLeast(8))

    // Without net ids (e.g. a decoded blueprint), colour belts by connected chain instead.
    val chainOf = if (layout.entities.any { it.kind.beltFamily && it.net != null }) emptyMap() else beltChains(layout)
    fun netColor(e: PlacedEntity): Color {
        val n = e.net ?: chainOf[e.tile] ?: return Color(110, 110, 110)
        val hue = ((n * 0.61803398875) % 1.0).toFloat()
        return Color.getHSBColor(hue, 0.75f, 0.8f)
    }

    fun arrow(cx: Double, cy: Double, d: Dir, len: Double, color: Color, width: Float) {
        g.color = color
        g.stroke = BasicStroke(width)
        val x0 = cx - d.dx * len / 2; val y0 = cy - d.dy * len / 2
        val x1 = cx + d.dx * len / 2; val y1 = cy + d.dy * len / 2
        g.drawLine(sx(x0), sy(y0), sx(x1), sy(y1))
        val hx = d.dx * 0.22; val hy = d.dy * 0.22
        val px2 = -d.dy * 0.18; val py2 = d.dx * 0.18
        val xs = intArrayOf(sx(x1), sx(x1 - hx + px2), sx(x1 - hx - px2))
        val ys = intArrayOf(sy(y1), sy(y1 - hy + py2), sy(y1 - hy - py2))
        g.fillPolygon(xs, ys, 3)
    }

    val byTile = layout.tileIndex()
    // Machines inside some beacon's supply area are drawn green.
    val beaconsDrawn = layout.entities.filter { it.kind == Kind.BEACON }
    val d = GameConstants.BEACON_SUPPLY
    fun beaconed(e: PlacedEntity) = beaconsDrawn.any { b ->
        e.x < b.x + b.w + d && e.x + e.w > b.x - d && e.y < b.y + b.h + d && e.y + e.h > b.y - d
    }
    layout.entities.forEach { e ->
        val cx = e.x + e.w / 2.0
        val cy = e.y + e.h / 2.0
        when (e.kind) {
            Kind.MACHINE -> {
                g.color = if (beaconed(e)) Color(170, 225, 165) else Color(200, 210, 230)
                g.fillRect(sx(e.x + 0.08), sy(e.y + 0.08), ((e.w - 0.16) * px).toInt(), ((e.h - 0.16) * px).toInt())
                g.color = Color(60, 70, 100)
                g.drawRect(sx(e.x + 0.08), sy(e.y + 0.08), ((e.w - 0.16) * px).toInt(), ((e.h - 0.16) * px).toInt())
                // Machine name, then the recipe one word per line, clipped to the machine's box.
                val lines = listOf(e.name) + (e.recipe?.split('-') ?: emptyList())
                val oldClip = g.clip
                g.clipRect(sx(e.x + 0.08) + 1, sy(e.y + 0.08) + 1, ((e.w - 0.16) * px).toInt() - 1, ((e.h - 0.16) * px).toInt() - 1)
                val font = g.font
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, (px * 0.42).toInt().coerceAtLeast(7))
                val lh = g.fontMetrics.height
                lines.forEachIndexed { i, line -> g.drawString(line, sx(e.x + 0.15), sy(e.y + 0.1) + lh * (i + 1) - 2) }
                g.font = font
                g.clip = oldClip
            }
            Kind.BEACON -> {
                g.color = Color(120, 160, 120)
                g.fillRect(sx(e.x + 0.1), sy(e.y + 0.1), ((e.w - 0.2) * px).toInt(), ((e.h - 0.2) * px).toInt())
                g.color = Color(40, 80, 40)
                g.drawRect(sx(e.x + 0.1), sy(e.y + 0.1), ((e.w - 0.2) * px).toInt(), ((e.h - 0.2) * px).toInt())
                g.color = Color(200, 230, 120)
                g.fillOval(sx(e.x + e.w / 2.0 - 0.45), sy(e.y + e.h / 2.0 - 0.45), (0.9 * px).toInt(), (0.9 * px).toInt())
            }
            Kind.TANK -> {
                g.color = Color(170, 190, 215)
                g.fillOval(sx(e.x + 0.15), sy(e.y + 0.15), ((e.w - 0.3) * px).toInt(), ((e.h - 0.3) * px).toInt())
                g.color = Color(40, 60, 110)
                g.drawOval(sx(e.x + 0.15), sy(e.y + 0.15), ((e.w - 0.3) * px).toInt(), ((e.h - 0.3) * px).toInt())
            }
            Kind.BELT -> arrow(cx, cy, e.dir, 0.8, netColor(e), (px / 6f).coerceAtLeast(2f))
            Kind.UG_IN, Kind.UG_OUT -> {
                g.color = netColor(e)
                g.fillRect(sx(e.x + 0.2), sy(e.y + 0.2), (px * 0.6).toInt(), (px * 0.6).toInt())
                arrow(cx, cy, e.dir, 0.6, Color.WHITE, 1.5f)
                if (e.kind == Kind.UG_IN) {
                    val tier = GameConstants.tierOfEntity(e.name)
                    val exit = (1..((tier?.maxUndergroundGap ?: 4) + 1)).map { e.tile.step(e.dir, it) }
                        .firstOrNull { t -> byTile[t]?.let { layout.entities[it].kind == Kind.UG_OUT && layout.entities[it].name == e.name } == true }
                    if (exit != null) {
                        g.color = netColor(e)
                        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
                        g.drawLine(sx(cx), sy(cy), sx(exit.x + 0.5), sy(exit.y + 0.5))
                    }
                }
            }
            Kind.SPLITTER -> {
                g.color = netColor(e)
                g.fillRect(sx(e.x + 0.1), sy(e.y + 0.1), ((e.w - 0.2) * px).toInt(), ((e.h - 0.2) * px).toInt())
            }
            Kind.INSERTER -> {
                val long = e.name.startsWith("long")
                val color = when {
                    long -> Color(200, 40, 40)
                    e.name.startsWith("fast") -> Color(30, 110, 220)
                    e.name.startsWith("bulk") -> Color(40, 160, 60)
                    else -> Color(200, 160, 0)
                }
                g.color = Color(color.red, color.green, color.blue, 60)
                g.fillRect(sx(e.x + 0.1), sy(e.y + 0.1), (px * 0.8).toInt(), (px * 0.8).toInt())
                // From pickup towards drop.
                arrow(cx, cy, e.dir.opposite, 0.7, color, 2f * px / 16)
                if (long) {
                    g.color = color
                    g.drawLine(sx(e.pickupTile.x + 0.5), sy(e.pickupTile.y + 0.5), sx(e.dropTile.x + 0.5), sy(e.dropTile.y + 0.5))
                }
            }
            Kind.CHEST -> {
                g.color = Color(150, 100, 50)
                g.fillRect(sx(e.x + 0.15), sy(e.y + 0.15), (px * 0.7).toInt(), (px * 0.7).toInt())
            }
            Kind.INFINITY_CHEST -> {
                g.color = Color(230, 90, 200)
                g.fillRect(sx(e.x + 0.15), sy(e.y + 0.15), (px * 0.7).toInt(), (px * 0.7).toInt())
            }
            Kind.INFINITY_PIPE -> {
                g.color = if (e.fluid != null) fluidColor(e.fluid) else Color(130, 60, 200)
                g.fillOval(sx(e.x + 0.15), sy(e.y + 0.15), (px * 0.7).toInt(), (px * 0.7).toInt())
            }
            Kind.PIPE, Kind.PIPE_UG -> {
                val c = fluidColor(e.fluid)
                g.color = c
                g.stroke = BasicStroke((px / 4f).coerceAtLeast(2f))
                // Stubs towards neighbouring pipes of any kind (the validator decides what really joins).
                Dir.entries.forEach { d ->
                    val o = byTile[e.tile + d]?.let { layout.entities[it] }
                    if (o != null && (o.kind == Kind.PIPE || o.kind == Kind.PIPE_UG || o.kind == Kind.INFINITY_PIPE) &&
                        (e.kind == Kind.PIPE || d == e.dir)) {
                        g.drawLine(sx(cx), sy(cy), sx(cx + d.dx * 0.5), sy(cy + d.dy * 0.5))
                    }
                }
                if (e.kind == Kind.PIPE_UG) {
                    g.fillRect(sx(e.x + 0.25), sy(e.y + 0.25), (px * 0.5).toInt(), (px * 0.5).toInt())
                    g.drawLine(sx(cx), sy(cy), sx(cx + e.dir.dx * 0.5), sy(cy + e.dir.dy * 0.5))
                } else g.fillOval(sx(cx - 0.18), sy(cy - 0.18), (px * 0.36).toInt(), (px * 0.36).toInt())
            }
            Kind.POLE -> {
                g.color = Color(90, 60, 30)
                g.fillOval(sx(cx - 0.25), sy(cy - 0.25), (px * 0.5).toInt(), (px * 0.5).toInt())
                if (opt.showSupply) {
                    val s = GameConstants.pole(e.name).supplyHalf
                    g.color = Color(90, 160, 255, 40)
                    g.fillRect(sx(cx - s), sy(cy - s), (2 * s * px).toInt(), (2 * s * px).toInt())
                }
            }
        }
    }

    // Wires.
    g.stroke = BasicStroke(1f)
    g.color = Color(184, 115, 51)
    layout.wires.forEach { (a, c) ->
        val pa = layout.entities[a]; val pc = layout.entities[c]
        g.drawLine(sx(pa.x + pa.w / 2.0), sy(pa.y + pa.h / 2.0), sx(pc.x + pc.w / 2.0), sy(pc.y + pc.h / 2.0))
    }

    g.stroke = BasicStroke(2f)
    layout.circuitWires.forEach { (a, c, color) ->
        g.color = if (color == GameConstants.WIRE_RED) Color(220, 30, 30) else Color(30, 170, 30)
        val pa = layout.entities[a]; val pc = layout.entities[c]
        g.drawLine(sx(pa.x + pa.w / 2.0), sy(pa.y + pa.h / 2.0), sx(pc.x + pc.w / 2.0), sy(pc.y + pc.h / 2.0))
    }

    // Errors, numbered.
    g.font = Font(Font.SANS_SERIF, Font.BOLD, lineH * 11 / 14)
    opt.errors.forEachIndexed { i, err ->
        g.color = Color(230, 0, 0)
        g.stroke = BasicStroke(2.5f)
        g.drawRect(sx(err.tile.x.toDouble()), sy(err.tile.y.toDouble()), px, px)
        g.drawString("${i + 1}", sx(err.tile.x + 1.0), sy(err.tile.y.toDouble()))
    }
    // Ports: a numbered ring on the tile, green for inputs, red for outputs.
    val inColor = Color(20, 150, 60); val outColor = Color(210, 40, 40)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, (px * 0.6).toInt().coerceAtLeast(9))
    opt.ports.forEachIndexed { i, p ->
        g.color = if (p.input) inColor else outColor
        g.stroke = BasicStroke(2.5f * px / 16)
        g.drawOval(sx(p.tile.x - 0.1), sy(p.tile.y - 0.1), (px * 1.2).toInt(), (px * 1.2).toInt())
        g.drawString("${i + 1}", sx(p.tile.x + 1.05), sy(p.tile.y + 0.2))
    }
    var ty = (b.h + 2) * px + lineH
    g.color = Color.BLACK
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, lineH * 11 / 14)
    opt.title?.let { g.drawString(it, 6, ty); ty += lineH }
    opt.ports.forEachIndexed { i, p ->
        g.color = if (p.input) inColor else outColor
        val lane = when {
            !p.input || p.fluid -> ""
            p.lane == 0 -> "  left lane"
            p.lane == 1 -> "  right lane"
            else -> "  both lanes"
        }
        g.drawString("${i + 1}. ${if (p.input) "in " else "out"}  ${p.item}  ${"%.2f".format(java.util.Locale.ROOT, p.rate)}/s  at (${p.tile.x}, ${p.tile.y})$lane", 6, ty)
        ty += lineH
    }
    g.color = Color.BLACK
    opt.errors.take(30).forEachIndexed { i, err -> g.drawString("${i + 1}. $err", 6, ty); ty += lineH }

    g.dispose()
    file.parentFile?.mkdirs()
    ImageIO.write(img, "png", file)
}
