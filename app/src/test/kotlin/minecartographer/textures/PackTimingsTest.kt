package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.WorldInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.system.measureNanoTime
import kotlin.test.Test

/** Where the time goes when a modded world's textures load (real worlds only; prints, asserts nothing). */
class PackTimingsTest {
    @Test
    fun `time each stage of loading a modded world's textures`() {
        val root = System.getenv("MINECARTOGRAPHER_WORLDS")?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return
        val extra = System.getenv("MINECARTOGRAPHER_MOD_FOLDERS").orEmpty().split(';').filter { it.isNotBlank() }.map { Path.of(it) }
        val worlds = Files.walk(root, 4).use { s -> s.filter { it.name == "level.dat" }.map { it.parent }.toList() }
            .mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
            .filter { it.forge?.playedMods?.isNotEmpty() == true && Files.isDirectory(it.folder.resolve("region")) }
            .groupBy { it.forge!!.generation }.values.map { g -> g.maxBy { it.forge!!.playedMods.size } }
        for (info in worlds) {
            val world = AnvilWorld.open(info.folder)
            try {
                fun ms(ns: Long) = "%.0f ms".format(ns / 1e6)
                lateinit var found: ModLocator.Found
                val tLocate = measureNanoTime { found = ModLocator.locate(world, extraFolders = extra) }
                val tLocateAgain = measureNanoTime { ModLocator.locate(world, extraFolders = extra) }
                val version = info.versionName.takeIf { (info.dataVersion ?: 0) >= 1519 }
                val files = found.archives + listOfNotNull(TextureFiles.findClientJar(version))
                val states = HashSet<BlockState>()
                val tChunks = measureNanoTime { world.chunks().take(600).forEach { p -> world.loadBlocks(p)?.palette?.let { states += it } } }
                val root = Files.createTempDirectory("pack-timings")
                val modded = states.filter { s -> !s.name.startsWith("minecraft:") && ':' in s.name }
                // Session 1 fills a pack cache; session 2 starts from it, as the app does after a restart.
                val looks = ArrayList<List<Int?>>()
                for (session in 1..2) {
                    if (session == 2) PackCache.forgetOpen()
                    lateinit var assets: AssetSource
                    val cache = PackCache.of(files, root)
                    val tOpen = measureNanoTime {
                        assets = AssetSource(files, cache.index)
                        if (cache.index == null) cache.putIndex(assets.index)
                    }
                    assets.use {
                        val models = BlockModels(it, cache)
                        lateinit var colors: List<Int?>
                        val tLooks = measureNanoTime { colors = modded.map { s -> models.mapLook(s)?.color } }
                        looks += colors
                        val tModels = measureNanoTime { states.forEach { s -> models.model(s, 0) } }
                        cache.save()
                        println("${info.forge!!.generation.label} ${info.name} session $session: locate ${ms(tLocate)} (again ${ms(tLocateAgain)}), " +
                            "${files.size} files open+index ${ms(tOpen)}, read 600 chunks ${ms(tChunks)}, " +
                            "${modded.size} modded map looks ${ms(tLooks)}, ${states.size} models ${ms(tModels)}; " +
                            "cache ${Files.walk(cache.dir).use { s -> s.filter(Files::isRegularFile).mapToLong(Files::size).sum() } / 1024} KB")
                    }
                }
                check(looks[0] == looks[1]) { "the cached session must give the same colors" }
            } finally {
                world.close()
            }
        }
    }
}
