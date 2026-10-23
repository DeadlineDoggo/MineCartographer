package minecartographer.core

/**
 * Top-down summary of one chunk: the height and color of the top visible block
 * for each of its 16x16 columns. This is all the 2.5D viewport needs to draw it.
 *
 * Arrays are indexed `lz * 16 + lx`. Colors are ARGB.
 */
class ChunkSurface(
    val pos: ChunkPos,
    val heights: IntArray,
    val colors: IntArray,
) {
    init {
        require(heights.size == COLUMNS && colors.size == COLUMNS) { "Expected $COLUMNS columns" }
    }

    val minHeight: Int = heights.min()
    val maxHeight: Int = heights.max()

    fun height(lx: Int, lz: Int) = heights[lz * 16 + lx]
    fun color(lx: Int, lz: Int) = colors[lz * 16 + lx]

    companion object {
        const val COLUMNS = 256
    }
}
