package minecartographer.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.LayerStack
import minecartographer.editor.EditorState
import minecartographer.editor.SelectMode
import minecartographer.editor.Tool
import minecartographer.ui.EditorTheme
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Vertical band assumed when deciding which chunks might be on screen. */
private const val VIEW_MIN_Y = 0
private const val VIEW_MAX_Y = 224
private const val KEY_PAN_SPEED = 900f // screen px per second

/** Surfaces are prefetched for an area this many times the viewport's width and height, at most [PREFETCH_LIMIT] chunks. */
private const val PREFETCH_SCALE = 2f
private const val PREFETCH_LIMIT = 20_000

/** Remembers what the prefetch queue was last built for, so it is only rebuilt once the view has moved far enough. */
private class PrefetchTracker {
    private var view: IsoView? = null
    private var stack: LayerStack? = null
    private var rotation = 0
    private var width = 0f
    private var height = 0f

    fun update(view: IsoView, stack: LayerStack, rotation: Int, width: Float, height: Float): Boolean {
        val last = this.view
        val stale = last == null || stack !== this.stack || rotation != this.rotation || width != this.width || height != this.height ||
            abs(view.centerU - last.centerU) * view.zoom > width / 4 ||
            abs(view.centerV - last.centerV) * view.zoom > height / 4 ||
            max(view.zoom / last.zoom, last.zoom / view.zoom) > 1.3
        if (stale) {
            this.view = view
            this.stack = stack
            this.rotation = rotation
            this.width = width
            this.height = height
        }
        return stale
    }
}

/** Squared iso distance of a chunk (in view space) from the view center; nearer chunks load first. */
private fun IsoView.distanceSq(viewPos: ChunkPos, hs: Double): Float {
    val du = 16.0 * (viewPos.x - viewPos.z) - centerU
    val dv = 8.0 * (viewPos.x + viewPos.z) + 8 - EditorState.SURFACE_Y * hs - centerV
    return (du * du + dv * dv).toFloat()
}

private class DrawnTile(
    val pos: ChunkPos,
    val tile: ChunkTile,
    val left: Int, val top: Int, val width: Int, val height: Int,
    /** Paste ghosts are drawn but must not be picked, or the anchor would chase itself. */
    val pickable: Boolean,
)

/** What the current left-button drag does. */
private enum class Drag { None, Pan, Brush, RectSelect, Erase, MoveLayer }

private fun ChunkPos.inRect(a: ChunkPos, b: ChunkPos) =
    x in min(a.x, b.x)..max(a.x, b.x) && z in min(a.z, b.z)..max(a.z, b.z)

private fun rectPositions(a: ChunkPos, b: ChunkPos) = buildList {
    for (x in min(a.x, b.x)..max(a.x, b.x)) for (z in min(a.z, b.z)..max(a.z, b.z)) add(ChunkPos(x, z))
}

/**
 * The central 2.5D view. The left button runs the active tool; right/middle drag always moves the view;
 * the wheel zooms around the cursor; WASD/arrow keys move. In paste mode a left click places the clipboard.
 */
@Composable
fun ChunkViewport(state: EditorState, modifier: Modifier = Modifier) {
    val drawn = remember { ArrayList<DrawnTile>() }
    val prefetch = remember { PrefetchTracker() }
    var drag by remember { mutableStateOf(Drag.None) }

    fun pick(p: Offset): ChunkPos? {
        for (i in drawn.indices.reversed()) {
            val d = drawn[i]
            if (!d.pickable) continue
            val lx = p.x - d.left
            val ly = p.y - d.top
            if (lx < 0 || ly < 0 || lx >= d.width || ly >= d.height) continue
            val px = (lx * d.tile.image.width / d.width).toInt()
            val py = (ly * d.tile.image.height / d.height).toInt()
            if (d.tile.hit(px, py)) return d.pos
        }
        return null
    }

    /** Chunk under the cursor on a flat plane at surface height, for empty areas and smooth dragging. */
    fun pickGround(p: Offset, width: Float, height: Float): ChunkPos {
        val hs = state.renderSettings.heightScale.toDouble()
        val u = state.view.screenToU(p.x, width)
        val v = state.view.screenToV(p.y, height)
        val sum = 2 * (v + EditorState.SURFACE_Y * hs) // x + z
        return state.fromView(ChunkPos(floor((sum + u) / 2 / 16).toInt(), floor((sum - u) / 2 / 16).toInt()))
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                val keys = state.heldKeys
                if (Key.CtrlLeft in keys || Key.CtrlRight in keys) return@withFrameNanos
                var dx = 0f
                var dy = 0f
                if (Key.W in keys || Key.DirectionUp in keys) dy += 1
                if (Key.S in keys || Key.DirectionDown in keys) dy -= 1
                if (Key.A in keys || Key.DirectionLeft in keys) dx += 1
                if (Key.D in keys || Key.DirectionRight in keys) dx -= 1
                if (dx != 0f || dy != 0f) {
                    val d = KEY_PAN_SPEED * dt
                    state.view = state.view.panned(dx * d, dy * d)
                }
            }
        }
    }

    val cursor = when {
        drag == Drag.Pan || drag == Drag.MoveLayer -> PointerIcon(Cursor(Cursor.MOVE_CURSOR))
        state.pasteMode -> PointerIcon(Cursor(Cursor.CROSSHAIR_CURSOR))
        state.tool == Tool.Pan -> PointerIcon(Cursor(Cursor.HAND_CURSOR))
        state.tool == Tool.MoveLayer -> PointerIcon(Cursor(Cursor.MOVE_CURSOR))
        state.tool == Tool.RectSelect -> PointerIcon(Cursor(Cursor.CROSSHAIR_CURSOR))
        else -> PointerIcon.Default
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { state.viewportSize = it }
            .pointerHoverIcon(cursor)
            .pointerInput(Unit) {
                var last = Offset.Zero
                var brushMode = SelectMode.Add
                var moveStart = ChunkPos(0, 0)
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val p = change.position
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val mods = event.keyboardModifiers
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val factor = 1.2.pow(-change.scrollDelta.y.toDouble())
                                state.view = state.view.zoomedAt(factor, p.x, p.y, w, h)
                                change.consume()
                            }
                            PointerEventType.Press -> {
                                last = p
                                val b = event.buttons
                                // Shift adds and Ctrl subtracts with either selection tool, like GIMP.
                                val mode = when {
                                    mods.isShiftPressed -> SelectMode.Add
                                    mods.isCtrlPressed -> SelectMode.Subtract
                                    else -> state.selectMode
                                }
                                val hit = pick(p)
                                drag = when {
                                    b.isSecondaryPressed || b.isTertiaryPressed -> Drag.Pan
                                    !b.isPrimaryPressed -> Drag.None
                                    state.pasteMode -> {
                                        state.pasteAt(hit ?: pickGround(p, w, h), keepPlacing = mods.isShiftPressed)
                                        Drag.None
                                    }
                                    else -> when (state.tool) {
                                        Tool.Pan -> Drag.Pan
                                        Tool.BrushSelect -> {
                                            if (mode == SelectMode.Replace) state.clearSelection()
                                            brushMode = if (mode == SelectMode.Subtract) SelectMode.Subtract else SelectMode.Add
                                            hit?.let { state.applySelection(state.brushAt(it), brushMode) }
                                            Drag.Brush
                                        }
                                        Tool.RectSelect -> {
                                            val start = hit ?: pickGround(p, w, h)
                                            brushMode = mode
                                            state.rectDrag = start to start
                                            Drag.RectSelect
                                        }
                                        Tool.Eraser -> if (state.beginErase()) {
                                            hit?.let { state.eraseAt(state.brushAt(it)) }
                                            Drag.Erase
                                        } else Drag.None
                                        Tool.MoveLayer -> if (state.beginMoveLayer()) {
                                            moveStart = pickGround(p, w, h)
                                            Drag.MoveLayer
                                        } else Drag.None
                                        Tool.PickLayer -> {
                                            hit?.let(state::pickLayerAt)
                                            Drag.None
                                        }
                                    }
                                }
                            }
                            PointerEventType.Move -> {
                                when (drag) {
                                    Drag.Pan -> state.view = state.view.panned(p.x - last.x, p.y - last.y)
                                    Drag.Brush -> pick(p)?.let { state.applySelection(state.brushAt(it), brushMode) }
                                    Drag.Erase -> pick(p)?.let { state.eraseAt(state.brushAt(it)) }
                                    Drag.RectSelect -> state.rectDrag?.let { (start, _) ->
                                        state.rectDrag = start to (pick(p) ?: pickGround(p, w, h))
                                    }
                                    Drag.MoveLayer -> {
                                        val now = pickGround(p, w, h)
                                        state.moveLayerBy(now.x - moveStart.x, now.z - moveStart.z)
                                    }
                                    Drag.None -> {}
                                }
                                last = p
                                val hit = pick(p)
                                state.hovered = hit
                                if (state.pasteMode) state.pasteAnchor = hit ?: pickGround(p, w, h)
                            }
                            PointerEventType.Release -> {
                                val b = event.buttons
                                if (!b.isPrimaryPressed && !b.isSecondaryPressed && !b.isTertiaryPressed) {
                                    when (drag) {
                                        Drag.RectSelect -> state.rectDrag?.let { (a, c) ->
                                            state.applySelection(rectPositions(a, c), brushMode)
                                            state.rectDrag = null
                                        }
                                        Drag.Erase, Drag.MoveLayer -> state.endStroke()
                                        else -> {}
                                    }
                                    drag = Drag.None
                                }
                            }
                            PointerEventType.Exit -> state.hovered = null
                        }
                    }
                }
            }
    ) {
        drawRect(EditorTheme.viewportBackground)
        state.tiles.generation // redraw whenever a tile finishes rendering
        val stack = state.activeStack ?: return@Canvas
        val view = state.view
        val selection = state.selection
        val hovered = state.hovered
        val layerColors = state.showLayerColors
        val hs = state.renderSettings.heightScale.toDouble()
        val lod = IsoView.lodFor(view.zoom)
        val anchor = state.pasteAnchor
        val ghosts: Map<ChunkPos, ChunkRef> =
            if (state.pasteMode && anchor != null) state.clipboard?.placedAt(anchor).orEmpty() else emptyMap()
        val rect = state.rectDrag
        val brushCenter = hovered?.takeIf { !state.pasteMode && (state.tool == Tool.BrushSelect || state.tool == Tool.Eraser) }
        val brushRadius = (state.brushSize - 1) / 2
        val emphasizeActive = state.tool == Tool.MoveLayer || state.tool == Tool.Eraser
        val activeId = stack.activeId
        val rotation = state.rotation
        var tilesTotal = 0
        var tilesMissing = 0

        state.tiles.beginFrame()
        drawn.clear()
        // The camera works in rotated coordinates (viewPos); everything else uses real positions (pos).
        for (viewPos in view.visibleChunks(size.width, size.height, VIEW_MIN_Y, VIEW_MAX_Y, hs)) {
            val pos = state.fromView(viewPos)
            val ghost = ghosts[pos]
            val layer = stack.layerAt(pos)
            val shown = ghost ?: layer?.let { stack.contentOf(it, pos) }
            // Erased original chunks stay visible as faint red ghosts so they can be selected and restored.
            val erased = shown == null && layerColors && stack.base.visible && stack.base.isErased(pos)
            val ref = shown ?: (if (erased) ChunkRef(stack.world, pos) else null) ?: continue

            val tile = state.tiles.request(ref.world, ref.pos, lod, rotation, view.distanceSq(viewPos, hs))
            if (!erased) tilesTotal++
            if (tile == null) {
                if (!erased) {
                    tilesMissing++
                    drawPlaceholder(viewPos, view, hs, if (ghost != null) EditorTheme.paste.copy(alpha = 0.4f) else EditorTheme.placeholder)
                }
                continue
            }
            val originU = tile.originU(viewPos)
            val originV = tile.originV(viewPos, hs)
            val left = view.toScreenX(originU, size.width).roundToInt()
            val top = view.toScreenY(originV, size.height).roundToInt()
            val right = view.toScreenX(originU + tile.image.width / tile.lod.toDouble(), size.width).roundToInt()
            val bottom = view.toScreenY(originV + tile.image.height / tile.lod.toDouble(), size.height).roundToInt()
            // The visible range assumes a height band; with the real heights known, skip what ends up off screen.
            if (right <= 0 || bottom <= 0 || left >= size.width || top >= size.height) continue

            val selected = pos in selection && ghost == null
            val inBrush = brushCenter != null && abs(pos.x - brushCenter.x) <= brushRadius && abs(pos.z - brushCenter.z) <= brushRadius
            val inRect = rect != null && pos.inRect(rect.first, rect.second)
            val tint = when {
                ghost != null -> EditorTheme.paste.copy(alpha = 0.45f)
                inRect -> EditorTheme.accent.copy(alpha = if (selected) 0.6f else 0.35f)
                erased && selected -> EditorTheme.accent.copy(alpha = 0.7f)
                erased -> EditorTheme.deleted.copy(alpha = 0.7f)
                inBrush && state.tool == Tool.Eraser -> EditorTheme.deleted.copy(alpha = 0.45f)
                selected && (pos == hovered || inBrush) -> EditorTheme.accent.copy(alpha = 0.6f)
                selected -> EditorTheme.accent.copy(alpha = 0.45f)
                pos == hovered || inBrush -> Color.White.copy(alpha = 0.22f)
                emphasizeActive && layer?.id == activeId -> Color(layer.color).copy(alpha = 0.5f)
                layerColors && layer is ChunkLayer -> Color(layer.color).copy(alpha = 0.22f)
                else -> null
            }
            drawImage(
                image = tile.image,
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(right - left, bottom - top),
                alpha = if (erased) 0.3f else 1f,
                colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcAtop) },
                filterQuality = if (view.zoom >= tile.lod) FilterQuality.None else FilterQuality.Low,
            )
            drawn += DrawnTile(pos, tile, left, top, right - left, bottom - top, pickable = ghost == null)
        }
        state.tiles.endFrame()

        if (prefetch.update(view, stack, rotation, size.width, size.height)) {
            val around = view.visibleChunks(size.width * PREFETCH_SCALE, size.height * PREFETCH_SCALE, VIEW_MIN_Y, VIEW_MAX_Y, hs)
                .sortedBy { view.distanceSq(it, hs) }
            val chunks = ArrayList<Pair<WorldSource, ChunkPos>>(min(around.size, PREFETCH_LIMIT))
            for (viewPos in around) {
                if (chunks.size == PREFETCH_LIMIT) break
                val pos = state.fromView(viewPos)
                val ref = stack.layerAt(pos)?.let { stack.contentOf(it, pos) } ?: continue
                chunks += ref.world to ref.pos
            }
            state.tiles.prefetch(chunks)
        }

        state.frameTilesTotal = tilesTotal
        state.frameTilesMissing = tilesMissing
        state.frameCount++
    }
}

/** Flat diamond at surface height, shown while a chunk's tile is still rendering. [pos] is in view space. */
private fun DrawScope.drawPlaceholder(pos: ChunkPos, view: IsoView, hs: Double, color: Color) {
    val x0 = pos.x * 16.0
    val z0 = pos.z * 16.0
    val y = EditorState.SURFACE_Y
    fun pt(x: Double, z: Double) = Offset(
        view.toScreenX(IsoView.u(x, z), size.width),
        view.toScreenY(IsoView.v(x, y, z, hs), size.height),
    )
    val path = Path().apply {
        val a = pt(x0, z0)
        moveTo(a.x, a.y)
        pt(x0 + 16, z0).let { lineTo(it.x, it.y) }
        pt(x0 + 16, z0 + 16).let { lineTo(it.x, it.y) }
        pt(x0, z0 + 16).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color)
}
