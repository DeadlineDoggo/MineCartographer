package minecartographer.core.convert

import minecartographer.core.BlockState
import minecartographer.core.nbt.ByteTag
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.ShortTag
import minecartographer.core.nbt.StringTag
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpgradeTest {
    private fun tables() = MinecraftTablesTest.tables.also { assumeTrue(it != null, "No 1.13+ Minecraft jar installed") }!!

    @Test
    fun `old blocks, items, chests and signs become 1_20 ones`() {
        Upgrade.tables = tables()
        val to = 3465 // 1.20.1
        assertEquals(BlockState.of("minecraft:granite"), Upgrade.legacyBlock((1 shl 4) or 1, null, to))
        assertEquals("minecraft:oak_sign", Upgrade.legacyBlock(63 shl 4, null, to).name, "1.14 renamed signs")
        // A flower pot with a red tulip, a creeper head on the floor, a blue bed.
        assertEquals("minecraft:potted_red_tulip", Upgrade.legacyBlock(140 shl 4, CompoundTag().apply {
            this["Item"] = StringTag("minecraft:red_flower"); putInt("Data", 4)
        }, to).name)
        assertEquals(BlockState.of("minecraft:creeper_head", mapOf("rotation" to "8")), Upgrade.legacyBlock((144 shl 4) or 1, CompoundTag().apply {
            putByte("SkullType", 4); putByte("Rot", 8)
        }, to))
        assertEquals("minecraft:blue_bed", Upgrade.legacyBlock(26 shl 4, CompoundTag().apply { putInt("color", 11) }, to).name)

        val chest = CompoundTag().apply {
            this["id"] = StringTag("Chest")
            this["Items"] = ListTag(10, mutableListOf(CompoundTag().apply {
                this["id"] = ShortTag(1); this["Damage"] = ShortTag(1); this["Count"] = ByteTag(5); putByte("Slot", 0)
            }))
        }
        val upgraded = Upgrade.blockEntity(chest, fromDv = 0, toDv = to)!!
        assertEquals("minecraft:chest", upgraded.string("id"))
        val item = upgraded.list("Items")!!.compound(0)
        assertEquals("minecraft:granite", item.string("id"))
        assertEquals(5, item.int("Count"))

        val sign = Upgrade.blockEntity(CompoundTag().apply {
            this["id"] = StringTag("Sign"); this["Text1"] = StringTag("Hello"); for (i in 2..4) this["Text$i"] = StringTag("")
        }, fromDv = 0, toDv = to)!!
        assertEquals("{\"text\":\"Hello\"}", (sign.compound("front_text")!!.list("messages")!![0] as StringTag).value)
        assertNull(sign["Text1"])
        assertEquals("minecraft:windswept_hills", Upgrade.biome(3, fromDv = 0, toDv = to))
    }
}
