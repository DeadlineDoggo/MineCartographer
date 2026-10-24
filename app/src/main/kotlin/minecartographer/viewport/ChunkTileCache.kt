package minecartographer.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkBlocks
import minecartographer.core.ChunkSurface
import minecartographer.core.WorldSource
import minecartographer.core.rotated
import minecartographer.textures.BlockModelSource
import minecartographer.textures.FlatModels
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads and renders chunk tiles on background threads, in three levels:
 *  - chunk data (a [ChunkSurface] for the Map mode, [ChunkBlocks] for the others) is what is slow to get,
 *    since it comes from disk and NBT. Plenty is kept, also for chunks around the view, so panning,
 *    zooming and changing settings only re-render.
 *  - tiles (bitmaps per LOD and rotation) are big, so only a memory-bounded LRU of them is kept.
 *  - at low LODs, [block] composites of [BLOCK_SIZE] x [BLOCK_SIZE] tiles, so a zoomed-out view
 *    needs few draws.
 *
 * The viewport calls [beginFrame], [request] for every visible chunk and [endFrame] on each draw,
 * so the work queue always holds exactly what is on screen, nearest to the center first.
 * [prefetch] queues surfaces for the area around the view, loaded when nothing visible is waiting.
 */
class ChunkTileCache(
    scope: CoroutineScope,
    workers: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8),
    private val maxBytes: Long = 512L shl 20,
    private val maxDataBytes: Long = 256L shl 20,
    private val maxBlockBytes: Long = 256L shl 20,
) {
    /** [rotation] is part of the key: the visible faces depend on the side the chunk is seen from. */
    private data class Key(val worldId: String, val pos: ChunkPos, val lod: Int, val rotation: Int) {
        val surfaceKey get() = SurfaceKey(worldId, pos)
    }
    private data class SurfaceKey(val worldId: String, val pos: ChunkPos)
    /** Chunk data of one kind: [blocks] false for the surface, true for the visible blocks. */
    private data class DataKey(val worldId: String, val pos: ChunkPos, val blocks: Boolean)
    private class Job(val key: Key, val world: WorldSource, val priority: Float)
    private class Prefetch(val key: DataKey, val world: WorldSource)
    /** A block of chunks in view space; [rotation] keeps blocks of different rotations apart. */
    private data class BlockKey(val bx: Int, val bz: Int, val lod: Int, val rotation: Int)
    private class Block(val parts: List<BlockPart>, val composite: BlockComposite)

    private val lock = Any()
    private val tiles = LinkedHashMap<Key, ChunkTile>(1024, 0.75f, true)
    private var bytes = 0L
    /** Evicted tiles and blocks; their images are freed at the next [beginFrame], when no frame can still be drawing them. */
    private val evicted = ArrayList<AutoCloseable>()
    private val blocks = LinkedHashMap<BlockKey, Block>(256, 0.75f, true)
    private var blockBytes = 0L
    private val requestedBlocks = LinkedHashMap<BlockKey, List<BlockPart>>()
    private var pendingBlocks = ArrayDeque<Pair<BlockKey, List<BlockPart>>>()
    private val compositing = HashMap<BlockKey, List<BlockPart>>()
    private val data = LinkedHashMap<DataKey, Any>(4096, 0.75f, true)
    private var dataBytes = 0L
    /** Chunks with nothing to draw (not fully generated, or failed to load). */
    private val empty = HashSet<SurfaceKey>()

    /** Requests of the frame being drawn; become [pending] at [endFrame]. */
    private val requested = HashMap<Key, Job>()
    private var pending = ArrayDeque<Job>()
    private var prefetching = ArrayDeque<Prefetch>()
    private val inFlight = HashSet<Key>()
    private val loading = HashSet<DataKey>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var epoch = 0

    /** Incremented whenever a tile arrives; read it while drawing to redraw on new tiles. */
    var generation by mutableIntStateOf(0)
        private set

    var settings: RenderSettings = RenderSettings()
        set(value) {
            if (value == field) return
            synchronized(lock) { field = value }
            invalidateTiles()
        }

    /**
     * Models for the Textured mode by world id. A chunk is drawn with the textures of the world it comes from,
     * also when it was pasted into another; worlds without any get the Blocks look.
     */
    var textures: Map<String, BlockModelSource> = emptyMap()
        set(value) {
            if (value == field) return
            synchronized(lock) { field = value }
            if (settings.mode == RenderMode.Textured) invalidateTiles()
        }

    /**
     * Reads the chunks of these worlds again (their block colors changed, e.g. learned from mod textures)
     * and redraws everything; other worlds keep their loaded data.
     */
    fun reloadWorlds(worldIds: Set<String>) {
        if (worldIds.isEmpty()) return
        synchronized(lock) {
            val it = data.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.worldId in worldIds) {
                    dataBytes -= sizeOf(e.value)
                    it.remove()
                }
            }
            empty.removeIf { it.worldId in worldIds }
        }
        invalidateTiles()
    }

    /** Drops every tile and block so they are rendered again; loaded chunk data is kept. */
    private fun invalidateTiles() {
        synchronized(lock) {
            epoch++
            evicted += tiles.values
            tiles.clear()
            bytes = 0
            pending.clear()
            blocks.values.mapTo(evicted) { it.composite }
            blocks.clear()
            blockBytes = 0
            pendingBlocks.clear()
        }
        generation++
    }

    init {
        // Own threads, a notch below normal priority, so decoding never competes with the UI thread
        // and blocking disk reads don't occupy Dispatchers.Default.
        val count = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "chunk-worker-${count.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        repeat(workers) { scope.launch(dispatcher) { workLoop() } }
        scope.coroutineContext.job.invokeOnCompletion { executor.shutdownNow() }
    }

    /** Call on the UI thread before drawing. */
    fun beginFrame() {
        val free = synchronized(lock) {
            requested.clear()
            requestedBlocks.clear()
            if (evicted.isEmpty()) return
            evicted.toList().also { evicted.clear() }
        }
        free.forEach { it.close() }
    }

    /**
     * Returns the tile at [lod] if cached, otherwise queues it and returns a cached tile
     * at another LOD (or null) to draw meanwhile. [pos] is the chunk's real position in [world].
     * Lower [priority] is rendered first.
     */
    fun request(world: WorldSource, pos: ChunkPos, lod: Int, rotation: Int, priority: Float): ChunkTile? = synchronized(lock) {
        if (SurfaceKey(world.id, pos) in empty) return null
        val key = Key(world.id, pos, lod, rotation)
        tiles[key]?.let { return it }
        if (key !in inFlight) requested[key] = Job(key, world, priority)
        IsoView.LODS.firstNotNullOfOrNull { other -> if (other == lod) null else tiles[Key(world.id, pos, other, rotation)] }
    }

    /** Hands the frame's requests to the workers. Until then they keep working on the previous frame's. */
    fun endFrame() {
        synchronized(lock) {
            pending = ArrayDeque(sortedByPriority(requested.values))
            requested.clear()
            pendingBlocks = ArrayDeque(requestedBlocks.size)
            for ((key, parts) in requestedBlocks) pendingBlocks += key to parts
            requestedBlocks.clear()
            if (pending.isEmpty() && pendingBlocks.isEmpty()) return
        }
        wakeUp.trySend(Unit)
    }

    /**
     * Replaces the background queue: surfaces of these chunks (most important first) are loaded
     * whenever no visible tile is waiting, so they are ready when they scroll into view.
     */
    fun prefetch(chunks: List<Pair<WorldSource, ChunkPos>>) {
        synchronized(lock) {
            prefetching = ArrayDeque(chunks.size)
            val blocks = settings.mode != RenderMode.Map
            for ((world, pos) in chunks) {
                val key = DataKey(world.id, pos, blocks)
                if (key !in data && SurfaceKey(world.id, pos) !in empty) prefetching += Prefetch(key, world)
            }
            if (prefetching.isEmpty()) return
        }
        wakeUp.trySend(Unit)
    }

    /**
     * The composite of block ([bx], [bz]) if one exists for exactly these [parts] (back to front, all tiles at
     * [lod] with pixels). Otherwise queues one and returns null; draw the parts one by one meanwhile.
     */
    fun block(bx: Int, bz: Int, lod: Int, rotation: Int, parts: List<BlockPart>): BlockComposite? = synchronized(lock) {
        val key = BlockKey(bx, bz, lod, rotation)
        val cached = blocks[key]
        if (cached != null && cached.parts == parts) return cached.composite
        if (compositing[key] != parts) requestedBlocks[key] = parts.toList()
        null
    }

    /** True when the chunk has nothing to draw (e.g. not fully generated), so no tile will ever come. */
    fun isBlank(worldId: String, pos: ChunkPos): Boolean = synchronized(lock) { SurfaceKey(worldId, pos) in empty }

    /** Any cached tile for the chunk, without queueing work. */
    fun peek(worldId: String, pos: ChunkPos): ChunkTile? = synchronized(lock) {
        for (r in 0..3) for (lod in IsoView.LODS) tiles[Key(worldId, pos, lod, r)]?.let { return it }
        null
    }

    private sealed interface Work
    private class RenderTile(val job: Job, val epoch: Int, val settings: RenderSettings, val textures: Map<String, BlockModelSource>) : Work
    private class LoadSurface(val prefetch: Prefetch) : Work
    private class Composite(val key: BlockKey, val parts: List<BlockPart>, val epoch: Int, val hs: Double) : Work

    /** Visible tiles first, then blocks, then prefetching. Called with [lock] held. */
    private fun nextWork(): Work? {
        while (pending.isNotEmpty()) {
            val job = pending.removeFirst()
            if (job.key in inFlight || job.key in tiles) continue
            inFlight += job.key
            return RenderTile(job, epoch, settings, textures)
        }
        while (pendingBlocks.isNotEmpty()) {
            val (key, parts) = pendingBlocks.removeFirst()
            if (compositing[key] == parts || blocks[key]?.parts == parts) continue
            compositing[key] = parts
            return Composite(key, parts, epoch, settings.heightScale.toDouble())
        }
        while (prefetching.isNotEmpty()) {
            val p = prefetching.removeFirst()
            if (p.key in data || SurfaceKey(p.key.worldId, p.key.pos) in empty || p.key in loading) continue
            return LoadSurface(p)
        }
        return null
    }

    private suspend fun workLoop() {
        while (currentCoroutineContext().isActive) {
            val work = synchronized(lock) {
                nextWork()?.also {
                    // Let another idle worker pick up the rest.
                    if (pending.isNotEmpty() || pendingBlocks.isNotEmpty() || prefetching.isNotEmpty()) wakeUp.trySend(Unit)
                }
            }
            when (work) {
                null -> wakeUp.receive()
                is LoadSurface -> load(work.prefetch.world, work.prefetch.key)
                is RenderTile -> renderTile(work)
                is Composite -> composite(work)
            }
        }
    }

    private fun renderTile(work: RenderTile) {
        val key = work.job.key
        val settings = work.settings
        val tile = runCatching {
            when (settings.mode) {
                RenderMode.Map -> (load(work.job.world, DataKey(key.worldId, key.pos, blocks = false)) as ChunkSurface?)?.let {
                    ChunkTileRenderer.render(it.rotated(key.rotation), key.lod, settings)
                }
                RenderMode.Blocks, RenderMode.Textured -> (load(work.job.world, DataKey(key.worldId, key.pos, blocks = true)) as ChunkBlocks?)?.let {
                    val models = if (settings.mode == RenderMode.Textured) work.textures[key.worldId] ?: FlatModels else FlatModels
                    BlockTileRenderer.render(it.rotated(key.rotation), key.rotation, key.lod, settings, models)
                }
            }
        }.onFailure { it.printStackTrace() }.getOrNull()

        synchronized(lock) {
            inFlight -= key
            // Nothing to draw, or it failed: don't retry every frame. Not for stale work, which may have failed
            // only because its textures were closed meanwhile.
            if (tile == null && work.epoch == epoch) empty += key.surfaceKey
            if (tile != null && work.epoch != epoch) evicted += tile
            if (tile == null || work.epoch != epoch) return@synchronized
            tiles.put(key, tile)?.let {
                bytes -= it.byteSize
                evicted += it
            }
            bytes += tile.byteSize
            while (bytes > maxBytes && tiles.isNotEmpty()) {
                val eldest = tiles.entries.iterator()
                val old = eldest.next().value
                bytes -= old.byteSize
                evicted += old
                eldest.remove()
            }
        }
        generation++
    }

    private fun composite(work: Composite) {
        val composite = runCatching { BlockComposite.of(work.parts, work.hs) }.onFailure { it.printStackTrace() }.getOrNull()
        synchronized(lock) {
            if (compositing[work.key] === work.parts) compositing -= work.key
            if (composite == null) return
            if (work.epoch != epoch) {
                evicted += composite
                return
            }
            blocks.put(work.key, Block(work.parts, composite))?.let {
                blockBytes -= it.composite.byteSize
                evicted += it.composite
            }
            blockBytes += composite.byteSize
            while (blockBytes > maxBlockBytes && blocks.isNotEmpty()) {
                val eldest = blocks.entries.iterator()
                val old = eldest.next().value.composite
                blockBytes -= old.byteSize
                evicted += old
                eldest.remove()
            }
        }
        generation++
    }

    /** Cached chunk data, or reads it from the world (null for chunks with nothing to draw). */
    private fun load(world: WorldSource, key: DataKey): Any? {
        val blank = SurfaceKey(key.worldId, key.pos)
        synchronized(lock) {
            data[key]?.let { return it }
            if (blank in empty) return null
            loading += key
        }
        val loaded: Any? = try {
            if (key.blocks) world.loadBlocks(key.pos) else world.loadSurface(key.pos)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        synchronized(lock) {
            loading -= key
            if (loaded == null) {
                empty += blank
            } else {
                data.put(key, loaded)?.let { dataBytes -= sizeOf(it) }
                dataBytes += sizeOf(loaded)
                val it = data.entries.iterator()
                while (dataBytes > maxDataBytes && it.hasNext()) {
                    dataBytes -= sizeOf(it.next().value)
                    it.remove()
                }
            }
        }
        return loaded
    }

    private fun sizeOf(value: Any): Long = when (value) {
        is ChunkBlocks -> value.byteSize
        else -> SURFACE_BYTES
    }

    companion object {
        /** Blocks of this many chunks squared are composited into one image, */
        const val BLOCK_SIZE = 8
        /** at LODs up to this one, where there are many small tiles on screen. */
        const val MAX_BLOCK_LOD = 2

        /** Two arrays of 256 ints plus object overhead. */
        private const val SURFACE_BYTES = 2 * (256 * 4 + 16) + 64L

        /** Sorts on primitive longs (priority bits, then index): sorting thousands of jobs each frame stays cheap. */
        private fun sortedByPriority(jobs: Collection<Job>): List<Job> {
            val list = jobs.toList()
            val order = LongArray(list.size) { i -> (list[i].priority.toRawBits().toLong() shl 32) or i.toLong() }
            order.sort() // non-negative floats order the same as their raw bits
            return List(list.size) { list[(order[it] and 0xFFFFFFFFL).toInt()] }
        }
    }
}
