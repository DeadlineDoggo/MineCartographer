package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.ChunkPos
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import minecartographer.editor.EditorState

/** GIMP-style layers dock: top of the list is the top of the stack. */
@Composable
fun LayersPanel(state: EditorState) {
    val stack = state.activeStack
    if (stack == null) {
        Txt("No world open", Modifier.padding(12.dp), color = EditorTheme.textDim, size = 12.sp)
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(stack.layers.asReversed(), key = { it.id }) { layer ->
                LayerRow(state, stack, layer, active = layer.id == stack.activeId)
            }
        }
        HDivider()
        val active = stack.active
        val isBase = active is BaseLayer
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(Icons.NewLayer, "New empty layer (Ctrl+Shift+N)", size = 28.dp, iconSize = 15.dp) { state.newLayer() }
            IconButton(Icons.Duplicate, "Duplicate layer (Ctrl+Shift+D)", size = 28.dp, iconSize = 15.dp) { state.duplicateLayer() }
            IconButton(Icons.Raise, "Raise layer (PgUp)", enabled = !isBase && stack.indexOf(active.id) < stack.layers.lastIndex, size = 28.dp, iconSize = 15.dp) { state.raiseLayer() }
            IconButton(Icons.Lower, "Lower layer (PgDn)", enabled = !isBase && stack.indexOf(active.id) > 1, size = 28.dp, iconSize = 15.dp) { state.lowerLayer() }
            IconButton(Icons.MergeDown, "Merge down (Ctrl+E)", enabled = stack.canMergeDown(active.id), size = 28.dp, iconSize = 15.dp) { state.mergeDown() }
            IconButton(Icons.ToSelection, "Layer to selection", size = 28.dp, iconSize = 15.dp) { state.layerToSelection(active.id) }
            IconButton(Icons.Delete, "Delete layer", enabled = !isBase, size = 28.dp, iconSize = 15.dp) { state.deleteLayer() }
        }
    }
}

@Composable
private fun LayerRow(state: EditorState, stack: LayerStack, layer: Layer, active: Boolean) {
    var renaming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (active) EditorTheme.accentSoft else Color.Transparent)
            .pointerInput(layer.id) {
                detectTapGestures(
                    onTap = { state.setActiveLayer(layer.id) },
                    onDoubleTap = { if (layer is ChunkLayer) renaming = true },
                )
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 6.dp).background(EditorTheme.accent))
        else Spacer(Modifier.width(3.dp))
        IconButton(
            if (layer.visible) Icons.Eye else Icons.EyeOff,
            if (layer.visible) "Hide layer" else "Show layer",
            size = 26.dp, iconSize = 15.dp,
        ) { state.toggleVisible(layer.id) }
        IconButton(
            if (layer.locked) Icons.Lock else Icons.Unlock,
            if (layer.locked) "Unlock layer" else "Lock layer (protect from edits)",
            size = 26.dp, iconSize = 13.dp,
        ) { state.toggleLocked(layer.id) }
        Spacer(Modifier.width(4.dp))
        LayerThumbnail(stack, layer, Modifier.size(44.dp, 32.dp).alpha(if (layer.visible) 1f else 0.4f))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).alpha(if (layer.visible) 1f else 0.5f)) {
            if (renaming && layer is ChunkLayer) {
                RenameField(state, layer.name, onDone = { name ->
                    renaming = false
                    if (name != null) state.renameLayer(layer.id, name)
                })
            } else {
                Txt(layer.name, size = 13.sp, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
            Txt(layerSummary(stack, layer), color = EditorTheme.textDim, size = 11.sp)
        }
        // Chunks with blocks this world doesn't have: open what they become.
        if (state.needsBlockMap(layer, stack)) {
            IconButton(Icons.Mapping, "Block mapping: what this layer's modded blocks become here",
                selected = state.blockMapLayer == layer.id, size = 26.dp, iconSize = 14.dp) {
                state.blockMapLayer = if (state.blockMapLayer == layer.id) null else layer.id
            }
        }
    }
}

private fun layerSummary(stack: LayerStack, layer: Layer): String = when (layer) {
    is ChunkLayer -> "${layer.chunks.size} chunks"
    is BaseLayer -> {
        val erased = layer.overrides.count { it.value == ChunkEdit.Delete }
        val merged = layer.overrides.size - erased
        listOfNotNull(
            "${stack.world.name} · ${stack.world.versionLabel}",
            if (erased > 0) "$erased erased" else null,
            if (merged > 0) "$merged merged" else null,
        ).joinToString(" · ")
    }
}

/** Inline name editor: Enter confirms, Esc or clicking elsewhere cancels. */
@Composable
private fun RenameField(state: EditorState, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(Unit) {
        state.textEditing = true
        onDispose { state.textEditing = false }
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = EditorTheme.text, fontSize = 13.sp),
        cursorBrush = SolidColor(EditorTheme.accent),
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(3.dp)).background(EditorTheme.window)
            .border(1.dp, EditorTheme.accent, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onDone(null)
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { onDone(text); true }
                    Key.Escape -> { onDone(null); true }
                    else -> false
                }
            },
    )
}

/** Top-down footprint of the layer's chunks, scaled to fit, in the layer's tag color. */
@Composable
private fun LayerThumbnail(stack: LayerStack, layer: Layer, modifier: Modifier) {
    val color = Color(layer.color)
    Canvas(
        modifier.clip(RoundedCornerShape(3.dp)).background(EditorTheme.window)
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
    ) {
        when (layer) {
            is BaseLayer -> {
                val b = stack.world.bounds ?: return@Canvas
                val inset = 4f
                drawRect(EditorTheme.grassTop.copy(alpha = 0.7f), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset))
                val overrides = layer.overrides.entries.map { it.key to (it.value == ChunkEdit.Delete) }
                drawFootprint(overrides, b.minX, b.minZ, b.maxX, b.maxZ, inset) { erased ->
                    if (erased) EditorTheme.deleted else EditorTheme.edited
                }
            }
            is ChunkLayer -> {
                if (layer.chunks.isEmpty()) return@Canvas
                val keys = layer.chunks.keys
                drawFootprint(keys.map { it to false }, keys.minOf { it.x }, keys.minOf { it.z }, keys.maxOf { it.x }, keys.maxOf { it.z }, 4f) { color }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFootprint(
    cells: List<Pair<ChunkPos, Boolean>>,
    minX: Int, minZ: Int, maxX: Int, maxZ: Int,
    inset: Float,
    colorOf: (Boolean) -> Color,
) {
    val w = (maxX - minX + 1).toFloat()
    val h = (maxZ - minZ + 1).toFloat()
    val scale = minOf((size.width - 2 * inset) / w, (size.height - 2 * inset) / h)
    val ox = (size.width - w * scale) / 2
    val oy = (size.height - h * scale) / 2
    val cell = Size(maxOf(scale, 1f), maxOf(scale, 1f))
    for ((pos, flag) in cells) {
        drawRect(colorOf(flag), Offset(ox + (pos.x - minX) * scale, oy + (pos.z - minZ) * scale), cell)
    }
}
