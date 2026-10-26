package minecartographer.core.anvil

import minecartographer.core.nbt.ByteTag
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.IntTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.LongTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import minecartographer.core.nbt.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldDetailsTest {
    private fun compound(vararg entries: Pair<String, Tag>) = CompoundTag().apply { for ((k, v) in entries) set(k, v) }

    private fun world(data: CompoundTag, forge: CompoundTag? = null): Path {
        val folder = Files.createTempDirectory("world")
        val root = compound("Data" to data)
        forge?.let { root["fml"] = it }
        Files.newOutputStream(folder.resolve("level.dat")).use { Nbt.writeGzip(it, root) }
        return folder
    }

    @Test
    fun `reads what level_dat says about the world`() {
        val folder = world(compound(
            "LevelName" to StringTag("Test"),
            "LastPlayed" to LongTag(1_700_000_000_000),
            "GameType" to IntTag(1),
            "hardcore" to ByteTag(0),
            "Difficulty" to ByteTag(2),
            "allowCommands" to ByteTag(1),
            "SpawnX" to IntTag(10), "SpawnY" to IntTag(64), "SpawnZ" to IntTag(-5),
            "Time" to LongTag(24000),
            "Version" to compound("Id" to IntTag(3465), "Name" to StringTag("1.20.1"), "Snapshot" to ByteTag(0)),
            "WorldGenSettings" to compound("seed" to LongTag(-42)),
            "Player" to compound("Pos" to ListTag(6, mutableListOf(DoubleTag(1.5), DoubleTag(70.0), DoubleTag(-3.25))), "Dimension" to StringTag("minecraft:the_nether")),
        ))
        Files.createDirectories(folder.resolve("region"))
        Files.write(folder.resolve("region").resolve("r.0.0.mca"), ByteArray(8192))
        Files.createDirectories(folder.resolve("DIM-1").resolve("region"))
        Files.write(folder.resolve("DIM-1").resolve("region").resolve("r.-1.0.mca"), ByteArray(4096))
        Files.createDirectories(folder.resolve("datapacks").resolve("pack1"))

        val d = WorldDetails.read(folder)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), d.lastPlayed)
        assertEquals("Creative", d.gameMode)
        assertEquals("Normal", d.difficulty)
        assertEquals(false, d.hardcore)
        assertEquals(true, d.cheats)
        assertEquals(-42L, d.seed)
        assertEquals(Triple(10, 64, -5), d.spawn)
        assertEquals(24000L, d.gameTicks)
        assertEquals(3465, d.versionId)
        assertEquals(WorldDetails.Player(1.5, 70.0, -3.25, "minecraft:the_nether"), d.player)
        assertEquals(listOf("pack1"), d.datapacks)
        assertEquals(listOf("Overworld", "Nether"), d.dimensions.map { it.name })
        assertEquals(listOf(1, 1), d.dimensions.map { it.regionFiles })
        assertEquals(8192L + 4096L, d.dimensions.sumOf { it.bytes })
        assertTrue(d.totalBytes >= 8192 + 4096)
        assertNotNull(d.newestRegion)
    }

    @Test
    fun `older worlds leave the fields they lack empty and Forge lists its loader`() {
        val folder = world(
            compound("LevelName" to StringTag("Old"), "RandomSeed" to LongTag(7)),
            forge = compound("LoadingModList" to ListTag(10, mutableListOf<Tag>(
                compound("ModId" to StringTag("forge"), "ModVersion" to StringTag("47.2.0")),
                compound("ModId" to StringTag("minecraft"), "ModVersion" to StringTag("1.20.1")),
                compound("ModId" to StringTag("jei"), "ModVersion" to StringTag("15.0")),
            ))),
        )
        val d = WorldDetails.read(folder)
        assertEquals(7L, d.seed)
        assertNull(d.versionId)
        assertNull(d.player)
        assertNull(d.difficulty)
        assertEquals(listOf("forge" to "47.2.0", "minecraft" to "1.20.1"), d.loaderEntries.map { it.id to it.version })
        assertTrue(d.dimensions.isEmpty())
    }

    @Test
    fun `real worlds`() {
        for (info in TestWorlds.require() + TestWorlds.forgeNumeric) {
            val start = System.nanoTime()
            val d = WorldDetails.read(info.folder)
            val ms = (System.nanoTime() - start) / 1_000_000
            println("${info.versionName} (${info.name}): ${ms} ms, ${d.totalBytes / (1 shl 20)} MB in ${d.files} files, played ${d.lastPlayed}, ${d.gameMode}, seed ${d.seed}, " +
                "dimensions ${d.dimensions.map { it.name }}, loader ${d.loaderEntries.map { it.id + " " + it.version }}, player ${d.player}")
            assertTrue(d.totalBytes > 0)
            assertTrue(d.dimensions.any { it.name == "Overworld" })
        }
    }
}
