package minecartographer.viewport

import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import minecartographer.textures.FlatModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlatViewTest {
    private val surface = ChunkSurface(ChunkPos(0, 0), IntArray(256) { 64 + (it and 15) }, IntArray(256) { 0xFF336699.toInt() })

    @Test
    fun `from above a chunk is a square at 32 units per chunk`() {
        val view = IsoView.centeredOn(16.0 * 5 + 8, 64.0, 16.0 * -3 + 8, hs = 1.0, zoom = 2.0, flat = true)
        assertEquals(32.0 * 5 + 16, view.centerU, 1e-9)
        val (x, z) = view.ground(view.centerU, view.centerV, 64.0, 1.0)
        assertEquals(16.0 * 5 + 8, x, 1e-9)
        assertEquals(16.0 * -3 + 8, z, 1e-9)
        assertTrue(ChunkPos(5, -3) in view.visibleChunks(800f, 600f, 0, 224, 1.0))
    }

    @Test
    fun `flat blocks cover every visible chunk exactly once`() {
        val view = IsoView(centerU = 123.0, centerV = -77.0, zoom = 0.7, flat = true)
        val visible = view.visibleChunks(1000f, 700f, 0, 224, 1.0).toSet()
        val area = view.visibleArea(1000f, 700f, 0, 224, 1.0)
        val fromBlocks = area.blocks(8).flatMap { VisibleArea.chunksOf(it, 8) }
        assertEquals(fromBlocks.size, fromBlocks.toSet().size)
        assertEquals(visible, fromBlocks.filter { it in area }.toSet())
    }

    @Test
    fun `zooming and the ground point keep the 2D view flat`() {
        val view = IsoView(centerU = 40.0, centerV = -12.0, zoom = 2.0, flat = true)
        assertTrue(view.zoomedAt(1.5, 10f, 10f, 800f, 600f).flat)
    }

    @Test
    fun `map and block tiles from above are square and fully covered`() {
        val settings = RenderSettings(flat = true, chunkBorders = false)
        val map = ChunkTileRenderer.render(surface, 2, settings)
        val blocks = BlockTileRenderer.render(ChunkBlocks.fromSurface(surface), 0, 2, settings.copy(mode = RenderMode.Blocks), FlatModels)
        for (tile in listOf(map, blocks)) {
            assertTrue(tile.flat)
            assertEquals(64, tile.width)
            assertEquals(64, tile.height)
            assertTrue((0 until 64).all { x -> (0 until 64).all { y -> tile.hit(x, y) } }, "every pixel of the square is drawn")
            assertEquals(32.0 * 3, tile.originU(ChunkPos(3, 1)))
            assertEquals(32.0, tile.originV(ChunkPos(3, 1), 1.0))
        }
    }
}
