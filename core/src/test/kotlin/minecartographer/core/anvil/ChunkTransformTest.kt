package minecartographer.core.anvil

import minecartographer.core.BlockRotation
import minecartographer.core.BlockState
import minecartographer.core.ChunkPos
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.FloatTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.LongArrayTag
import minecartographer.core.nbt.StringTag
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
