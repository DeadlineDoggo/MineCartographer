package minecartographer.model

import minecartographer.textures.Dir
import minecartographer.textures.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuarterTurnsTest {
    // Every texel of a 16x16 texture has its own color, so a face drawn from the wrong region or turned wrongly differs.
    private val texture = Texture(16, listOf(IntArray(256) { i -> (0xFF shl 24) or ((i % 16 * 16) shl 16) or ((i / 16 * 16) shl 8) or (0x40 + (i * 7) % 160) }), true)

    private fun element(): ModelElement {
        val faces = (0 until 6).associateWith { dir ->
            when (dir) {
                Dir.UP -> ModelFace("#t", rotation = 90)
                Dir.NORTH -> ModelFace("#t", uv = Uv(2f, 3f, 11f, 13f))
                Dir.EAST -> ModelFace("#t", uv = Uv(12f, 1f, 3f, 9f), rotation = 180, cull = Dir.EAST)
                else -> ModelFace("#t")
            }
        }
        // Not a cube and not centered: the turn must move and reshape it.
        return ModelElement(1, "e", Vec3(2f, 1f, 3f), Vec3(14f, 5f, 9f), faces = faces)
    }

    private fun render(e: ModelElement): IntArray {
        val doc = ModelDoc(listOf(e), mapOf("t" to "x"), 2)
        val quads = ModelRenderer.quads(doc, emptySet()) { texture }
        return ModelRenderer.render(quads, ModelCamera(yaw = 38f, pitch = 27f, zoom = 11f), 300, 300).pixels
    }

    private fun assertSameLook(expected: IntArray, actual: IntArray, what: String) {
        val different = expected.indices.count { expected[it] != actual[it] }
        val drawn = expected.count { it ushr 24 != 0 }
        assertTrue(drawn > 500, "$what: something was drawn")
        // Edges may differ by a pixel where float rounding differs; a wrong face or texture turn differs by hundreds.
        assertTrue(different < drawn * 0.01, "$what: $different of $drawn pixels differ")
    }

    @Test
    fun `angles beyond 45 look the same as the game would draw them if it allowed them`() {
        for (axis in 0..2) for (angle in listOf(67.5f, 90f, 112.5f, 135f, 157.5f, 180f, 202.5f, 270f, -67.5f, -90f, -135f, -180f)) {
            val e = element()
            val pivot = Vec3(6f, 5f, 4f)
            // The reference: the renderer rotates by any angle it is given.
            val reference = e.copy(rotation = ElementRotation(axis, angle, pivot))
            val baked = QuarterTurns.withAngle(e.copy(rotation = ElementRotation(axis, 0f, pivot)), axis, angle)
            assertTrue(baked.rotation!!.angle in -45f..45f, "angle $angle: residual ${baked.rotation!!.angle}")
            assertSameLook(render(reference), render(baked), "axis $axis, $angle degrees")
        }
    }

    @Test
    fun `a quarter turn swaps the sizes and moves the faces`() {
        val e = ModelElement(1, "e", Vec3(0f, 0f, 0f), Vec3(4f, 2f, 8f), faces = mapOf(Dir.UP to ModelFace("#t"), Dir.NORTH to ModelFace("#t", cull = Dir.NORTH)))
        val turned = QuarterTurns.withAngle(e, 1, 90f)
        // About y through the center (2, 1, 4): x and z swap, so the box is 8 wide and 4 deep.
        assertEquals(Vec3(8f, 2f, 4f), turned.size)
        assertEquals(Vec3(2f, 1f, 4f), turned.center)
        // Right-handed about +y takes +z toward +x, so north (-z) goes to west (-x).
        assertEquals(setOf(Dir.UP, Dir.WEST), turned.faces.keys)
        assertEquals(Dir.WEST, turned.faces.getValue(Dir.WEST).cull)
        assertEquals(0f, turned.rotation!!.angle)
    }

    @Test
    fun `four quarter turns are the identity`() {
        val e = element()
        val back = QuarterTurns.turn(e, 0, 4, e.center)
        assertEquals(e.from, back.from)
        assertEquals(e.to, back.to)
        assertEquals(e.faces.mapValues { it.value.rotation % 360 }, back.faces.mapValues { it.value.rotation % 360 })
    }
}
