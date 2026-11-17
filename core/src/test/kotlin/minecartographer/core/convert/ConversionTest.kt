package minecartographer.core.convert

import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.WorldInfo
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversionTest {
    /** A 1.12.2 Forge world without chunks whose level.dat numbers modded blocks as [ids] (name -> id). */
    private fun forgeWorld(folder: Path, ids: Map<String, Int>): AnvilWorld {
        Files.createDirectories(folder)
        folder.resolve("level.dat").outputStream().use {
            Nbt.writeGzip(it, CompoundTag().apply {
                this["Data"] = CompoundTag().apply {
                    putInt("version", WorldInfo.ANVIL)
                    putInt("DataVersion", 1343)
                    this["LevelName"] = StringTag(folder.fileName.toString())
                    this["Version"] = CompoundTag().apply { this["Name"] = StringTag("1.12.2") }
                }
                this["FML"] = CompoundTag().apply {
                    this["ModList"] = ListTag(10, mutableListOf(CompoundTag().apply {
                        this["ModId"] = StringTag("ic2")
                        this["ModVersion"] = StringTag("2.8")
                    }))
                    this["Registries"] = CompoundTag().apply {
                        this["minecraft:blocks"] = CompoundTag().apply {
                            this["ids"] = ListTag(10, ids.entries.mapTo(ArrayList()) { (name, id) ->
                                CompoundTag().apply { this["K"] = StringTag(name); putInt("V", id) }
                            })
                        }
                    }
                }
            })
        }
        return AnvilWorld.open(folder)
    }

    @Test
    fun `a modded block both worlds have gets the target's number`(@TempDir tmp: Path) {
        // Same mod, numbered differently: Forge hands out ids per world.
        val source = forgeWorld(tmp.resolve("a"), mapOf("ic2:resource" to 600, "ic2:te" to 601))
        val target = forgeWorld(tmp.resolve("b"), mapOf("ic2:te" to 600, "ic2:resource" to 715))
        try {
            val conversion = Conversion(source, target)
            assertEquals(true, conversion.remaps, "the numbering differs, so chunks are rewritten")
            assertEquals((715 shl 4) or 3, conversion.remapEntry((600 shl 4) or 3), "ic2:resource is 715 in the target, data value kept")
            assertEquals((600 shl 4) or 0, conversion.remapEntry((601 shl 4) or 0), "ic2:te is 600 in the target")
            assertEquals((1 shl 4) or 2, conversion.remapEntry((1 shl 4) or 2), "vanilla numbers stay")
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun `a block text without its closing bracket is read to the end`() {
        assertEquals(BlockState.of("minecraft:oak_log", mapOf("axis" to "y")), Conversion.parse("minecraft:oak_log[axis=y"))
        assertEquals(BlockState.of("minecraft:oak_log", mapOf("axis" to "y")), Conversion.parse("minecraft:oak_log[axis=y]"))
        assertEquals(BlockState.of("minecraft:stone"), Conversion.parse("minecraft:stone"))
    }
}
