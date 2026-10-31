package geom

import data.GameConstants

enum class Kind { MACHINE, BELT, UG_IN, UG_OUT, SPLITTER, INSERTER, CHEST, POLE, INFINITY_PIPE, INFINITY_CHEST, PIPE, PIPE_UG, TANK, BEACON;

    val beltFamily: Boolean get() = this == BELT || this == UG_IN || this == UG_OUT || this == SPLITTER
}

/**
 * One entity of a layout, in tile coordinates with top-left (x, y).
 * [dir] means: travel direction for belts, undergrounds and splitters; pickup side for inserters (as in the
 * blueprint); fluid input side for machines.
 */
data class PlacedEntity(
    val name: String,
    val kind: Kind,
    val x: Int,
    val y: Int,
    val dir: Dir = Dir.N,
    val recipe: String? = null,
    /** Item for infinity chests (what they supply), filtered sink chests and filtered inserters. */
    val item: String? = null,
    /** Fluid for infinity pipes. */
    val fluid: String? = null,
    /** Net id, for renders and validator messages only; not written to blueprints. */
    val net: Int? = null,
    /** Machines: mirrored (2.0 blueprint "mirror"); applied before the rotation in [dir]. */
    val mirror: Boolean = false,
    /** Infinity pipes: keep the pipe empty (a sink for [fluid]) instead of full. */
    val drain: Boolean = false,
    val w: Int = defaultSize(name, kind, dir).first,
    val h: Int = defaultSize(name, kind, dir).second,
    /** Machines: works only while this condition holds on its circuit network (null: always). */
    val circuit: CircuitCondition? = null,
    /** Machines and beacons: modules in their slots, in slot order (blueprint item requests). */
    val modules: List<String>? = null,
) {
    val rect: Rect get() = Rect(x, y, w, h)
    val tile: Tile get() = Tile(x, y)

    /** Comparable identity without debug-only fields. */
    fun stripped() = copy(net = null)

    companion object {
        fun defaultSize(name: String, kind: Kind, dir: Dir): Pair<Int, Int> = when (kind) {
            Kind.MACHINE -> GameConstants.MACHINE_SIZES[name] ?: (3 to 3)
            Kind.SPLITTER -> if (dir.axis == 1) 2 to 1 else 1 to 2
            Kind.POLE -> GameConstants.footprint(name)
            Kind.TANK -> 3 to 3
            Kind.BEACON -> 3 to 3
            else -> 1 to 1
        }

        fun kindOf(name: String, ugType: String? = null): Kind {
            val tier = GameConstants.tierOfEntity(name)
            return when {
                tier != null && name == tier.belt -> Kind.BELT
                tier != null && name == tier.underground -> if (ugType == "output") Kind.UG_OUT else Kind.UG_IN
                tier != null && name == tier.splitter -> Kind.SPLITTER
                name in GameConstants.INSERTERS -> Kind.INSERTER
                name == GameConstants.INFINITY_CHEST -> Kind.INFINITY_CHEST
                name in GameConstants.CHESTS -> Kind.CHEST
                name in GameConstants.POLES -> Kind.POLE
                name == GameConstants.INFINITY_PIPE -> Kind.INFINITY_PIPE
                name in GameConstants.MACHINE_SIZES -> Kind.MACHINE
                name == GameConstants.PIPE -> Kind.PIPE
                name == GameConstants.PIPE_TO_GROUND -> Kind.PIPE_UG
                name == GameConstants.STORAGE_TANK -> Kind.TANK
                name == GameConstants.BEACON -> Kind.BEACON
                else -> error("Unknown entity '$name'")
            }
        }
    }
}

/** "Work while [signal] [comparator] [constant]" ([signal] a fluid when [fluid], else an item). */
data class CircuitCondition(val signal: String, val comparator: String, val constant: Int, val fluid: Boolean = true)

/** A floor tile of the blueprint ([name] is a tile prototype, e.g. hazard-concrete-left). */
data class GroundTile(val name: String, val x: Int, val y: Int)

/** The ring of tiles just inside [r], as floor tiles named [name]. */
fun borderTiles(r: Rect, name: String): List<GroundTile> =
    r.tiles().filter { it.x == r.x || it.y == r.y || it.x == r.x2 || it.y == r.y2 }.map { GroundTile(name, it.x, it.y) }

/** Inserter reach from its name (1, or 2 for long-handed). */
val PlacedEntity.reach: Int get() = GameConstants.inserter(name).reach
/** Tile an inserter picks from. */
val PlacedEntity.pickupTile: Tile get() = Tile(x, y).step(dir, reach)
/** Tile an inserter drops into. */
val PlacedEntity.dropTile: Tile get() = Tile(x, y).step(dir.opposite, reach)

/** Tile a belt-family entity pushes items into (null for underground entrances, which go below ground). */
val PlacedEntity.outputTile: Tile? get() = when (kind) {
    Kind.BELT, Kind.UG_OUT -> Tile(x, y) + dir
    else -> null
}

/** The final format consumed by validate, emit and viz. */
data class Layout(
    /** Box size; entities may also lie in the ring around it (ports, harness, outside poles). */
    val w: Int,
    val h: Int,
    val entities: List<PlacedEntity>,
    /** Copper wires between poles, as entity indices. */
    val wires: List<Pair<Int, Int>> = emptyList(),
    val label: String = "factorio-optimizer layout",
    /** Floor tiles (markers: block borders, reserved areas); entities stand on top of them. */
    val tiles: List<GroundTile> = emptyList(),
    /** Red or green circuit wires: entity indices and the colour ([GameConstants.WIRE_RED] or GREEN). */
    val circuitWires: List<Triple<Int, Int, Int>> = emptyList(),
) {
    fun count(kind: Kind) = entities.count { it.kind == kind }

    /** Map tile -> entity index; later entities overwrite earlier ones (the validator reports overlaps separately). */
    fun tileIndex(): Map<Tile, Int> {
        val m = HashMap<Tile, Int>()
        entities.forEachIndexed { i, e -> e.rect.tiles().forEach { m[it] = i } }
        return m
    }

    fun bounds(): Rect {
        if (entities.isEmpty()) return Rect(0, 0, w, h)
        val x0 = minOf(0, entities.minOf { it.x })
        val y0 = minOf(0, entities.minOf { it.y })
        val x1 = maxOf(w - 1, entities.maxOf { it.rect.x2 })
        val y1 = maxOf(h - 1, entities.maxOf { it.rect.y2 })
        return Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
