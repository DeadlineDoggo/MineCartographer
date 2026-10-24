package minecartographer.core

import java.util.concurrent.ConcurrentHashMap

/**
 * A block with its state properties, e.g. `minecraft:oak_stairs` with `facing=east, half=bottom`.
 * Interned: equal states are the same object, so they can be compared and hashed by identity.
 */
class BlockState private constructor(val name: String, val properties: Map<String, String>) {
    /** The name without its namespace, e.g. `oak_stairs`. */
    val path: String = name.substringAfter(':')
    val isAir: Boolean = path == "air" || path == "cave_air" || path == "void_air"

    override fun toString() = if (properties.isEmpty()) name else "$name$properties"

    companion object {
        private val interned = ConcurrentHashMap<Pair<String, Map<String, String>>, BlockState>()

        fun of(name: String, properties: Map<String, String> = emptyMap()): BlockState {
            val full = if (':' in name) name else "minecraft:$name"
            val key = full to properties
            return interned[key] ?: interned.computeIfAbsent(key) { BlockState(full, properties) }
        }

        val AIR = of("minecraft:air")

        /** Not a real block: a cube of one color, for worlds that only have colors (see [ChunkBlocks.fromSurface]). */
        const val COLOR_BLOCK = "minecartographer:color"
        fun ofColor(argb: Int) = of(COLOR_BLOCK, mapOf("rgb" to Integer.toHexString(argb and 0xFFFFFF)))
    }
}

/**
 * The blocks of a chunk that can be seen from above: from just below its lowest surface point up to
 * its highest block. Caves and ground further down are left out, which keeps this small.
 *
 * Blocks are indices into [palette] (`palette[0]` is always air), stored `(y - minY) * 256 + lz * 16 + lx`.
 * Positions outside [minY]..[maxY] are air.
 */
class ChunkBlocks(
    val pos: ChunkPos,
    val minY: Int,
    val maxY: Int,
    val palette: Array<BlockState>,
    private val blocks: CharArray,
    /** Depth of the water under each column's top water block, 0 where the column is dry; indexed `lz * 16 + lx`. */
    val waterDepth: ByteArray,
) {
    init {
        require(palette.isNotEmpty() && palette[0] === BlockState.AIR) { "palette[0] must be air" }
        require(blocks.size == (maxY - minY + 1) * 256) { "Expected ${(maxY - minY + 1) * 256} blocks" }
    }

    val byteSize: Long get() = blocks.size * 2L + waterDepth.size + palette.size * 8L + 64

    fun index(lx: Int, y: Int, lz: Int): Int = if (y < minY || y > maxY) 0 else blocks[((y - minY) shl 8) or (lz shl 4) or lx].code

    fun state(lx: Int, y: Int, lz: Int): BlockState = palette[index(lx, y, lz)]

    /** The same chunk seen after rotating the view; see [ChunkSurface.rotated]. */
    fun rotated(turns: Int): ChunkBlocks {
        val t = Rotation.normalize(turns)
        if (t == 0) return this
        val out = CharArray(blocks.size)
        val depth = ByteArray(256)
        for (lz in 0 until 16) for (lx in 0 until 16) {
            val (rx, rz) = when (t) {
                1 -> (15 - lz) to lx
                2 -> (15 - lx) to (15 - lz)
                else -> lz to (15 - lx)
            }
            depth[rz * 16 + rx] = waterDepth[lz * 16 + lx]
            for (layer in 0 until blocks.size / 256) out[(layer shl 8) or (rz shl 4) or rx] = blocks[(layer shl 8) or (lz shl 4) or lx]
        }
        return ChunkBlocks(pos, minY, maxY, palette, out, depth)
    }

    companion object {
        /** Columns of single-colored blocks, for worlds that only provide a [ChunkSurface]. */
        fun fromSurface(surface: ChunkSurface): ChunkBlocks {
            val minY = surface.minHeight - 1
            val maxY = surface.maxHeight
            val palette = arrayListOf(BlockState.AIR)
            val indexOf = HashMap<Int, Int>()
            val blocks = CharArray((maxY - minY + 1) * 256)
            for (lz in 0 until 16) for (lx in 0 until 16) {
                val color = surface.color(lx, lz)
                val index = indexOf.getOrPut(color) { palette += BlockState.ofColor(color); palette.size - 1 }
                for (y in minY..surface.height(lx, lz)) blocks[((y - minY) shl 8) or (lz shl 4) or lx] = index.toChar()
            }
            return ChunkBlocks(surface.pos, minY, maxY, palette.toTypedArray(), blocks, ByteArray(256))
        }
    }
}
