package minecartographer.core.anvil

import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkBounds
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import minecartographer.core.WorldSource
import minecartographer.core.edit.ChunkRef
import minecartographer.core.convert.Conversion

/**
 * A chunk as it will be exported: [ref] (turned, raised or cut, and converted by [conversion] for the world it goes
 * into) placed at [at] over [under], as a world of that one chunk, so it is loaded and drawn like any other. It reads
 * what [ChunkTransform.build] writes on export, so the map shows the result.
 */
class TransformedChunk(val ref: ChunkRef, val at: ChunkPos, val under: AnvilWorld?, val conversion: Conversion? = null) : WorldSource {
    private val source = ref.world as AnvilWorld

    /** Starts with the source world's id followed by '#', so caches keyed by world can find it. */
    override val id = "${source.id}#${ref.pos.x},${ref.pos.z}/${ref.turns}/${ref.dy}/${ref.cut}@${at.x},${at.z}" +
        (under?.let { "/" + it.id } ?: "") + (conversion?.let { "/map" + it.overrides.hashCode() } ?: "")

    /** Remapped chunks number their blocks the way the target does. */
    private val legacy get() = if (conversion?.remaps == true) conversion.target.legacy else source.legacy
    override val name get() = source.name
    override val versionLabel get() = source.versionLabel
    override val dataVersion get() = source.dataVersion
    override val minY get() = source.minY
    override val maxY get() = source.maxY
    override val bounds = ChunkBounds(ref.pos.x, ref.pos.z, ref.pos.x, ref.pos.z)

    override fun hasChunk(pos: ChunkPos) = pos == ref.pos

    override fun loadSurface(pos: ChunkPos): ChunkSurface? =
        ChunkTransform.build(ref, at, under, conversion, withEntities = false)?.first?.let { SurfaceExtractor.extract(it, pos, legacy) }

    override fun loadBlocks(pos: ChunkPos): ChunkBlocks? =
        ChunkTransform.build(ref, at, under, conversion, withEntities = false)?.first?.let { SurfaceExtractor.extractBlocks(it, pos, legacy) }

    companion object {
        /** The id of the world a chunk cache entry belongs to, for transformed chunks too. */
        fun baseId(worldId: String) = worldId.substringBefore('#')
    }
}
