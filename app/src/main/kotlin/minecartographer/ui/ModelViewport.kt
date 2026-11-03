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
import minecartographer.model.ModelFrame
import minecartographer.model.ModelRenderer
import minecartographer.model.ModelShading
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
    // The edges of every shown element, for the wireframe shading.
    val wire = remember(doc, model.hidden) { doc.elements.filter { it.id !in model.hidden }.map(ModelRenderer::boxEdges) }
    val solid = model.shading == ModelShading.Solid
    val handles = remember(selected, model.tool, camera, size, solid) { handlesOf(model, selected, camera, size, solid) }
    // The handle under the pointer, and the one being dragged, are drawn lit.
    var hovered by remember { mutableStateOf<Handle?>(null) }
    var dragged by remember { mutableStateOf<Handle?>(null) }

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
                    var startAngle: Double? = null
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
                                    b.isTertiaryPressed -> drag = if (event.keyboardModifiers.isShiftPressed) Drag.Pan else Drag.Orbit
                                    b.isSecondaryPressed -> drag = Drag.Orbit
                                    b.isPrimaryPressed -> {
                                        handle = hitHandle(latestHandles, p)
                                        drag = if (handle != null) Drag.Handle else Drag.Click
                                        dragged = handle
                                        handle?.let {
                                            model.beginGesture()
                                            startAngle = if (it.tool == ModelTool.Rotate) planeAngle(model.camera, centerOf(model.selected), it.index, p.x, p.y, w, h) else null
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
                                    Drag.None -> hovered = hitHandle(latestHandles, p)
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
                                    dragged = null
                                    hovered = hitHandle(latestHandles, p)
                                }
                            }
                            PointerEventType.Exit -> hovered = null
                            else -> {}
                        }
                    }
                }
            },
        ) {
            val w = size.width
            val h = size.height
            drawGrid(camera, w, h)
            if (model.shading != ModelShading.Wireframe) bitmap?.let { drawImage(it) }
            // Plain solid shading hides what is behind a face; the wire shadings show every edge through the walls.
            val hideBehind = if (model.shading == ModelShading.Solid) frame else null
            if (model.shading != ModelShading.Solid) {
                for (edges in wire) for ((a, b) in edges) line(camera, a, b, w, h, Color(0xB3C8CCD2), 1f, hideBehind)
            }
            for (e in selected) {
                for ((a, b) in ModelRenderer.boxEdges(e)) line(camera, a, b, w, h, EditorTheme.accent, 1.5f, hideBehind)
            }
            val face = model.face
            if (face != null) {
                for (q in quads) if (q.dir == face && q.element in model.selection) faceOutline(camera, q.quad.corners, w, h, hideBehind)
            }
            for (hd in handles) drawHandle(hd, lit = hd.sameAs(dragged ?: hovered))
        }
        ViewPresets(model, Modifier.align(Alignment.BottomStart).padding(12.dp))
        ShadingSwitcher(model, Modifier.align(Alignment.TopEnd).padding(12.dp))
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

/** Top right of the view, like Blender's shading buttons: wireframe, solid, or solid with the edges drawn over it. */
@Composable
private fun ShadingSwitcher(model: ModelEditorState, modifier: Modifier) {
    Box(modifier.width(300.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC2B2D30)).padding(4.dp)) {
        Segmented(ModelShading.entries, model.shading, { it.label }) { model.shading = it }
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

private fun pick(model: ModelEditorState, frame: minecartographer.model.ModelFrame?, x: Float, y: Float, add: Boolean) {
    val hit = frame?.pick(x.toInt(), y.toInt())
    if (hit == null) {
        if (!add) model.clearSelection()
        return
    }
    model.select(hit.first, add, hit.second)
}

// ---- drawing ----

/** How far behind a face (model units) a point may lie and still count as on it: edges sit on the faces they bound. */
private const val DEPTH_SLACK = 0.25f

/** A line between two model points; with a [hideBehind] frame, the stretches that a face covers are left out. */
private fun DrawScope.line(camera: ModelCamera, a: FloatArray, b: FloatArray, w: Int, h: Int, color: Color, width: Float = 1f, hideBehind: ModelFrame? = null) {
    val p = camera.project(a[0], a[1], a[2], w, h)
    val q = camera.project(b[0], b[1], b[2], w, h)
    if (hideBehind == null) {
        drawLine(color, Offset(p[0], p[1]), Offset(q[0], q[1]), strokeWidth = width)
        return
    }
    val steps = (hypot(q[0] - p[0], q[1] - p[1]) / 3f).toInt().coerceIn(1, 400)
    var from: Offset? = null
    var to = Offset.Zero
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = p[0] + (q[0] - p[0]) * t
        val y = p[1] + (q[1] - p[1]) * t
        val z = p[2] + (q[2] - p[2]) * t
        val px = x.toInt()
        val py = y.toInt()
        val covered = px in 0 until hideBehind.width && py in 0 until hideBehind.height && z < hideBehind.depth[py * hideBehind.width + px] - DEPTH_SLACK
        if (covered) {
            from?.let { drawLine(color, it, to, strokeWidth = width) }
            from = null
        } else {
            if (from == null) from = Offset(x, y)
            to = Offset(x, y)
        }
    }
    from?.let { drawLine(color, it, to, strokeWidth = width) }
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

private fun DrawScope.faceOutline(camera: ModelCamera, c: FloatArray, w: Int, h: Int, hideBehind: ModelFrame?) {
    val a = floatArrayOf(c[0] * 16, c[1] * 16, c[2] * 16)
    val b = floatArrayOf(c[3] * 16, c[4] * 16, c[5] * 16)
    val d = floatArrayOf(c[6] * 16, c[7] * 16, c[8] * 16)
    val e = floatArrayOf(b[0] + d[0] - a[0], b[1] + d[1] - a[1], b[2] + d[2] - a[2])
    val color = Color(0xFFFFD34D)
    line(camera, a, b, w, h, color, 2.5f, hideBehind); line(camera, b, e, w, h, color, 2.5f, hideBehind)
    line(camera, e, d, w, h, color, 2.5f, hideBehind); line(camera, d, a, w, h, color, 2.5f, hideBehind)
}

