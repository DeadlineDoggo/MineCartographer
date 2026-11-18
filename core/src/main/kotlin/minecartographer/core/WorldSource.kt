package minecartographer.core

/**
 * A world the editor can display and copy chunks from.
 * Implementations: [minecartographer.core.anvil.AnvilWorld] for real saves, [DemoWorld] for samples.
 *
 * [loadSurface] may hit the disk, so callers must not invoke it on the UI thread.
 */
interface WorldSource {
    val id: String
    val name: String

    /** Human-readable game version, e.g. "1.20.4". */
    val versionLabel: String

    /** Minecraft DataVersion from level.dat, null when unknown. */
    val dataVersion: Int?

    /** Build height range of this world's version (e.g. -64..319 since 1.18). */
    val minY: Int
    val maxY: Int

    /** Chunk area that contains generated chunks, null if the world is empty. */
    val bounds: ChunkBounds?

    /** Where most of the world is (ignores far-off outliers); used to frame the camera. */
    val focusBounds: ChunkBounds? get() = bounds

    /** World spawn as block (x, z), where the camera starts in worlds too big to show at once. */
    val spawn: Pair<Int, Int>? get() = null

    fun hasChunk(pos: ChunkPos): Boolean

    /**
     * Every position [hasChunk] is true for. The default looks through [bounds], which is fine for a small world;
     * a real save lists the chunks it has, as one far-off chunk can make its bounds huge.
     */
    fun chunks(): Sequence<ChunkPos> {
        val b = bounds ?: return emptySequence()
        return sequence { for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) ChunkPos(x, z).let { if (hasChunk(it)) yield(it) } }
    }

    fun loadSurface(pos: ChunkPos): ChunkSurface?

    /** The chunk's visible blocks; worlds without block data build them from the surface. May hit the disk too. */
    fun loadBlocks(pos: ChunkPos): ChunkBlocks? = loadSurface(pos)?.let(ChunkBlocks::fromSurface)

    /** The chunk's blocks all the way down, caves included, for the Cave view. Worlds without block data have none, so no caves. */
    fun loadCaves(pos: ChunkPos): ChunkBlocks? = loadBlocks(pos)

    /** How many of each block the chunk holds (air left out); worlds without block data count what they draw. */
    fun countBlocks(pos: ChunkPos): Map<BlockState, Int>? = loadBlocks(pos)?.let { b ->
        val counts = HashMap<BlockState, Int>()
        for (y in b.minY..b.maxY) for (z in 0..15) for (x in 0..15) b.state(x, y, z).takeIf { !it.isAir }?.let { counts.merge(it, 1, Int::plus) }
        counts
    }
}
