package minecartographer.viewport

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkPos
import minecartographer.textures.FlatModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnknownWallTest {
    /** A slab of stone from y 10 to 20 with nothing known under it. */
    private fun chunk(): ChunkBlocks {
        val blocks = CharArray(11 * 256) { 1.toChar() }
        return ChunkBlocks(ChunkPos(0, 0), 10, 20, arrayOf(BlockState.AIR, BlockState.of("minecraft:stone")), blocks, ByteArray(256))
    }

    @Test
    fun `the sides of a chunk that ends above the bottom go on as a checkerboard`() {
        val plain = BlockTileRenderer.render(chunk(), 0, 1, RenderSettings(), FlatModels)
        val walled = BlockTileRenderer.render(chunk(), 0, 1, RenderSettings(), FlatModels, unknownBelow = 32)
        assertEquals(plain.height + 32, walled.height)
        val pixels = assertNotNull(walled.pixels)
        // Both tones of the checkerboard are there, below the stone.
        val below = pixels.drop(plain.height * walled.width).filter { it ushr 24 == 255 }
        assertTrue(below.map { it and 0xFF }.toSet().size >= 2)
    }
}
