package minecartographer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import minecartographer.model.ModelCamera
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelElement
import minecartographer.model.ModelRenderer
import minecartographer.model.ModelTool
import minecartographer.textures.Dir
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal val AXIS_COLORS = listOf(Color(0xFFE5534B), Color(0xFF6CC46C), Color(0xFF4A9EE8))

/** Pixels from a handle's shape that still grab it. */
private const val GRAB = 10f
private const val MOVE_REACH = 88f
private const val RESIZE_REACH = 38f
private const val HEAD_LENGTH = 20f
private const val HEAD_HALF_WIDTH = 9f
private const val RING_STEPS = 72

/**
 * A grabbable part of the gizmo of the active tool. Move and resize have an arrow from [from] to [to] ([index] is an
 * axis or a face direction); rotate has a ring of screen points ([index] is the axis it turns about) with [front]
 * telling per point whether it lies on the viewer's side. [behind] marks a handle on a face the viewer can't see.
 */
internal class Handle(
    val tool: ModelTool,
    val index: Int,
    val from: Offset = Offset.Zero,
    val to: Offset = Offset.Zero,
    val ring: List<Offset> = emptyList(),
    val front: List<Boolean> = emptyList(),
    val behind: Boolean = false,
) {
    val axis get() = if (tool == ModelTool.Resize) Dir.axisOf(index) else index

    fun sameAs(other: Handle?) = other != null && other.tool == tool && other.index == index

    /** Pixels from [p] to the shape: the whole arrow counts, shaft and head, and the whole ring. */
    fun distance(p: Offset): Float {
        if (ring.isEmpty()) return segmentDistance(p, from, to)
        var best = Float.MAX_VALUE
        for (i in ring.indices) best = min(best, segmentDistance(p, ring[i], ring[(i + 1) % ring.size]))
        return best
    }
}

private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 < 1e-6f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** The handle under [p], the nearest one if several are close enough. */
internal fun hitHandle(handles: List<Handle>, p: Offset): Handle? =
    handles.map { it to it.distance(p) }.filter { it.second <= GRAB }.minByOrNull { it.second }?.first

/** The mean center of the picked elements, in model units. */
internal fun centerOf(selected: List<ModelElement>): FloatArray {
    val n = selected.size.coerceAtLeast(1)
    return floatArrayOf(
        selected.sumOf { it.center.x.toDouble() }.toFloat() / n,
        selected.sumOf { it.center.y.toDouble() }.toFloat() / n,
        selected.sumOf { it.center.z.toDouble() }.toFloat() / n,
    )
}

/**
 * The handles of the tool for the picked elements. With [solid] shading the faces turned away from the viewer get
 * none (they are hidden behind the model); the wire shadings see through, so those faces have theirs, dimmed.
 */
internal fun handlesOf(model: ModelEditorState, selected: List<ModelElement>, camera: ModelCamera, size: IntSize, solid: Boolean): List<Handle> {
    if (selected.isEmpty() || size.width < 2 || model.tool == ModelTool.Select || model.tool == ModelTool.Grab) return emptyList()
    val w = size.width
    val h = size.height
    val c = centerOf(selected)
    val s = camera.project(c[0], c[1], c[2], w, h)
    val center = Offset(s[0], s[1])
    // An axis pointing at the viewer has no direction on screen to drag along.
    fun usable(axis: Int) = camera.axisOnScreen(axis).second > camera.zoom * 0.25f
    return when (model.tool) {
        ModelTool.Move -> (0..2).filter(::usable).map { a ->
            val d = camera.axisOnScreen(a).first
            Handle(ModelTool.Move, a, center + Offset(d[0], d[1]) * 12f, center + Offset(d[0], d[1]) * MOVE_REACH)
        }
        ModelTool.Rotate -> {
            val reach = (selected.maxOf { max(it.size.x, max(it.size.y, it.size.z)) } / 2 + 3f).coerceIn(6f, 14f)
            (0..2).map { a ->
                val e1 = (a + 1) % 3
                val e2 = (a + 2) % 3
                val points = ArrayList<Offset>(RING_STEPS)
                val front = ArrayList<Boolean>(RING_STEPS)
                for (i in 0 until RING_STEPS) {
                    val t = 2 * PI * i / RING_STEPS
                    val p = floatArrayOf(c[0], c[1], c[2])
                    p[e1] += (cos(t) * reach).toFloat()
                    p[e2] += (sin(t) * reach).toFloat()
                    val q = camera.project(p[0], p[1], p[2], w, h)
                    points += Offset(q[0], q[1])
                    front += q[2] >= s[2]
                }
                Handle(ModelTool.Rotate, a, ring = points, front = front)
            }
        }
        ModelTool.Resize -> {
            val primary = selected.first()
            ModelRenderer.faceCenters(primary).withIndex().mapNotNull { (dir, p) ->
                val axis = Dir.axisOf(dir)
                if (!usable(axis)) return@mapNotNull null
                val facing = camera.facesViewer(Dir.DX[dir].toFloat(), Dir.DY[dir].toFloat(), Dir.DZ[dir].toFloat())
                if (solid && !facing) return@mapNotNull null
                val sign = if (dir and 1 == 1) 1f else -1f
                val d = camera.axisOnScreen(axis).first
                val q = camera.project(p[0], p[1], p[2], w, h)
                val from = Offset(q[0], q[1])
                Handle(ModelTool.Resize, dir, from, from + Offset(d[0], d[1]) * (sign * RESIZE_REACH), behind = !facing)
            }
        }
        ModelTool.Select, ModelTool.Grab -> emptyList()
    }
}

/** Angle of a screen point in the plane through [c] that [axis] is the normal of; null when the plane is seen edge-on. */
internal fun planeAngle(camera: ModelCamera, c: FloatArray, axis: Int, x: Float, y: Float, w: Int, h: Int): Double? {
    val e1 = (axis + 1) % 3
    val e2 = (axis + 2) % 3
    val o = camera.project(c[0], c[1], c[2], w, h)
    fun along(e: Int): Offset {
        val p = floatArrayOf(c[0], c[1], c[2])
        p[e] += 1f
        val q = camera.project(p[0], p[1], p[2], w, h)
        return Offset(q[0] - o[0], q[1] - o[1])
    }
    val s1 = along(e1)
    val s2 = along(e2)
    val det = s1.x * s2.y - s1.y * s2.x
    if (abs(det) < camera.zoom * camera.zoom * 0.05f) return null
    val dx = x - o[0]
    val dy = y - o[1]
    val u = (dx * s2.y - dy * s2.x) / det
    val v = (s1.x * dy - s1.y * dx) / det
    return atan2(v.toDouble(), u.toDouble())
}

/** Applies the drag of [handle] to the model: [dx], [dy] since the press, ([px], [py]) the pointer now. */
internal fun dragHandle(model: ModelEditorState, handle: Handle, dx: Float, dy: Float, px: Float, py: Float, startAngle: Double?, w: Int, h: Int) {
    val camera = model.camera
    when (handle.tool) {
        ModelTool.Move -> {
            val (d, len) = camera.axisOnScreen(handle.index)
            model.dragMove(handle.index, (dx * d[0] + dy * d[1]) / len)
        }
        ModelTool.Resize -> {
            val (d, len) = camera.axisOnScreen(handle.axis)
            model.dragResize(handle.index, (dx * d[0] + dy * d[1]) / len)
        }
        ModelTool.Rotate -> {
            val start = startAngle ?: return
            val now = planeAngle(camera, centerOf(model.selected), handle.index, px, py, w, h) ?: return
            var delta = now - start
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            // The plane's axes are right-handed about the axis, so a positive angle is a positive turn.
            model.dragRotate(handle.index, Math.toDegrees(delta).toFloat())
        }
        ModelTool.Select, ModelTool.Grab -> {}
    }
}

/** Draws a handle; [lit] is for the one under the pointer or being dragged. */
internal fun DrawScope.drawHandle(handle: Handle, lit: Boolean) {
    val base = AXIS_COLORS[handle.axis]
    val color = if (lit) lerp(base, Color.White, 0.45f) else base
    when (handle.tool) {
        ModelTool.Move, ModelTool.Resize -> {
            val alpha = if (handle.behind) 0.4f else 1f
            arrow(handle.from, handle.to, color.copy(alpha = alpha), if (lit) 4.5f else 3f)
        }
        ModelTool.Rotate -> {
            val width = if (lit) 4.5f else 2.5f
            for (i in handle.ring.indices) {
                val a = handle.ring[i]
                val b = handle.ring[(i + 1) % handle.ring.size]
                // The half on the far side of the element is dimmer, which also shows how the ring sits in space.
                drawLine(color.copy(alpha = if (handle.front[i]) 1f else 0.35f), a, b, strokeWidth = width)
            }
        }
        ModelTool.Select, ModelTool.Grab -> {}
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

/** A shaft with a filled head at [to]. */
private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, width: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < 1f) return
    val ux = dx / len
    val uy = dy / len
    val head = min(HEAD_LENGTH, len * 0.6f)
    val baseX = to.x - ux * head
    val baseY = to.y - uy * head
    drawLine(color, from, Offset(baseX, baseY), strokeWidth = width)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(baseX - uy * HEAD_HALF_WIDTH, baseY + ux * HEAD_HALF_WIDTH)
        lineTo(baseX + uy * HEAD_HALF_WIDTH, baseY - ux * HEAD_HALF_WIDTH)
        close()
    }
    drawPath(path, color)
}
