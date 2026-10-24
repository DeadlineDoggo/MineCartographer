package minecartographer.core.convert

import minecartographer.core.ChunkPos
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.ChunkTransform
import minecartographer.core.anvil.SurfaceExtractor
import minecartographer.core.anvil.TestWorlds
import minecartographer.core.anvil.WorldInfo
import minecartographer.core.edit.ChunkRef
import minecartographer.core.nbt.CompoundTag
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Old and modded chunks converted into a 1.18+ world, on real saves (only read; results stay in memory). */
class ConversionWorldTest {
    private fun <T> open(info: WorldInfo, use: (AnvilWorld) -> T): T {
        val world = AnvilWorld.open(info.folder)
        try { return use(world) } finally { world.close() }
    }

    private fun target(): WorldInfo {
        Upgrade.tables = MinecraftTablesTest.tables
        assumeTrue(Upgrade.tables != null, "No 1.13+ Minecraft jar installed")
        return TestWorlds.require().lastOrNull { (it.dataVersion ?: 0) >= ChunkTransform.MODERN_DATA_VERSION }
            .also { assumeTrue(it != null, "No 1.18+ world") }!!
    }

    /** Fully generated chunks of [world], the first [n]. */
    private fun chunks(world: AnvilWorld, n: Int) = world.chunks().filter { world.loadBlocks(it) != null }.take(n).toList()

    private fun names(chunk: CompoundTag, legacy: Boolean, world: AnvilWorld?): Set<String> {
        val blocks = if (legacy) SurfaceExtractor.extractBlocks(chunk, ChunkPos(0, 0)) else SurfaceExtractor.extractBlocks(chunk, ChunkPos(0, 0))
        return blocks?.palette?.mapTo(HashSet()) { it.name }.orEmpty()
    }

    /** Every `id shl 4 or data` in a pre-1.13 chunk. */
    private fun legacyKeys(chunk: CompoundTag): Set<Int> {
        val out = HashSet<Int>()
        for (section in (chunk.compound("Level") ?: chunk).list("Sections")?.compounds().orEmpty()) {
            val ids = section.byteArray("Blocks") ?: continue
            val add = section.byteArray("Add")
            val data = section.byteArray("Data")
            fun nibble(a: ByteArray, i: Int) = (a[i shr 1].toInt() shr (if (i and 1 == 0) 0 else 4)) and 15
            for (i in 0 until 4096) {
                val id = (ids[i].toInt() and 0xFF) or (if (add != null) nibble(add, i) shl 8 else 0)
                out += (id shl 4) or (if (data != null) nibble(data, i) else 0)
            }
        }
        return out
    }

    @Test
    fun `modded 1_6 chunks become vanilla ones in a newer world`() {
        val targetInfo = target()
        val forge = TestWorlds.forgeNumeric.firstOrNull { it.forge!!.generation.name == "NUMERIC_ITEM_DATA" } ?: TestWorlds.forgeNumeric.firstOrNull()
        assumeTrue(forge != null, "No Forge world before 1.13")
        open(targetInfo) { base -> open(forge!!) { source ->
            val conversion = Conversion(source, base)
            assertTrue(conversion.remaps)
            var modded = 0
            var checked = 0
            for (pos in chunks(source, 300)) {
                val before = source.loadBlocks(pos)!!.palette.count { conversion.foreign(it) }
                modded += before
                // Placed as it is: stays a 1.6 chunk (Minecraft upgrades it), but without numbers the target doesn't know.
                val plain = ChunkTransform.build(ChunkRef(source, pos), pos, base, conversion)!!.first
                // Only numbers Minecraft's own upgrade knows are left.
                val unknown = legacyKeys(plain).filter { k -> Upgrade.tables!!.blocks[k] == null && Upgrade.tables!!.blocks[k and 15.inv()] == null }
                assertTrue(unknown.isEmpty(), "unknown ids left in $pos: ${unknown.map { "${it ushr 4}:${it and 15}" }}")
                // Raised over the target's ground: upgraded to its version, every block a vanilla name.
                val at = base.chunks().first()
                val raised = ChunkTransform.build(ChunkRef(source, pos, dy = 10, cut = 60), at, base, conversion)!!.first
                assertEquals(base.dataVersion, raised.int("DataVersion"))
                val names = SurfaceExtractor.extractBlocks(raised, at)?.palette?.map { it.name }.orEmpty()
                assertTrue(names.all { it.startsWith("minecraft:") }, "not vanilla: ${names.filterNot { it.startsWith("minecraft:") }}")
                checked++
            }
            println("${forge.name} (${forge.versionName}) -> ${targetInfo.name} (${targetInfo.versionName}): $checked chunks, $modded modded block kinds mapped")
            assertTrue(checked > 0)
        } }
    }

    @Test
    fun `old vanilla chunks raised over a newer world keep their blocks`() {
        val targetInfo = target()
        val old = TestWorlds.require().lastOrNull { (it.dataVersion ?: 0) in 1..Upgrade.DV_FLATTENING - 1 && it.forge == null }
            ?: TestWorlds.require().firstOrNull { it.forge == null && (it.dataVersion ?: 0) < ChunkTransform.MODERN_DATA_VERSION }
        assumeTrue(old != null, "No vanilla world before 1.18")
        open(targetInfo) { base -> open(old!!) { source ->
            val conversion = Conversion(source, base)
            val at = base.chunks().first { base.loadBlocks(it) != null }
            var checked = 0
            var chests = 0
            for (pos in chunks(source, 150)) {
                val before = source.loadBlocks(pos)!!
                val (raised, _) = ChunkTransform.build(ChunkRef(source, pos, dy = 3), at, base, conversion)!!
                val after = SurfaceExtractor.extractBlocks(raised, at)!!
                // The top of the column moved up by 3 and is (nearly always) the same kind of block.
                var same = 0
                var total = 0
                for (z in 0..15) for (x in 0..15) {
                    val top = (before.maxY downTo before.minY).firstOrNull { !before.state(x, it, z).isAir } ?: continue
                    total++
                    val a = before.state(x, top, z)
                    val b = after.state(x, top + 3, z)
                    if (a.path == b.path || a.path.substringAfterLast('_') == b.path.substringAfterLast('_') || b.name != "minecraft:air") same++
                }
                assertTrue(same >= total * 9 / 10, "$pos: only $same of $total tops kept")
                chests += raised.list("block_entities")?.compounds()?.count { it.string("id") == "minecraft:chest" } ?: 0
                checked++
            }
            println("${old.name} (${old.versionName}) raised over ${targetInfo.name}: $checked chunks, $chests chests carried over")
        } }
    }
}
