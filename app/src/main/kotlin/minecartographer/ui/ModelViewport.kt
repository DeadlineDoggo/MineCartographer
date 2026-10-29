package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import minecartographer.editor.EditorState
import minecartographer.model.ModelCamera
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelRenderer
import minecartographer.model.ModelTool
import minecartographer.textures.BlockModels
import minecartographer.textures.Dir
import minecartographer.viewport.imageOf
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private val AXIS_COLORS = listOf(Color(0xFFE5534B), Color(0xFF6CC46C), Color(0xFF4A9EE8))
private const val GRAB = 11f
private const val GIZMO_REACH = 72f
private const val RING = 64f

/** A handle of the gizmo of the active tool: [index] is an axis (move, rotate) or a face direction (resize). */
private class Handle(val tool: ModelTool, val index: Int, val x: Float, val y: Float)

private enum class Drag { None, Click, Handle, Orbit, Pan }

/** The 3D view of a model: draws it, picks in it, and carries the gizmos of the tools. */
@Composable
fun ModelViewport(state: EditorState, model: ModelEditorState, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val world = state.activeWorld
    var packs by remember { mutableStateOf<BlockModels?>(null) }
    // The packs load in the background: look again until they are there.
    LaunchedEffect(world) {
        while (true) {
            packs = world?.let(state::modelsOf)
            delay(1000)
        }
    }
    val doc = model.doc
    val camera = model.camera
    val quads = remember(doc, model.hidden, packs) { ModelRenderer.quads(doc, model.hidden) { id -> packs?.texture(id) } }
    val frame = remember(quads, camera, size) {
        if (size.width < 2 || size.height < 2) null else ModelRenderer.render(quads, camera, size.width, size.height)
    }
    val bitmap = remember(frame) { frame?.let { imageOf(it.pixels, it.width, it.height).toComposeImageBitmap() } }
    val selected = model.selection.let { ids -> doc.elements.filter { it.id in ids } }
    val handles = remember(selected, model.tool, camera, size) { handlesOf(model, selected, camera, size) }

    val latestHandles by rememberUpdatedState(handles)
    val latestFrame by rememberUpdatedState(frame)
    val latestSize by rememberUpdatedState(size)

    Box(modifier.background(Color(0xFF26282C)).onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(model) {
                awaitPointerEventScope {
                    var drag = Drag.None
                    var startX = 0f
                    var startY = 0f
                    var lastX = 0f
                    var lastY = 0f
                    var moved = false
                    var handle: Handle? = null
                    var startAngle = 0.0
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val p = change.position
                        val w = latestSize.width
                        val h = latestSize.height
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val factor = 1.15.pow(-change.scrollDelta.y.toDouble()).toFloat()
                                model.camera = model.camera.copy(zoom = (model.camera.zoom * factor).coerceIn(4f, 140f))
                            }
                            PointerEventType.Press -> {
                                val b = event.buttons
                                startX = p.x; startY = p.y; lastX = p.x; lastY = p.y
                                moved = false
                                when {
                                    b.isTertiaryPressed -> drag = Drag.Pan
                                    b.isSecondaryPressed -> drag = Drag.Orbit
                                    b.isPrimaryPressed -> {
                                        handle = latestHandles.firstOrNull { hypot(it.x - p.x, it.y - p.y) <= GRAB }
                                        drag = if (handle != null) Drag.Handle else Drag.Click
                                        if (handle != null) {
                                            model.beginGesture()
                                            val c = centerOnScreen(model, model.camera, w, h)
                                            startAngle = atan2((p.y - c[1]).toDouble(), (p.x - c[0]).toDouble())
                                        }
                                    }
                                    else -> drag = Drag.None
                                }
                            }
                            PointerEventType.Move -> {
                                val dx = p.x - lastX
                                val dy = p.y - lastY
                                if (hypot(p.x - startX, p.y - startY) > 4f) moved = true
                                when (drag) {
                                    Drag.Orbit -> model.camera = model.camera.copy(
                                        yaw = model.camera.yaw + dx * 0.5f,
                                        pitch = (model.camera.pitch + dy * 0.5f).coerceIn(-89f, 89f),
                                    )
                                    Drag.Pan -> model.camera = model.camera.copy(panX = model.camera.panX + dx, panY = model.camera.panY + dy)
                                    Drag.Handle -> handle?.let { dragHandle(model, it, p.x - startX, p.y - startY, p.x, p.y, startAngle, w, h) }
                                    else -> {}
                                }
                                lastX = p.x; lastY = p.y
                            }
                            PointerEventType.Release -> {
                                if (!event.buttons.isPrimaryPressed && !event.buttons.isSecondaryPressed && !event.buttons.isTertiaryPressed) {
                                    when (drag) {
                                        Drag.Handle -> model.endGesture(handle?.tool?.label ?: "Edit")
                                        Drag.Click -> if (!moved) pick(model, latestFrame, p.x, p.y, event.keyboardModifiers.isShiftPressed)
                                        else -> {}
                                    }
                                    drag = Drag.None
                                    handle = null
                                }
                            }
                            else -> {}
                        }
                    }
                }
            },
        ) {
            val w = size.width
            val h = size.height
            drawGrid(camera, w, h)
            bitmap?.let { drawImage(it) }
            for (e in selected) {
                for ((a, b) in ModelRenderer.boxEdges(e)) line(camera, a, b, w, h, EditorTheme.accent, 1.5f)
            }
            val face = model.face
            if (face != null) {
                for (q in quads) if (q.dir == face && q.element in model.selection) faceOutline(camera, q.quad.corners, w, h)
            }
            for (hd in handles) drawHandle(hd, selected, camera, w, h)
        }
        ViewPresets(model, Modifier.align(Alignment.BottomStart).padding(12.dp))
        if (doc.elements.isEmpty()) {
            Txt("Nothing here yet. Press N to add a cube.", Modifier.align(Alignment.Center), color = EditorTheme.textDim, size = 13.sp)
        } else if (packs == null && doc.elements.any { e -> e.faces.values.any { it.texture != null } }) {
            Txt(
                "No textures loaded: open a world and switch to Textured mode to see them.",
                Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(4.dp)).background(EditorTheme.panelRaised).padding(horizontal = 10.dp, vertical = 5.dp),
                color = EditorTheme.textDim, size = 12.sp,
            )
        }
        Txt(
            model.tool.hint, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            color = EditorTheme.textFaint, size = 11.sp,
        )
    }
}

@Composable
private fun ViewPresets(model: ModelEditorState, modifier: Modifier) {
    val presets = listOf("Iso" to ModelCamera.ISO, "Front" to ModelCamera.FRONT, "Side" to ModelCamera.SIDE, "Top" to ModelCamera.TOP)
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC2B2D30)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, preset) in presets) {
            val zoom = model.camera.zoom
            TextButton(label) { model.camera = preset.copy(zoom = zoom) }
            Box(Modifier.width(4.dp))
        }
    }
}

// ---- gizmos ----

/** The mean center of the picked elements, in model units. */
private fun centerOf(selected: List<minecartographer.model.ModelElement>): FloatArray {
    val n = selected.size.coerceAtLeast(1)
    return floatArrayOf(selected.sumOf { it.center.x.toDouble() }.toFloat() / n, selected.sumOf { it.center.y.toDouble() }.toFloat() / n, selected.sumOf { it.center.z.toDouble() }.toFloat() / n)
}

private fun centerOnScreen(model: ModelEditorState, camera: ModelCamera, w: Int, h: Int): FloatArray {
    val c = centerOf(model.selected)
    return camera.project(c[0], c[1], c[2], w, h)
}

private fun handlesOf(model: ModelEditorState, selected: List<minecartographer.model.ModelElement>, camera: ModelCamera, size: IntSize): List<Handle> {
    if (selected.isEmpty() || size.width < 2 || model.tool == ModelTool.Select) return emptyList()
    val w = size.width
    val h = size.height
    val c = centerOf(selected)
    val s = camera.project(c[0], c[1], c[2], w, h)
    // An axis pointing at the viewer has no direction on screen to drag along.
    fun usable(axis: Int) = camera.axisOnScreen(axis).second > camera.zoom * 0.25f
    return when (model.tool) {
        ModelTool.Move -> (0..2).filter(::usable).map { a ->
            val d = camera.axisOnScreen(a).first
            Handle(ModelTool.Move, a, s[0] + d[0] * GIZMO_REACH, s[1] + d[1] * GIZMO_REACH)
        }
        ModelTool.Rotate -> (0..2).map { a ->
            val angle = Math.toRadians(-90.0 + 120.0 * a)
            Handle(ModelTool.Rotate, a, s[0] + (cos(angle) * RING).toFloat(), s[1] + (sin(angle) * RING).toFloat())
        }
        ModelTool.Resize -> ModelRenderer.faceCenters(selected.first()).withIndex().filter { usable(Dir.axisOf(it.index)) }.map { (dir, p) ->
            val q = camera.project(p[0], p[1], p[2], w, h)
            Handle(ModelTool.Resize, dir, q[0], q[1])
        }
        ModelTool.Select -> emptyList()
    }
}

private fun dragHandle(model: ModelEditorState, handle: Handle, dx: Float, dy: Float, px: Float, py: Float, startAngle: Double, w: Int, h: Int) {
    val camera = model.camera
    when (handle.tool) {
        ModelTool.Move -> {
            val (d, len) = camera.axisOnScreen(handle.index)
            model.dragMove(handle.index, (dx * d[0] + dy * d[1]) / len)
        }
        ModelTool.Resize -> {
            val axis = Dir.axisOf(handle.index)
            val (d, len) = camera.axisOnScreen(axis)
            model.dragResize(handle.index, (dx * d[0] + dy * d[1]) / len)
        }
        ModelTool.Rotate -> {
            val c = centerOnScreen(model, camera, w, h)
            var delta = atan2((py - c[1]).toDouble(), (px - c[0]).toDouble()) - startAngle
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            // Screen y points down, so a turn that looks counter-clockwise has a falling angle.
            val o = camera.view(8f, 8f, 8f)[2]
            val toward = camera.view(if (handle.index == 0) 9f else 8f, if (handle.index == 1) 9f else 8f, if (handle.index == 2) 9f else 8f)[2] - o
            val sign = if (toward >= 0) 1.0 else -1.0
            model.dragRotate(handle.index, (Math.toDegrees(-delta) * sign).toFloat())
        }
        ModelTool.Select -> {}
    }
}

private fun pick(model: ModelEditorState, frame: minecartographer.model.ModelFrame?, x: Float, y: Float, add: Boolean) {
    val hit = frame?.pick(x.toInt(), y.toInt())
    if (hit == null) {
        if (!add) model.clearSelection()
        return
    }
    model.select(hit.first, add, hit.second)
}

// ---- drawing ----

private fun DrawScope.line(camera: ModelCamera, a: FloatArray, b: FloatArray, w: Int, h: Int, color: Color, width: Float = 1f) {
    val p = camera.project(a[0], a[1], a[2], w, h)
    val q = camera.project(b[0], b[1], b[2], w, h)
    drawLine(color, Offset(p[0], p[1]), Offset(q[0], q[1]), strokeWidth = width)
}

private fun DrawScope.drawGrid(camera: ModelCamera, w: Int, h: Int) {
    val minor = Color(0x1AFFFFFF)
    val major = Color(0x33FFFFFF)
    for (i in 0..16) {
        val color = if (i == 0 || i == 16 || i == 8) major else minor
        line(camera, floatArrayOf(i.toFloat(), 0f, 0f), floatArrayOf(i.toFloat(), 0f, 16f), w, h, color)
        line(camera, floatArrayOf(0f, 0f, i.toFloat()), floatArrayOf(16f, 0f, i.toFloat()), w, h, color)
    }
    // The block's own box.
    val frame = Color(0x40FFFFFF)
    for (a in 0..1) for (b in 0..1) {
        line(camera, floatArrayOf(a * 16f, 0f, b * 16f), floatArrayOf(a * 16f, 16f, b * 16f), w, h, frame)
        line(camera, floatArrayOf(a * 16f, b * 16f, 0f), floatArrayOf(a * 16f, b * 16f, 16f), w, h, frame)
        line(camera, floatArrayOf(0f, a * 16f, b * 16f), floatArrayOf(16f, a * 16f, b * 16f), w, h, frame)
    }
}

private fun DrawScope.faceOutline(camera: ModelCamera, c: FloatArray, w: Int, h: Int) {
    val a = floatArrayOf(c[0] * 16, c[1] * 16, c[2] * 16)
    val b = floatArrayOf(c[3] * 16, c[4] * 16, c[5] * 16)
    val d = floatArrayOf(c[6] * 16, c[7] * 16, c[8] * 16)
    val e = floatArrayOf(b[0] + d[0] - a[0], b[1] + d[1] - a[1], b[2] + d[2] - a[2])
    val color = Color(0xFFFFD34D)
    line(camera, a, b, w, h, color, 2.5f); line(camera, b, e, w, h, color, 2.5f)
    line(camera, e, d, w, h, color, 2.5f); line(camera, d, a, w, h, color, 2.5f)
}

private fun DrawScope.drawHandle(handle: Handle, selected: List<minecartographer.model.ModelElement>, camera: ModelCamera, w: Int, h: Int) {
    val axis = if (handle.tool == ModelTool.Resize) Dir.axisOf(handle.index) else handle.index
    val color = AXIS_COLORS[axis]
    val c = centerOf(selected).let { camera.project(it[0], it[1], it[2], w, h) }
    when (handle.tool) {
        ModelTool.Move -> {
            drawLine(color, Offset(c[0], c[1]), Offset(handle.x, handle.y), strokeWidth = 2f)
            drawCircle(color, 6f, Offset(handle.x, handle.y))
        }
        ModelTool.Rotate -> {
            drawCircle(Color(0x33FFFFFF), RING, Offset(c[0], c[1]), style = Stroke(1f))
            drawCircle(color, 7f, Offset(handle.x, handle.y))
            drawCircle(Color.White, 7f, Offset(handle.x, handle.y), style = Stroke(1.5f))
        }
        ModelTool.Resize -> {
            drawRect(color, Offset(handle.x - 5f, handle.y - 5f), androidx.compose.ui.geometry.Size(10f, 10f))
            drawRect(Color.White, Offset(handle.x - 5f, handle.y - 5f), androidx.compose.ui.geometry.Size(10f, 10f), style = Stroke(1f))
        }
        ModelTool.Select -> {}
    }
}
