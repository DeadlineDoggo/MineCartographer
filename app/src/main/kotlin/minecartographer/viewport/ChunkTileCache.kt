package minecartographer.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource
import minecartographer.core.rotated

/**
 * Renders chunk tiles on background workers and keeps them in an LRU cache bounded by memory.
 *
 * The viewport calls [beginFrame] and then [request] for every visible chunk on each draw,
 * so the work queue always holds exactly what is currently on screen.
 */
class ChunkTileCache(
    scope: CoroutineScope,
    workers: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6),
    private val maxBytes: Long = 512L shl 20,
) {
    /** [rotation] is part of the key: the visible faces depend on the side the chunk is seen from. */
    private data class Key(val worldId: String, val pos: ChunkPos, val lod: Int, val rotation: Int)
    private data class Job(val key: Key, val world: WorldSource, val epoch: Int)

    private val lock = Any()
    private val tiles = LinkedHashMap<Key, ChunkTile>(1024, 0.75f, true)
    private var bytes = 0L
    private val queue = LinkedHashMap<Key, Job>()
    private val inFlight = HashSet<Key>()
    private val empty = HashSet<Pair<String, ChunkPos>>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var epoch = 0

    /** Incremented whenever a tile arrives; read it while drawing to redraw on new tiles. */
    var generation by mutableIntStateOf(0)
        private set

    var settings: RenderSettings = RenderSettings()
        set(value) {
            if (value == field) return
            synchronized(lock) {
                field = value
                epoch++
                tiles.clear()
                bytes = 0
                queue.clear()
            }
            generation++
        }

    init {
        repeat(workers) { scope.launch { workLoop() } }
    }

    fun beginFrame() = synchronized(lock) { queue.clear() }

    /**
     * Returns the tile at [lod] if cached, otherwise queues it and returns a cached tile
     * at another LOD (or null) to draw meanwhile. [pos] is the chunk's real position in [world].
     */
    fun request(world: WorldSource, pos: ChunkPos, lod: Int, rotation: Int): ChunkTile? = synchronized(lock) {
        if (world.id to pos in empty) return null
        val key = Key(world.id, pos, lod, rotation)
        tiles[key]?.let { return it }
        if (key !in inFlight && key !in queue) {
            queue[key] = Job(key, world, epoch)
            wakeUp.trySend(Unit)
        }
        IsoView.LODS.firstNotNullOfOrNull { other -> if (other == lod) null else tiles[Key(world.id, pos, other, rotation)] }
    }

    /** Any cached tile for the chunk, without queueing work. */
    fun peek(worldId: String, pos: ChunkPos): ChunkTile? = synchronized(lock) {
        for (r in 0..3) for (lod in IsoView.LODS) tiles[Key(worldId, pos, lod, r)]?.let { return it }
        null
    }

    private suspend fun workLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val job = synchronized(lock) {
                queue.entries.firstOrNull()?.value?.also {
                    queue.remove(it.key)
                    inFlight += it.key
                    if (queue.isNotEmpty()) wakeUp.trySend(Unit) // let another idle worker pick up the rest
                }
            }
            if (job == null) {
                wakeUp.receive()
                continue
            }

            val tile = runCatching {
                job.world.loadSurface(job.key.pos)?.let {
                    ChunkTileRenderer.render(it.rotated(job.key.rotation), job.key.lod, settings)
                }
            }.onFailure { it.printStackTrace() }.getOrNull()

            synchronized(lock) {
                inFlight -= job.key
                if (job.epoch != epoch) return@synchronized
                if (tile == null) {
                    empty += job.key.worldId to job.key.pos
                } else {
                    tiles.put(job.key, tile)?.let { bytes -= it.byteSize }
                    bytes += tile.byteSize
                    evict()
                }
            }
            generation++
        }
    }

    private fun evict() {
        val it = tiles.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().value.byteSize
            it.remove()
        }
    }
}
