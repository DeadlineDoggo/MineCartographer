package minecartographer.core.anvil

import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkBounds
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import minecartographer.core.WorldSource
import minecartographer.core.nbt.Nbt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What level.dat says about a world. */
data class WorldInfo(
    val folder: Path,
    val name: String,
    /** Null before 1.9, which did not store it. */
    val dataVersion: Int?,
    val versionName: String,
    /** SpawnX / SpawnZ from level.dat. */
    val spawn: Pair<Int, Int>? = null,
) {
    companion object {
        /** level.dat `version` of the Anvil format (1.2+); 19132 is the older McRegion format. */
        const val ANVIL = 19133

        fun read(folder: Path): WorldInfo {
            val levelDat = folder.resolve("level.dat")
            if (!levelDat.exists()) throw IOException("No level.dat in $folder")
            val data = levelDat.inputStream().use { Nbt.readGzip(it) }.compound("Data") ?: throw IOException("level.dat has no Data compound")
            val format = data.int("version")
            if (format != null && format != ANVIL) {
                throw IOException("This world uses an old save format (McRegion, before 1.2). Open it once in Minecraft 1.2 or newer to convert it.")
            }
            val dataVersion = data.int("DataVersion")
            val versionName = data.compound("Version")?.string("Name") ?: if (dataVersion == null) "1.2 – 1.8" else "DV $dataVersion"
            val spawnX = data.int("SpawnX")
            val spawnZ = data.int("SpawnZ")
            val spawn = if (spawnX != null && spawnZ != null) spawnX to spawnZ else null
            return WorldInfo(folder, data.string("LevelName") ?: folder.name, dataVersion, versionName, spawn)
        }
    }
}

/**
 * The overworld of a Java Edition world folder (Anvil format, Minecraft 1.2+).
 * Opening reads level.dat and every region header to know which chunks exist;
 * chunk data is read on demand. Never writes to the folder.
 */
class AnvilWorld private constructor(
    val info: WorldInfo,
    private val regionDir: Path,
    private val present: Map<ChunkPos, BitSet>,
    override val bounds: ChunkBounds?,
    override val focusBounds: ChunkBounds?,
    val chunkCount: Int,
) : WorldSource {
    override val id = "anvil:" + info.folder.toAbsolutePath().normalize()
    override val name get() = info.name
    override val versionLabel get() = info.versionName
    override val dataVersion get() = info.dataVersion
    override val spawn get() = info.spawn
    override val minY = if ((info.dataVersion ?: 0) >= SurfaceExtractor.DV_FLAT_CHUNK) -64 else 0
    override val maxY = if ((info.dataVersion ?: 0) >= SurfaceExtractor.DV_FLAT_CHUNK) 319 else 255
    val folder: Path get() = info.folder

    /** Region files stay open while the world is open; reads on them are thread-safe. */
    private val regions = ConcurrentHashMap<ChunkPos, RegionFile>()
    private val entityRegions = ConcurrentHashMap<ChunkPos, RegionFile>()

    override fun hasChunk(pos: ChunkPos) = present[regionKey(pos.regionX, pos.regionZ)]?.get(RegionFile.index(pos)) == true

    override fun loadSurface(pos: ChunkPos): ChunkSurface? {
        val raw = readChunk(pos) ?: return null
        return SurfaceExtractor.extract(raw.nbt(SurfaceExtractor.UNUSED_TAGS), pos)
    }

    override fun loadBlocks(pos: ChunkPos): ChunkBlocks? {
        val raw = readChunk(pos) ?: return null
        return SurfaceExtractor.extractBlocks(raw.nbt(SurfaceExtractor.UNUSED_TAGS), pos)
    }

    fun readChunk(pos: ChunkPos): RawChunk? = if (!hasChunk(pos)) null else region(regions, regionDir, pos)?.read(pos)

    /** Entities live in their own region files since 1.17; null for older worlds or no entities. */
    fun readEntities(pos: ChunkPos): RawChunk? = region(entityRegions, info.folder.resolve("entities"), pos)?.read(pos)

    private fun region(cache: ConcurrentHashMap<ChunkPos, RegionFile>, dir: Path, pos: ChunkPos): RegionFile? {
        val key = regionKey(pos.regionX, pos.regionZ)
        cache[key]?.let { return it }
        val file = dir.resolve(RegionFile.fileName(pos.regionX, pos.regionZ))
        if (!file.exists()) return null
        return cache.computeIfAbsent(key) { RegionFile(file) }
    }

    /** All chunk positions with data, region by region. */
    fun chunks(): Sequence<ChunkPos> = present.asSequence().flatMap { (key, bits) ->
        val rx = key.x
        val rz = key.z
        bits.stream().toArray().asSequence().map { i -> ChunkPos(rx * 32 + (i and 31), rz * 32 + (i shr 5)) }
    }

    fun close() {
        regions.values.forEach { runCatching { it.close() } }
        entityRegions.values.forEach { runCatching { it.close() } }
        regions.clear()
        entityRegions.clear()
    }

    companion object {
        /** Regions are keyed by their coordinates; ChunkPos hashes well, unlike a packed Long (rx xor rz). */
        private fun regionKey(rx: Int, rz: Int) = ChunkPos(rx, rz)

        /** Opens a world folder; [progress] gets (region files scanned, total). */
        fun open(folder: Path, progress: (Int, Int) -> Unit = { _, _ -> }): AnvilWorld {
            val info = WorldInfo.read(folder)
            val regionDir = folder.resolve("region")
            val files = if (regionDir.isDirectory()) regionDir.listDirectoryEntries("r.*.mca").filter { RegionFile.parseName(it.name) != null } else emptyList()
            val present = HashMap<ChunkPos, BitSet>()
            var minX = Int.MAX_VALUE; var minZ = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
            var count = 0
            files.forEachIndexed { i, file ->
                if (Files.size(file) >= RegionFile.HEADER_BYTES) {
                    RegionFile(file).use { region ->
                        val bits = BitSet(1024)
                        for (pos in region.chunks()) {
                            bits.set(RegionFile.index(pos))
                            minX = minOf(minX, pos.x); minZ = minOf(minZ, pos.z)
                            maxX = maxOf(maxX, pos.x); maxZ = maxOf(maxZ, pos.z)
                            count++
                        }
                        if (!bits.isEmpty) present[regionKey(region.regionX, region.regionZ)] = bits
                    }
                }
                progress(i + 1, files.size)
            }
            val bounds = if (count == 0) null else ChunkBounds(minX, minZ, maxX, maxZ)
            return AnvilWorld(info, regionDir, present, bounds, focusOf(present) ?: bounds, count)
        }

        /** Region-granular area holding the middle 96% of chunks along each axis. */
        private fun focusOf(present: Map<ChunkPos, BitSet>): ChunkBounds? {
            if (present.isEmpty()) return null
            fun range(coord: (ChunkPos) -> Int): IntRange {
                val weighted = present.entries.map { coord(it.key) to it.value.cardinality() }.sortedBy { it.first }
                val total = weighted.sumOf { it.second }
                var acc = 0
                val lo = weighted.first { acc += it.second; acc > total * 0.02 }.first
                acc = 0
                val hi = weighted.first { acc += it.second; acc >= total * 0.98 }.first
                return lo..hi
            }
            val xs = range { it.x }
            val zs = range { it.z }
            return ChunkBounds(xs.first * 32, zs.first * 32, xs.last * 32 + 31, zs.last * 32 + 31)
        }

        /** True when [folder] looks like a world save (has level.dat). */
        fun isWorld(folder: Path) = folder.resolve("level.dat").exists()
    }
}
