package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.LayerStack
import minecartographer.core.nbt.CompoundTag
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exports merged worlds built from real saves (see [TestWorlds]) into a temp folder. */
class ExportTest {
    private fun ChunkPos.far() = ChunkPos(x + 5000, z - 5000) // outside anything a test world has generated

    @Test
    fun `chunks of a Forge world before 1_13 can't be merged into a world that numbers blocks differently`() {
        val forgeInfo = TestWorlds.requireForge().first()
        val baseInfo = TestWorlds.require().last().takeIf { it.forge?.numericIds != true }
        assumeTrue(baseInfo != null, "Needs a base world without numeric modded ids")
        val base = AnvilWorld.open(baseInfo!!.folder)
        val modded = AnvilWorld.open(forgeInfo.folder)
        try {
            val pos = modded.chunks().first()
            val stack = LayerStack.of(base).addLayer("pasted", mapOf(pos.far() to ChunkRef(modded, pos)))
            val problems = WorldExporter.validate(stack)
            assertTrue(problems.any { "numbers its modded blocks differently" in it }, problems.toString())
        } finally {
            base.close()
            modded.close()
        }
    }

    @Test
    fun `older chunks pasted into a newer world keep their own version and move`(@TempDir tmp: Path) {
        // Forge worlds before 1.13 can't be merged into other worlds (see the test above).
        val (newInfo, oldInfo) = TestWorlds.newestAnd { (it.dataVersion ?: 0) < SurfaceExtractor.DV_FLATTENING && it.forge?.numericIds != true }
        val target = AnvilWorld.open(newInfo.folder)
        val old = AnvilWorld.open(oldInfo.folder)
        val sourcePos = old.chunks().first { old.loadSurface(it) != null }
        val targetPos = sourcePos.far()

        val stack = LayerStack.of(target).addLayer("pasted", mapOf(targetPos to ChunkRef(old, sourcePos)))
        val out = tmp.resolve("merged")
        val report = WorldExporter.export(stack, out, "Merged test")

        assertEquals(target.chunkCount + 1, report.chunks)
        assertEquals(1, report.relocated)

        val merged = AnvilWorld.open(out)
        try {
            assertEquals("Merged test", merged.name)
            assertEquals(target.chunkCount + 1, merged.chunkCount)
            val nbt = merged.readChunk(targetPos)!!.nbt()
            val level = nbt.compound("Level")!!
            assertEquals(old.dataVersion, nbt.int("DataVersion"), "the pasted chunk keeps its own DataVersion")
            assertEquals(targetPos.x, level.int("xPos"))
            assertEquals(targetPos.z, level.int("zPos"))
            level.list("TileEntities")?.compounds()?.forEach { te: CompoundTag ->
                assertTrue(te.int("x")!! shr 4 == targetPos.x && te.int("z")!! shr 4 == targetPos.z, "block entity moved with the chunk")
            }
            assertNotNull(merged.loadSurface(targetPos))
            // An untouched chunk is byte-for-byte the original.
            val same = target.chunks().first()
            assertTrue(target.readChunk(same)!!.data.contentEquals(merged.readChunk(same)!!.data))
            assertTrue(out.resolve("level.dat").exists())
        } finally {
            merged.close(); target.close(); old.close()
        }
    }

    @Test
    fun `chunks from a newer world are rejected`() {
        val (newInfo, oldInfo) = TestWorlds.newestAnd { (it.dataVersion ?: 0) < (TestWorlds.byVersion.last().dataVersion ?: 0) }
        val target = AnvilWorld.open(oldInfo.folder)
        val newer = AnvilWorld.open(newInfo.folder)
        val stack = LayerStack.of(target).addLayer("pasted", mapOf(ChunkPos(0, 0) to ChunkRef(newer, newer.chunks().first())))
        val problems = WorldExporter.validate(stack)
        assertEquals(1, problems.size)
        assertTrue("newer" in problems.single())
        target.close(); newer.close()
    }

    @Test
    fun `entity files move with their chunk`(@TempDir tmp: Path) {
        val worlds = TestWorlds.require().filter { (it.dataVersion ?: 0) >= WorldExporter.DV_ENTITY_FILES }
        assumeTrue(worlds.isNotEmpty(), "Needs a 1.17+ world")
        val target = AnvilWorld.open(worlds.last().folder)
        val source = target
        val withEntities = source.chunks().firstOrNull { source.readEntities(it)?.nbt()?.list("Entities")?.isNotEmpty() == true }
        assumeTrue(withEntities != null, "No entities in ${source.name}")
        val to = withEntities!!.far()
        val stack = LayerStack.of(target).addLayer("pasted", mapOf(to to ChunkRef(source, withEntities)))
        WorldExporter.export(stack, tmp.resolve("merged"), "x")

        val merged = AnvilWorld.open(tmp.resolve("merged"))
        val entities = merged.readEntities(to)!!.nbt()
        assertEquals(listOf(to.x, to.z), entities.intArray("Position")!!.toList())
        entities.list("Entities")!!.compounds().forEach { e ->
            val (x, _, z) = e.list("Pos")!!.doubles()
            assertEquals(to.x, Math.floorDiv(x.toInt(), 16), "entity x moved")
            assertEquals(to.z, Math.floorDiv(z.toInt(), 16), "entity z moved")
        }
        merged.close(); target.close()
    }
}
