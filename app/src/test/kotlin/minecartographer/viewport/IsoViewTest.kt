package minecartographer.viewport

import minecartographer.core.ChunkPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsoViewTest {
    @Test
    fun `zooming keeps the point under the cursor fixed`() {
        val view = IsoView(centerU = 40.0, centerV = -12.0, zoom = 2.0)
        val zoomed = view.zoomedAt(1.7, 300f, 120f, 800f, 600f)
        assertEquals(view.screenToU(300f, 800f), zoomed.screenToU(300f, 800f), 1e-9)
        assertEquals(view.screenToV(120f, 600f), zoomed.screenToV(120f, 600f), 1e-9)
    }

    @Test
    fun `visible chunks include the chunk at the screen center`() {
        val view = IsoView.centeredOn(8.0 + 16 * 5, 64.0, 8.0 - 16 * 3, hs = 1.0, zoom = 4.0)
        val visible = view.visibleChunks(800f, 600f, 0, 224, 1.0)
        assertTrue(ChunkPos(5, -3) in visible)
    }

    @Test
    fun `visible chunks are ordered back to front`() {
        val visible = IsoView(zoom = 1.0).visibleChunks(800f, 600f, 0, 224, 1.0)
        val depths = visible.map { it.x + it.z }
        assertEquals(depths.sorted(), depths)
    }

    @Test
    fun `blocks cover every visible chunk exactly once`() {
        val view = IsoView(centerU = 123.0, centerV = -77.0, zoom = 0.7)
        val visible = view.visibleChunks(1000f, 700f, 0, 224, 1.0).toSet()
        val area = view.visibleArea(1000f, 700f, 0, 224, 1.0)
        val fromBlocks = area.blocks(8).flatMap { VisibleArea.chunksOf(it, 8) }
        assertEquals(fromBlocks.size, fromBlocks.toSet().size)
        assertEquals(visible, fromBlocks.filter { it in area }.toSet())
    }

    @Test
    fun `block order never draws a chunk behind one it overlaps`() {
        val view = IsoView(zoom = 0.6)
        val area = view.visibleArea(900f, 600f, 0, 224, 1.0)
        val order = area.blocks(4).flatMap { VisibleArea.chunksOf(it, 4) }
        val index = order.withIndex().associate { (i, p) -> p to i }
        for (p in order) for (q in order) {
            // Tiles can only overlap when their screen columns (x - z) are at most one apart.
            val overlap = kotlin.math.abs((p.x - p.z) - (q.x - q.z)) <= 1
            if (overlap && p.x + p.z < q.x + q.z) assertTrue(index.getValue(p) < index.getValue(q), "$p must be drawn before $q")
        }
    }
}
