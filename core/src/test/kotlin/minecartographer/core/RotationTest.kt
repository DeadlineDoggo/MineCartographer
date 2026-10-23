package minecartographer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RotationTest {
    @Test
    fun `four quarter turns are the identity`() {
        var p = ChunkPos(5, -3)
        repeat(4) { p = Rotation.chunk(1, p) }
        assertEquals(ChunkPos(5, -3), p)
    }

    @Test
    fun `turning by n and then by 4 - n restores the cell`() {
        for (t in 0..3) {
            val r = Rotation.chunk(t, ChunkPos(7, 2))
            assertEquals(ChunkPos(7, 2), Rotation.chunk(4 - t, r))
        }
    }

    @Test
    fun `rotated surface matches rotating each block cell`() {
        val surface = DemoWorld("w", seed = 3).loadSurface(ChunkPos(2, 1))!!
        for (t in 0..3) {
            val rotated = surface.rotated(t)
            val (cx, cz) = Rotation.cell(t, 2, 1)
            for (lz in 0 until 16) for (lx in 0 until 16) {
                val (bx, bz) = Rotation.cell(t, 2 * 16 + lx, 1 * 16 + lz)
                assertEquals(surface.height(lx, lz), rotated.height(bx - cx * 16, bz - cz * 16))
            }
        }
    }

    @Test
    fun `point and cell rotations agree`() {
        for (t in 0..3) {
            val (x, z) = Rotation.cell(t, 4, 9)
            val (px, pz) = Rotation.point(t, 4.5, 9.5) // cell center
            assertEquals(x + 0.5, px)
            assertEquals(z + 0.5, pz)
        }
        assertContentEquals(intArrayOf(0, 1, 2, 3), IntArray(4) { Rotation.normalize(it + 8) })
    }
}
