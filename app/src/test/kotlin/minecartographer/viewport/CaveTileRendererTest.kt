package minecartographer.viewport

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkPos
import minecartographer.textures.BlockModel
import minecartographer.textures.BlockModelSource
import minecartographer.textures.Dir
import minecartographer.textures.Quad
import minecartographer.textures.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaveTileRendererTest {
    private val stone = BlockState.of("minecraft:stone")
    private val grass = BlockState.of("minecraft:grass_block")

    /** Ground up to y 20 with a hollow at x, z 4..10 and y 5..9, closed by stone above it. */
    private fun chunk(withCave: Boolean = true): ChunkBlocks {
        val palette = arrayOf(BlockState.AIR, stone, grass)
        val blocks = CharArray(32 * 256)
        for (y in 0..20) for (z in 0..15) for (x in 0..15) {
            val cave = withCave && x in 4..10 && z in 4..10 && y in 5..9
            if (!cave) blocks[(y shl 8) or (z shl 4) or x] = (if (y == 20) 2 else 1).toChar()
        }
        return ChunkBlocks(ChunkPos(0, 0), 0, 31, palette, blocks, ByteArray(256))
    }

    private fun alpha(pixel: Int) = pixel ushr 24

    @Test
    fun `from above the floor of a cave shows through the ground`() {
        val tile = CaveTileRenderer.render(chunk(), 1, RenderSettings(cave = true, flat = true, caveSurface = true))
        val pixels = assertNotNull(tile.pixels)
        // Column (6, 6) is over the cave and (1, 1) is not: the cave floor is solid, the ground only a faint trace.
        assertEquals(255, alpha(pixels[(6 * 2) * tile.width + 6 * 2]))
        assertEquals(56, alpha(pixels[(1 * 2) * tile.width + 1 * 2]))
    }

    @Test
    fun `a chunk without caves is just its surface, or nothing`() {
        for (flat in listOf(false, true)) {
            val shown = assertNotNull(CaveTileRenderer.render(chunk(withCave = false), 1, RenderSettings(cave = true, flat = flat, caveSurface = true)).pixels)
            assertTrue(shown.any { alpha(it) == 56 } && shown.none { alpha(it) == 255 }, "flat=$flat")
            val hidden = assertNotNull(CaveTileRenderer.render(chunk(withCave = false), 1, RenderSettings(cave = true, flat = flat)).pixels)
            assertTrue(hidden.all { it == 0 }, "flat=$flat")
        }
    }

    @Test
    fun `in the isometric view the walls of the cave are seen through the ground`() {
        val tile = CaveTileRenderer.render(chunk(), 2, RenderSettings(cave = true, caveSurface = true))
        val pixels = assertNotNull(tile.pixels)
        val solid = pixels.count { alpha(it) == 255 }
        assertTrue(solid > 100, "cave faces drawn: $solid")
        val bare = assertNotNull(CaveTileRenderer.render(chunk(), 2, RenderSettings(cave = true)).pixels)
        // Without the surface only the cave is left: the same solid pixels, and nothing faint around them.
        assertEquals(solid, bare.count { alpha(it) != 0 })
        assertTrue(pixels.count { alpha(it) == 56 } > solid)
    }

    @Test
    fun `without the surface only the caves are drawn`() {
        for (flat in listOf(false, true)) {
            val tile = CaveTileRenderer.render(chunk(), 2, RenderSettings(cave = true, flat = flat))
            val pixels = assertNotNull(tile.pixels)
            assertTrue(pixels.any { alpha(it) == 255 }, "flat=$flat")
            assertTrue(pixels.none { alpha(it) != 0 && alpha(it) != 255 }, "flat=$flat")
            val bare = CaveTileRenderer.render(chunk(withCave = false), 2, RenderSettings(cave = true, flat = flat))
            assertTrue(assertNotNull(bare.pixels).all { it == 0 }, "flat=$flat")
        }
    }

    @Test
    fun `with block models the floor of a cave has the texture`() {
        // A stone whose top is a texture of a red and a blue half.
        val texture = Texture(16, listOf(IntArray(256) { if (it % 16 < 8) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }), true)
        val up = Quad(floatArrayOf(0f, 1f, 0f, 1f, 1f, 0f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 16f, 0f, 0f, 16f), texture, 0, -1, true, Dir.UP, 0f, 1f, 0f)
        val models = object : BlockModelSource {
            override fun model(state: BlockState, turns: Int) = BlockModel(listOf(up), occludes = true, fluid = false)
        }
        val settings = RenderSettings(cave = true, flat = true)
        val plain = assertNotNull(CaveTileRenderer.render(chunk(), 2, settings).pixels)
        val textured = assertNotNull(CaveTileRenderer.render(chunk(), 2, settings, models).pixels)
        // The floor of the cave, column (6, 6): one color, or the two halves of the texture.
        fun colors(pixels: IntArray) = (0 until 4).flatMap { j -> (0 until 4).map { i -> pixels[(6 * 4 + j) * 64 + 6 * 4 + i] } }.toSet()
        assertEquals(1, colors(plain).size)
        assertEquals(2, colors(textured).size)
        // And in the isometric view, where the faces without a texture of their own fall back to the block's color.
        val iso = assertNotNull(CaveTileRenderer.render(chunk(), 2, RenderSettings(cave = true), models, turns = 1).pixels)
        assertTrue(iso.count { alpha(it) == 255 } > 100)
    }
}
