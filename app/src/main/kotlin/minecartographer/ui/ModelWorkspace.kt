package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.editor.EditorState
import minecartographer.editor.Workspace
import minecartographer.model.ElementRotation
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelElement
import minecartographer.model.ModelTool
import minecartographer.model.Uv
import minecartographer.model.Vec3
import minecartographer.textures.Dir

/** The middle of a model tab: the 3D view. */
@Composable
fun ModelWorkspaceView(state: EditorState, workspace: Workspace.Model) {
    val model = state.workspaces.modelOf(workspace) ?: return
    ModelViewport(state, model, Modifier.fillMaxSize())
}

private fun toolIcon(tool: ModelTool) = when (tool) {
    ModelTool.Select -> Icons.Selection
    ModelTool.Move -> Icons.Move
    ModelTool.Rotate -> Icons.RotateRight
    ModelTool.Resize -> Icons.Fit
}

/** Vertical tool palette of the model editor, like the map's. */
@Composable
fun ModelToolbox(model: ModelEditorState) {
    Column(
        Modifier.width(48.dp).fillMaxHeight().background(EditorTheme.navbar).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (tool in ModelTool.entries) {
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                if (model.tool == tool) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp).background(EditorTheme.accent))
                IconButton(toolIcon(tool), "${tool.label} (${tool.keyLabel})", selected = model.tool == tool, size = 36.dp, iconSize = 18.dp) { model.tool = tool }
            }
        }
        Box(Modifier.padding(vertical = 6.dp).width(24.dp).height(1.dp).background(EditorTheme.border))
        IconButton(Icons.NewLayer, "Add cube (N)", size = 36.dp, iconSize = 18.dp) { model.addCube() }
    }
}

/** Left dock: the elements, and the texture variables they use. */
@Composable
fun ModelLeftDock(state: EditorState, model: ModelEditorState, modifier: Modifier) {
    Column(modifier.background(EditorTheme.panel)) {
        Txt("ELEMENTS · ${model.doc.elements.size}", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
        val list = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.doc.elements.isEmpty()) {
                Txt("No elements. Press N to add a cube.", Modifier.padding(12.dp), color = EditorTheme.textFaint, size = 12.sp, maxLines = 2)
            }
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), list) {
                items(model.doc.elements, key = { it.id }) { e -> ElementRow(model, e) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        HDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Txt("TEXTURES", color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            for ((variable, id) in model.doc.textures) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommitInput("#$variable", { model.renameTexture(variable, it) }, Modifier.weight(1f), mono = true)
                        Spacer(Modifier.width(4.dp))
                        IconButton(Icons.Close, "Remove #$variable", size = 24.dp, iconSize = 10.dp) { model.removeTextureVariable(variable) }
                    }
                    Spacer(Modifier.height(3.dp))
                    CommitInput(id, { model.setTexture(variable, it.trim()) }, Modifier.fillMaxWidth(), mono = true)
                }
            }
            TextButton("Add texture") { model.addTextureVariable() }
        }
    }
}

@Composable
private fun ElementRow(model: ModelEditorState, e: ModelElement) {
    val selected = e.id in model.selection
    val hidden = e.id in model.hidden
    Row(
        Modifier.fillMaxWidth().background(if (selected) EditorTheme.accentSoft else Color.Transparent).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(if (hidden) Icons.EyeOff else Icons.Eye, if (hidden) "Show" else "Hide", size = 24.dp, iconSize = 13.dp) { model.toggleHidden(e.id) }
        // Shift adds to the selection, which a plain click can't tell.
        Row(
            Modifier.weight(1f).pointerInput(e.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                            model.select(e.id, add = event.keyboardModifiers.isShiftPressed)
                        }
                    }
                }
            }.padding(vertical = 5.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(e.name, Modifier.weight(1f), color = if (selected) EditorTheme.accent else if (hidden) EditorTheme.textFaint else EditorTheme.text, size = 12.sp)
            Txt(e.faces.size.toString(), color = EditorTheme.textFaint, size = 11.sp, mono = true)
        }
    }
}

private val XYZ = listOf("X", "Y", "Z")
private val DIR_LABELS = listOf("Down", "Up", "North", "South", "West", "East")
private val CULL_LABELS = listOf("–", "D", "U", "N", "S", "W", "E")

/** Right dock: the picked element (the first one of several; edits go to all), then its faces. */
@Composable
fun ModelRightDock(state: EditorState, model: ModelEditorState, modifier: Modifier) {
    ToolWindow("Element", onClose = { state.rightDockVisible = false }, modifier) {
        val e = model.selected.firstOrNull()
        if (e == null) {
            Txt("Pick an element in the view or in the list.", Modifier.padding(12.dp), color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
            return@ToolWindow
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (model.selection.size > 1) Txt("${model.selection.size} elements: edits go to all of them", color = EditorTheme.textFaint, size = 11.sp)
            CommitInput(e.name, { v -> model.edit("Rename") { it.copy(name = v) } }, Modifier.fillMaxWidth())
            SectionHeader("POSITION")
            NumberRow("From", XYZ, listOf(e.from.x, e.from.y, e.from.z)) { i, v -> model.edit("Resize") { it.copy(from = it.from.with(i, v)) } }
            Spacer(Modifier.height(4.dp))
            NumberRow("To", XYZ, listOf(e.to.x, e.to.y, e.to.z)) { i, v -> model.edit("Resize") { it.copy(to = it.to.with(i, v)) } }
            Spacer(Modifier.height(4.dp))
            NumberRow("Size", XYZ, listOf(e.size.x, e.size.y, e.size.z)) { i, v ->
                model.edit("Resize") { it.copy(to = it.to.with(i, it.from[i] + v)) }
            }

            SectionHeader("ROTATION")
            val r = e.rotation
            Segmented(listOf(0, 1, 2), r?.axis ?: 1, { XYZ[it] }) { axis ->
                model.edit("Rotate") { it.copy(rotation = ElementRotation(axis, it.rotation?.angle ?: 0f, it.rotation?.origin ?: it.center, it.rotation?.rescale ?: false)) }
            }
            Spacer(Modifier.height(4.dp))
            NumberRow("Angle", listOf(""), listOf(r?.angle ?: 0f)) { _, v ->
                model.edit("Rotate") { it.copy(rotation = ElementRotation(it.rotation?.axis ?: 1, v.coerceIn(-45f, 45f), it.rotation?.origin ?: it.center, it.rotation?.rescale ?: false)) }
            }
            if (r != null) {
                Spacer(Modifier.height(4.dp))
                NumberRow("Origin", XYZ, listOf(r.origin.x, r.origin.y, r.origin.z)) { i, v ->
                    model.edit("Rotate") { el -> el.rotation?.let { el.copy(rotation = it.copy(origin = it.origin.with(i, v))) } ?: el }
                }
                Checkbox("Rescale", r.rescale) { on -> model.edit("Rotate") { el -> el.rotation?.let { el.copy(rotation = it.copy(rescale = on)) } ?: el } }
            }
            Checkbox("Shade", e.shade) { on -> model.edit("Shade") { it.copy(shade = on) } }

            SectionHeader("FACES")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                for (dir in 0 until 6) {
                    val present = dir in e.faces
                    val current = model.face == dir
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                            .background(if (current) EditorTheme.accentSoft else EditorTheme.panelRaised)
                            .clickable { model.face = dir }.padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(DIR_LABELS[dir].first().toString(), color = if (present) (if (current) EditorTheme.accent else EditorTheme.text) else EditorTheme.textFaint, size = 12.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            val dir = model.face
            if (dir != null) {
                Spacer(Modifier.height(8.dp))
                val face = e.faces[dir]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(DIR_LABELS[dir], Modifier.weight(1f), size = 12.sp, weight = FontWeight.SemiBold)
                    TextButton(if (face == null) "Add face" else "Remove face") { model.toggleFace(dir) }
                }
                if (face != null) {
                    Spacer(Modifier.height(6.dp))
                    Txt("Texture", color = EditorTheme.textDim, size = 11.sp)
                    CommitInput(face.texture.orEmpty(), { v -> model.editFace(dir, "Texture") { it.copy(texture = v.trim().ifEmpty { null }) } }, Modifier.fillMaxWidth(), mono = true)
                    Spacer(Modifier.height(6.dp))
                    val uv = face.uv
                    NumberRow("UV", listOf("u", "v", "u", "v"), listOf(uv?.u0 ?: 0f, uv?.v0 ?: 0f, uv?.u1 ?: 16f, uv?.v1 ?: 16f)) { i, v ->
                        model.editFace(dir, "UV") { f ->
                            val cur = f.uv ?: Uv(0f, 0f, 16f, 16f)
                            f.copy(uv = when (i) { 0 -> cur.copy(u0 = v); 1 -> cur.copy(v0 = v); 2 -> cur.copy(u1 = v); else -> cur.copy(v1 = v) })
                        }
                    }
                    if (uv != null) TextButton("Default UV", Modifier.padding(top = 4.dp)) { model.editFace(dir, "UV") { it.copy(uv = null) } }
                    Spacer(Modifier.height(6.dp))
                    Txt("Texture turn", color = EditorTheme.textDim, size = 11.sp)
                    Segmented(listOf(0, 90, 180, 270), face.rotation, { "$it°" }) { deg -> model.editFace(dir, "Face turn") { it.copy(rotation = deg) } }
                    Spacer(Modifier.height(6.dp))
                    Txt("Cull face (hidden against this neighbor)", color = EditorTheme.textDim, size = 11.sp)
                    Segmented((-1 until 6).toList(), face.cull, { CULL_LABELS[it + 1] }) { c -> model.editFace(dir, "Cull face") { it.copy(cull = c) } }
                    Checkbox("Biome tint", face.tint) { on -> model.editFace(dir, "Tint") { it.copy(tint = on) } }
                }
            }
        }
    }
}

/** Status bar of a model tab. */
@Composable
fun RowScope.ModelStatus(model: ModelEditorState) {
    Pill(model.name + if (model.dirty) " ●" else "")
    model.origin?.let {
        VDivider(Modifier.height(14.dp))
        Pill("from $it")
    }
    VDivider(Modifier.height(14.dp))
    val faces = model.doc.elements.sumOf { it.faces.size }
    Pill("${model.doc.elements.size} elements · $faces faces")
    if (model.selection.isNotEmpty()) {
        VDivider(Modifier.height(14.dp))
        Pill("${model.selection.size} selected")
    }
    Spacer(Modifier.weight(1f))
    Pill("Snap ${formatNumber(model.snap)}")
    VDivider(Modifier.height(14.dp))
    Pill(model.tool.label)
}

/** Snap steps the toolbar offers, in model units (pixels of a 16-pixel texture). */
val SNAPS = listOf(1f, 0.5f, 0.25f)

@Composable
fun ModelToolbar(model: ModelEditorState) {
    IconButton(Icons.Undo, model.undoLabel?.let { "Undo $it (Ctrl+Z)" } ?: "Nothing to undo", enabled = model.canUndo) { model.undo() }
    IconButton(Icons.Redo, model.redoLabel?.let { "Redo $it (Ctrl+Y)" } ?: "Nothing to redo", enabled = model.canRedo) { model.redo() }
    Spacer(Modifier.width(8.dp))
    IconButton(Icons.NewLayer, "Add cube (N)") { model.addCube() }
    IconButton(Icons.Duplicate, "Duplicate (Ctrl+D)", enabled = model.selection.isNotEmpty()) { model.duplicateSelected() }
    IconButton(Icons.Delete, "Delete (Del)", enabled = model.selection.isNotEmpty()) { model.deleteSelected() }
    Spacer(Modifier.width(12.dp))
    Txt("Snap", color = EditorTheme.textDim, size = 12.sp)
    Spacer(Modifier.width(6.dp))
    Box(Modifier.width(150.dp)) { Segmented(SNAPS, model.snap, { formatNumber(it) }) { model.snap = it } }
}
