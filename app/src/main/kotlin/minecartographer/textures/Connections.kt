package minecartographer.textures

import minecartographer.core.BlockState

/**
 * Block state properties that old worlds (before 1.13) don't store because the game worked them out
 * from the neighbors while drawing: which sides fences, panes and walls connect to, snowy grass,
 * redstone wire, and the kind of the upper half of tall plants. Newer worlds have them already.
 */
object Connections {
    private val HORIZONTAL = listOf("north" to (0 to -1), "south" to (0 to 1), "west" to (-1 to 0), "east" to (1 to 0))

    /** True when [state] lacks properties this object can fill in, so [derive] should be called for it. */
    fun needed(state: BlockState): Boolean {
        val p = state.path
        val props = state.properties
        return when {
            family(p) != null || p == "redstone_wire" -> "north" !in props
            p == "grass_block" || p == "podzol" || p == "mycelium" -> "snowy" !in props
            else -> false
        }
    }

    /**
     * The state with its missing properties filled in. [at] returns the neighbor at a world-space offset
     * (dx, dy, dz), or null when it is unknown (another chunk). [occludes] tells full opaque blocks.
     */
    fun derive(state: BlockState, at: (Int, Int, Int) -> BlockState?, occludes: (BlockState) -> Boolean): BlockState {
        val p = state.path
        val props = LinkedHashMap(state.properties)
        when {
            p == "grass_block" || p == "podzol" || p == "mycelium" -> {
                val above = at(0, 1, 0)?.path
                props["snowy"] = (above == "snow" || above == "snow_block" || above == "powder_snow").toString()
            }
            p == "redstone_wire" -> for ((side, d) in HORIZONTAL) {
                props[side] = if (at(d.first, 0, d.second)?.path == "redstone_wire") "side" else "none"
            }
            else -> {
                val family = family(p) ?: return state
                for ((side, d) in HORIZONTAL) {
                    val n = at(d.first, 0, d.second)
                    val joins = n != null && (family(n.path) == family || occludes(n) ||
                        ((family == "fence" || family == "wall") && n.path.endsWith("_fence_gate")))
                    props[side] = if (family == "wall") (if (joins) "low" else "none") else joins.toString()
                }
                if (family == "wall") {
                    // A wall has a post unless it runs straight through.
                    val straight = (props["north"] != "none" && props["south"] != "none" && props["east"] == "none" && props["west"] == "none") ||
                        (props["east"] != "none" && props["west"] != "none" && props["north"] == "none" && props["south"] == "none")
                    props["up"] = (!straight).toString()
                }
            }
        }
        return BlockState.of(state.name, props)
    }

    /** Old worlds only store "upper half" for tall plants; the kind comes from the half below. */
    fun upperHalf(state: BlockState, below: BlockState?): BlockState {
        if (state.properties["half"] != "upper" || below == null || below.properties["half"] != "lower" || below.name == state.name) return state
        return BlockState.of(below.name, below.properties + ("half" to "upper"))
    }

    /** Blocks that connect to each other: panes and bars, wooden fences, nether brick fences, walls. */
    private fun family(path: String): String? = when {
        path.endsWith("glass_pane") || path == "iron_bars" -> "pane"
        path == "nether_brick_fence" -> "nether_fence"
        path.endsWith("_fence") -> "fence"
        path.endsWith("_wall") -> "wall"
        else -> null
    }
}
