package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.edit.ChunkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ChunkTransform] on real 1.18+ saves (only read; the results stay in memory). */
class ChunkTransformWorldTest {
    private fun worlds() = TestWorlds.require() + TestWorlds.forgeNumeric

    @Test
    fun `an untransformed rewrite and four quarter turns give the same blocks`() {
        var total = 0
        for (info in worlds()) AnvilWorld.open(info.folder).let { world -> try {
            val chunks = world.chunks().take(200).toList()
            var checked = 0
            for (pos in chunks) {
                val raw = world.readChunk(pos)?.nbt() ?: continue
                val original = SurfaceExtractor.extractBlocks(raw, pos, world.legacy) ?: continue
                val same = SurfaceExtractor.extractBlocks(ChunkTransform.chunk(raw, pos, null, pos, 0, 0, null), pos, world.legacy)!!
                var turned = raw
                repeat(4) { turned = ChunkTransform.chunk(turned, pos, null, pos, 1, 0, null) }
                val round = SurfaceExtractor.extractBlocks(turned, pos, world.legacy)!!
                for (other in listOf(same, round)) {
                    assertEquals(original.minY, other.minY, "${info.name} $pos")
                    assertEquals(original.maxY, other.maxY, "${info.name} $pos")
                    for (y in original.minY..original.maxY) for (z in 0..15) for (x in 0..15) {
                        assertEquals(original.state(x, y, z), other.state(x, y, z), "${info.name} $pos at $x $y $z")
                    }
                }
                checked++
            }
            total += checked
            println("${info.name} (${info.versionName}): $checked chunks rewrite unchanged")
        } finally { world.close() } }
        assertTrue(total > 0, "no fully generated chunk found")
    }

    @Test
    fun `a raised chunk is drawn over the original one`() {
        for (info in worlds()) raisedOver(info)
    }

    private fun raisedOver(info: WorldInfo) {
        AnvilWorld.open(info.folder).let { world -> try {
            val pos = world.chunks().take(300).firstOrNull { world.loadBlocks(it) != null } ?: return
            val ref = ChunkRef(world, pos, turns = 1, dy = 20)
            val view = TransformedChunk(ref, pos, world)
            val raised = view.loadBlocks(pos)!!
            val original = world.loadBlocks(pos)!!
            assertEquals(minOf(original.maxY + 20, world.maxY), raised.maxY, "${info.name}: the top moved up by 20")
            val (chunk, _) = ChunkTransform.build(ref, ChunkPos(pos.x + 1, pos.z), world)!!
            assertEquals(pos.x + 1, (chunk.compound("Level") ?: chunk).int("xPos"), info.name)
        } finally { world.close() } }
    }
}
