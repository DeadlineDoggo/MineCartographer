package minecartographer.viewport

import org.jetbrains.skia.Image
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals

class LodBudgetTest {
    /** A tile of [height] rows at [lod], 32 units wide, holding no pixels of its own. */
    private fun tile(lod: Int, height: Int): ChunkTile {
        val width = 32 * lod
        val image = imageOf(IntArray(width * height), width, height)
        return ChunkTile(lod, image, 0, 0, BitSet(), pixels = null)
    }

    @Test
    fun `small tiles keep the finest LOD`() {
        val budget = LodBudget()
        val t = tile(8, 300) // about 300 KB
        repeat(500) { budget.add(t) }
        budget.endFrame(512L shl 20)
        assertEquals(8, budget.lodFor(8))
        t.close()
    }

    @Test
    fun `a screen of tall tiles that doesn't fit gets a coarser LOD`() {
        val budget = LodBudget()
        // The Cave view of a real world at zoom 6: 632 tiles of about 1.2 MB do not fit in 512 MB.
        val t = tile(8, 1230 * 1024 / (256 * 4))
        repeat(400) { budget.add(t) }
        repeat(232) { budget.missing() }
        budget.endFrame(512L shl 20)
        assertEquals(4, budget.lodFor(8))
        assertEquals(2, budget.lodFor(2), "a coarser LOD than the cap stays as it is")
        // Fewer chunks on screen again: the finest LOD comes back.
        repeat(50) { budget.add(t) }
        budget.endFrame(512L shl 20)
        assertEquals(8, budget.lodFor(8))
        t.close()
    }

    @Test
    fun `a frame without tiles keeps the last cap`() {
        val budget = LodBudget()
        val t = tile(8, 2000)
        repeat(1000) { budget.add(t) }
        budget.endFrame(512L shl 20)
        val cap = budget.lodFor(8)
        repeat(10) { budget.missing() }
        budget.endFrame(512L shl 20)
        assertEquals(cap, budget.lodFor(8))
        t.close()
    }
}
