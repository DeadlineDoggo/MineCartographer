package minecartographer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import minecartographer.model.ModelCamera
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelRenderer
import minecartographer.model.ModelTool
import minecartographer.textures.Dir
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelGizmosTest {
    private val size = IntSize(800, 800)

    private fun model(tool: ModelTool) = ModelEditorState(1, "m").apply {
        addCube()
        this.tool = tool
    }

    @Test
    fun `resize arrows on hidden faces exist only in the see-through shadings`() {
        val m = model(ModelTool.Resize)
        val camera = ModelCamera.ISO
        val solid = handlesOf(m, m.selected, camera, size, solid = true)
        val wire = handlesOf(m, m.selected, camera, size, solid = false)
        assertEquals(setOf(Dir.UP, Dir.SOUTH, Dir.WEST), solid.map { it.index }.toSet(), "up and the two sides toward the viewer at yaw 35")
        assertEquals(6, wire.size)
        assertEquals(3, wire.count { it.behind })
    }

    @Test
    fun `an arrow is grabbed along its whole length, not just at the tip`() {
        val m = model(ModelTool.Move)
        val handles = handlesOf(m, m.selected, ModelCamera.ISO, size, solid = true)
        assertEquals(3, handles.size)
        val h = handles.first()
        val middle = (h.from + h.to) / 2f
        assertEquals(h.index, hitHandle(handles, middle + Offset(0f, 4f))?.index, "grabbed beside the middle of the shaft")
        assertNotNull(hitHandle(handles, h.to + Offset(4f, 4f)))
        assertNull(hitHandle(handles, Offset(5f, 5f)))
    }

    @Test
    fun `each axis has its own ring and the pointer picks the one it is on`() {
        val m = model(ModelTool.Rotate)
        val handles = handlesOf(m, m.selected, ModelCamera.ISO, size, solid = true)
        assertEquals(listOf(0, 1, 2), handles.map { it.index })
        for (h in handles) {
            val on = h.ring[h.ring.size / 5]
            val hit = hitHandle(handles, on)
            assertNotNull(hit)
            assertTrue(h.distance(on) < 0.01f)
        }
    }

    @Test
    fun `dragging along the ring of the y axis turns the element the way the renderer turns it`() {
        val m = model(ModelTool.Rotate)
        val camera = m.camera
        val c = centerOf(m.selected)
        val reach = 9f
        fun screenAt(degrees: Double): Offset {
            // The ring about y runs from z toward x.
            val t = Math.toRadians(degrees)
            val q = camera.project(c[0] + (sin(t) * reach).toFloat(), c[1], c[2] + (cos(t) * reach).toFloat(), size.width, size.height)
            return Offset(q[0], q[1])
        }
        val start = screenAt(0.0)
        val now = screenAt(30.0)
        val begin = planeAngle(camera, c, 1, start.x, start.y, size.width, size.height)
        val handle = handlesOf(m, m.selected, camera, size, solid = true).first { it.index == 1 }
        m.beginGesture()
        dragHandle(m, handle, now.x - start.x, now.y - start.y, now.x, now.y, begin, size.width, size.height)
        m.endGesture("Rotate")
        val r = m.doc.elements.single().rotation!!
        assertEquals(1, r.axis)
        assertEquals(22.5f, r.angle, "30 degrees snapped to the 22.5 step")
        // And the renderer turns the south face toward east, as the drag did.
        val south = ModelRenderer.faceCenters(m.doc.elements.single())[Dir.SOUTH]
        assertTrue(south[0] > 8f + 0.5f, "the south face moved toward +x: ${south[0]}")
    }

    @Test
    fun `a plane seen edge-on gives no angle`() {
        // From straight above, the ring about x lies on edge.
        assertNull(planeAngle(ModelCamera.TOP, floatArrayOf(8f, 8f, 8f), 0, 400f, 400f, 800, 800))
    }
}
