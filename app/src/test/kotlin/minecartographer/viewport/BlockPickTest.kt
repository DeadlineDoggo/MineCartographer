package minecartographer.viewport

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkPos
import minecartographer.core.Rotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockPickTest {
    private val pos = ChunkPos(2, -3)
    private val stone = BlockState.of("minecraft:stone")
    private val gold = BlockState.of("minecraft:gold_block")

    /** Ground of stone from 0 to 10, and a gold tower from 11 to 20 at local (8, 8) when [tower]. */
    private fun chunk(tower: Boolean = false, empty: Boolean = false): ChunkBlocks {
        val minY = 0
        val maxY = 20
        val blocks = CharArray((maxY - minY + 1) * 256)
        if (!empty) for (y in 0..10) for (i in 0 until 256) blocks[(y shl 8) or i] = 1.toChar()
        if (tower) for (y in 11..20) blocks[(y shl 8) or (8 shl 4) or 8] = 2.toChar()
        return ChunkBlocks(pos, minY, maxY, arrayOf(BlockState.AIR, stone, gold), blocks, ByteArray(256))
    }

    /** The view point (u, v) of the world point (x, y, z) seen with [rotation] turns. */
    private fun at(x: Double, y: Double, z: Double, rotation: Int, hs: Double): Pair<Double, Double> {
        val (rx, rz) = Rotation.point(rotation, x, z)
        return IsoView.u(rx, rz) to IsoView.v(rx, y, rz, hs)
    }

    /** The view point of the point ([vx], [y], [vz]) given in view space (already turned). */
    private fun atView(vx: Double, y: Double, vz: Double, hs: Double) = IsoView.u(vx, vz) to IsoView.v(vx, y, vz, hs)

    @Test
    fun `the middle of a top face picks that block in every rotation`() {
        val blocks = chunk()
        for (hs in listOf(1.0, 0.5, 1.5)) for (rotation in 0..3) {
            for ((lx, lz) in listOf(0 to 0, 7 to 9, 15 to 15, 3 to 14, 15 to 0)) {
                val x = pos.x * 16 + lx
                val z = pos.z * 16 + lz
                val (u, v) = at(x + 0.5, 11.0, z + 0.5, rotation, hs)
                val hit = BlockPick.inChunk(blocks, pos, IsoView(), hs, rotation, u, v)
                assertEquals(BlockHit(x, 10, z, stone), hit, "rotation $rotation, hs $hs, local $lx $lz")
            }
        }
    }

    @Test
    fun `a tower hides the ground behind it and its side can be picked`() {
        val blocks = chunk(tower = true)
        val x = pos.x * 16 + 8
        val z = pos.z * 16 + 8
        for (rotation in 0..3) {
            val (cx, cz) = Rotation.cell(rotation, x, z)
            // The middle of the tower's side facing +x in view space, at height 15.
            val (su, sv) = atView(cx + 1.0, 15.5, cz + 0.5, 1.0)
            assertEquals(BlockHit(x, 15, z, gold), BlockPick.inChunk(blocks, pos, IsoView(), 1.0, rotation, su, sv), "side, rotation $rotation")
            // The ground block right behind the tower (one block further from the eye on both axes) is hidden by it.
            val (gu, gv) = atView(cx - 0.5, 11.0, cz - 0.5, 1.0)
            assertEquals(BlockHit(x, 12, z, gold), BlockPick.inChunk(blocks, pos, IsoView(), 1.0, rotation, gu, gv), "behind, rotation $rotation")
            // The top of the tower.
            val (tu, tv) = atView(cx + 0.5, 21.0, cz + 0.5, 1.0)
            assertEquals(BlockHit(x, 20, z, gold), BlockPick.inChunk(blocks, pos, IsoView(), 1.0, rotation, tu, tv), "top, rotation $rotation")
        }
    }

    @Test
    fun `from above the top block of the column is picked`() {
        val blocks = chunk(tower = true)
        val flat = IsoView(flat = true)
        for (rotation in 0..3) {
            for ((lx, lz, top) in listOf(Triple(8, 8, 20), Triple(2, 13, 10))) {
                val x = pos.x * 16 + lx
                val z = pos.z * 16 + lz
                val (rx, rz) = Rotation.point(rotation, x + 0.5, z + 0.5)
                val hit = BlockPick.inChunk(blocks, pos, flat, 1.0, rotation, 2 * rx, 2 * rz)
                assertEquals(x to z, hit?.let { it.x to it.z }, "rotation $rotation")
                assertEquals(top, hit?.y)
            }
        }
    }

    @Test
    fun `nothing is picked in an empty chunk or beside the chunk`() {
        val (u, v) = at(pos.x * 16 + 4.5, 11.0, pos.z * 16 + 4.5, 0, 1.0)
        assertNull(BlockPick.inChunk(chunk(empty = true), pos, IsoView(), 1.0, 0, u, v))
        // A line of sight that never crosses the chunk: its ground point is far to the side.
        val (fu, fv) = at(pos.x * 16 + 200.5, 11.0, pos.z * 16 - 200.5, 0, 1.0)
        assertNull(BlockPick.inChunk(chunk(), pos, IsoView(), 1.0, 0, fu, fv))
    }
}
