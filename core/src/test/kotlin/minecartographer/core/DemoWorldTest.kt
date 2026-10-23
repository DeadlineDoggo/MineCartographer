package minecartographer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoWorldTest {
    @Test
    fun `same seed produces the same chunk`() {
        val a = DemoWorld("a", seed = 42).loadSurface(ChunkPos(3, -5))!!
        val b = DemoWorld("b", seed = 42).loadSurface(ChunkPos(3, -5))!!
        assertContentEquals(a.heights, b.heights)
        assertContentEquals(a.colors, b.colors)
    }

    @Test
    fun `chunks outside bounds do not exist`() {
        val world = DemoWorld("w", seed = 1, radiusChunks = 4)
        assertNull(world.loadSurface(ChunkPos(4, 0)))
        assertTrue(world.hasChunk(ChunkPos(-4, 3)))
    }

    @Test
    fun `heights stay inside the build range`() {
        val world = DemoWorld("w", seed = 7)
        for (x in -10..10) for (z in -10..10) {
            val s = world.loadSurface(ChunkPos(x, z))!!
            assertTrue(s.minHeight >= world.minY && s.maxHeight <= world.maxY)
        }
    }
}
