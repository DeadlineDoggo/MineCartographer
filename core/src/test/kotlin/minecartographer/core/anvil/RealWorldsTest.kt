package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertNotNull
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

    @TestFactory
    fun `modded block ids get their names from the world's Forge data`(): List<DynamicTest> = TestWorlds.requireForge().map { info ->
        DynamicTest.dynamicTest("${info.forge!!.generation.label} (${info.name})") {
            val world = AnvilWorld.open(info.folder)
            try {
                val b = world.focusBounds!!
                val names = HashSet<String>()
                for (x in b.minX..b.maxX step maxOf(1, (b.maxX - b.minX) / 12)) for (z in b.minZ..b.maxZ step maxOf(1, (b.maxZ - b.minZ) / 12)) {
                    val blocks = world.loadBlocks(ChunkPos(x, z)) ?: continue
                    blocks.palette.mapTo(names) { it.name }
                }
                val modded = names.filter { !it.startsWith("minecraft:") && !it.startsWith("minecartographer:") }
                println("${info.name}: ${names.size} block names, ${modded.size} modded, unknown: ${"minecartographer:unknown" in names}; e.g. ${modded.take(5)}")
                assertTrue(modded.isNotEmpty(), "No modded blocks named in ${info.name}")
                assertTrue(modded.all { it in info.forge.blockNames.values }, "Names outside the world's table")
            } finally {
                world.close()
            }
        }
    }

    @TestFactory
    fun `visible blocks agree with the surface`(): List<DynamicTest> = TestWorlds.require().map { info ->
        DynamicTest.dynamicTest("${info.versionName} (${info.name})") {
            val world = AnvilWorld.open(info.folder)
            try {
                val b = world.focusBounds!!
                val cx = (b.minX + b.maxX) / 2
                val cz = (b.minZ + b.maxZ) / 2
                var checked = 0
                var layers = 0L
                for (x in cx - 4..cx + 4) for (z in cz - 4..cz + 4) {
                    val pos = ChunkPos(x, z)
                    val surface = world.loadSurface(pos) ?: continue
                    val blocks = world.loadBlocks(pos)
                    assertNotNull(blocks, "Chunk $pos has a surface but no blocks")
                    checked++
                    layers += blocks.maxY - blocks.minY + 1
                    assertTrue(blocks.minY < surface.minHeight && blocks.maxY >= surface.maxHeight, "Block range misses the surface at $pos")
                    for (lz in 0 until 16) for (lx in 0 until 16) {
                        val h = surface.height(lx, lz)
                        val top = blocks.state(lx, h, lz)
                        // Void columns sit at the lowest height with nothing in them; everything else has its top block there.
                        if (surface.color(lx, lz) ushr 24 == 0) continue
                        assertTrue(!top.isAir, "Air at the surface of $pos ($lx, $h, $lz)")
                        for (y in h + 1..blocks.maxY) {
                            val above = blocks.state(lx, y, lz)
                            assertTrue(BlockColors.look(above).kind != BlockKind.SOLID, "Solid $above above the surface of $pos ($lx, $y, $lz)")
                        }
                    }
                }
                println("${info.versionName}: $checked chunks, ${if (checked == 0) 0 else layers / checked} layers each on average")
            } finally {
                world.close()
            }
        }
    }
}
