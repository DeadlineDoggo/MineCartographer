package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.BlockKind
import minecartographer.core.anvil.WorldInfo
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import minecartographer.core.nbt.Tag
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModTexturesTest {
    private val dir = Files.createTempDirectory("mod-textures")

    private fun png(rgb: Int): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, rgb or (0xFF shl 24))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun zip(name: String, vararg entries: Pair<String, Any>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(path.outputStream()).use { out ->
            for ((entry, content) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(if (content is ByteArray) content else content.toString().toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `the first archive with an asset wins and namespaces are listed`() {
        val pack = zip("pack.zip", "assets/minecraft/textures/block/stone.png" to "pack")
        val jar = zip("client.jar", "assets/minecraft/textures/block/stone.png" to "jar", "assets/minecraft/textures/block/dirt.png" to "jar")
        val mod = zip("mod.jar", "assets/biomesoplenty/blockstates/fir_log.json" to "{}")
        AssetSource(listOf(pack, mod, jar)).use { assets ->
            assertEquals("pack", String(assets.read("assets/minecraft/textures/block/stone.png")!!))
            assertEquals("jar", String(assets.read("assets/minecraft/textures/block/dirt.png")!!))
            assertNull(assets.read("assets/minecraft/textures/block/sand.png"))
            assertEquals(setOf("minecraft", "biomesoplenty"), assets.namespaces)
        }
    }

    @Test
    fun `map colors come from the top texture, also without the vanilla jar`() {
        val mod = zip(
            "mod.jar",
            // 1.13+: blockstate -> model with a parent that lives in the (missing) vanilla jar.
            "assets/m/blockstates/fir_log.json" to """{"variants": {"axis=y": {"model": "m:block/fir_log"}}}""",
            "assets/m/models/block/fir_log.json" to """{"parent": "minecraft:block/cube_column", "textures": {"end": "m:block/fir_log_top", "side": "m:block/fir_log"}}""",
            "assets/m/textures/block/fir_log_top.png" to png(0xC08040),
            "assets/m/textures/block/fir_log.png" to png(0x402010),
            // A plant: cross models are skipped like vanilla flowers.
            "assets/m/blockstates/bush.json" to """{"variants": {"": {"model": "m:block/bush"}}}""",
            "assets/m/models/block/bush.json" to """{"parent": "minecraft:block/cross", "textures": {"cross": "m:block/bush"}}""",
            // 1.8 - 1.12 Forge blockstate: textures in "defaults"; numbered Chisel-style blocks share it.
            "assets/c/blockstates/marble.json" to """{"forge_marker": 1, "defaults": {"model": "cube_all", "textures": {"all": "c:blocks/marble"}}, "variants": {"variation": {"0": {}}}}""",
            "assets/c/textures/blocks/marble.png" to png(0xE0E0E8),
        )
        AssetSource(listOf(mod)).use { assets ->
            val models = BlockModels(assets)
            val log = models.mapLook(BlockState.of("m:fir_log", mapOf("axis" to "y")))
            assertNotNull(log)
            assertEquals(0xC08040, log.color and 0xFFFFFF, "the end (top) texture, not the side")
            assertEquals(BlockKind.SKIP, models.mapLook(BlockState.of("m:bush"))?.kind)
            assertEquals(0xE0E0E8, models.mapLook(BlockState.of("c:marble2", mapOf("meta" to "3")))?.color?.and(0xFFFFFF))
            assertNull(models.mapLook(BlockState.of("m:nothing")))
        }
    }

    @Test
    fun `mods of a world are found in its instance, matched by mod id`() {
        val instance = dir.resolve("instance").createDirectories()
        val mods = instance.resolve("mods").createDirectories()
        val world = instance.resolve("saves/Modded").createDirectories()
        fun mod(name: String, id: String, assets: Boolean) = zip("instance/mods/$name",
            "META-INF/mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"$id\"\n",
            *(if (assets) arrayOf("assets/$id/blockstates/a.json" to "{}") else emptyArray()))
        mod("bop.jar", "biomesoplenty", assets = true)
        mod("lib.jar", "geckolib", assets = false)
        mod("other.jar", "create", assets = true) // installed, but not used by this world
        val level = CompoundTag().apply {
            set("Data", CompoundTag().apply { set("LevelName", StringTag("Modded")) })
            set("fml", CompoundTag().apply {
                set("LoadingModList", ListTag(10, mutableListOf<Tag>(
                    CompoundTag().apply { set("ModId", StringTag("minecraft")); set("ModVersion", StringTag("1.20.1")) },
                    CompoundTag().apply { set("ModId", StringTag("biomesoplenty")); set("ModVersion", StringTag("18")) },
                    CompoundTag().apply { set("ModId", StringTag("geckolib")); set("ModVersion", StringTag("4")) },
                    CompoundTag().apply { set("ModId", StringTag("supplementaries")); set("ModVersion", StringTag("2")) },
                )))
            })
        }
        world.resolve("level.dat").outputStream().use { Nbt.writeGzip(it, level) }

        val found = ModLocator.locate(AnvilWorld.open(world))
        assertEquals(listOf(mods.resolve("bop.jar")), found.archives, "only archives with assets of this world's mods")
        assertEquals(listOf("supplementaries"), found.missing.map { it.id }, "geckolib is found even without assets")
    }

    @Test
    fun `mods_toml ids come from mods tables only, with the minecraft range`() {
        val (ids, range) = ModLocator.modsToml("""
            modLoader="javafml"
            [[mods]]
            modId="supplementaries"
            version="2.4"
            [[dependencies.supplementaries]]
                modId="moonlight"
                mandatory=true
            [[dependencies.supplementaries]]
                modId="minecraft"
                versionRange="[1.20.1,1.21)"
        """.trimIndent())
        assertEquals(listOf("supplementaries"), ids)
        assertEquals("[1.20.1,1.21)", range)
        // Comments after headers, single quotes and the inline-array form, all seen in real mods.
        assertEquals(listOf("skyvillages"), ModLocator.modsToml("[[mods]] #mandatory\r\nmodId=\"skyvillages\" #mandatory\r\n[[dependencies.skyvillages]] #optional\r\n    modId=\"forge\"").first)
        assertEquals(listOf("explorify"), ModLocator.modsToml("mods = [\n\t{ modId = 'explorify', version = '1.3.0' },\n]").first)
        assertTrue(ModLocator.inRange("1.20.1", "[1.20.1,1.21)"))
        assertTrue(!ModLocator.inRange("1.21", "[1.20.1,1.21)"))
        assertTrue(ModLocator.inRange("1.12.2", "[1.12.2]"))
        assertTrue(ModLocator.inRange("1.19.4", "[1.19,)"))
    }

    @Test
    fun `archives for another game version are not used`() {
        val old = ModLocator.Archive(dir.resolve("a"), setOf("worldedit"), setOf("worldedit"), modern = false, minecraft = "1.6.4")
        assertTrue(old.fits(null, namedWorld = false), "a 1.6 world has no version to compare")
        assertTrue(!old.fits("1.12.2", namedWorld = false))
        assertTrue(ModLocator.Archive(dir.resolve("ie"), setOf("immersiveengineering"), setOf("immersiveengineering"), false, "1.12").fits("1.12.2", namedWorld = false))
        assertTrue(!old.fits("1.20.1", namedWorld = true), "mcmod.info mods don't run on 1.13+")
        val modern = ModLocator.Archive(dir.resolve("b"), setOf("bop"), setOf("biomesoplenty"), modern = true, minecraft = "[1.20.1,1.21)")
        assertTrue(modern.fits("1.20.1", namedWorld = true))
        assertTrue(!modern.fits("1.19.2", namedWorld = true))
        assertTrue(!modern.fits(null, namedWorld = false))
    }

    /**
     * Measures, on real modded worlds under MINECARTOGRAPHER_WORLDS, how many modded block names found in
     * their chunks get a color from the mods' textures. Prints the numbers; skipped without the variable.
     */
    @Test
    fun `real modded worlds get map colors from their mods`() {
        val root = System.getenv("MINECARTOGRAPHER_WORLDS")?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return
        val worlds = Files.walk(root, 4).use { s -> s.filter { it.name == "level.dat" }.map { it.parent }.toList() }
            .mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
            .filter { it.forge?.playedMods?.isNotEmpty() == true && Files.isDirectory(it.folder.resolve("region")) }
            .groupBy { it.forge!!.generation }.values.map { group -> group.maxBy { it.forge!!.playedMods.size } }
        for (info in worlds) {
            val world = AnvilWorld.open(info.folder)
            try {
                // Extra mod folders (e.g. a backup of an old instance), separated by ';'; the user's own settings are not touched.
                val extra = System.getenv("MINECARTOGRAPHER_MOD_FOLDERS").orEmpty().split(';').filter { it.isNotBlank() }.map { Path.of(it) }
                val found = ModLocator.locate(world, extraFolders = extra)
                val names = HashSet<String>()
                world.chunks().take(400).forEach { pos -> world.loadBlocks(pos)?.palette?.mapTo(names) { it.name } }
                val modded = names.filter { ':' in it && !it.startsWith("minecraft:") && !it.startsWith("minecartographer:") }
                if (found.archives.isEmpty()) {
                    println("${info.forge!!.generation.label} ${info.name}: no mod archives found; missing ${found.missing.size}")
                    continue
                }
                val colored = AssetSource(found.archives).use { assets -> BlockModels(assets).let { m -> modded.filter { m.mapLook(BlockState.of(it)) != null } } }
                println("${info.forge!!.generation.label} ${info.name}: ${found.archives.size} archives, ${found.missing.size} mods missing ${found.missing.map { it.id }}; " +
                    "${colored.size}/${modded.size} modded names colored; uncolored e.g. ${(modded - colored.toSet()).take(6)}")
                if (info.forge!!.generation != minecartographer.core.anvil.ForgeData.Generation.NUMERIC_ITEM_DATA && modded.isNotEmpty()) {
                    assertTrue(colored.isNotEmpty(), "No modded block of ${info.name} got a color")
                }
            } finally {
                world.close()
            }
        }
    }
}
