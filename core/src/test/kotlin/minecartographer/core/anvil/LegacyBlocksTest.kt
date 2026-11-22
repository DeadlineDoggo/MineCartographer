package minecartographer.core.anvil

import minecartographer.core.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LegacyBlocksTest {
    @Test
    fun `data values pick the variant`() {
        assertEquals(BlockState.of("spruce_planks"), LegacyBlocks.state(5, 1))
        assertEquals(BlockState.of("orange_wool"), LegacyBlocks.state(35, 1))
        assertEquals(BlockState.of("birch_log", mapOf("axis" to "x")), LegacyBlocks.state(17, 2 or 4))
        assertEquals(BlockState.of("rail", mapOf("shape" to "north_west")), LegacyBlocks.state(66, 8))
        assertEquals(BlockState.of("oak_stairs", mapOf("facing" to "north", "half" to "top", "shape" to "straight")), LegacyBlocks.state(53, 3 or 4))
        assertEquals(BlockState.of("wall_torch", mapOf("facing" to "south")), LegacyBlocks.state(50, 3))
    }

    @Test
    fun `states are interned`() {
        assertSame(BlockState.of("minecraft:stone"), BlockState.of("stone"))
        assertSame(BlockState.AIR, LegacyBlocks.state(0, 0))
    }

    @Test
    fun `unknown ids are gray blocks`() {
        val state = LegacyBlocks.state(3000, 0)
        assertEquals("minecartographer:unknown", state.name)
        assertEquals(BlockKind.SOLID, BlockColors.look(state).kind)
    }

    @Test
    fun `states go back to their numbers, modded ones with their meta`() {
        val mapping = LegacyMapping(mapOf(2001 to "extrabiomesxl:leavesfir"))
        assertEquals(5 to 1, mapping.idOf(LegacyBlocks.state(5, 1)))
        assertEquals(35 to 14, mapping.idOf(LegacyBlocks.state(35, 14)))
        assertEquals(1 to 0, mapping.idOf(BlockState.of("stone")))
        assertEquals(2001 to 3, mapping.idOf(mapping.state(2001, 3)))
        for (id in 1 until 160) for (data in 0 until 16) {
            val state = LegacyBlocks.state(id, data)
            assertEquals(state, mapping.idOf(state)?.let { (i, d) -> LegacyBlocks.state(i, d) }, "$id:$data")
        }
        assertEquals(null, mapping.idOf(BlockState.of("minecraft:copper_block")), "no number gives a block newer than 1.13")
    }
}
