package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.ListTag

/**
 * Builds a [ChunkSurface] (top block height and color per column) from chunk NBT of any
 * Anvil version. Handles the three storage layouts:
 *  - 1.2-1.12: `Level.Sections[].Blocks/Add/Data` numeric ids
 *  - 1.13-1.17: `Level.Sections[].Palette + BlockStates` (values span longs before 1.16)
 *  - 1.18+: root `sections[].block_states.{palette,data}`
 */
object SurfaceExtractor {
    /** 1.13 (17w47a): block names and palettes. */
    const val DV_FLATTENING = 1451
    /** 1.16 (20w17a): packed values no longer span two longs. */
    const val DV_NO_SPANNING = 2529
    /** 1.18 (21w43a): chunk data moved out of the `Level` compound. */
    const val DV_FLAT_CHUNK = 2844

    private val COMPLETE_STATUS = setOf("full", "minecraft:full", "fullchunk", "postprocessed", "minecraft:fullchunk")

    /** Chunk tags the surface scan never looks at; they are stepped over while parsing, not decoded. */
    val UNUSED_TAGS = setOf(
        "Entities", "TileEntities", "block_entities", "Heightmaps", "BlockLight", "SkyLight", "Biomes", "biomes",
        "Structures", "structures", "TileTicks", "LiquidTicks", "block_ticks", "fluid_ticks", "PostProcessing",
        "Lights", "ToBeTicked", "LiquidsToBeTicked", "CarvingMasks", "blending_data", "UpgradeData",
    )

    /**
     * A 16x16x16 section: what each block looks like from above. Block indices are only unpacked
     * when the scan first reaches the section; most columns stop long before the lower ones.
     */
    private class Section(val y: Int, val looks: Array<BlockLook>, unpack: (() -> IntArray)?) {
        private val indices by lazy(LazyThreadSafetyMode.NONE) { unpack?.invoke() }
        fun look(x: Int, y: Int, z: Int): BlockLook = if (looks.size == 1) looks[0] else looks[indices!![(y shl 8) or (z shl 4) or x]]
        val allSkip = looks.all { it.kind == BlockKind.SKIP }
    }

    fun extract(root: CompoundTag, pos: ChunkPos): ChunkSurface? {
        val dataVersion = root.int("DataVersion") ?: 0
        val level = root.compound("Level")
        val chunk = level ?: root
        // Proto-chunks (partly generated) are stored too; they would show as holes or half terrain.
        val status = chunk.string("Status")
        if (status != null && status !in COMPLETE_STATUS) return null

        val sections = when {
            level == null -> modernSections(root.list("sections"))
            dataVersion >= DV_FLATTENING -> paletteSections(level.list("Sections"), spanning = dataVersion < DV_NO_SPANNING)
            else -> legacySections(level.list("Sections"))
        }.filterNot { it.allSkip }.sortedByDescending { it.y }
        if (sections.isEmpty()) return null
        return scan(pos, sections)
    }

    private fun scan(pos: ChunkPos, sections: List<Section>): ChunkSurface? {
        val heights = IntArray(ChunkSurface.COLUMNS) { Int.MIN_VALUE }
        val colors = IntArray(ChunkSurface.COLUMNS)
        val lowest = sections.last().y
        val bySectionY = arrayOfNulls<Section>(sections.first().y - lowest + 1)
        for (s in sections) bySectionY[s.y - lowest] = s
        val topY = sections.first().y * 16 + 15
        val bottomY = lowest * 16

        fun lookAt(x: Int, y: Int, z: Int): BlockLook? = bySectionY[(y shr 4) - lowest]?.look(x, y and 15, z)

        for (z in 0 until 16) for (x in 0 until 16) {
            var y = topY
            var waterTop = Int.MIN_VALUE
            while (y >= bottomY) {
                val look = lookAt(x, y, z)
                if (look == null) { // missing section: all air
                    y = (y shr 4 shl 4) - 1
                    continue
                }
                when (look.kind) {
                    BlockKind.SKIP -> {}
                    BlockKind.WATER -> if (waterTop == Int.MIN_VALUE) waterTop = y
                    BlockKind.SOLID -> break
                }
                y--
            }
            val i = z * 16 + x
            when {
                waterTop != Int.MIN_VALUE -> {
                    heights[i] = waterTop
                    colors[i] = BlockColors.waterColor(waterTop - y)
                }
                y >= bottomY -> {
                    heights[i] = y
                    colors[i] = lookAt(x, y, z)!!.color
                }
            }
        }

        // Columns with nothing in them (void) are transparent and sit at the lowest real height,
        // so they don't stretch the tile down to the bottom of the world.
        val solid = heights.filter { it != Int.MIN_VALUE }
        if (solid.isEmpty()) return null
        val floor = solid.min()
        for (i in heights.indices) if (heights[i] == Int.MIN_VALUE) heights[i] = floor
        return ChunkSurface(pos, heights, colors)
    }

    // ---- 1.18+ ----

    private fun modernSections(list: ListTag?): List<Section> = list.orEmpty().mapNotNull { tag ->
        val s = tag as? CompoundTag ?: return@mapNotNull null
        val states = s.compound("block_states") ?: return@mapNotNull null
        val palette = states.list("palette") ?: return@mapNotNull null
        val looks = palette.compounds().map { BlockColors.look(it.string("Name") ?: "air") }.toTypedArray()
        if (looks.isEmpty()) return@mapNotNull null
        val data = states.longArray("data")
        if (data == null) return@mapNotNull Section(s.int("Y") ?: 0, arrayOf(looks[0]), null)
        Section(s.int("Y") ?: 0, looks) { unpack(data, looks.size, spanning = false) }
    }

    // ---- 1.13 - 1.17 ----

    private fun paletteSections(list: ListTag?, spanning: Boolean): List<Section> = list.orEmpty().mapNotNull { tag ->
        val s = tag as? CompoundTag ?: return@mapNotNull null
        val palette = s.list("Palette") ?: return@mapNotNull null // light-only sections
        val states = s.longArray("BlockStates") ?: return@mapNotNull null
        val looks = palette.compounds().map { BlockColors.look(it.string("Name") ?: "air") }.toTypedArray()
        if (looks.isEmpty()) return@mapNotNull null
        Section(s.int("Y") ?: 0, looks) { unpack(states, looks.size, spanning) }
    }

    /**
     * Unpacks 4096 palette indices. Bits per entry are max(4, ceil(log2(paletteSize))).
     * Before 1.16 entries are packed back to back and may straddle two longs; since 1.16
     * each long holds floor(64 / bits) entries and the leftover bits are padding.
     */
    internal fun unpack(longs: LongArray, paletteSize: Int, spanning: Boolean): IntArray {
        val bits = maxOf(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1))
        val mask = (1L shl bits) - 1
        val out = IntArray(4096)
        if (spanning) {
            for (i in 0 until 4096) {
                val bit = i * bits
                val li = bit ushr 6
                val off = bit and 63
                if (li >= longs.size) break
                var v = longs[li] ushr off
                if (off + bits > 64 && li + 1 < longs.size) v = v or (longs[li + 1] shl (64 - off))
                out[i] = (v and mask).toInt().coerceAtMost(paletteSize - 1)
            }
        } else {
            val perLong = 64 / bits
            for (i in 0 until 4096) {
                val li = i / perLong
                if (li >= longs.size) break
                out[i] = ((longs[li] ushr ((i % perLong) * bits)) and mask).toInt().coerceAtMost(paletteSize - 1)
            }
        }
        return out
    }

    // ---- 1.2 - 1.12 ----

    private fun legacySections(list: ListTag?): List<Section> = list.orEmpty().mapNotNull { tag ->
        val s = tag as? CompoundTag ?: return@mapNotNull null
        val blocks = s.byteArray("Blocks") ?: return@mapNotNull null
        if (blocks.size < 4096) return@mapNotNull null
        val add = s.byteArray("Add")
        val data = s.byteArray("Data")
        // Build a small per-section palette of (id, data) so the scan works like the modern formats.
        // paletteIndex maps (id << 4 | data) to palette index + 1; only the used slots are cleared afterwards.
        val paletteIndex = legacyPaletteIndex.get()
        val keys = ArrayList<Int>()
        val looks = ArrayList<BlockLook>()
        val indices = IntArray(4096) { i ->
            var id = blocks[i].toInt() and 0xFF
            if (add != null) id = id or (nibble(add, i) shl 8)
            val meta = if (data != null) nibble(data, i) else 0
            val key = (id shl 4) or meta
            var index = paletteIndex[key]
            if (index == 0) {
                looks += BlockColors.legacy(id, meta)
                keys += key
                index = looks.size
                paletteIndex[key] = index
            }
            index - 1
        }
        for (key in keys) paletteIndex[key] = 0
        Section(s.int("Y") ?: 0, looks.toTypedArray()) { indices }
    }

    private val legacyPaletteIndex = ThreadLocal.withInitial { IntArray(1 shl 16) }

    private fun nibble(array: ByteArray, i: Int): Int {
        val b = array[i shr 1].toInt()
        return if (i and 1 == 0) b and 0x0F else (b shr 4) and 0x0F
    }
}
