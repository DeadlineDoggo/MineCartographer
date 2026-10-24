package minecartographer.core.anvil

import minecartographer.core.BlockRotation
import minecartographer.core.BlockState
import minecartographer.core.ChunkPos
import minecartographer.core.Rotation
import minecartographer.core.WorldSource
import minecartographer.core.edit.ChunkRef
import minecartographer.core.convert.Conversion
import minecartographer.core.nbt.ByteArrayTag
import minecartographer.core.nbt.ByteTag
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.FloatTag
import minecartographer.core.nbt.IntArrayTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.LongArrayTag
import minecartographer.core.nbt.LongTag
import minecartographer.core.nbt.StringTag
import minecartographer.core.nbt.Tag
import java.util.UUID

/**
 * Rewrites a chunk turned, raised or laid over another: the work behind [ChunkRef]'s transforms, for export and
 * for drawing (which reads the result like any chunk, so the map shows what will be written).
 *
 * Every Anvil version is handled, each chunk in its own format: numeric ids with data values (1.2 - 1.12),
 * palettes packed across longs (1.13 - 1.15) or not (1.16 - 1.17), and sections with their own palettes and
 * biomes (1.18+). Blocks, their facing, biomes, block entities and entities move together. Scheduled ticks are
 * dropped and light is left for the game to compute again; so are heightmaps where the game rebuilds them.
 */
object ChunkTransform {
    /** 1.18: sections with their own palettes and biome names. */
    const val MODERN_DATA_VERSION = SurfaceExtractor.DV_FLAT_CHUNK

    /** How a chunk stores its blocks. */
    enum class Format { LEGACY, PALETTE_SPANNING, PALETTE, MODERN }

    fun formatOf(dataVersion: Int?): Format {
        val dv = dataVersion ?: 0
        return when {
            dv >= SurfaceExtractor.DV_FLAT_CHUNK -> Format.MODERN
            dv >= SurfaceExtractor.DV_NO_SPANNING -> Format.PALETTE
            dv >= SurfaceExtractor.DV_FLATTENING -> Format.PALETTE_SPANNING
            else -> Format.LEGACY
        }
    }

    /** 1.14: light is computed again from `isLightOn`, and missing heightmaps are rebuilt. */
    private const val DV_RELIGHT = 1952

    fun supports(world: WorldSource) = world is AnvilWorld

    /** How the source's part of a chunk is written. */
    private enum class Mode {
        /** As it is stored. */
        KEEP,
        /** In its own format, with blocks the target doesn't have mapped to others. */
        REMAP,
        /** Upgraded to the target's version, to share one chunk with the original below it. */
        UPGRADE,
    }

    /**
     * [ref]'s chunk and entity chunk (1.17+ entity files; null where entities are in the chunk) as stored at [to],
     * over the chunk of [under] (the original world) there, converted by [conversion] for the target world.
     * Null when the source chunk can't be read.
     */
    fun build(ref: ChunkRef, to: ChunkPos, under: AnvilWorld?, conversion: Conversion? = null, withEntities: Boolean = true): Pair<CompoundTag, CompoundTag?>? {
        val source = ref.world as? AnvilWorld ?: return null
        val raw = source.readChunk(ref.pos)?.nbt() ?: return null
        val floor = ref.floor
        val below = if (floor != null) under?.readChunk(to)?.nbt() else null
        val chunk = chunk(raw, ref.pos, below, to, ref.turns, ref.dy, floor, conversion)
        val outDv = chunk.int("DataVersion") ?: 0
        if (!withEntities || outDv < WorldExporter.DV_ENTITY_FILES) return chunk to null
        // Entities of the source: from its entity files, or from inside its chunk when it was upgraded from before 1.17.
        val sourceEntities = if ((source.dataVersion ?: 0) >= WorldExporter.DV_ENTITY_FILES) source.readEntities(ref.pos)?.nbt()
            else (raw.compound("Level") ?: raw).list("Entities")?.let { CompoundTag().apply { this["Entities"] = it } }
        val belowEntities = if (floor != null) under?.readEntities(to)?.nbt() else null
        val upgrade = conversion?.takeIf { it.fromDv < it.toDv && below != null }
        val entities = entities(sourceEntities, ref.pos, belowEntities, to, ref.turns, ref.dy, floor, outDv,
            frames3d = (source.dataVersion ?: 0) >= SurfaceExtractor.DV_FLATTENING, convert = upgrade?.let { c -> c::upgradeEntity })
        return chunk to entities
    }

    /**
     * The chunk to store at [to]: [source] (read at [from]) turned by [turns] and raised by [dy]. With a [floor],
     * only its blocks from there up are used and [under] (the original chunk at [to], or null for nothing) fills in
     * below; the source's part is then upgraded to [under]'s version by [conversion] when the two differ. Without a
     * floor the chunk keeps its own version (Minecraft upgrades it), with foreign blocks mapped by [conversion].
     * Neither input is changed.
     */
    fun chunk(source: CompoundTag, from: ChunkPos, under: CompoundTag?, to: ChunkPos, turns: Int, dy: Int, floor: Int?,
              conversion: Conversion? = null): CompoundTag {
        val t = Rotation.normalize(turns)
        val src = Volume.read(source)
        val read = if (floor != null) under?.let(Volume::read) else null
        val sourceDv = source.int("DataVersion") ?: 0
        val underDv = under?.int("DataVersion") ?: 0
        val needsUpgrade = read != null && (read.format != src.format || sourceDv < underDv)
        // Without a way to upgrade, chunks of two formats can't become one: the source then replaces the whole chunk.
        val below = if (needsUpgrade && conversion == null) null else read
        val keepUnder = below != null
        val mode = when {
            keepUnder && needsUpgrade -> Mode.UPGRADE
            conversion?.remaps == true -> Mode.REMAP
            else -> Mode.KEEP
        }
        // The original chunk keeps what belongs to its place (structures, inhabited time); otherwise it's the source's.
        val root = copy(if (keepUnder) under!! else source) as CompoundTag
        val level = root.compound("Level") ?: root
        val shape = below ?: src
        val out = Volume(shape.format, shape.minSection, shape.maxSection, shape.biomeKind)
        val dataVersion = when (mode) {
            Mode.UPGRADE -> underDv
            else -> listOfNotNull(source.int("DataVersion"), if (keepUnder) underDv else null).minOrNull()
        }
        dataVersion?.let { root.putInt("DataVersion", it) }
        val frames3d = (dataVersion ?: 0) >= SurfaceExtractor.DV_FLATTENING
        val sourceLevel = source.compound("Level") ?: source
        val sourceBeKey = if (src.format == Format.MODERN) "block_entities" else "TileEntities"

        // Blocks some of which need their block entity to be upgraded (flower pots, heads...), by source position.
        val sourceBe = HashMap<Int, CompoundTag>()
        if (mode == Mode.UPGRADE) for (be in sourceLevel.list(sourceBeKey)?.compounds().orEmpty()) {
            val x = be.int("x") ?: continue
            val y = be.int("y") ?: continue
            val z = be.int("z") ?: continue
            val turnedBe = if (t == 0) be else (copy(be) as CompoundTag).also { c -> c.int("Rot")?.let { c["Rot"] = ByteTag(((it + 4 * t) and 15).toByte()) } }
            sourceBe[packPos(x - from.x * 16, y, z - from.z * 16)] = turnedBe
        }
        // Entries repeat a lot: convert each distinct one once (palette entries are shared objects).
        val converted = java.util.IdentityHashMap<Any, Any>()
        val convertedInts = HashMap<Int, Any>()
        fun convert(entry: Any, sx: Int, sy: Int, sz: Int): Any {
            if (mode == Mode.KEEP) return entry
            if (mode == Mode.UPGRADE && entry is Int && (entry ushr 4) in BLOCKS_WITH_ENTITY) {
                return conversion!!.upgradeEntry(entry, sourceBe[packPos(sx, sy, sz)])
            }
            val cache = if (entry is Int) convertedInts as MutableMap<Any, Any> else converted
            return cache.getOrPut(entry) { if (mode == Mode.UPGRADE) conversion!!.upgradeEntry(entry, null) else conversion!!.remapEntry(entry) }
        }

        // Blocks: from the source (turned back to where they came from) above the floor, the original below.
        for (sy in out.minSection..out.maxSection) for (i in 0 until 4096) {
            val lx = i and 15
            val lz = (i shr 4) and 15
            val y = sy * 16 + (i shr 8)
            val entry = if (floor == null || y >= floor) {
                val (sx, sz) = unturn(t, lx, lz, 16)
                src.block(sx, y - dy, sz)?.let { convert(turned(it, t), sx, y - dy, sz) }
            } else {
                below?.block(lx, y, lz)
            }
            if (entry != null) out.setBlock(lx, y, lz, entry)
        }

        // Biomes: per 4x4x4 cell or per column, whichever the format has.
        fun biome(lx: Int, y: Int, lz: Int): Any? {
            val (sx, sz) = unturn(t, lx, lz, 16)
            val fromSource = src.biomeAt(sx, y - dy, sz)?.let { if (mode == Mode.UPGRADE) conversion!!.upgradeBiome(it) else it }
            val fromBelow = below?.biomeAt(lx, y, lz)
            return if (floor == null || y >= floor) fromSource ?: fromBelow else fromBelow ?: fromSource
        }
        when (out.biomeKind) {
            BiomeKind.SECTIONS, BiomeKind.INT_CELLS -> for (cy in out.minSection * 4 until (out.maxSection + 1) * 4) {
                for (cz in 0..3) for (cx in 0..3) biome(cx * 4 + 1, cy * 4 + 2, cz * 4 + 1)?.let { out.setBiomeCell(cx, cy, cz, it) }
            }
            BiomeKind.INT_COLUMNS, BiomeKind.BYTE_COLUMNS -> for (lz in 0..15) for (lx in 0..15) {
                // A column has one biome: the one of what is on top.
                (biome(lx, Int.MAX_VALUE / 2, lz) as? Int)?.let { out.setBiomeColumn(lx, lz, it) }
            }
            BiomeKind.NONE -> {}
        }

        val light = (dataVersion ?: 0) < DV_RELIGHT
        if (out.format == Format.MODERN) root["sections"] = out.writeSections(light = false)
        else level["Sections"] = out.writeSections(light)
        out.writeBiomes(level)
        level.putInt("xPos", to.x)
        level.putInt("zPos", to.z)
        if (out.format == Format.MODERN) level.putInt("yPos", out.minSection)

        // Block entities go with their blocks.
        val beKey = if (out.format == Format.MODERN) "block_entities" else "TileEntities"
        if (beKey != sourceBeKey) level.remove(sourceBeKey)
        val blockEntities = ListTag(10)
        for (be in sourceLevel.list(sourceBeKey)?.compounds().orEmpty()) {
            val x = be.int("x") ?: continue
            val sourceY = be.int("y") ?: continue
            val z = be.int("z") ?: continue
            val y = sourceY + dy
            if (floor != null && y < floor) continue
            if (y < out.minSection * 16 || y > out.maxSection * 16 + 15) continue
            // A block mapped to another loses its block entity: it belongs to a block the target doesn't have.
            if (mode == Mode.REMAP && src.block(x - from.x * 16, sourceY, z - from.z * 16)?.let { conversion!!.isForeign(it) } == true) continue
            val (lx, lz) = turn(t, x - from.x * 16, z - from.z * 16, 16)
            var moved = copy(be) as CompoundTag
            moved.putInt("x", to.x * 16 + lx)
            moved.putInt("y", y)
            moved.putInt("z", to.z * 16 + lz)
            // Heads on the floor before 1.13 keep their turn in the block entity.
            moved.int("Rot")?.let { if (t != 0) moved["Rot"] = ByteTag(((it + 4 * t) and 15).toByte()) }
            if (mode == Mode.UPGRADE) {
                if (src.block(x - from.x * 16, sourceY, z - from.z * 16)?.let { conversion!!.isForeign(it) } == true) continue
                moved = conversion!!.upgradeBlockEntity(moved) ?: continue
            }
            blockEntities += moved
        }
        if (keepUnder) for (be in (under!!.compound("Level") ?: under).list(beKey)?.compounds().orEmpty()) {
            if ((be.int("y") ?: continue) < floor!!) blockEntities += copy(be)
        }
        level[beKey] = blockEntities

        // Entities in the chunk itself (before 1.17); from 1.17 on they are in their own files (see [build]).
        level.remove("Entities")
        if ((dataVersion ?: 0) < WorldExporter.DV_ENTITY_FILES) {
            val list = ListTag(10)
            for (e in sourceLevel.list("Entities")?.compounds().orEmpty()) {
                var moved = copy(e) as CompoundTag
                transformEntity(moved, from, to, t, dy, frames3d)
                if (mode == Mode.UPGRADE) moved = conversion!!.upgradeEntity(moved) ?: continue
                val y = moved.list("Pos")?.doubles()?.getOrNull(1) ?: continue
                if (floor == null || y >= floor) list += moved
            }
            if (keepUnder) for (e in (under!!.compound("Level") ?: under).list("Entities")?.compounds().orEmpty()) {
                val y = e.list("Pos")?.doubles()?.getOrNull(1) ?: continue
                if (y < floor!!) list += copy(e)
            }
            level["Entities"] = list
        }

        for (name in listOf("block_ticks", "fluid_ticks", "TileTicks", "LiquidTicks", "ToBeTicked", "LiquidsToBeTicked", "PostProcessing", "Lights")) {
            level.remove(name)
        }
        // Heightmaps: rebuilt by the game since 1.14; before that, it trusts what is stored.
        level.remove("Heightmaps")
        level.remove("HeightMap")
        if (!light) {
            level.putByte("isLightOn", 0)
        } else when (out.format) {
            Format.LEGACY -> {
                level["HeightMap"] = IntArrayTag(out.tops())
                level.putByte("LightPopulated", 0)
            }
            else -> level["Heightmaps"] = CompoundTag().apply {
                val packed = LongArrayTag(packLongs(out.tops(), 9, spanning = true))
                for (type in listOf("WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "LIGHT_BLOCKING")) {
                    this[type] = LongArrayTag(packed.value.copyOf())
                }
            }
        }
        if (!keepUnder) for (name in listOf("structures", "Structures")) level.compound(name)?.let {
            it["References"] = CompoundTag()
            if ("starts" in it) it["starts"] = CompoundTag()
            if ("Starts" in it) it["Starts"] = CompoundTag()
        }
        return root
    }

    /** Pre-1.13 blocks that need their block entity to be upgraded: bed, note block, flower pot, head, banners. */
    private val BLOCKS_WITH_ENTITY = setOf(25, 26, 140, 144, 176, 177)

    private fun packPos(x: Int, y: Int, z: Int) = ((y + 2048) shl 8) or (z shl 4) or x

    /**
     * The entity chunk for [to] (1.17+ entity files): [source]'s entities (read at [from]) turned and raised,
     * upgraded by [convert], plus [under]'s below [floor]. Null when none are left.
     */
    fun entities(source: CompoundTag?, from: ChunkPos, under: CompoundTag?, to: ChunkPos, turns: Int, dy: Int, floor: Int?, dataVersion: Int,
                 frames3d: Boolean = true, convert: ((CompoundTag) -> CompoundTag?)? = null): CompoundTag? {
        val t = Rotation.normalize(turns)
        val list = ListTag(10)
        for (e in source?.list("Entities")?.compounds().orEmpty()) {
            var moved = copy(e) as CompoundTag
            transformEntity(moved, from, to, t, dy, frames3d)
            if (convert != null) moved = convert(moved) ?: continue
            val y = moved.list("Pos")?.doubles()?.getOrNull(1) ?: continue
            if (floor == null || y >= floor) list += moved
        }
        if (floor != null) for (e in under?.list("Entities")?.compounds().orEmpty()) {
            val y = e.list("Pos")?.doubles()?.getOrNull(1) ?: continue
            if (y < floor) list += copy(e)
        }
        if (list.isEmpty()) return null
        return CompoundTag().apply {
            putInt("DataVersion", dataVersion)
            this["Position"] = IntArrayTag(intArrayOf(to.x, to.z))
            this["Entities"] = list
        }
    }

    private fun transformEntity(e: CompoundTag, from: ChunkPos, to: ChunkPos, t: Int, dy: Int, frames3d: Boolean) {
        e.list("Pos")?.let { pos ->
            if (pos.size == 3) {
                val (x, y, z) = pos.doubles()
                var lx = x - from.x * 16
                var lz = z - from.z * 16
                repeat(t) { val nx = 16 - lz; lz = lx; lx = nx }
                pos.clear()
                pos.addAll(listOf(DoubleTag(to.x * 16 + lx), DoubleTag(y + dy), DoubleTag(to.z * 16 + lz)))
                pos.elementType = 6
            }
        }
        e.list("Motion")?.let { motion ->
            if (motion.size == 3) {
                var (mx, my, mz) = motion.doubles()
                repeat(t) { val nx = -mz; mz = mx; mx = nx }
                motion.clear()
                motion.addAll(listOf(DoubleTag(mx), DoubleTag(my), DoubleTag(mz)))
                motion.elementType = 6
            }
        }
        // Yaw 0 faces south, 90 west: a clockwise turn adds 90.
        e.list("Rotation")?.let { rotation ->
            if (rotation.size == 2) {
                val (yaw, pitch) = rotation.doubles()
                rotation.clear()
                rotation.addAll(listOf(FloatTag(((yaw + 90.0 * t) % 360).toFloat()), FloatTag(pitch.toFloat())))
                rotation.elementType = 5
            }
        }
        // Hanging entities (item frames, paintings) store the block they hang on, and which way they face.
        val tx = e.int("TileX")
        val tz = e.int("TileZ")
        if (tx != null && tz != null) {
            val (lx, lz) = turn(t, tx - from.x * 16, tz - from.z * 16, 16)
            e.putInt("TileX", to.x * 16 + lx)
            e.putInt("TileZ", to.z * 16 + lz)
            e.int("TileY")?.let { e.putInt("TileY", it + dy) }
        }
        // Since 1.13 item frames face any of 6 directions; everything else (and older frames) one of 4.
        val frame = frames3d && e.string("id")?.contains("item_frame") == true
        for (key in listOf("Facing", "facing", "Direction")) {
            val f = e.int(key) ?: continue
            e[key] = ByteTag(if (frame && key == "Facing") turnFacing3d(f, t) else ((f + t) and 3).toByte())
        }
        // Before 1.8 paintings also store "Dir", Direction with north and south swapped.
        e.int("Direction")?.let { d -> if ("Dir" in e) e["Dir"] = ByteTag(when (d) { 0 -> 2; 2 -> 0; else -> d }.toByte()) }
        e.remove("leash")
        e.remove("Leash")
        val id = UUID.randomUUID()
        if ("UUID" in e) {
            val m = id.mostSignificantBits
            val l = id.leastSignificantBits
            e["UUID"] = IntArrayTag(intArrayOf((m shr 32).toInt(), m.toInt(), (l shr 32).toInt(), l.toInt()))
        }
        if ("UUIDMost" in e) {
            e["UUIDMost"] = LongTag(id.mostSignificantBits)
            e["UUIDLeast"] = LongTag(id.leastSignificantBits)
        }
        e.list("Passengers")?.compounds()?.forEach { transformEntity(it, from, to, t, dy, frames3d) }
    }

    /** Item frame facing (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) turned clockwise. */
    private fun turnFacing3d(f: Int, t: Int): Byte {
        val order = intArrayOf(2, 5, 3, 4) // north, east, south, west
        val i = order.indexOf(f)
        return (if (i < 0) f else order[(i + t) % 4]).toByte()
    }

    /** A cell of a [size] x [size] grid turned [t] times clockwise: (x, z) -> (size - 1 - z, x). */
    private fun turn(t: Int, x: Int, z: Int, size: Int): Pair<Int, Int> {
        var a = x
        var b = z
        repeat(t) { val n = size - 1 - b; b = a; a = n }
        return a to b
    }

    /** Where a cell of the turned grid came from. */
    private fun unturn(t: Int, x: Int, z: Int, size: Int) = turn((4 - t) % 4, x, z, size)

    private val AIR_ENTRY = CompoundTag().apply { this["Name"] = StringTag("minecraft:air") }

    /** A block entry turned: a palette entry (NBT) or a legacy `id shl 4 or data` key. */
    private fun turned(entry: Any, t: Int): Any {
        if (t == 0) return entry
        if (entry is Int) return LegacyRotation.rotate(entry, t)
        entry as CompoundTag
        val props = entry.compound("Properties") ?: return entry
        val state = BlockState.of(entry.string("Name") ?: return entry, props.entries.mapNotNull { (k, v) -> (v as? StringTag)?.let { k to it.value } }.toMap())
        val turned = BlockRotation.rotate(state, t)
        if (turned === state) return entry
        return CompoundTag().apply {
            this["Name"] = StringTag(turned.name)
            this["Properties"] = CompoundTag().apply { for ((k, v) in turned.properties) this[k] = StringTag(v) }
        }
    }

    /** Deep copy of mutable tags; immutable ones are shared. */
    fun copy(tag: Tag): Tag = when (tag) {
        is CompoundTag -> CompoundTag(LinkedHashMap<String, Tag>().also { m -> for ((k, v) in tag.entries) m[k] = copy(v) })
        is ListTag -> ListTag(tag.elementType, tag.items.mapTo(ArrayList()) { copy(it) })
        is ByteArrayTag -> ByteArrayTag(tag.value.copyOf())
        is IntArrayTag -> IntArrayTag(tag.value.copyOf())
        is LongArrayTag -> LongArrayTag(tag.value.copyOf())
        else -> tag
    }

    /** How a chunk stores biomes. */
    private enum class BiomeKind { SECTIONS, INT_CELLS, INT_COLUMNS, BYTE_COLUMNS, NONE }

    /**
     * A chunk's blocks and biomes, sections [minSection]..[maxSection]. Blocks are palette entries (NBT) or, before
     * 1.13, `id shl 4 or data` ints; biomes are names (1.18+) or numeric ids, per 4x4x4 cell or per column.
     */
    private class Volume(val format: Format, val minSection: Int, val maxSection: Int, val biomeKind: BiomeKind) {
        private val count = maxSection - minSection + 1
        private val blocks = arrayOfNulls<Array<Any?>>(count)
        private val cells = arrayOfNulls<Any>(count * 64)
        private val columns = IntArray(256)

        fun block(x: Int, y: Int, z: Int): Any? {
            val s = Math.floorDiv(y, 16) - minSection
            if (s !in 0 until count) return null
            return blocks[s]?.get(((y and 15) shl 8) or (z shl 4) or x)
        }

        fun setBlock(x: Int, y: Int, z: Int, entry: Any) {
            val s = Math.floorDiv(y, 16) - minSection
            if (s !in 0 until count) return
            val arr = blocks[s] ?: arrayOfNulls<Any>(4096).also { blocks[s] = it }
            arr[((y and 15) shl 8) or (z shl 4) or x] = entry
        }

        private fun isAir(entry: Any?): Boolean = when (entry) {
            null -> true
            is Int -> entry ushr 4 == 0
            is CompoundTag -> entry.string("Name")?.substringAfter(':').let { it == "air" || it == "cave_air" || it == "void_air" }
            else -> false
        }

        /** The biome at block ([x], [y], [z]); null where this chunk has none. */
        fun biomeAt(x: Int, y: Int, z: Int): Any? = when (biomeKind) {
            BiomeKind.SECTIONS, BiomeKind.INT_CELLS -> {
                val cy = Math.floorDiv(y, 4).coerceIn(minSection * 4, (maxSection + 1) * 4 - 1) - minSection * 4
                cells[(cy shl 4) or ((z shr 2) shl 2) or (x shr 2)]
            }
            BiomeKind.INT_COLUMNS, BiomeKind.BYTE_COLUMNS -> columns[(z shl 4) or x]
            BiomeKind.NONE -> null
        }

        fun setBiomeCell(cx: Int, cy: Int, cz: Int, biome: Any) {
            val c = cy - minSection * 4
            if (c in 0 until count * 4) cells[(c shl 4) or (cz shl 2) or cx] = biome
        }

        fun setBiomeColumn(x: Int, z: Int, biome: Int) { columns[(z shl 4) or x] = biome }

        /** Per column (z * 16 + x) the height above the highest block, counted from the bottom of the world. */
        fun tops(): IntArray = IntArray(256) { i ->
            var y = (maxSection + 1) * 16 - 1
            while (y >= minSection * 16 && isAir(block(i and 15, y, i shr 4))) y--
            y + 1 - minSection * 16
        }

        fun writeSections(light: Boolean): ListTag {
            val list = ListTag(10)
            for (s in 0 until count) {
                val entries = blocks[s]
                val section = CompoundTag()
                section["Y"] = ByteTag((minSection + s).toByte())
                when (format) {
                    Format.MODERN -> {
                        section["block_states"] = pack(Array(4096) { (entries?.get(it) ?: AIR_ENTRY) as CompoundTag }, minBits = 4, spanning = false) { key(it) }
                        section["biomes"] = pack(Array(64) { StringTag(cells[(s shl 6) or it] as? String ?: "minecraft:plains") }, minBits = 0, spanning = false) { it.value }
                    }
                    else -> {
                        // Older formats leave sections of only air out.
                        if (entries == null || entries.all(::isAir)) continue
                        if (format == Format.LEGACY) {
                            val ids = ByteArray(4096)
                            val data = ByteArray(2048)
                            val add = ByteArray(2048)
                            var hasAdd = false
                            for (i in 0 until 4096) {
                                val key = entries[i] as? Int ?: 0
                                val id = key ushr 4
                                ids[i] = id.toByte()
                                setNibble(data, i, key and 15)
                                if (id > 255) { setNibble(add, i, id ushr 8); hasAdd = true }
                            }
                            section["Blocks"] = ByteArrayTag(ids)
                            section["Data"] = ByteArrayTag(data)
                            if (hasAdd) section["Add"] = ByteArrayTag(add)
                        } else {
                            val packed = pack(Array(4096) { (entries[it] ?: AIR_ENTRY) as CompoundTag }, minBits = 4, spanning = format == Format.PALETTE_SPANNING) { key(it) }
                            section["Palette"] = packed.list("palette")!!
                            section["BlockStates"] = packed["data"] ?: LongArrayTag(LongArray(256))
                        }
                        if (light) {
                            section["BlockLight"] = ByteArrayTag(ByteArray(2048))
                            section["SkyLight"] = ByteArrayTag(ByteArray(2048) { -1 })
                        }
                    }
                }
                list += section
            }
            return list
        }

        fun writeBiomes(level: CompoundTag) {
            when (biomeKind) {
                BiomeKind.INT_CELLS -> level["Biomes"] = IntArrayTag(IntArray(1024) { (cells[it] as? Int) ?: 1 })
                BiomeKind.INT_COLUMNS -> level["Biomes"] = IntArrayTag(columns.copyOf())
                BiomeKind.BYTE_COLUMNS -> level["Biomes"] = ByteArrayTag(ByteArray(256) { columns[it].toByte() })
                BiomeKind.SECTIONS, BiomeKind.NONE -> {}
            }
        }

        companion object {
            fun read(root: CompoundTag): Volume {
                val format = formatOf(root.int("DataVersion"))
                val level = root.compound("Level") ?: root
                val sections = (if (format == Format.MODERN) root.list("sections") else level.list("Sections"))?.compounds().orEmpty()
                val (min, max) = if (format == Format.MODERN) {
                    val ys = sections.mapNotNull { it.int("Y") }
                    (ys.minOrNull() ?: root.int("yPos") ?: -4) to (ys.maxOrNull() ?: 19)
                } else 0 to 15
                val biomeKind = if (format == Format.MODERN) BiomeKind.SECTIONS else when (val b = level["Biomes"]) {
                    is ByteArrayTag -> BiomeKind.BYTE_COLUMNS
                    is IntArrayTag -> if (b.value.size >= 1024) BiomeKind.INT_CELLS else BiomeKind.INT_COLUMNS
                    else -> BiomeKind.NONE
                }
                val volume = Volume(format, min, max, biomeKind)
                for (section in sections) {
                    val s = (section.int("Y") ?: continue) - min
                    if (s !in 0 until volume.count) continue
                    when (format) {
                        Format.LEGACY -> {
                            val ids = section.byteArray("Blocks")?.takeIf { it.size >= 4096 } ?: continue
                            val add = section.byteArray("Add")
                            val data = section.byteArray("Data")
                            volume.blocks[s] = Array(4096) { i ->
                                var id = ids[i].toInt() and 0xFF
                                if (add != null) id = id or (nibble(add, i) shl 8)
                                (id shl 4) or (if (data != null) nibble(data, i) else 0)
                            }
                        }
                        Format.PALETTE_SPANNING, Format.PALETTE -> {
                            val palette = section.list("Palette")?.compounds().orEmpty()
                            val states = section.longArray("BlockStates")
                            if (palette.isEmpty() || states == null) continue
                            val indices = SurfaceExtractor.unpack(states, palette.size, spanning = format == Format.PALETTE_SPANNING)
                            volume.blocks[s] = Array(4096) { palette[indices[it]] }
                        }
                        Format.MODERN -> {
                            section.compound("block_states")?.let { states ->
                                val palette = states.list("palette")?.compounds().orEmpty()
                                if (palette.isNotEmpty()) {
                                    val data = states.longArray("data")
                                    val indices = if (data == null) IntArray(4096) else unpackLongs(data, bitsFor(palette.size, 4), 4096)
                                    volume.blocks[s] = Array(4096) { palette[indices[it].coerceAtMost(palette.size - 1)] }
                                }
                            }
                            section.compound("biomes")?.let { b ->
                                val palette = b.list("palette")?.mapNotNull { (it as? StringTag)?.value }.orEmpty()
                                if (palette.isNotEmpty()) {
                                    val data = b.longArray("data")
                                    val indices = if (data == null) IntArray(64) else unpackLongs(data, bitsFor(palette.size, 0), 64)
                                    for (i in 0 until 64) volume.cells[(s shl 6) or i] = palette[indices[i].coerceAtMost(palette.size - 1)]
                                }
                            }
                        }
                    }
                }
                if (format != Format.MODERN) when (val b = level["Biomes"]) {
                    is ByteArrayTag -> for (i in 0 until minOf(256, b.value.size)) volume.columns[i] = b.value[i].toInt() and 0xFF
                    is IntArrayTag -> if (biomeKind == BiomeKind.INT_CELLS) {
                        for (i in 0 until minOf(1024, b.value.size)) volume.cells[i] = b.value[i]
                    } else for (i in 0 until minOf(256, b.value.size)) volume.columns[i] = b.value[i]
                    else -> {}
                }
                return volume
            }

            private fun nibble(array: ByteArray, i: Int): Int {
                val b = array[i shr 1].toInt()
                return if (i and 1 == 0) b and 0x0F else (b shr 4) and 0x0F
            }

            private fun setNibble(array: ByteArray, i: Int, v: Int) {
                val j = i shr 1
                array[j] = if (i and 1 == 0) ((array[j].toInt() and 0xF0) or (v and 15)).toByte()
                else ((array[j].toInt() and 0x0F) or ((v and 15) shl 4)).toByte()
            }

            private fun key(entry: CompoundTag): String {
                val props = entry.compound("Properties")?.entries?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${(it.value as? StringTag)?.value}" }
                return "${entry.string("Name")}[$props]"
            }

            /** Palette and packed indices; a single entry has no data (1.18+) and older formats always pack. */
            fun <T : Tag> pack(values: Array<T>, minBits: Int, spanning: Boolean, keyOf: (T) -> String): CompoundTag {
                val palette = ListTag(if (values.firstOrNull() is CompoundTag) 10 else 8)
                val indexOf = HashMap<String, Int>()
                val indices = IntArray(values.size) { i ->
                    indexOf.getOrPut(keyOf(values[i])) { palette += values[i]; palette.size - 1 }
                }
                val out = CompoundTag()
                out["palette"] = palette
                val bits = bitsFor(palette.size, minBits)
                if (bits > 0) out["data"] = LongArrayTag(packLongs(indices, bits, spanning))
                return out
            }
        }
    }

    private fun bitsFor(size: Int, minBits: Int) = if (size <= 1) 0 else maxOf(minBits, 32 - Integer.numberOfLeadingZeros(size - 1))

    private fun unpackLongs(data: LongArray, bits: Int, count: Int): IntArray {
        val out = IntArray(count)
        if (bits == 0) return out
        val perLong = 64 / bits
        val mask = (1L shl bits) - 1
        for (i in 0 until count) {
            val li = i / perLong
            if (li >= data.size) break
            out[i] = ((data[li] ushr ((i % perLong) * bits)) and mask).toInt()
        }
        return out
    }

    /** Packs [values] at [bits] each: back to back across longs before 1.16 ([spanning]), whole entries per long since. */
    private fun packLongs(values: IntArray, bits: Int, spanning: Boolean): LongArray {
        if (!spanning) {
            val perLong = 64 / bits
            val data = LongArray((values.size + perLong - 1) / perLong)
            for (i in values.indices) data[i / perLong] = data[i / perLong] or (values[i].toLong() shl ((i % perLong) * bits))
            return data
        }
        val data = LongArray((values.size * bits + 63) / 64)
        for (i in values.indices) {
            val bit = i * bits
            val li = bit ushr 6
            val off = bit and 63
            val v = values[i].toLong()
            data[li] = data[li] or (v shl off)
            if (off + bits > 64) data[li + 1] = data[li + 1] or (v ushr (64 - off))
        }
        return data
    }
}
