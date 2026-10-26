package minecartographer.core.anvil

import minecartographer.core.nbt.ByteTag
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.IntTag
import minecartographer.core.nbt.LongTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.ShortTag
import minecartographer.core.nbt.StringTag
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Everything level.dat and the world folder tell about a world, for the Info window: when it was played and made,
 * the game mode, seed, where the player was, the dimensions and the size on disk. Only reads; fields an older
 * version doesn't store are null. Walking a big folder takes a while, so [read] belongs off the UI thread.
 */
data class WorldDetails(
    val folder: Path,
    val lastPlayed: Instant?,
    val levelDatModified: Instant?,
    val folderCreated: Instant?,
    /** The newest region file: when chunks were last written. */
    val newestRegion: Instant?,
    val gameMode: String?,
    val hardcore: Boolean?,
    val difficulty: String?,
    val cheats: Boolean?,
    val seed: Long?,
    val generator: String?,
    val spawn: Triple<Int, Int, Int>?,
    /** Ticks the world has run, and the time of day, as level.dat counts them (20 ticks a second). */
    val gameTicks: Long?,
    val dayTime: Long?,
    /** Where the single player was when the world was saved, with the dimension. */
    val player: Player?,
    val versionId: Int?,
    val snapshot: Boolean?,
    /** The Minecraft, Forge and loader entries of a Forge world's mod list, e.g. `forge 47.2.0`. */
    val loaderEntries: List<ModInfo>,
    val dimensions: List<Dimension>,
    val datapacks: List<String>,
    val playerFiles: Int,
    val totalBytes: Long,
    val files: Int,
    val levelDatBytes: Long,
) {
    data class Player(val x: Double, val y: Double, val z: Double, val dimension: String?)

    /** A dimension folder: `region` (overworld), `DIM-1` (Nether), `DIM1` (End) or another one. */
    data class Dimension(val name: String, val folder: String, val regionFiles: Int, val bytes: Long)

    companion object {
        private val LOADER_IDS = setOf("minecraft", "mcp", "fml", "forge", "neoforge")

        fun read(folder: Path): WorldDetails {
            val levelDat = folder.resolve("level.dat")
            val root = runCatching { levelDat.inputStream().use { Nbt.readGzip(it) } }.getOrNull()
            val data = root?.compound("Data")
            val generation = data?.compound("WorldGenSettings")
            val player = data?.compound("Player")
            val pos = player?.list("Pos")?.doubles()?.takeIf { it.size == 3 }
            val forge = runCatching { root?.let { ForgeData.read(it) } }.getOrNull()
            val stats = Stats(folder)
            return WorldDetails(
                folder = folder,
                lastPlayed = data?.long("LastPlayed")?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                levelDatModified = time { levelDat.getLastModifiedTime().toInstant() },
                folderCreated = time { Files.readAttributes(folder, java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toInstant() },
                newestRegion = stats.newestRegion,
                gameMode = data?.int("GameType")?.let { listOf("Survival", "Creative", "Adventure", "Spectator").getOrNull(it) ?: it.toString() },
                hardcore = data?.int("hardcore")?.let { it != 0 },
                difficulty = data?.int("Difficulty")?.let { listOf("Peaceful", "Easy", "Normal", "Hard").getOrNull(it) ?: it.toString() },
                cheats = data?.int("allowCommands")?.let { it != 0 },
                // 1.16 moved the seed into the world generation settings.
                seed = data?.long("RandomSeed") ?: generation?.long("seed"),
                generator = data?.string("generatorName") ?: generation?.compound("dimensions")?.compound("minecraft:overworld")
                    ?.compound("generator")?.string("type"),
                spawn = data?.int("SpawnX")?.let { x -> Triple(x, data.int("SpawnY") ?: 0, data.int("SpawnZ") ?: 0) },
                gameTicks = data?.long("Time"),
                dayTime = data?.long("DayTime"),
                player = pos?.let { Player(it[0], it[1], it[2], player.string("Dimension") ?: player.int("Dimension")?.let(::dimensionName)) },
                versionId = data?.compound("Version")?.int("Id"),
                snapshot = data?.compound("Version")?.int("Snapshot")?.let { it != 0 },
                loaderEntries = forge?.mods.orEmpty().filter { it.id.lowercase() in LOADER_IDS },
                dimensions = stats.dimensions,
                datapacks = folder.resolve("datapacks").takeIf { it.isDirectory() }?.let { dir ->
                    runCatching { dir.listDirectoryEntries().map { it.name }.sorted() }.getOrDefault(emptyList())
                }.orEmpty(),
                playerFiles = listOf("playerdata", "players", "stats").firstNotNullOfOrNull { dir ->
                    folder.resolve(dir).takeIf { it.isDirectory() }?.let { runCatching { it.listDirectoryEntries().size }.getOrNull() }
                } ?: 0,
                totalBytes = stats.bytes,
                files = stats.files,
                levelDatBytes = runCatching { levelDat.fileSize() }.getOrDefault(0),
            )
        }

        private fun dimensionName(id: Int) = when (id) {
            0 -> "minecraft:overworld"
            -1 -> "minecraft:the_nether"
            1 -> "minecraft:the_end"
            else -> id.toString()
        }

        private inline fun <T> time(block: () -> T): T? = runCatching(block).getOrNull()

        /** Level.dat stores most numbers as whichever tag the writer liked. */
        private fun CompoundTag.long(name: String): Long? = when (val t = this[name]) {
            is LongTag -> t.value
            is IntTag -> t.value.toLong()
            is ShortTag -> t.value.toLong()
            is ByteTag -> t.value.toLong()
            is DoubleTag -> t.value.toLong()
            is StringTag -> t.value.toLongOrNull()
            else -> null
        }
    }

    /** One walk over the folder: sizes, files, the region files of each dimension. */
    private class Stats(folder: Path) {
        var bytes = 0L
        var files = 0
        var newestRegion: Instant? = null
        val dimensions = ArrayList<Dimension>()

        init {
            runCatching {
                Files.walk(folder).use { paths ->
                    for (p in paths) {
                        if (!p.isRegularFile()) continue
                        val size = runCatching { p.fileSize() }.getOrDefault(0)
                        bytes += size
                        files++
                        if (p.name.endsWith(".mca")) {
                            val modified = runCatching { p.getLastModifiedTime().toInstant() }.getOrNull()
                            if (modified != null && (newestRegion == null || modified > newestRegion!!)) newestRegion = modified
                        }
                    }
                }
            }
            // `region` is the overworld; the others are folders with a region folder of their own (DIM-1, DIM1, dimensions/...).
            val candidates = buildList {
                add("Overworld" to folder)
                add("Nether" to folder.resolve("DIM-1"))
                add("The End" to folder.resolve("DIM1"))
                runCatching { folder.listDirectoryEntries("DIM*").filter { it.name != "DIM-1" && it.name != "DIM1" }.forEach { add(it.name to it) } }
                folder.resolve("dimensions").takeIf { it.isDirectory() }?.let { dims ->
                    runCatching {
                        dims.listDirectoryEntries().filter { it.isDirectory() }.forEach { ns ->
                            ns.listDirectoryEntries().filter { it.isDirectory() }.forEach { add("${ns.name}:${it.name}" to it) }
                        }
                    }
                }
            }
            for ((name, dir) in candidates) {
                val region = dir.resolve("region").takeIf { it.isDirectory() } ?: continue
                val mca = runCatching { region.listDirectoryEntries("r.*.mca") }.getOrDefault(emptyList())
                if (mca.isEmpty()) continue
                dimensions += Dimension(name, folder.relativize(region).toString().ifEmpty { "region" }, mca.size, mca.sumOf { runCatching { it.fileSize() }.getOrDefault(0) })
            }
        }
    }
}
