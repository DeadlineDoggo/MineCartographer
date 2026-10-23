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
        val area = visibleArea(width, height, minY, maxY, hs)
        val result = ArrayList<ChunkPos>()
        for (a in area.aMin..area.aMax) {
            for (b in area.bMin..area.bMax) {
                if ((a + b) and 1 != 0) continue
                result += ChunkPos((a + b) / 2, (a - b) / 2)
            }
        }
        return result
    }

    /** The area [visibleChunks] covers, for drawing it block by block. */
    fun visibleArea(width: Float, height: Float, minY: Int, maxY: Int, hs: Double): VisibleArea {
        // Diagonal chunk coordinates: b = cx - cz picks the screen column, a = cx + cz the depth row.
        // A chunk spans u in [16b - 16, 16b + 16] and v in [8a - (maxY + 1) * hs, 8a + 16 - minY * hs].
        return VisibleArea(
            aMin = floor((screenToV(0f, height) - 16 + minY * hs) / 8).toInt(),
            aMax = ceil((screenToV(height, height) + (maxY + 1) * hs) / 8).toInt(),
            bMin = floor((screenToU(0f, width) - 16) / 16).toInt(),
            bMax = ceil((screenToU(width, width) + 16) / 16).toInt(),
        )
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

/** Chunks with x + z in [aMin]..[aMax] and x - z in [bMin]..[bMax]: a screen-aligned rectangle of chunks. */
class VisibleArea(val aMin: Int, val aMax: Int, val bMin: Int, val bMax: Int) {
    operator fun contains(pos: ChunkPos): Boolean {
        val a = pos.x + pos.z
        val b = pos.x - pos.z
        return a in aMin..aMax && b in bMin..bMax
    }

    /**
     * Blocks of [size] x [size] chunks that touch the area, as (block x, block z), in a back-to-front order:
     * by bx + bz. Chunks of two blocks can only overlap on screen when one block is at or beyond the
     * other on both axes, so drawing blocks in this order and each block's chunks by depth gives the
     * same picture as drawing every chunk by depth.
     */
    fun blocks(size: Int): List<ChunkPos> {
        val bxMin = Math.floorDiv(Math.floorDiv(aMin + bMin, 2), size)
        val bxMax = Math.floorDiv(Math.floorDiv(aMax + bMax + 1, 2), size)
        val bzMin = Math.floorDiv(Math.floorDiv(aMin - bMax, 2), size)
        val bzMax = Math.floorDiv(Math.floorDiv(aMax - bMin + 1, 2), size)
        val result = ArrayList<ChunkPos>()
        for (sum in bxMin + bzMin..bxMax + bzMax) {
            for (bx in maxOf(bxMin, sum - bzMax)..minOf(bxMax, sum - bzMin)) {
                val bz = sum - bx
                // The block's chunks span a in [sum * size, sum * size + 2 size - 2], b likewise around (bx - bz) * size.
                val a0 = sum * size
                val b0 = (bx - bz) * size
                if (a0 > aMax || a0 + 2 * size - 2 < aMin || b0 - size + 1 > bMax || b0 + size - 1 < bMin) continue
                result += ChunkPos(bx, bz)
            }
        }
        return result
    }

    companion object {
        /** The chunks of a block, back to front. */
        fun chunksOf(block: ChunkPos, size: Int): List<ChunkPos> = buildList(size * size) {
            for (d in 0..2 * size - 2) {
                for (lx in maxOf(0, d - size + 1)..minOf(size - 1, d)) add(ChunkPos(block.x * size + lx, block.z * size + d - lx))
            }
        }
    }
}
