package minecartographer.core

/**
 * Quarter-turn rotations of the horizontal plane, used to look at the world from 4 sides.
 * One turn maps block cell (x, z) to (-z - 1, x); chunk cells rotate by the same rule.
 */
object Rotation {
    fun normalize(turns: Int) = ((turns % 4) + 4) % 4

    /** Rotates an integer cell (block or chunk) by [turns] quarter turns. */
    fun cell(turns: Int, x: Int, z: Int): Pair<Int, Int> = when (normalize(turns)) {
        0 -> x to z
        1 -> (-z - 1) to x
        2 -> (-x - 1) to (-z - 1)
        else -> z to (-x - 1)
    }

    /** Rotates a continuous point; consistent with [cell] (a cell's corners map to the rotated cell's corners). */
    fun point(turns: Int, x: Double, z: Double): Pair<Double, Double> = when (normalize(turns)) {
        0 -> x to z
        1 -> -z to x
        2 -> -x to -z
        else -> z to -x
    }

    /** Same as [cell] for a chunk position, without the intermediate pair (it runs for every chunk on screen). */
    fun chunk(turns: Int, pos: ChunkPos): ChunkPos = when (normalize(turns)) {
        0 -> pos
        1 -> ChunkPos(-pos.z - 1, pos.x)
        2 -> ChunkPos(-pos.x - 1, -pos.z - 1)
        else -> ChunkPos(pos.z, -pos.x - 1)
    }
}

/** The same chunk seen after rotating the view; [ChunkSurface.pos] stays the original position. */
fun ChunkSurface.rotated(turns: Int): ChunkSurface {
    val t = Rotation.normalize(turns)
    if (t == 0) return this
    val h = IntArray(ChunkSurface.COLUMNS)
    val c = IntArray(ChunkSurface.COLUMNS)
    for (lz in 0 until 16) for (lx in 0 until 16) {
        val (rx, rz) = when (t) {
            1 -> (15 - lz) to lx
            2 -> (15 - lx) to (15 - lz)
            else -> lz to (15 - lx)
        }
        h[rz * 16 + rx] = heights[lz * 16 + lx]
        c[rz * 16 + rx] = colors[lz * 16 + lx]
    }
    return ChunkSurface(pos, h, c)
}
