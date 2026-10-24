package minecartographer.core.convert

import minecartographer.core.BlockState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the upgrade tables out of an installed Minecraft jar (skipped without one; the jar is only read). */
class MinecraftTablesTest {
    companion object {
        /** The newest 1.13+ client jar of the vanilla launcher, if installed. */
        fun jar(): Path? {
            val versions = System.getenv("APPDATA")?.let { Path.of(it, ".minecraft", "versions") }?.takeIf { it.isDirectory() } ?: return null
            return Files.list(versions).use { dirs ->
                dirs.map { it.resolve("${it.name}.jar") }.filter { it.exists() && Regex("""1\.(1[3-9]|2\d)(\.\d+)?""").matches(it.parent.name) }.toList()
            }.maxByOrNull { it.parent.name.split('.').map { p -> p.toIntOrNull() ?: 0 }.let { v -> v[1] * 100 + v.getOrElse(2) { 0 } } }
        }

        val tables: MinecraftTables? by lazy { jar()?.let(MinecraftTables::read) }
    }

    @Test
    fun `tables come out of the jar`() {
        val t = tables
        assumeTrue(t != null, "No 1.13+ Minecraft jar installed")
        t!!
        println("${jar()}: ${t.blocks.size} blocks, ${t.itemIds.size} item ids, ${t.itemFlattening.size} items, " +
            "${t.blockEntityIds.size} block entities, ${t.entityIds.size} entities, ${t.biomeIds.size} biomes, " +
            "${t.biomeRenames113.size} + ${t.biomeRenames118.size} biome renames")
        assertEquals(BlockState.of("minecraft:granite"), t.blocks[(1 shl 4) or 1])
        assertEquals(BlockState.of("minecraft:oak_log", mapOf("axis" to "y")), t.blocks[17 shl 4])
        assertEquals("minecraft:stone", t.itemIds[1])
        assertEquals("minecraft:granite", t.itemFlattening["minecraft:stone.1"])
        assertEquals("minecraft:chest", t.blockEntityIds["Chest"])
        assertEquals("minecraft:creeper", t.entityIds["Creeper"])
        assertEquals("minecraft:plains", t.biomeIds[1])
        assertEquals("minecraft:mountains", t.biomeRenames113["minecraft:extreme_hills"])
        assertEquals("minecraft:windswept_hills", t.biomeRenames118["minecraft:mountains"])
        assertTrue(t.legacyKeys.getValue("minecraft:oak_planks") == (5 shl 4))
    }
}
