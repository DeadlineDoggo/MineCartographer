package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.LayerStack
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exports of a world with a broken chunk, and exports that fail half way. */
class ExportFailureTest {
    private val good = ChunkPos(0, 0)
    private val broken = ChunkPos(1, 0)

    private fun chunk(pos: ChunkPos) = CompoundTag().apply {
        putInt("DataVersion", 3700)
        putInt("xPos", pos.x)
        putInt("zPos", pos.z)
        putInt("yPos", -4)
        this["Status"] = StringTag("minecraft:full")
        this["sections"] = ListTag(10)
        this["block_entities"] = ListTag(10)
    }

    /** A 1.20.4 world with a good chunk at [good] and one whose zlib data is garbage at [broken]. */
    private fun world(folder: Path): AnvilWorld {
        RegionWriter.write(
            folder.resolve("region").resolve(RegionFile.fileName(0, 0)),
            mapOf(good to RawChunk.of(chunk(good)), broken to RawChunk(RawChunk.ZLIB, byteArrayOf(1, 2, 3, 4, 5))),
        )
        folder.resolve("level.dat").outputStream().use {
            Nbt.writeGzip(it, CompoundTag().apply {
                this["Data"] = CompoundTag().apply {
                    putInt("version", WorldInfo.ANVIL)
                    putInt("DataVersion", 3700)
                    this["LevelName"] = StringTag("Broken")
                    this["Version"] = CompoundTag().apply { this["Name"] = StringTag("1.20.4") }
                }
            })
        }
        return AnvilWorld.open(folder)
    }

    @Test
    fun `a chunk that can't be read is left out with a warning and the rest is exported`(@TempDir tmp: Path) {
        val world = world(tmp.resolve("world").also(Files::createDirectories))
        val stack = LayerStack.of(world).addLayer("pasted", mapOf(ChunkPos(2, 0) to ChunkRef(world, broken), ChunkPos(3, 0) to ChunkRef(world, good)))
        val out = tmp.resolve("merged")
        val report = WorldExporter.export(stack, out, "Merged")

        assertEquals(1, report.warnings.size, report.warnings.toString())
        assertTrue("could not be read" in report.warnings.single())
        val merged = AnvilWorld.open(out)
        try {
            assertNotNull(merged.readChunk(good))
            assertNotNull(merged.readChunk(ChunkPos(3, 0)), "the good chunk still moved")
            assertNull(merged.readChunk(ChunkPos(2, 0)), "the broken one is left out")
        } finally {
            merged.close(); world.close()
        }
    }

    @Test
    fun `a failed export leaves nothing behind`(@TempDir tmp: Path) {
        val world = world(tmp.resolve("world").also(Files::createDirectories))
        val stack = LayerStack.of(world)
        val out = tmp.resolve("merged")
        val failure = runCatching {
            WorldExporter.export(stack, out, "Merged") { step, _, _ -> if (step == "Writing region files") error("disk full") }
        }
        assertEquals("disk full", failure.exceptionOrNull()?.message)
        assertFalse(out.exists(), "the half written folder is removed")

        // An empty folder given for the export stays, still empty, so the export can be tried again.
        Files.createDirectories(out)
        runCatching { WorldExporter.export(stack, out, "Merged") { step, _, _ -> if (step == "Writing region files") error("disk full") } }
        assertTrue(out.exists() && Files.list(out).use { it.count() } == 0L)
        WorldExporter.export(stack, out, "Merged")
        assertTrue(out.resolve("level.dat").exists())
        world.close()
    }
}
