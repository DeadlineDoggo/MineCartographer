package minecartographer.core.anvil

import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.IntTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.StringTag
import minecartographer.core.nbt.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForgeDataTest {
    private fun compound(vararg entries: Pair<String, Any>) = CompoundTag().apply {
        for ((k, v) in entries) set(k, when (v) {
            is Int -> IntTag(v)
            is String -> StringTag(v)
            is Tag -> v
            else -> error(v)
        })
    }

    private fun list(vararg items: CompoundTag) = ListTag(10, items.toMutableList<Tag>())

    private val modList = list(
        compound("ModId" to "mcp", "ModVersion" to "8.09"),
        compound("ModId" to "Forge", "ModVersion" to "9.11.1.1345"),
        compound("ModId" to "IC2", "ModVersion" to "2.0.397"),
    )

    @Test
    fun `1_6 ModItemData names modded ids and leaves vanilla to the built-in table`() {
        val root = compound(
            "Data" to compound(),
            "FML" to compound("ModList" to modList, "ModItemData" to list(
                compound("ItemId" to 1, "ModId" to "Minecraft", "ForcedName" to "", "ItemType" to "net.minecraft.item.ItemBlock"),
                compound("ItemId" to 3002, "ModId" to "Mekanism", "ForcedName" to "", "ItemType" to "mekanism.common.item.ItemBlockOre"),
                compound("ItemId" to 3372, "ModId" to "GalacticraftCore", "ForcedName" to "tile.gcBlockCore", "ItemType" to "x"),
                compound("ItemId" to 2130, "ModId" to "ProjRed|Exploration", "ForcedName" to "projectred.exploration.ore", "ItemType" to "x"),
                compound("ItemId" to 30000, "ModId" to "IC2", "ForcedName" to "item.wrench", "ItemType" to "x"), // item id, not a block
            )),
            "Forge" to compound(),
        )
        val forge = ForgeData.read(root)!!
        assertEquals(ForgeData.Generation.NUMERIC_ITEM_DATA, forge.generation)
        assertEquals(listOf(ModInfo("IC2", "2.0.397")), forge.playedMods)
        assertEquals(
            mapOf(3002 to "mekanism:id_3002", 3372 to "galacticraftcore:gcblockcore", 2130 to "projred_exploration:projectred.exploration.ore"),
            forge.blockNames,
        )
    }

    @Test
    fun `1_12 registries give real registry names`() {
        val root = compound("Data" to compound(), "FML" to compound(
            "ModList" to modList,
            "Registries" to compound("minecraft:blocks" to compound("ids" to list(
                compound("K" to "minecraft:stone", "V" to 1),
                compound("K" to "chisel:marble2", "V" to 2300),
                compound("K" to "galacticraftcore:basic_block_core", "V" to 1612),
            ))),
        ))
        val forge = ForgeData.read(root)!!
        assertEquals(ForgeData.Generation.NUMERIC_REGISTRY, forge.generation)
        assertEquals(mapOf(2300 to "chisel:marble2", 1612 to "galacticraftcore:basic_block_core"), forge.blockNames)
    }

    @Test
    fun `1_13 and later only list mods`() {
        val root = compound("Data" to compound(), "fml" to compound("LoadingModList" to list(
            compound("ModId" to "minecraft", "ModVersion" to "1.20.1"),
            compound("ModId" to "biomesoplenty", "ModVersion" to "18.0.0.592"),
        )))
        val forge = ForgeData.read(root)!!
        assertEquals(ForgeData.Generation.NAMED, forge.generation)
        assertEquals(listOf(ModInfo("biomesoplenty", "18.0.0.592")), forge.playedMods)
        assertTrue(!forge.numericIds)
        assertSame(LegacyMapping.VANILLA, forge.legacyMapping())
    }

    @Test
    fun `learned colors of modded blocks can differ per data value`() {
        val ore = minecartographer.core.BlockState.of("ie:ore_test", mapOf("meta" to "2"))
        BlockColors.look(ore) // a modded block with a data value is wanted under its own key
        assertTrue(BlockColors.keyOf("ie:ore_test", "2") in BlockColors.takeWanted())
        BlockColors.learn(BlockColors.keyOf("ie:ore_test", "2"), BlockColors.solidLook(0x404070))
        assertEquals(0x404070, BlockColors.look(ore).color and 0xFFFFFF)
        assertEquals(ore, BlockColors.stateOf(BlockColors.keyOf("ie:ore_test", "2")))
        assertTrue(BlockColors.look(minecartographer.core.BlockState.of("ie:ore_test")).color and 0xFFFFFF != 0x404070, "meta 0 keeps its own look")
    }

    @Test
    fun `vanilla worlds have no Forge data`() {
        assertNull(ForgeData.read(compound("Data" to compound())))
    }

    @Test
    fun `modded ids keep their data value, vanilla ids are mapped as before`() {
        val mapping = LegacyMapping(mapOf(1612 to "galacticraftcore:basic_block_core"))
        assertEquals("galacticraftcore:basic_block_core", mapping.state(1612, 0).name)
        assertEquals(mapOf("meta" to "0"), mapping.state(1612, 0).properties)
        assertEquals(mapOf("meta" to "7"), mapping.state(1612, 7).properties)
        assertSame(mapping.state(1612, 7), mapping.state(1612, 7))
        assertEquals(LegacyBlocks.state(17, 2), mapping.state(17, 2))
        assertEquals("minecartographer:unknown", mapping.state(3000, 0).name)
    }
}
