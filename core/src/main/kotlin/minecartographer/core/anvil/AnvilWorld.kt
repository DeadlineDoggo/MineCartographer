package minecartographer.core.anvil

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkBounds
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import minecartographer.core.WorldSource
import minecartographer.core.nbt.CompoundTag
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
    /** Mods and modded block ids, for worlds played with Forge; null otherwise. */
    val forge: ForgeData? = null,
) {
    companion object {
        /** level.dat `version` of the Anvil format (1.2+); 19132 is the older McRegion format. */
        const val ANVIL = 19133

        fun read(folder: Path): WorldInfo {
            val levelDat = folder.resolve("level.dat")
            if (!levelDat.exists()) throw IOException("No level.dat in $folder")
            val root = levelDat.inputStream().use { Nbt.readGzip(it) }
            val data = root.compound("Data") ?: throw IOException("level.dat has no Data compound")
            val format = data.int("version")
            if (format != null && format != ANVIL) {
                throw IOException("This world uses an old save format (McRegion, before 1.2). Open it once in Minecraft 1.2 or newer to convert it.")
            }
            val dataVersion = data.int("DataVersion")
            val versionName = data.compound("Version")?.string("Name") ?: if (dataVersion == null) "1.2 – 1.8" else "DV $dataVersion"
            val spawnX = data.int("SpawnX")
            val spawnZ = data.int("SpawnZ")
            val spawn = if (spawnX != null && spawnZ != null) spawnX to spawnZ else null
            val forge = runCatching { ForgeData.read(root) }.getOrNull()
            return WorldInfo(folder, data.string("LevelName") ?: folder.name, dataVersion, versionName, spawn, forge)
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

    /** Numeric block ids (before 1.13) to states, including this world's modded ids. */
    internal val legacy = info.forge?.legacyMapping() ?: LegacyMapping.VANILLA

    /** The numeric id and data value [state] is stored as in this world; null from 1.13 on, where chunks store names. */
    fun legacyIdOf(state: BlockState): Pair<Int, Int>? =
        if ((dataVersion ?: 0) >= SurfaceExtractor.DV_FLATTENING) null else legacy.idOf(state)

    /** Region files stay open while the world is open; reads on them are thread-safe. */
    private val regions = ConcurrentHashMap<ChunkPos, RegionFile>()
    private val entityRegions = ConcurrentHashMap<ChunkPos, RegionFile>()
    private val poiRegions = ConcurrentHashMap<ChunkPos, RegionFile>()

    override fun hasChunk(pos: ChunkPos) = present[regionKey(pos.regionX, pos.regionZ)]?.get(RegionFile.index(pos)) == true

    override fun loadSurface(pos: ChunkPos): ChunkSurface? {
        val root = chunkNbt(pos) ?: return null
        return SurfaceExtractor.extract(root, pos, legacy)?.also { lowestTops.putIfAbsent(pos, it.minHeight) }
    }

    /**
     * The chunk's blocks seen from above, and also from the side down to the lowest ground of the chunks around it
     * (see [groundAround]): under overhangs and floating builds, and on the walls of cliffs and pits, where only the
     * blocks seen from above would leave the sides unknown.
     */
    override fun loadBlocks(pos: ChunkPos): ChunkBlocks? {
        // The ground around first: reading it leaves this chunk's own data ready when it is one of the neighbors read.
        val ground = groundAround(pos)
        val root = chunkNbt(pos) ?: return null
        // Two below: a neighbor's top may be a plant on its ground.
        return SurfaceExtractor.extractBlocks(root, pos, legacy, floor = ground?.let { it - 2 }) { lowestTops.putIfAbsent(pos, it) }
    }

    /** The lowest top of each chunk read so far, a chunk's own or one around it; [NO_SURFACE] for chunks with none. */
    private val lowestTops = ConcurrentHashMap<ChunkPos, Int>()

    /**
     * Chunks read only for their lowest top, kept for a while: the chunks around the ones being drawn are mostly
     * drawn next, and then are not read and decompressed twice. A chunk is taken out when it is read for itself.
     */
    private val readAhead = object : LinkedHashMap<ChunkPos, CompoundTag>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkPos, CompoundTag>) = size > READ_AHEAD
    }

    /** The chunk's NBT, from [readAhead] if it was read for its top, else from its region file; null where there is none. */
    private fun chunkNbt(pos: ChunkPos): CompoundTag? {
        synchronized(readAhead) { readAhead.remove(pos) }?.let { return it }
        return readChunk(pos)?.nbt(SurfaceExtractor.UNUSED_TAGS)
    }

    /**
     * The lowest top of the chunk at [pos], read once (other threads asking for it meanwhile wait for that read);
     * null where there is no chunk or nothing in it.
     */
    private fun lowestTop(pos: ChunkPos): Int? {
        if (!hasChunk(pos)) return null
        lowestTops[pos]?.let { return it.takeIf { t -> t != NO_SURFACE } }
        val top = lowestTops.computeIfAbsent(pos) {
            val root = runCatching { readChunk(pos)?.nbt(SurfaceExtractor.UNUSED_TAGS) }.getOrNull()
            val top = root?.let { runCatching { SurfaceExtractor.lowestTop(it, legacy) }.getOrNull() }
            if (root != null && top != null) synchronized(readAhead) { readAhead[pos] = root }
            top ?: NO_SURFACE
        }
        return top.takeIf { it != NO_SURFACE }
    }

    /**
     * The lowest ground of the chunks up to [AROUND] chunks from [pos], the chunk itself left out: from it up, the
     * chunk's sides can be seen through the gaps around it. A look under a roof reaches about as far in as the gap
     * is high, and the gaps under builds and overhangs are rarely higher than two chunks.
     */
    private fun groundAround(pos: ChunkPos): Int? {
        var lowest: Int? = null
        for (dx in -AROUND..AROUND) for (dz in -AROUND..AROUND) {
            if (dx == 0 && dz == 0) continue
            val top = lowestTop(ChunkPos(pos.x + dx, pos.z + dz)) ?: continue
            if (lowest == null || top < lowest) lowest = top
        }
        return lowest
    }

    override fun loadCaves(pos: ChunkPos): ChunkBlocks? {
        val root = chunkNbt(pos) ?: return null
        return SurfaceExtractor.extractBlocks(root, pos, legacy, deep = true) { lowestTops.putIfAbsent(pos, it) }
    }

    override fun countBlocks(pos: ChunkPos): Map<minecartographer.core.BlockState, Int>? {
        val raw = readChunk(pos) ?: return null
        return SurfaceExtractor.countBlocks(raw.nbt(SurfaceExtractor.UNUSED_TAGS), legacy)
    }

    fun readChunk(pos: ChunkPos): RawChunk? = if (!hasChunk(pos)) null else region(regions, regionDir, pos)?.read(pos)

    /** Entities live in their own region files since 1.17; null for older worlds or no entities. */
    fun readEntities(pos: ChunkPos): RawChunk? = region(entityRegions, info.folder.resolve("entities"), pos)?.read(pos)

    /** The chunk's points of interest (beds, job sites, portals...) since 1.14; null for older worlds or none. */
    fun readPoi(pos: ChunkPos): RawChunk? = region(poiRegions, info.folder.resolve("poi"), pos)?.read(pos)

    private fun region(cache: ConcurrentHashMap<ChunkPos, RegionFile>, dir: Path, pos: ChunkPos): RegionFile? {
        val key = regionKey(pos.regionX, pos.regionZ)
        cache[key]?.let { return it }
        val file = dir.resolve(RegionFile.fileName(pos.regionX, pos.regionZ))
        if (!file.exists()) return null
        return cache.computeIfAbsent(key) { RegionFile(file) }
    }

    /** All chunk positions with data, region by region. */
    override fun chunks(): Sequence<ChunkPos> = present.asSequence().flatMap { (key, bits) ->
        val rx = key.x
        val rz = key.z
        bits.stream().toArray().asSequence().map { i -> ChunkPos(rx * 32 + (i and 31), rz * 32 + (i shr 5)) }
    }

    fun close() {
        regions.values.forEach { runCatching { it.close() } }
        entityRegions.values.forEach { runCatching { it.close() } }
        poiRegions.values.forEach { runCatching { it.close() } }
        regions.clear()
        synchronized(readAhead) { readAhead.clear() }
        entityRegions.clear()
        poiRegions.clear()
    }

    companion object {
        /** How many chunks around a chunk are looked at for the ground its sides can be seen down to. */
        private const val AROUND = 2
        private const val NO_SURFACE = Int.MIN_VALUE
        /** Chunks kept after being read for their top: a few rings of chunks around the ones being drawn. */
        private const val READ_AHEAD = 1024
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
