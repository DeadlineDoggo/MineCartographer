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
}
