package minecartographer.core.edit

import minecartographer.core.ChunkPos
import minecartographer.core.DemoWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayerStackTest {
    private val valley = DemoWorld("valley", seed = 1, radiusChunks = 8)
    private val hills = DemoWorld("hills", seed = 2, radiusChunks = 8)
    private val p = ChunkPos(1, 1)

    private fun hillsChunk(x: Int, z: Int) = ChunkRef(hills, ChunkPos(x, z))

    @Test
    fun `topmost visible layer wins`() {
        val stack = LayerStack.of(valley).addLayer("pasted", mapOf(p to hillsChunk(0, 0)))
        assertEquals(hillsChunk(0, 0), stack.resolve(p))

        val hidden = stack.setVisible(stack.activeId, false)
        assertEquals(ChunkRef(valley, p), hidden.resolve(p))
    }

    @Test
    fun `clearing a chunk layer reveals the layer below, clearing the base erases`() {
        val stack = LayerStack.of(valley).addLayer("pasted", mapOf(p to hillsChunk(0, 0)))
        val cleared = stack.clear(stack.activeId, listOf(p))
        assertEquals(ChunkRef(valley, p), cleared.resolve(p))

        val erased = cleared.clear(BaseLayer.BASE_ID, listOf(p))
        assertNull(erased.resolve(p))
        assertTrue(erased.base.isErased(p))
        assertEquals(ChunkRef(valley, p), erased.restore(listOf(p)).resolve(p))
    }

    @Test
    fun `merge down into another layer keeps the upper chunks`() {
        val stack = LayerStack.of(valley)
            .addLayer("lower", mapOf(p to hillsChunk(0, 0), ChunkPos(2, 2) to hillsChunk(5, 5)))
            .addLayer("upper", mapOf(p to hillsChunk(3, 3)))
        val merged = stack.mergeDown(stack.activeId)

        assertEquals(2, merged.layers.size)
        val lower = merged.active as ChunkLayer
        assertEquals("lower", lower.name)
        assertEquals(hillsChunk(3, 3), lower.chunks[p])
        assertEquals(hillsChunk(5, 5), lower.chunks[ChunkPos(2, 2)])
    }

    @Test
    fun `merging into the base layer records overrides and keeps the world untouched`() {
        val stack = LayerStack.of(valley).addLayer("pasted", mapOf(p to hillsChunk(0, 0)))
        val merged = stack.mergeDown(stack.activeId)

        assertEquals(1, merged.layers.size)
        assertEquals(hillsChunk(0, 0), merged.resolve(p))
        assertEquals(ChunkEdit.Place(hillsChunk(0, 0)), merged.base.overrides[p])
    }

    @Test
    fun `flatten drops hidden layers`() {
        var stack = LayerStack.of(valley).addLayer("shown", mapOf(p to hillsChunk(0, 0)))
        stack = stack.addLayer("hidden", mapOf(ChunkPos(3, 3) to hillsChunk(1, 1)))
        stack = stack.setVisible(stack.activeId, false)

        val flat = stack.flatten()
        assertEquals(1, flat.layers.size)
        assertEquals(hillsChunk(0, 0), flat.resolve(p))
        assertEquals(ChunkRef(valley, ChunkPos(3, 3)), flat.resolve(ChunkPos(3, 3)))
    }

    @Test
    fun `layers move but never below the base`() {
        var stack = LayerStack.of(valley).addLayer("a").addLayer("b")
        val b = stack.activeId
        stack = stack.move(b, -5)
        assertEquals(1, stack.indexOf(b))
        assertSame(stack, stack.move(BaseLayer.BASE_ID, 1))
    }

    @Test
    fun `offset moves a layer's chunks`() {
        val stack = LayerStack.of(valley).addLayer("pasted", mapOf(p to hillsChunk(0, 0)))
        val moved = stack.offset(stack.activeId, 10, -3)
        assertEquals(hillsChunk(0, 0), moved.resolve(ChunkPos(11, -2)))
        assertEquals(ChunkRef(valley, p), moved.resolve(p))
    }

    @Test
    fun `merging back the original chunk leaves no override`() {
        val stack = LayerStack.of(valley).addLayer("same", mapOf(p to ChunkRef(valley, p)))
        assertTrue(stack.mergeDown(stack.activeId).base.overrides.isEmpty())
    }

    @Test
    fun `copying a pasted chunk keeps pointing at the original world`() {
        val stack = LayerStack.of(hills).addLayer("pasted", mapOf(p to ChunkRef(valley, ChunkPos(-3, 1))))
        val clip = ChunkClipboard.copy(listOf(p), "x", stack::resolve)!!
        assertEquals(ChunkRef(valley, ChunkPos(-3, 1)), clip.entries.values.single())
    }

    @Test
    fun `bounds include chunks pasted outside the world`() {
        val stack = LayerStack.of(valley).addLayer("far", mapOf(ChunkPos(20, -30) to hillsChunk(0, 0)))
        val b = stack.bounds!!
        assertEquals(20, b.maxX)
        assertEquals(-30, b.minZ)
    }
}
