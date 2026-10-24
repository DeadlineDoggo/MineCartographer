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
}
