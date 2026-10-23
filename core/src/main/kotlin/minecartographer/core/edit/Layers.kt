package minecartographer.core.edit

import minecartographer.core.ChunkBounds
import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource

/** A chunk inside an original, unmodified world. */
data class ChunkRef(val world: WorldSource, val pos: ChunkPos)

/** Override of one chunk in the [BaseLayer]. */
sealed interface ChunkEdit {
    /** Show this chunk from (possibly another) world here. */
    data class Place(val source: ChunkRef) : ChunkEdit

    /** Remove the original chunk at this position. */
    data object Delete : ChunkEdit
}

sealed interface Layer {
    val id: Long
    val name: String
    val visible: Boolean
    val locked: Boolean

    /** ARGB tag color shown in the layers panel and used to tint the layer's chunks. */
    val color: Int
}

/**
 * The bottom layer: the original world plus non-destructive overrides. The world itself
 * is never written to; erasing a chunk here records a [ChunkEdit.Delete], merging a layer
 * down into it records [ChunkEdit.Place]s.
 */
data class BaseLayer(
    val world: WorldSource,
    val overrides: Map<ChunkPos, ChunkEdit> = emptyMap(),
    override val visible: Boolean = true,
    override val locked: Boolean = false,
) : Layer {
    override val id: Long get() = BASE_ID
    override val name: String get() = "Original world"
    override val color: Int get() = BASE_COLOR

    fun resolve(pos: ChunkPos): ChunkRef? = when (val edit = overrides[pos]) {
        is ChunkEdit.Place -> edit.source
        ChunkEdit.Delete -> null
        null -> if (world.hasChunk(pos)) ChunkRef(world, pos) else null
    }

    fun isErased(pos: ChunkPos) = overrides[pos] == ChunkEdit.Delete

    /** Sets (or clears, for null) overrides, dropping ones that would equal the original world. */
    fun withOverrides(changes: Map<ChunkPos, ChunkEdit?>): BaseLayer {
        val next = overrides.toMutableMap()
        for ((pos, edit) in changes) {
            val noOp = when (edit) {
                null -> true
                is ChunkEdit.Place -> edit.source == ChunkRef(world, pos)
                ChunkEdit.Delete -> !world.hasChunk(pos)
            }
            if (noOp) next.remove(pos) else next[pos] = edit!!
        }
        return copy(overrides = next)
    }

    companion object {
        const val BASE_ID = 0L
        const val BASE_COLOR = 0xFF8A8F98.toInt()
    }
}

/** A layer of chunks placed on top of the world, typically created by pasting. */
data class ChunkLayer(
    override val id: Long,
    override val name: String,
    val chunks: Map<ChunkPos, ChunkRef>,
    override val visible: Boolean = true,
    override val locked: Boolean = false,
    override val color: Int,
) : Layer

/**
 * Immutable stack of layers for one world, bottom to top; `layers[0]` is always the [BaseLayer].
 * Every operation returns a new stack, so undo is just keeping the previous value.
 *
 * At each chunk position the topmost visible layer that has a chunk there wins.
 * Chunk layers can only add chunks; holes can only be made in the base layer.
 */
data class LayerStack(
    val world: WorldSource,
    val layers: List<Layer>,
    val activeId: Long,
    private val nextId: Long,
) {
    init {
        require(layers.firstOrNull() is BaseLayer && layers.drop(1).none { it is BaseLayer }) { "Base layer must be at the bottom" }
        require(layers.any { it.id == activeId }) { "Active layer $activeId not in stack" }
    }

    val base: BaseLayer get() = layers[0] as BaseLayer
    val active: Layer get() = layer(activeId)!!

    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }
    fun indexOf(id: Long) = layers.indexOfFirst { it.id == id }

    /** The chunk shown at [pos], or null when the position is empty. */
    fun resolve(pos: ChunkPos): ChunkRef? = layerAt(pos)?.let { contentOf(it, pos) }

    /** Topmost visible layer that provides a chunk at [pos]. */
    fun layerAt(pos: ChunkPos): Layer? {
        for (i in layers.indices.reversed()) {
            val layer = layers[i]
            if (layer.visible && contentOf(layer, pos) != null) return layer
        }
        return null
    }

    fun contentOf(layer: Layer, pos: ChunkPos): ChunkRef? = when (layer) {
        is ChunkLayer -> layer.chunks[pos]
        is BaseLayer -> layer.resolve(pos)
    }

    /** True if any layer shows chunks copied from [other] (so [other] must stay open). */
    fun usesWorld(other: WorldSource): Boolean =
        base.overrides.values.any { it is ChunkEdit.Place && it.source.world == other } ||
            layers.any { it is ChunkLayer && it.chunks.values.any { ref -> ref.world == other } }

    /** Positions where [id] has a chunk (for "layer to selection"). */
    fun positionsOf(id: Long): Set<ChunkPos> = when (val layer = layer(id)) {
        is ChunkLayer -> layer.chunks.keys
        is BaseLayer -> buildSet {
            val b = layer.world.bounds
            if (b != null) for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                val pos = ChunkPos(x, z)
                if (layer.resolve(pos) != null) add(pos)
            }
            for ((pos, edit) in layer.overrides) if (edit is ChunkEdit.Place) add(pos)
        }
        null -> emptySet()
    }

    /** Area covering every layer, visible or not. */
    val bounds: ChunkBounds? get() = boundsFrom(world.bounds)

    /** Like [bounds] but starting from the world's main area, for framing the camera. */
    val focusBounds: ChunkBounds? get() = boundsFrom(world.focusBounds)

    private fun boundsFrom(start: ChunkBounds?): ChunkBounds? {
        var b = start
        fun include(pos: ChunkPos) {
            b = b?.let { ChunkBounds(minOf(it.minX, pos.x), minOf(it.minZ, pos.z), maxOf(it.maxX, pos.x), maxOf(it.maxZ, pos.z)) }
                ?: ChunkBounds(pos.x, pos.z, pos.x, pos.z)
        }
        for ((pos, edit) in base.overrides) if (edit is ChunkEdit.Place) include(pos)
        for (layer in layers) if (layer is ChunkLayer) layer.chunks.keys.forEach(::include)
        return b
    }

    // ---- Layer operations (each returns a new stack) ----

    fun withActive(id: Long) = if (layer(id) == null) this else copy(activeId = id)

    fun update(id: Long, transform: (Layer) -> Layer) = copy(layers = layers.map { if (it.id == id) transform(it) else it })

    fun setVisible(id: Long, visible: Boolean) = update(id) {
        when (it) { is BaseLayer -> it.copy(visible = visible); is ChunkLayer -> it.copy(visible = visible) }
    }

    fun setLocked(id: Long, locked: Boolean) = update(id) {
        when (it) { is BaseLayer -> it.copy(locked = locked); is ChunkLayer -> it.copy(locked = locked) }
    }

    fun rename(id: Long, name: String) = update(id) { if (it is ChunkLayer && name.isNotBlank()) it.copy(name = name.trim()) else it }

    /** Adds a chunk layer directly above the active layer and makes it active. */
    fun addLayer(name: String, chunks: Map<ChunkPos, ChunkRef> = emptyMap()): LayerStack {
        val layer = ChunkLayer(nextId, name, chunks, color = LAYER_COLORS[((nextId - 1) % LAYER_COLORS.size).toInt()])
        val at = indexOf(activeId) + 1
        return copy(layers = layers.toMutableList().apply { add(at, layer) }, activeId = layer.id, nextId = nextId + 1)
    }

    fun duplicate(id: Long): LayerStack {
        val layer = layer(id) ?: return this
        val chunks = when (layer) {
            is ChunkLayer -> layer.chunks
            is BaseLayer -> positionsOf(id).associateWith { layer.resolve(it)!! }
        }
        val name = if (layer is BaseLayer) "${world.name} copy" else "${layer.name} copy"
        return withActive(id).addLayer(name, chunks)
    }

    fun remove(id: Long): LayerStack {
        val i = indexOf(id)
        if (i <= 0) return this
        val newActive = if (id == activeId) layers[i - 1].id else activeId
        return copy(layers = layers.filterNot { it.id == id }, activeId = newActive)
    }

    /** Moves a chunk layer up (+) or down (-); it can never go below the base layer. */
    fun move(id: Long, delta: Int): LayerStack {
        val i = indexOf(id)
        if (i <= 0) return this
        val target = (i + delta).coerceIn(1, layers.lastIndex)
        if (target == i) return this
        return copy(layers = layers.toMutableList().apply { add(target, removeAt(i)) })
    }

    fun canMergeDown(id: Long) = indexOf(id) > 0

    /** Merges a layer into the one below it; its chunks win where both have one. */
    fun mergeDown(id: Long): LayerStack {
        val i = indexOf(id)
        if (i <= 0) return this
        val upper = layers[i] as ChunkLayer
        val merged = when (val lower = layers[i - 1]) {
            is ChunkLayer -> lower.copy(chunks = lower.chunks + upper.chunks)
            is BaseLayer -> lower.withOverrides(upper.chunks.mapValues { ChunkEdit.Place(it.value) })
        }
        val next = layers.toMutableList().apply {
            removeAt(i)
            set(i - 1, merged)
        }
        return copy(layers = next, activeId = merged.id)
    }

    /** Merges every visible layer into the base layer and drops hidden ones, like GIMP's Flatten Image. */
    fun flatten(): LayerStack {
        var b = base.copy(visible = true)
        for (layer in layers.drop(1)) {
            if (layer is ChunkLayer && layer.visible) b = b.withOverrides(layer.chunks.mapValues { ChunkEdit.Place(it.value) })
        }
        return copy(layers = listOf(b), activeId = BaseLayer.BASE_ID)
    }

    /** Removes chunks from a layer: chunk layers become empty there, the base layer gets erased. */
    fun clear(id: Long, positions: Collection<ChunkPos>): LayerStack = update(id) { layer ->
        when (layer) {
            is ChunkLayer -> layer.copy(chunks = layer.chunks - positions.toSet())
            is BaseLayer -> layer.withOverrides(positions.associateWith { ChunkEdit.Delete })
        }
    }

    /** Restores original chunks in the base layer at [positions]. */
    fun restore(positions: Collection<ChunkPos>) = update(BaseLayer.BASE_ID) { (it as BaseLayer).withOverrides(positions.associateWith { null }) }

    /** Shifts every chunk of a chunk layer by ([dx], [dz]) chunks. */
    fun offset(id: Long, dx: Int, dz: Int): LayerStack = update(id) { layer ->
        if (layer !is ChunkLayer || (dx == 0 && dz == 0)) layer
        else layer.copy(chunks = layer.chunks.mapKeys { (pos, _) -> ChunkPos(pos.x + dx, pos.z + dz) })
    }

    companion object {
        fun of(world: WorldSource) = LayerStack(world, listOf(BaseLayer(world)), BaseLayer.BASE_ID, nextId = 1)

        val LAYER_COLORS = intArrayOf(
            0xFFFFB547.toInt(), 0xFF3DD6D0.toInt(), 0xFFFF6FB5.toInt(), 0xFFA98BFF.toInt(),
            0xFF9BE15D.toInt(), 0xFFFF8A4C.toInt(), 0xFF5CB8FF.toInt(),
        )
    }
}
