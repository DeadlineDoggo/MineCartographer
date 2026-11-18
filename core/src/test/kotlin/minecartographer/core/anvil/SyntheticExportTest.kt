package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.LayerStack
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.IntArrayTag
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

/** Exports of small worlds made here: a broken chunk, a failed export, points of interest, entity UUIDs. */
class SyntheticExportTest {
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
    private fun world(folder: Path, region: Boolean = true): AnvilWorld {
        if (region) RegionWriter.write(
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
    fun `points of interest are kept only for chunks that stay as they were`(@TempDir tmp: Path) {
        val folder = tmp.resolve("world").also(Files::createDirectories)
        val other = ChunkPos(4, 0)
        RegionWriter.write(folder.resolve("region").resolve(RegionFile.fileName(0, 0)), mapOf(good to RawChunk.of(chunk(good)), other to RawChunk.of(chunk(other))))
        val poi = mapOf(good to RawChunk.of(poi(good)), other to RawChunk.of(poi(other)))
        RegionWriter.write(folder.resolve("poi").resolve(RegionFile.fileName(0, 0)), poi)
        val world = world(folder, region = false)
        // other is erased, good is also pasted at (2, 0).
        val stack = LayerStack.of(world).update(BaseLayer.BASE_ID) { (it as BaseLayer).withOverrides(mapOf(other to ChunkEdit.Delete)) }
            .addLayer("pasted", mapOf(ChunkPos(2, 0) to ChunkRef(world, good)))
        val out = tmp.resolve("merged")
        WorldExporter.export(stack, out, "Merged")

        val merged = AnvilWorld.open(out)
        try {
            assertTrue(poi.getValue(good).data.contentEquals(merged.readPoi(good)!!.data), "an untouched chunk keeps its own")
            assertNull(merged.readPoi(other), "an erased chunk leaves none behind")
            assertNull(merged.readPoi(ChunkPos(2, 0)), "a pasted chunk gets none: the game builds it from the blocks")
        } finally {
            merged.close(); world.close()
        }
    }

    @Test
    fun `a world without points of interest is exported without them`(@TempDir tmp: Path) {
        val world = world(tmp.resolve("world").also(Files::createDirectories))
        val out = tmp.resolve("merged")
        WorldExporter.export(LayerStack.of(world), out, "Merged")
        assertFalse(out.resolve("poi").exists())
        world.close()
    }

    /** A POI chunk with one bed in section 4. */
    private fun poi(pos: ChunkPos) = CompoundTag().apply {
        putInt("DataVersion", 3700)
        this["Sections"] = CompoundTag().apply {
            this["4"] = CompoundTag().apply {
                putByte("Valid", 1)
                this["Records"] = ListTag(10, mutableListOf(CompoundTag().apply {
                    this["pos"] = IntArrayTag(intArrayOf(pos.x * 16 + 3, 70, pos.z * 16 + 3))
                    this["type"] = StringTag("minecraft:home")
                    putInt("free_tickets", 1)
                }))
            }
        }
    }

    /** The world of [world] with a cow at [good] in its entity files, UUID 1 2 3 4. */
    private fun worldWithCow(folder: Path): AnvilWorld {
        RegionWriter.write(folder.resolve("entities").resolve(RegionFile.fileName(0, 0)), mapOf(good to RawChunk.of(CompoundTag().apply {
            putInt("DataVersion", 3700)
            this["Position"] = IntArrayTag(intArrayOf(good.x, good.z))
            this["Entities"] = ListTag(10, mutableListOf(CompoundTag().apply {
                this["id"] = StringTag("minecraft:cow")
                this["Pos"] = ListTag(6, mutableListOf(DoubleTag(3.5), DoubleTag(70.0), DoubleTag(3.5)))
                this["UUID"] = IntArrayTag(intArrayOf(1, 2, 3, 4))
            }))
        })))
        return world(folder)
    }

    private fun cowId(world: AnvilWorld, pos: ChunkPos) =
        world.readEntities(pos)!!.nbt().list("Entities")!!.compound(0).intArray("UUID")!!.toList()

    @Test
    fun `a chunk only moved keeps its entities' UUIDs, a copy gets new ones`(@TempDir tmp: Path) {
        val world = worldWithCow(tmp.resolve("world").also(Files::createDirectories))
        val to = ChunkPos(3, 0)
        val moved = LayerStack.of(world).update(BaseLayer.BASE_ID) { (it as BaseLayer).withOverrides(mapOf(good to ChunkEdit.Delete)) }
            .addLayer("pasted", mapOf(to to ChunkRef(world, good)))
        WorldExporter.export(moved, tmp.resolve("moved"), "Moved")
        val copied = LayerStack.of(world).addLayer("pasted", mapOf(to to ChunkRef(world, good)))
        WorldExporter.export(copied, tmp.resolve("copied"), "Copied")

        val a = AnvilWorld.open(tmp.resolve("moved"))
        val b = AnvilWorld.open(tmp.resolve("copied"))
        try {
            assertEquals(listOf(1, 2, 3, 4), cowId(a, to), "moved")
            assertEquals(listOf(1, 2, 3, 4), cowId(b, good), "the original stays as it was")
            assertTrue(cowId(b, to) != listOf(1, 2, 3, 4), "the copy differs")
        } finally {
            a.close(); b.close(); world.close()
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
