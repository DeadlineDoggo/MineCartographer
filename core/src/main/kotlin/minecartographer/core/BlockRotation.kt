package minecartographer.core

/**
 * Turns block states with the world, a quarter turn clockwise seen from above (north -> east -> south -> west),
 * the same direction as [Rotation]. Covers the properties Minecraft itself rotates in structures:
 * `facing`, `axis`, `rotation` (signs, banners, heads), rail `shape`, the side connections of fences,
 * walls, panes, redstone and vines, and `orientation` (jigsaws, crafters).
 */
object BlockRotation {
    private val SIDES = listOf("north", "east", "south", "west")

    fun rotate(state: BlockState, turns: Int): BlockState {
        val t = Rotation.normalize(turns)
        if (t == 0 || state.properties.isEmpty()) return state
        val props = state.properties
        val out = LinkedHashMap<String, String>(props.size)
        for ((key, value) in props) {
            when {
                // Side connections move to the side they now face: north=true becomes east=true.
                key in SIDES -> out[side(key, t)] = value
                key == "facing" || key == "horizontal_facing" -> out[key] = side(value, t)
                key == "axis" -> out[key] = if (t % 2 == 1) when (value) { "x" -> "z"; "z" -> "x"; else -> value } else value
                key == "rotation" -> out[key] = value.toIntOrNull()?.let { ((it + 4 * t) % 16).toString() } ?: value
                key == "shape" -> out[key] = shape(value, t)
                key == "orientation" -> out[key] = value.split('_').joinToString("_") { side(it, t) }
                else -> out[key] = value
            }
        }
        return BlockState.of(state.name, out)
    }

    /** A horizontal direction turned; anything else (up, down, values that aren't directions) stays. */
    fun side(name: String, turns: Int): String {
        val i = SIDES.indexOf(name)
        return if (i < 0) name else SIDES[(i + turns) % 4]
    }

    /** Rails: `north_south`, `ascending_east`, `south_west`...; stairs (`inner_left`) don't change. */
    private fun shape(value: String, turns: Int): String {
        if (value.startsWith("ascending_")) return "ascending_" + side(value.removePrefix("ascending_"), turns)
        val parts = value.split('_')
        if (parts.size != 2 || parts.any { it !in SIDES }) return value
        val turned = parts.map { side(it, turns) }.toSet()
        // Minecraft's spelling: north_south, east_west, and corners with north/south first.
        if (turned == setOf("north", "south")) return "north_south"
        if (turned == setOf("east", "west")) return "east_west"
        return turned.sortedBy { if (it == "north" || it == "south") 0 else 1 }.joinToString("_")
    }
}
