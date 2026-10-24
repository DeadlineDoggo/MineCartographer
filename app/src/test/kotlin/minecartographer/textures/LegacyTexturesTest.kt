package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.BlockKind
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import minecartographer.core.anvil.ForgeData
import minecartographer.core.anvil.WorldInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stands in for a 1.6 block class: its constants name its icons, like `registerIcon("t:leavesfirfancy")` would. */
@Suppress("unused")
class TestLeavesItem {
    val icons = arrayOf("t:leavesfirfancy", "t:leavesfirfast", "t:leavesredwoodfancy", "t:leavesredwoodfast")
}

class LegacyTexturesTest {
    private val dir = Files.createTempDirectory("legacy-textures")

    private fun png(rgb: Int): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, rgb or (0xFF shl 24))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((entry, content) in entries) { out.putNextEntry(ZipEntry(entry)); out.write(content); out.closeEntry() }
        }
        return path
    }

    private val itemClass = TestLeavesItem::class.java.name
    private val classBytes = TestLeavesItem::class.java.classLoader
        .getResourceAsStream(itemClass.replace('.', '/') + ".class")!!.use { it.readAllBytes() }

    private fun mod() = zip(
        "mod.jar",
        "mcmod.info" to """[{"modid": "TestMod", "mcversion": "1.6.4"}]""".toByteArray(),
        itemClass.replace('.', '/') + ".class" to classBytes,
        "assets/t/textures/blocks/oreCopper.png" to png(0xC06040),
        "assets/t/textures/blocks/leavesfirfancy.png" to png(0x305030),
        "assets/t/textures/blocks/leavesfirfast.png" to png(0x000000),
        "assets/t/textures/blocks/leavesredwoodfancy.png" to png(0x406020),
        "assets/t/textures/blocks/leavesredwoodfast.png" to png(0x000000),
        "assets/t/textures/blocks/saplingfir.png" to png(0x00FF00),
    )

    @Test
    fun `a png named like the block wins, icons of the item class give the variants`() {
        AssetSource(listOf(mod())).use { assets ->
            val models = BlockModels(assets)
            val ore = ForgeData.LegacyBlock(3000, "testmod:orecopper", "TestMod", "tile.oreCopper", "x.Unknown")
            val leaves = ForgeData.LegacyBlock(3001, "testmod:id_3001", "TestMod", "", itemClass)
            models.registerLegacy(listOf(ore, leaves), mapOf(3001 to listOf("greenleaves.id")))

            val oreState = BlockState.of("testmod:orecopper", mapOf("meta" to "0"))
            assertEquals(0xC06040, models.mapLook(oreState)?.color?.and(0xFFFFFF))
            assertEquals("name (100)", models.textureChoice(oreState)?.source)
            // Data value 1 is the second variant; the "fast" textures belong to the same variant.
            assertEquals("t:blocks/leavesredwoodfancy", models.textureChoice(BlockState.of("testmod:id_3001", mapOf("meta" to "1")))?.top)
            assertEquals("t:blocks/leavesfirfancy", models.textureChoice(BlockState.of("testmod:id_3001", mapOf("meta" to "2")))?.top)
            val cube = models.model(BlockState.of("testmod:id_3001", mapOf("meta" to "0")), 0)
            assertEquals(6, cube.quads.size)
            assertEquals(false, cube.occludes, "leaves let neighbors show")
        }
    }

    @Test
    fun `the user's choice wins and plants are not drawn as cubes`() {
        AssetSource(listOf(mod())).use { assets ->
            val models = BlockModels(assets)
            models.registerLegacy(listOf(ForgeData.LegacyBlock(3000, "testmod:orecopper", "TestMod", "tile.oreCopper", "x.Unknown")), emptyMap())
            models.overrides["testmod:orecopper[meta=0]"] = "t:leavesredwoodfancy"
            val state = BlockState.of("testmod:orecopper", mapOf("meta" to "0"))
            assertEquals(0x406020, models.mapLook(state)?.color?.and(0xFFFFFF))
            assertEquals("your choice", models.textureChoice(state)?.source)
            models.overrides["testmod:orecopper"] = "t:saplingfir"
            assertEquals(BlockKind.SKIP, models.mapLook(BlockState.of("testmod:orecopper", mapOf("meta" to "5")))?.kind)
        }
    }

    @Test
    fun `stairs and slabs take their material's texture`() {
        val mod = zip(
            "wood.jar",
            "mcmod.info" to """[{"modid": "Woods", "mcversion": "1.6.4"}]""".toByteArray(),
            "assets/w/textures/blocks/planksacacia.png" to png(0xB06030),
            "assets/w/textures/blocks/planksfir.png" to png(0x705030),
            "assets/w/textures/blocks/logacaciaside.png" to png(0x404040),
        )
        AssetSource(listOf(mod)).use { assets ->
            val models = BlockModels(assets)
            models.registerLegacy(listOf(
                ForgeData.LegacyBlock(2220, "woods:id_2220", "Woods", "", "x.BlockWoodStairs"),
                ForgeData.LegacyBlock(2216, "woods:id_2216", "Woods", "", "x.ItemWoodSlab"),
            ), mapOf(2220 to listOf("acaciastairs.id"), 2216 to listOf("woodslab.id")))
            assertEquals("w:blocks/planksacacia", models.textureChoice(BlockState.of("woods:id_2220", mapOf("meta" to "3")))?.top, "planks, not the log")
            assertEquals("w:blocks/planksfir", models.textureChoice(BlockState.of("woods:id_2216", mapOf("meta" to "1")))?.top, "a wood slab per data value")
        }
        assertEquals("seats", LegacyTextures.stripModPrefix("biblioseats", "bibliocraft"))
        assertEquals("oak", LegacyTextures.stripModPrefix("oak", "bibliocraft"))
    }

    @Test
    fun `block info says where each look comes from`() {
        AssetSource(listOf(mod())).use { assets ->
            val models = BlockModels(assets)
            models.registerLegacy(listOf(
                ForgeData.LegacyBlock(3000, "testmod:orecopper", "TestMod", "tile.oreCopper", "x.Unknown"),
                ForgeData.LegacyBlock(3002, "testmod:id_3002", "TestMod", "", "x.Nothing"),
            ), emptyMap())
            val ore = models.info(BlockState.of("testmod:orecopper", mapOf("meta" to "0")))
            assertEquals(BlockModels.Source.GUESS, ore.source)
            assertEquals("t:blocks/oreCopper", ore.texture)
            assertEquals(BlockModels.Source.UNKNOWN, models.info(BlockState.of("testmod:id_3002", mapOf("meta" to "0"))).source)
            models.overrides["testmod:id_3002"] = "t:blocks/leavesfirfancy"
            val chosen = models.info(BlockState.of("testmod:id_3002", mapOf("meta" to "0")))
            assertEquals(BlockModels.Source.CHOICE, chosen.source)
            assertEquals("t:blocks/leavesfirfancy", chosen.texture)
            assertEquals(listOf("t:blocks/leavesfirfancy", "t:blocks/leavesfirfast", "t:blocks/leavesredwoodfancy", "t:blocks/leavesredwoodfast",
                "t:blocks/oreCopper", "t:blocks/saplingfir"), assets.blockTextures[0])
        }
    }

    @Test
    fun `config files give block ids their keys, only from block categories`() {
        val config = Files.createDirectories(dir.resolve("config"))
        Files.writeString(config.resolve("Extrabiomes.cfg"), "block {\n    I:greenleaves.id=2204\n}\nitem {\n    I:saddle.id=2204\n}\n")
        Files.writeString(config.resolve("Plain.cfg"), "general {\n    I:OreBlock=3002\n}\n")
        val keys = LegacyTextures.configKeys(listOf(config))
        assertEquals(listOf("greenleaves.id"), keys[2204])
        assertEquals(listOf("OreBlock"), keys[3002], "files without a block category count every id")
    }

    @Test
    fun `icons group into variants by stem`() {
        val v = LegacyTextures.variants(listOf(
            "x:leavesfirfancy", "x:leavesfirfast", "x:leavesredwoodfancy", "x:leavesredwoodfast", "x:logtop", "x:logside",
        ))
        assertEquals(listOf(
            LegacyTextures.Variant("x:leavesfirfancy", "x:leavesfirfancy"),
            LegacyTextures.Variant("x:leavesredwoodfancy", "x:leavesredwoodfancy"),
            LegacyTextures.Variant("x:logtop", "x:logside"),
        ), v)
    }

    @Test
    fun `config keys name the block`() {
        assertEquals("greenleaves", LegacyTextures.stripConfigKey("greenleaves.id"))
        assertEquals("brick.infernal", LegacyTextures.stripConfigKey("block.brick.infernal"))
        assertEquals("RubberLeaves", LegacyTextures.stripConfigKey("ID.RubberLeaves"))
    }

    /** Prints what the resolver picks for a real 1.6 world (MINECARTOGRAPHER_WORLDS + MINECARTOGRAPHER_MOD_FOLDERS). */
    @Test
    fun `real 1_6 world - report`() {
        val root = System.getenv("MINECARTOGRAPHER_WORLDS")?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return
        val extra = System.getenv("MINECARTOGRAPHER_MOD_FOLDERS").orEmpty().split(';').filter { it.isNotBlank() }.map { Path.of(it) }
        val info = Files.walk(root, 4).use { s -> s.filter { it.name == "level.dat" }.map { it.parent }.toList() }
            .mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
            .filter { it.forge?.generation == ForgeData.Generation.NUMERIC_ITEM_DATA && Files.isDirectory(it.folder.resolve("region")) }
            .maxByOrNull { it.forge!!.legacyBlocks.size } ?: return
        val world = AnvilWorld.open(info.folder)
        try {
            val found = ModLocator.locate(world, extraFolders = extra)
            if (found.archives.isEmpty()) return println("no archives for ${info.name}")
            val configs = LegacyTextures.configKeys(extra.mapNotNull { it.parent?.resolve("config") } + listOfNotNull(info.folder.parent?.parent?.resolve("config")))
            val counts = HashMap<Pair<Int, Int>, Int>()
            val legacy = info.forge!!.legacyBlocks
            val byName = legacy.values.associateBy { it.name }
            world.chunks().take(800).forEach { pos ->
                world.loadBlocks(pos)?.palette?.forEach { s ->
                    val b = byName[s.name] ?: return@forEach
                    counts.merge(b.id to (s.properties["meta"]?.toIntOrNull() ?: 0), 1, Int::plus)
                }
            }
            AssetSource(found.archives).use { assets ->
                val resolver = LegacyTextures(assets)
                val ids = counts.keys.map { it.first }.toSet()
                var resolved = 0
                val bySource = HashMap<String, Int>()
                val lines = ArrayList<String>()
                for (id in ids.sortedByDescending { i -> counts.filterKeys { it.first == i }.values.sum() }) {
                    val b = legacy.getValue(id)
                    val choice = resolver.choose(b, configs[id].orEmpty())
                    if (choice != null) { resolved++; bySource.merge(choice.source, 1, Int::plus) }
                    val metas = counts.keys.filter { it.first == id }.map { it.second }.sorted()
                    lines += "  $id ${b.modId} '${b.forcedName}' cfg=${configs[id].orEmpty().take(2)} -> " +
                        (choice?.let { c -> "${c.source}(${c.score}) " + metas.joinToString { m -> "$m:${c.variant(m).top.substringAfter(':')}" } } ?: "none")
                }
                println("${info.name}: ${found.archives.size} archives, ${configs.size} config ids; resolved $resolved/${ids.size}; by clue $bySource")
                lines.take(45).forEach(::println)
            }
        } finally {
            world.close()
        }
    }
}

class ModSearchTest {
    /** Searches MINECARTOGRAPHER_MOD_SEARCH (e.g. H:\Zalohy) for the mods of the biggest 1.6 world; prints, never saves. */
    @Test
    fun `search a backup folder for a world's mods`() {
        val root = System.getenv("MINECARTOGRAPHER_WORLDS")?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return
        val searchRoot = System.getenv("MINECARTOGRAPHER_MOD_SEARCH")?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return
        val info = Files.walk(root, 4).use { s -> s.filter { it.name == "level.dat" }.map { it.parent }.toList() }
            .mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
            .filter { it.forge?.generation == ForgeData.Generation.NUMERIC_ITEM_DATA && Files.isDirectory(it.folder.resolve("region")) }
            .maxByOrNull { it.forge!!.legacyBlocks.size } ?: return
        val world = AnvilWorld.open(info.folder)
        try {
            val before = ModLocator.locate(world, extraFolders = emptyList()).missing.size
            val start = System.nanoTime()
            var foldersSeen = 0
            val chosen = ModLocator.search(world, searchRoot, progress = { f, _ -> foldersSeen = f })
            val ms = (System.nanoTime() - start) / 1_000_000
            val after = ModLocator.locate(world, extraFolders = ModLocator.folders + chosen)
            println("${info.folder}: ${info.forge!!.playedMods.size} mods, missing $before -> ${after.missing.size} " +
                "(${after.archives.size} archives with textures); $foldersSeen mods folders under $searchRoot, ${ms} ms; chosen: $chosen; still missing ${after.missing.map { it.id }}")
        } finally {
            world.close()
        }
    }
}

class LegacyDiagnosisTest {
    /** Reproduces the app for MINECARTOGRAPHER_DIAG_WORLD with archives from MINECARTOGRAPHER_MOD_SEARCH; prints why blocks stay unknown. */
    @Test
    fun `why blocks stay unknown`() {
        val folder = System.getenv("MINECARTOGRAPHER_DIAG_WORLD")?.let { Path.of(it) } ?: return
        val search = System.getenv("MINECARTOGRAPHER_MOD_SEARCH")?.let { Path.of(it) } ?: return
        val world = AnvilWorld.open(folder)
        try {
            val forge = world.info.forge!!
            val folders = ModLocator.search(world, search)
            val found = ModLocator.locate(world, extraFolders = ModLocator.folders + folders)
            val picked = System.getenv("MINECARTOGRAPHER_DIAG_PICKED")?.split(';')?.filter { it.isNotBlank() }?.map { Path.of(it) }.orEmpty()
            val files = picked + found.archives + listOfNotNull(TextureFiles.findClientJar(null))
            val configs = LegacyTextures.configKeys(found.archives.mapNotNull { a -> generateSequence(a.parent) { it.parent }.take(3).firstOrNull { it.fileName.toString().equals("mods", true) }?.parent?.resolve("config") }.distinct())
            AssetSource(files).use { assets ->
                val models = BlockModels(assets)
                models.registerLegacy(forge.legacyBlocks.values, configs)
                val states = HashSet<BlockState>()
                val limit = System.getenv("MINECARTOGRAPHER_DIAG_CHUNKS")?.toIntOrNull() ?: 3000
                world.chunks().take(limit).forEach { p -> world.loadBlocks(p)?.palette?.filterTo(states) { it.name in forge.blockNames.values } }
                val infos = states.associateWith { models.info(it) }
                val bySource = infos.values.groupingBy { it.source }.eachCount()
                println("${states.size} modded states, ${configs.size} config ids, ${found.archives.size} archives; $bySource")
                val unknown = infos.filterValues { it.source == BlockModels.Source.UNKNOWN }.keys
                val byMod = unknown.groupBy { s -> forge.legacyBlocks.values.firstOrNull { it.name == s.name }?.modId ?: "?" }
                for ((mod, list) in byMod.entries.sortedByDescending { it.value.size }) {
                    val names = list.map { it.name }.distinct()
                    println("  $mod: ${list.size} states, ${names.size} blocks: " + names.take(40).joinToString { n ->
                        val b = forge.legacyBlocks.values.first { it.name == n }
                        "$n (forced='${b.forcedName}', item=${b.itemClass.substringAfterLast('.')}, cfg=${configs[b.id]})"
                    })
                }
            }
        } finally {
            world.close()
        }
    }
}

class AppDiagnosisTest {
    /** The whole app path (EditorState) for MINECARTOGRAPHER_DIAG_WORLD, on a copy of the real cache; prints what the panel would show. */
    @Test
    fun `what the mods panel shows`() {
        val folder = System.getenv("MINECARTOGRAPHER_DIAG_WORLD")?.let { Path.of(it) } ?: return
        val realCache = Path.of(System.getenv("LOCALAPPDATA"), "MineCartographer", "cache")
        val copy = Files.createTempDirectory("cache-copy")
        if (Files.isDirectory(realCache)) Files.walk(realCache).use { s -> s.forEach { src ->
            val dst = copy.resolve(realCache.relativize(src).toString())
            if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
        } }
        System.setProperty("minecartographer.cache", copy.toString())
        // The cache root is read once per JVM: if another test got there first, this one would write the real cache.
        org.junit.jupiter.api.Assumptions.assumeTrue(PackCache.defaultRoot == copy, "Run this test on its own")
        val state = minecartographer.editor.EditorState()
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 120_000
            while (!cond() && System.currentTimeMillis() < end) Thread.sleep(100)
            javax.swing.SwingUtilities.invokeAndWait {}
            println("  waited for $what: ${cond()}")
        }
        try {
            javax.swing.SwingUtilities.invokeAndWait { state.openWorldFolder(folder) }
            waitFor("world") { !state.busy && state.worlds.isNotEmpty() }
            val world = state.worlds.first() as AnvilWorld
            System.getenv("MINECARTOGRAPHER_DIAG_PICKED")?.let { p -> javax.swing.SwingUtilities.invokeAndWait { state.chooseTextures(world, listOf(Path.of(p))) } }
            javax.swing.SwingUtilities.invokeAndWait { state.setRenderMode(minecartographer.viewport.RenderMode.Textured) }
            waitFor("textures") { state.modelsOf(world) != null && !state.texturesLoading }
            world.chunks().take(3000).forEach { world.loadBlocks(it) } // as the viewport would
            Thread.sleep(4000)
            waitFor("learner") { state.moddedKeysOf(world).isNotEmpty() }
            val models = state.modelsOf(world)!!
            val keys = state.moddedKeysOf(world)
            val infos = keys.associateWith { models.info(minecartographer.core.anvil.BlockColors.stateOf(it)) }
            println("files: ${state.loadedTexturesOf(world)?.label}; keys ${keys.size}; " + infos.values.groupingBy { it.source }.eachCount())
            println("unknown e.g. " + infos.filterValues { it.source == BlockModels.Source.UNKNOWN }.keys.take(12))
        } finally {
            state.dispose()
        }
    }
}
