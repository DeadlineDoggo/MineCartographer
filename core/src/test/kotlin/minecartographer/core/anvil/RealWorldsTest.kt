package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue

/** Reads chunk surfaces from one real world per game version (see [TestWorlds]). */
class RealWorldsTest {
    @TestFactory
    fun `reads chunk surfaces`(): List<DynamicTest> = TestWorlds.require().map { info ->
        DynamicTest.dynamicTest("${info.versionName} (${info.name})") {
            val world = AnvilWorld.open(info.folder)
            try {
                assertTrue(world.chunkCount > 0, "No chunks found")
                // Sample chunks around the middle of the world.
                val b = world.focusBounds!!
                val cx = (b.minX + b.maxX) / 2
                val cz = (b.minZ + b.maxZ) / 2
                var tried = 0
                var read = 0
                for (x in cx - 6..cx + 6) for (z in cz - 6..cz + 6) {
                    val pos = ChunkPos(x, z)
                    if (!world.hasChunk(pos)) continue
                    tried++
                    val surface = world.loadSurface(pos)
                    if (surface == null) {
                        // Only partly generated chunks (e.g. status "structure_starts") may have no surface.
                        val nbt = world.readChunk(pos)!!.nbt()
                        val status = (nbt.compound("Level") ?: nbt).string("Status")
                        assertTrue(status != null && "full" !in status, "Complete chunk $pos has no surface")
                        continue
                    }
                    read++
                    assertTrue(surface.minHeight >= world.minY - 1 && surface.maxHeight <= world.maxY, "Heights out of range at $pos")
                    assertTrue(surface.colors.any { it ushr 24 != 0 }, "All-transparent surface at $pos")
                }
                println("${info.versionName}: ${world.chunkCount} chunks, $read of $tried sampled chunks complete")
            } finally {
                world.close()
            }
        }
    }
}
