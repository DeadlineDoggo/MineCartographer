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

    fun loadSurface(pos: ChunkPos): ChunkSurface?
}
