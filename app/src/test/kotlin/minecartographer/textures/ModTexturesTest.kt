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
    fun `Forge blockstates pick the variant from the data value and override textures`() {
        // Like Immersive Engineering's ore: one block, the data value picks the ore in the order the file lists them.
        val mod = zip(
            "ie.jar",
            "assets/ie/blockstates/ore.json" to """{
                "forge_marker": 1,
                "defaults": {"model": "cube_all", "textures": {"all": "ie:blocks/ore_copper"}},
                "variants": {
                    "inventory,type=copper": [{}],
                    "type": {
                        "copper": {"textures": {"all": "ie:blocks/ore_copper"}},
                        "aluminum": {"textures": {"all": "ie:blocks/ore_bauxite"}},
                        "lead": {"textures": {"all": "ie:blocks/ore_lead"}}
                    },
                    "north": {"true": {}, "false": {}}
                }
            }""",
            // models/block/cube_all.json, as the 1.12 vanilla jar has it (a model name without folder means block/).
            "assets/minecraft/models/block/cube_all.json" to """{"textures": {"particle": "#all"}, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                "faces": {"up": {"texture": "#all"}, "down": {"texture": "#all"}, "north": {"texture": "#all"}, "south": {"texture": "#all"}, "west": {"texture": "#all"}, "east": {"texture": "#all"}}}]}""",
            "assets/ie/textures/blocks/ore_copper.png" to png(0xC06040),
            "assets/ie/textures/blocks/ore_bauxite.png" to png(0xE0A080),
            "assets/ie/textures/blocks/ore_lead.png" to png(0x404070),
        )
        AssetSource(listOf(mod)).use { assets ->
            val models = BlockModels(assets)
            fun color(meta: Int) = models.mapLook(BlockState.of("ie:ore", mapOf("meta" to "$meta")))?.color?.and(0xFFFFFF)
            assertEquals(0xC06040, color(0))
            assertEquals(0xE0A080, color(1), "meta 1 is the second type, not a connection flag")
            assertEquals(0x404070, color(2))
            assertEquals(0xC06040, color(3), "past the end wraps around")
            val model = models.model(BlockState.of("ie:ore", mapOf("meta" to "2")), 0)
            assertTrue(model !== FlatModels.model(BlockState.of("ie:ore", mapOf("meta" to "2")), 0), "a real model, not the flat fallback")
            assertEquals(6, model.quads.size)
            assertEquals(0x404070, model.quads.first().texture?.averageRgb)
        }
    }

    /** Like Galacticraft's EnumBlockBasic: the name is the blockstate value, the int the data value in chunks. */
    @Suppress("unused")
    enum class TestBasic(val value: String, val meta: Int) {
        DECO("deco_block_0", 3), COPPER("ore_copper_gc", 5), TIN("ore_tin_gc", 6), ALUMINUM("ore_aluminum_gc", 7),
    }

    @Test
    fun `data values are read from enum constants in compiled classes`() {
        val name = TestBasic::class.java.name.replace('.', '/') + ".class"
        val bytes = TestBasic::class.java.classLoader.getResourceAsStream(name)!!.use { it.readAllBytes() }
        val constants = EnumConstants.read(name, bytes)
        val copper = constants.first { "ore_copper_gc" in it.strings }
        assertEquals(listOf(1, 5), copper.ints, "ordinal, then the data value")
        val index = constants.flatMap { c -> c.strings.map { it.lowercase() to c } }.groupBy({ it.first }, { it.second })
        assertEquals(mapOf("deco_block_0" to 3, "ore_copper_gc" to 5, "ore_tin_gc" to 6, "ore_aluminum_gc" to 7),
            EnumConstants.dataValues(index, listOf("deco_block_0", "ore_copper_gc", "ore_tin_gc", "ore_aluminum_gc")))
        assertNull(EnumConstants.dataValues(index, listOf("granite", "diorite")), "no enum names these")
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

        // An archive the user picks counts for that mod, whatever its metadata says.
        val picked = zip("elsewhere.jar", "assets/supp/blockstates/a.json" to "{}")
        val withPick = ModLocator.locate(AnvilWorld.open(world), manual = mapOf("supplementaries" to picked))
        assertTrue(withPick.missing.isEmpty())
        assertEquals(picked, withPick.providers["supplementaries"])
        assertTrue(picked in withPick.archives)
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
                val states = HashSet<BlockState>()
                world.chunks().take(400).forEach { pos -> world.loadBlocks(pos)?.palette?.filterTo(states) { it.name in modded } }
                // As the app does: the mods over a vanilla jar (the world's own version from 1.13 on, else the newest).
                val version = info.versionName.takeIf { (info.dataVersion ?: 0) >= 1519 }
                val sources = found.archives + listOfNotNull(TextureFiles.findClientJar(version))
                val (colored, modelled, colors) = AssetSource(sources).use { assets ->
                    val m = BlockModels(assets)
                    // As the app does for 1.6 worlds: their blocks and the mods' config files are the clues.
                    info.forge!!.legacyBlocks.takeIf { it.isNotEmpty() }?.let { legacy ->
                        val configs = extra.mapNotNull { it.parent?.resolve("config") } + listOfNotNull(info.folder.parent?.parent?.resolve("config"))
                        m.registerLegacy(legacy.values, LegacyTextures.configKeys(configs))
                    }
                    Triple(
                        modded.filter { name -> m.mapLook(states.firstOrNull { it.name == name } ?: BlockState.of(name)) != null },
                        states.filter { s -> m.model(s, 0) !== FlatModels.model(s, 0) },
                        states.mapNotNull { m.mapLook(it)?.color }.toSet(),
                    ).also { if (states.any { "meta" in it.properties }) println("  variants: " + states.filter { "meta" in it.properties }.sortedBy { it.toString() }.joinToString { "$it -> ${m.variantOf(it).properties}" }) }
                }
                println("${info.forge!!.generation.label} ${info.name}: ${found.archives.size} archives, ${found.missing.size} mods missing ${found.missing.map { it.id }}; " +
                    "${colored.size}/${modded.size} modded names colored; ${modelled.size}/${states.size} modded states with a real model, " +
                    "${colors.size} distinct colors; uncolored e.g. ${(modded - colored.toSet()).take(6)}; flat e.g. ${(states - modelled.toSet()).take(4)}")
                if (info.forge!!.generation != minecartographer.core.anvil.ForgeData.Generation.NUMERIC_ITEM_DATA && modded.isNotEmpty()) {
                    assertTrue(colored.isNotEmpty(), "No modded block of ${info.name} got a color")
                }
            } finally {
                world.close()
            }
        }
    }
}
