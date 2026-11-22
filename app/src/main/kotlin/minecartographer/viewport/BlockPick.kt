package minecartographer.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkPos
import minecartographer.core.Rotation
import minecartographer.ui.EditorTheme
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The block at world position ([x], [y], [z]) and what is there. */
data class BlockHit(val x: Int, val y: Int, val z: Int, val state: BlockState)

object BlockPick {
    /** Steps along the line of sight, in blocks of height: fine enough not to miss a block's corner by more than a hair. */
    private const val STEP = 1.0 / 32

    /**
     * The first block the line of sight through the view point ([u], [v]) meets in the chunk at [pos] (its real
     * position), whose blocks as the map draws them are [blocks] (in the chunk's local coordinates, whatever world
     * they were read from). [rotation] is the view's turn, [hs] the height scale. Blocks of other chunks are not
     * looked at: the caller already knows from the drawn tiles which chunk is seen there. Null when the line passes
     * through the chunk without meeting a block.
     *
     * With [cave], what the Cave view draws there (see [CaveTileRenderer]): the line goes through the ground, and once
     * it is in a cave (air under the column's roof) the first solid block it meets. Where it finds no cave, the first
     * solid block, the ground the Cave view may show as a faint trace.
     */
    fun inChunk(blocks: ChunkBlocks, pos: ChunkPos, view: IsoView, hs: Double, rotation: Int, u: Double, v: Double, cave: Boolean = false): BlockHit? {
        val x0 = pos.x * 16
        val z0 = pos.z * 16
        val unrotate = 4 - rotation
        val roofs = IntArray(256) { Int.MIN_VALUE }
        // The column's highest ground block (trees are no roof), as the Cave view finds it.
        fun roof(lx: Int, lz: Int): Int {
            val i = lz * 16 + lx
            if (roofs[i] == Int.MIN_VALUE) {
                var y = blocks.maxY
                while (y >= blocks.minY && blocks.state(lx, y, lz).let { !caveSolid(it) || caveGrowth(it) }) y--
                roofs[i] = y
            }
            return roofs[i]
        }
        var ground: BlockHit? = null
        var inCave = false
        // The answer once the block at (vx, y, vz) in view space is met, or null to go on.
        fun at(vx: Double, vz: Double, y: Int): BlockHit? {
            val (wx, wz) = Rotation.point(unrotate, vx, vz)
            val bx = floor(wx).toInt()
            val bz = floor(wz).toInt()
            val lx = bx - x0
            val lz = bz - z0
            if (lx !in 0..15 || lz !in 0..15) return null
            val state = blocks.state(lx, y, lz)
            if (!cave) return if (state.isAir) null else BlockHit(bx, y, bz, state)
            val solid = caveSolid(state)
            if (inCave) return if (solid) BlockHit(bx, y, bz, state) else null
            if (solid) { if (ground == null) ground = BlockHit(bx, y, bz, state) }
            else if (!caveWater(state) && y < roof(lx, lz)) inCave = true
            return null
        }
        if (view.flat || hs <= 1e-9) {
            // From above: the top block of the column.
            val (vx, vz) = view.ground(u, v, 0.0, hs)
            for (y in blocks.maxY downTo blocks.minY) at(vx, vz, y)?.let { return it }
            return ground
        }
        // Along the line, x = v + y hs + u / 2 and z = v + y hs - u / 2 (view space): the part over the chunk is
        // where both are inside its 16 blocks, walked from the top (nearest the eye) down.
        val chunk = Rotation.chunk(rotation, pos)
        val cx = chunk.x * 16.0
        val cz = chunk.z * 16.0
        val lo = max(max((cx - v - u / 2) / hs, (cz - v + u / 2) / hs), blocks.minY.toDouble())
        val hi = min(min((cx + 16 - v - u / 2) / hs, (cz + 16 - v + u / 2) / hs), blocks.maxY + 1.0)
        var y = hi - STEP / 2
        while (y > lo) {
            val s = v + y * hs
            at(s + u / 2, s - u / 2, floor(y).toInt())?.let { return it }
            y -= STEP
        }
        return ground
    }
}

/**
 * Outlines the block at world position ([x], [y], [z]) on the map: the top face lightly filled, the outline of what
 * the view shows of the cube (top and the two near sides) in the accent color over a dark edge. [faint]: a thin
 * white outline, for the block under the pointer.
 */
internal fun DrawScope.drawBlockOutline(x: Int, y: Int, z: Int, view: IsoView, hs: Double, rotation: Int, faint: Boolean = false) {
    val (cx, cz) = Rotation.cell(rotation, x, z)
    val w = size.width
    val h = size.height
    fun p(vx: Int, vy: Int, vz: Int): Offset = if (view.flat) Offset(view.toScreenX(2.0 * vx, w), view.toScreenY(2.0 * vz, h))
        else Offset(view.toScreenX(IsoView.u(vx.toDouble(), vz.toDouble()), w), view.toScreenY(IsoView.v(vx.toDouble(), vy.toDouble(), vz.toDouble(), hs), h))
    val top = y + 1
    val edge = Color(if (faint) 0x90000000.toInt() else 0xE0000000.toInt())
    val accent = if (faint) Color.White.copy(alpha = 0.85f) else EditorTheme.accent
    val fill = accent.copy(alpha = if (faint) 0.12f else 0.25f)
    if (view.flat) {
        val square = Path().apply {
            p(cx, top, cz).let { moveTo(it.x, it.y) }
            p(cx + 1, top, cz).let { lineTo(it.x, it.y) }
            p(cx + 1, top, cz + 1).let { lineTo(it.x, it.y) }
            p(cx, top, cz + 1).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(square, fill)
        drawPath(square, edge, style = Stroke(if (faint) 2.5f else 3.5f))
        drawPath(square, accent, style = Stroke(if (faint) 1.2f else 1.8f))
        return
    }
    // The camera looks from +x +z (view space): the top face and the +x and +z sides show.
    val topFace = Path().apply {
        p(cx, top, cz).let { moveTo(it.x, it.y) }
        p(cx + 1, top, cz).let { lineTo(it.x, it.y) }
        p(cx + 1, top, cz + 1).let { lineTo(it.x, it.y) }
        p(cx, top, cz + 1).let { lineTo(it.x, it.y) }
        close()
    }
    val outline = Path().apply {
        p(cx, top, cz).let { moveTo(it.x, it.y) }
        p(cx + 1, top, cz).let { lineTo(it.x, it.y) }
        p(cx + 1, y, cz).let { lineTo(it.x, it.y) }
        p(cx + 1, y, cz + 1).let { lineTo(it.x, it.y) }
        p(cx, y, cz + 1).let { lineTo(it.x, it.y) }
        p(cx, top, cz + 1).let { lineTo(it.x, it.y) }
        close()
    }
    val near = p(cx + 1, top, cz + 1)
    val inner = Path().apply {
        moveTo(near.x, near.y); p(cx + 1, top, cz).let { lineTo(it.x, it.y) }
        moveTo(near.x, near.y); p(cx, top, cz + 1).let { lineTo(it.x, it.y) }
        moveTo(near.x, near.y); p(cx + 1, y, cz + 1).let { lineTo(it.x, it.y) }
    }
    drawPath(topFace, fill)
    drawPath(outline, edge, style = Stroke(if (faint) 2.5f else 3.5f))
    drawPath(inner, edge, style = Stroke(if (faint) 1.8f else 2.5f))
    drawPath(outline, accent, style = Stroke(if (faint) 1.2f else 1.8f))
    drawPath(inner, accent, style = Stroke(if (faint) 0.8f else 1.2f))
}
