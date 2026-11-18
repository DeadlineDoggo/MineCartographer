package minecartographer.core.anvil

import minecartographer.core.BlockRotation
import minecartographer.core.BlockState
import minecartographer.core.ChunkPos
import minecartographer.core.edit.ChunkRef
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.FloatTag
import minecartographer.core.nbt.IntArrayTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.LongArrayTag
import minecartographer.core.nbt.StringTag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkTransformTest {
    private fun entry(name: String, props: Map<String, String> = emptyMap()) = CompoundTag().apply {
        this["Name"] = StringTag(name)
        if (props.isNotEmpty()) this["Properties"] = CompoundTag().apply { for ((k, v) in props) this[k] = StringTag(v) }
    }

    /** A 1.18+ chunk at [pos], sections -4..19, from a block function (null = air). */
    private fun chunk(pos: ChunkPos, block: (Int, Int, Int) -> CompoundTag?): CompoundTag {
        val sections = ListTag(10)
        for (sy in -4..19) {
            val palette = ArrayList<CompoundTag>().apply { add(entry("minecraft:air")) }
            val keys = HashMap<String, Int>().apply { put("minecraft:air[]", 0) }
            val indices = IntArray(4096) { i ->
                val e = block(i and 15, sy * 16 + (i shr 8), (i shr 4) and 15) ?: return@IntArray 0
                val key = "${e.string("Name")}[${e.compound("Properties")?.entries?.map { "${it.key}=${(it.value as StringTag).value}" }?.sorted()}]"
                keys.getOrPut(key) { palette += e; palette.size - 1 }
            }
            val states = CompoundTag()
            states["palette"] = ListTag(10, palette.toMutableList())
            if (palette.size > 1) {
                val bits = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.size - 1))
                val perLong = 64 / bits
                val data = LongArray((4096 + perLong - 1) / perLong)
                for (i in 0 until 4096) data[i / perLong] = data[i / perLong] or (indices[i].toLong() shl ((i % perLong) * bits))
                states["data"] = LongArrayTag(data)
            }
            sections += CompoundTag().apply {
                putByte("Y", sy)
                this["block_states"] = states
                this["biomes"] = CompoundTag().apply { this["palette"] = ListTag(8, mutableListOf(StringTag("minecraft:desert"))) }
            }
        }
        return CompoundTag().apply {
            putInt("DataVersion", 3465)
            putInt("xPos", pos.x)
            putInt("zPos", pos.z)
            putInt("yPos", -4)
            this["Status"] = StringTag("minecraft:full")
            this["sections"] = sections
            this["block_entities"] = ListTag(10)
            this["Heightmaps"] = CompoundTag()
        }
    }

    private fun blockAt(root: CompoundTag, x: Int, y: Int, z: Int): BlockState {
        val section = root.list("sections")!!.compounds().first { it.int("Y") == Math.floorDiv(y, 16) }
        val states = section.compound("block_states")!!
        val palette = states.list("palette")!!.compounds()
        val data = states.longArray("data")
        val i = ((y and 15) shl 8) or (z shl 4) or x
        val index = if (data == null) 0 else {
            val bits = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.size - 1))
            val perLong = 64 / bits
            ((data[i / perLong] ushr ((i % perLong) * bits)) and ((1L shl bits) - 1)).toInt()
        }
        val e = palette[index]
        return BlockState.of(e.string("Name")!!, e.compound("Properties")?.entries?.entries?.associate { it.key to (it.value as StringTag).value }.orEmpty())
    }

    private val from = ChunkPos(2, 3)
    private val to = ChunkPos(-5, 7)

    @Test
    fun `turning moves blocks and turns their facing`() {
        val source = chunk(from) { x, y, z ->
            when {
                x == 0 && z == 0 && y == 70 -> entry("minecraft:oak_stairs", mapOf("facing" to "north", "half" to "bottom"))
                y < 64 -> entry("minecraft:stone")
                else -> null
            }
        }
        val out = ChunkTransform.chunk(source, from, null, to, turns = 1, dy = 0, floor = null)
        // One turn clockwise: local (x, z) -> (15 - z, x), north -> east.
        assertEquals(BlockState.of("minecraft:oak_stairs", mapOf("facing" to "east", "half" to "bottom")), blockAt(out, 15, 70, 0))
        assertEquals("minecraft:air", blockAt(out, 0, 70, 0).name)
        assertEquals("minecraft:stone", blockAt(out, 7, 10, 3).name)
        assertEquals(to.x, out.int("xPos"))
        assertNull(out["Heightmaps"], "the game recomputes them")
    }

    @Test
    fun `raised and cut chunks keep the original below`() {
        val source = chunk(from) { _, y, _ -> if (y < 64) entry("minecraft:sandstone") else null }
        val under = chunk(to) { _, y, _ -> if (y < 70) entry("minecraft:dirt") else null }
        // Raised by 3 and cut at 66: sandstone (up to 63, now 66) from 66 up, the original dirt below.
        val out = ChunkTransform.chunk(source, from, under, to, turns = 0, dy = 3, floor = 66)
        assertEquals("minecraft:dirt", blockAt(out, 4, 65, 4).name)
        assertEquals("minecraft:sandstone", blockAt(out, 4, 66, 4).name)
        assertEquals("minecraft:air", blockAt(out, 4, 67, 4).name, "the source ends at 63 + 3")
        assertEquals("minecraft:dirt", blockAt(out, 4, -60, 4).name)
    }

    @Test
    fun `a world's own older chunk laid over a newer one keeps the original below and is upgraded`(@TempDir tmp: Path) {
        // A world played in 1.20.1, then in 1.20.4: chunks not loaded since are still 1.20.1 (3465).
        val older = ChunkPos(0, 0)
        val newer = ChunkPos(1, 0)
        val source = chunk(older) { _, y, _ ->
            when {
                y < 64 -> entry("minecraft:sandstone")
                y == 64 -> entry("minecraft:grass") // short_grass since 1.20.3
                else -> null
            }
        }
        val under = chunk(newer) { _, y, _ -> if (y < 70) entry("minecraft:dirt") else null }.apply { putInt("DataVersion", 3700) }
        val world = world1204(tmp, mapOf(older to source, newer to under))
        try {
            // Raised by 3 and cut at 66 over the newer chunk, as the export and the map build it.
            val (out, _) = ChunkTransform.build(ChunkRef(world, older, dy = 3, cut = 66), newer, world)!!
            assertEquals(3700, out.int("DataVersion"), "one chunk, of the newer version")
            assertEquals("minecraft:dirt", blockAt(out, 4, 65, 4).name, "the original stays below the cut")
            assertEquals("minecraft:dirt", blockAt(out, 4, -60, 4).name, "down to the bottom")
            assertEquals("minecraft:sandstone", blockAt(out, 4, 66, 4).name)
            assertEquals("minecraft:short_grass", blockAt(out, 4, 67, 4).name, "upgraded from the chunk's own version, not level.dat's")
        } finally {
            world.close()
        }
    }

    @Test
    fun `a newer chunk laid over an older original upgrades the original instead of being labeled older`(@TempDir tmp: Path) {
        // The other way round: the pasted chunk is 1.20.4 (3700), the original below is still 1.20.1 (3465).
        val newer = ChunkPos(0, 0)
        val older = ChunkPos(1, 0)
        val source = chunk(newer) { _, y, _ -> if (y < 64) entry("minecraft:sandstone") else null }.apply { putInt("DataVersion", 3700) }
        val under = chunk(older) { _, y, _ ->
            when {
                y < 20 -> entry("minecraft:dirt")
                y == 20 -> entry("minecraft:grass") // short_grass since 1.20.3
                else -> null
            }
        }
        val world = world1204(tmp, mapOf(newer to source, older to under))
        try {
            val (out, _) = ChunkTransform.build(ChunkRef(world, newer, dy = 3, cut = 40), older, world)!!
            assertEquals(3700, out.int("DataVersion"), "labeled with the source's version, so Minecraft doesn't upgrade it again")
            assertEquals("minecraft:short_grass", blockAt(out, 4, 20, 4).name, "the original's part was upgraded to it")
            assertEquals("minecraft:dirt", blockAt(out, 4, 19, 4).name)
            assertEquals("minecraft:sandstone", blockAt(out, 4, 40, 4).name)
        } finally {
            world.close()
        }
    }

    /** A 1.20.4 world in [tmp] with [chunks] in its region r.0.0. */
    private fun world1204(tmp: Path, chunks: Map<ChunkPos, CompoundTag>): AnvilWorld {
        RegionWriter.write(tmp.resolve("region").resolve(RegionFile.fileName(0, 0)), chunks.mapValues { RawChunk.of(it.value) })
        tmp.resolve("level.dat").outputStream().use {
            Nbt.writeGzip(it, CompoundTag().apply {
                this["Data"] = CompoundTag().apply {
                    putInt("version", WorldInfo.ANVIL)
                    putInt("DataVersion", 3700)
                    this["LevelName"] = StringTag("Two versions")
                    this["Version"] = CompoundTag().apply { this["Name"] = StringTag("1.20.4") }
                }
            })
        }
        return AnvilWorld.open(tmp)
    }

    @Test
    fun `block entities and entities move with their blocks`() {
        val source = chunk(from) { _, _, _ -> null }
        source.list("block_entities")!! += CompoundTag().apply {
            this["id"] = StringTag("minecraft:chest")
            putInt("x", from.x * 16 + 1); putInt("y", 70); putInt("z", from.z * 16 + 2)
        }
        val out = ChunkTransform.chunk(source, from, null, to, turns = 1, dy = 5, floor = null)
        val be = out.list("block_entities")!!.compounds().single()
        assertEquals(listOf(to.x * 16 + 13, 75, to.z * 16 + 1), listOf(be.int("x"), be.int("y"), be.int("z")))

        val entities = CompoundTag().apply {
            this["Entities"] = ListTag(10, mutableListOf(CompoundTag().apply {
                this["id"] = StringTag("minecraft:cow")
                this["Pos"] = ListTag(6, mutableListOf(DoubleTag(from.x * 16 + 1.5), DoubleTag(70.0), DoubleTag(from.z * 16 + 2.5)))
                this["Rotation"] = ListTag(5, mutableListOf(FloatTag(0f), FloatTag(0f)))
            }))
        }
        val moved = ChunkTransform.entities(entities, from, null, to, turns = 1, dy = 5, floor = null, dataVersion = 3465)!!
        val e = moved.list("Entities")!!.compound(0)
        assertEquals(listOf(to.x * 16 + 13.5, 75.0, to.z * 16 + 1.5), e.list("Pos")!!.doubles())
        assertEquals(90.0, e.list("Rotation")!!.doubles()[0], 1e-6)
    }

    @Test
    fun `what an entity hangs on or is tied to moves with it, and what it remembers elsewhere is forgotten`() {
        val block = intArrayOf(from.x * 16 + 1, 70, from.z * 16 + 2)
        val movedBlock = listOf(to.x * 16 + 13, 75, to.z * 16 + 1) // turned once, raised by 5
        val frame = CompoundTag().apply {
            this["id"] = StringTag("minecraft:item_frame")
            this["Pos"] = ListTag(6, mutableListOf(DoubleTag(block[0] + 0.5), DoubleTag(70.5), DoubleTag(block[2] + 0.97)))
            this["block_pos"] = IntArrayTag(block.copyOf())
        }
        val dog = CompoundTag().apply {
            this["id"] = StringTag("minecraft:wolf")
            this["Pos"] = ListTag(6, mutableListOf(DoubleTag(block[0] + 0.5), DoubleTag(70.0), DoubleTag(block[2] + 0.5)))
            this["leash"] = IntArrayTag(block.copyOf())
            this["UUID"] = IntArrayTag(intArrayOf(1, 2, 3, 4))
        }
        val oldDog = CompoundTag().apply {
            this["id"] = StringTag("Wolf")
            this["Pos"] = ListTag(6, mutableListOf(DoubleTag(block[0] + 0.5), DoubleTag(70.0), DoubleTag(block[2] + 0.5)))
            this["Leash"] = CompoundTag().apply { putInt("X", block[0]); putInt("Y", block[1]); putInt("Z", block[2]) }
        }
        val villager = CompoundTag().apply {
            this["id"] = StringTag("minecraft:villager")
            this["Pos"] = ListTag(6, mutableListOf(DoubleTag(block[0] + 0.5), DoubleTag(70.0), DoubleTag(block[2] + 0.5)))
            putInt("SleepingX", block[0]); putInt("SleepingY", block[1]); putInt("SleepingZ", block[2])
            this["Brain"] = CompoundTag().apply {
                this["memories"] = CompoundTag().apply {
                    this["minecraft:home"] = CompoundTag()
                    this["minecraft:job_site"] = CompoundTag()
                    this["minecraft:last_slept"] = CompoundTag()
                }
            }
        }
        val bee = CompoundTag().apply {
            this["id"] = StringTag("minecraft:bee")
            this["Pos"] = ListTag(6, mutableListOf(DoubleTag(block[0] + 0.5), DoubleTag(72.0), DoubleTag(block[2] + 0.5)))
            this["hive_pos"] = IntArrayTag(block.copyOf())
        }
        val entities = CompoundTag().apply { this["Entities"] = ListTag(10, mutableListOf(frame, dog, oldDog, villager, bee)) }

        val moved = ChunkTransform.entities(entities, from, null, to, turns = 1, dy = 5, floor = null, dataVersion = 4325, newIds = false)!!
            .list("Entities")!!.compounds()
        assertEquals(movedBlock, moved[0].intArray("block_pos")!!.toList(), "a frame hangs on the block it hung on")
        assertEquals(movedBlock, moved[1].intArray("leash")!!.toList(), "a lead stays tied to its fence")
        assertEquals(listOf(1, 2, 3, 4), moved[1].intArray("UUID")!!.toList(), "a chunk only moved keeps its UUIDs")
        val leash = moved[2].compound("Leash")!!
        assertEquals(movedBlock, listOf(leash.int("X"), leash.int("Y"), leash.int("Z")))
        assertNull(moved[3].int("SleepingX"))
        assertEquals(setOf("minecraft:last_slept"), moved[3].compound("Brain")!!.compound("memories")!!.entries.keys)
        assertNull(moved[4]["hive_pos"])

        val copied = ChunkTransform.entities(entities, from, null, to, turns = 1, dy = 5, floor = null, dataVersion = 4325, newIds = true)!!
        assertTrue(copied.list("Entities")!!.compound(1).intArray("UUID")!!.toList() != listOf(1, 2, 3, 4), "a copy gets new ones")
    }

    @Test
    fun `old data values turn and four turns give them back`() {
        for (id in 0 until 4096) for (data in 0..15) {
            val key = (id shl 4) or data
            assertEquals(key, LegacyRotation.rotate(key, 4), "id $id data $data")
            assertEquals(key ushr 4, LegacyRotation.rotate(key, 1) ushr 4, "the id stays")
        }
        // Oak stairs facing east (0) -> south (2); upside-down bit kept.
        assertEquals((53 shl 4) or 6, LegacyRotation.rotate((53 shl 4) or 4, 1))
        // Rail north-south -> east-west; chest facing north (2) -> east (5).
        assertEquals((66 shl 4) or 1, LegacyRotation.rotate(66 shl 4, 1))
        assertEquals((54 shl 4) or 5, LegacyRotation.rotate((54 shl 4) or 2, 1))
    }

    @Test
    fun `rails, connections and signs turn too`() {
        assertEquals("east_west", BlockRotation.rotate(BlockState.of("rail", mapOf("shape" to "north_south")), 1).properties["shape"])
        assertEquals("north_south", BlockRotation.rotate(BlockState.of("rail", mapOf("shape" to "east_west")), 1).properties["shape"])
        assertEquals("south_east", BlockRotation.rotate(BlockState.of("rail", mapOf("shape" to "north_east")), 1).properties["shape"])
        assertEquals("ascending_south", BlockRotation.rotate(BlockState.of("rail", mapOf("shape" to "ascending_east")), 1).properties["shape"])
        val fence = BlockRotation.rotate(BlockState.of("oak_fence", mapOf("north" to "true", "east" to "false", "south" to "false", "west" to "false")), 1)
        assertEquals("true", fence.properties["east"])
        assertEquals("false", fence.properties["north"])
        assertEquals("6", BlockRotation.rotate(BlockState.of("oak_sign", mapOf("rotation" to "2")), 1).properties["rotation"])
        assertEquals("z", BlockRotation.rotate(BlockState.of("oak_log", mapOf("axis" to "x")), 1).properties["axis"])
        assertTrue(BlockRotation.rotate(BlockState.of("stone"), 3) === BlockState.of("stone"))
    }
}
