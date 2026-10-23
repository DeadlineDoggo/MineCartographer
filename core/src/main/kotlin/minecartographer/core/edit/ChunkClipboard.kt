package minecartographer.core.edit

import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource

/**
 * Copied chunks as references to original worlds, stored relative to an anchor
 * (the center of the copied area) so they can be pasted anywhere.
 */
class ChunkClipboard private constructor(
    /** Offset from the anchor -> chunk to place there. */
    val entries: Map<ChunkPos, ChunkRef>,
    /** Where the anchor was when copied; pasting here puts chunks back at their coordinates. */
    val origin: ChunkPos,
    /** Describes where the chunks came from, e.g. "Demo Valley" or "Pasted layer 2". */
    val sourceName: String,
) {
    val size: Int get() = entries.size

    val sourceWorlds: Set<WorldSource> get() = entries.values.mapTo(HashSet()) { it.world }

    /** Target position -> chunk, with the anchor at [anchor]. */
    fun placedAt(anchor: ChunkPos): Map<ChunkPos, ChunkRef> =
        entries.entries.associate { (offset, ref) -> ChunkPos(anchor.x + offset.x, anchor.z + offset.z) to ref }

    companion object {
        /**
         * Copies [positions] using [resolve] to find the chunk at each one (a single layer or the
         * visible composite). Empty positions are skipped; returns null if nothing was copied.
         */
        fun copy(positions: Collection<ChunkPos>, sourceName: String, resolve: (ChunkPos) -> ChunkRef?): ChunkClipboard? {
            val refs = positions.mapNotNull { pos -> resolve(pos)?.let { pos to it } }
            if (refs.isEmpty()) return null
            val anchor = ChunkPos(
                (refs.minOf { it.first.x } + refs.maxOf { it.first.x }) / 2,
                (refs.minOf { it.first.z } + refs.maxOf { it.first.z }) / 2,
            )
            val entries = refs.associate { (pos, ref) -> ChunkPos(pos.x - anchor.x, pos.z - anchor.z) to ref }
            return ChunkClipboard(entries, anchor, sourceName)
        }
    }
}
