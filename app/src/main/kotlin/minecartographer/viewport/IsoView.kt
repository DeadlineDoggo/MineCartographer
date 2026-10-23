package minecartographer.viewport

import minecartographer.core.ChunkPos
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fixed-angle 2:1 isometric camera, looking down from the south-east.
 *
 * World point (x, y, z) projects to "iso units":
 *   u = x - z                     (screen right)
 *   v = (x + z) / 2 - y * hs      (screen down; hs = vertical scale)
 * so a block's top face is a diamond 2 units wide and 1 unit tall,
 * +x runs right-down, +z runs left-down and north (-z) points right-up.
 *
 * [zoom] is screen pixels per iso unit. The camera only translates and zooms; it never rotates.
 */
data class IsoView(
    val centerU: Double = 0.0,
    val centerV: Double = 0.0,
    val zoom: Double = 2.0,
) {
    fun toScreenX(u: Double, width: Float) = ((u - centerU) * zoom + width / 2).toFloat()
    fun toScreenY(v: Double, height: Float) = ((v - centerV) * zoom + height / 2).toFloat()
    fun screenToU(x: Float, width: Float) = centerU + (x - width / 2) / zoom
    fun screenToV(y: Float, height: Float) = centerV + (y - height / 2) / zoom

    /** Moves the content by a screen-space delta (the content follows the mouse). */
    fun panned(dx: Float, dy: Float) = copy(centerU = centerU - dx / zoom, centerV = centerV - dy / zoom)

    /** Zooms while keeping the iso point under ([ax], [ay]) fixed on screen. */
    fun zoomedAt(factor: Double, ax: Float, ay: Float, width: Float, height: Float): IsoView {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val u = screenToU(ax, width)
        val v = screenToV(ay, height)
        return IsoView(u - (ax - width / 2) / newZoom, v - (ay - height / 2) / newZoom, newZoom)
    }

    /**
     * Chunks whose tiles may overlap the screen, in back-to-front draw order.
     * Heights are only known after loading, so the vertical extent is assumed to be [minY]..[maxY].
     */
    fun visibleChunks(width: Float, height: Float, minY: Int, maxY: Int, hs: Double): List<ChunkPos> {
        // Diagonal chunk coordinates: b = cx - cz picks the screen column, a = cx + cz the depth row.
        // A chunk spans u in [16b - 16, 16b + 16] and v in [8a - (maxY + 1) * hs, 8a + 16 - minY * hs].
        val bMin = floor((screenToU(0f, width) - 16) / 16).toInt()
        val bMax = ceil((screenToU(width, width) + 16) / 16).toInt()
        val aMin = floor((screenToV(0f, height) - 16 + minY * hs) / 8).toInt()
        val aMax = ceil((screenToV(height, height) + (maxY + 1) * hs) / 8).toInt()

        val result = ArrayList<ChunkPos>()
        for (a in aMin..aMax) {
            for (b in bMin..bMax) {
                if ((a + b) and 1 != 0) continue
                result += ChunkPos((a + b) / 2, (a - b) / 2)
            }
        }
        return result
    }

    companion object {
        const val MIN_ZOOM = 0.5
        const val MAX_ZOOM = 24.0

        fun u(x: Double, z: Double) = x - z
        fun v(x: Double, y: Double, z: Double, hs: Double) = (x + z) / 2 - y * hs

        fun centeredOn(x: Double, y: Double, z: Double, hs: Double, zoom: Double) =
            IsoView(u(x, z), v(x, y, z, hs), zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))

        /** Tile resolution (pixels per iso unit) to render for a zoom level. */
        fun lodFor(zoom: Double): Int = when {
            zoom >= 6 -> 8
            zoom >= 3 -> 4
            zoom >= 1.5 -> 2
            else -> 1
        }

        val LODS = listOf(1, 2, 4, 8)
    }
}
