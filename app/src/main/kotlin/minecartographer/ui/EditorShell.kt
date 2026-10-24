package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import minecartographer.core.Rotation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import minecartographer.core.edit.BaseLayer
import minecartographer.editor.DockTab
import minecartographer.editor.EditorState
import minecartographer.editor.Tool
import minecartographer.editor.WorldLibrary
import minecartographer.editor.newProjectInteractive
import minecartographer.editor.openProjectInteractive
import minecartographer.editor.saveProjectInteractive
import minecartographer.core.project.ProjectFile
import minecartographer.viewport.ChunkViewport
import minecartographer.viewport.RenderMode
import kotlin.math.roundToInt

private val MIN_DOCK_WIDTH = 200.dp
private val MAX_DOCK_WIDTH = 520.dp

/**
 * GIMP-like layout: menu bar and quick-action toolbar on top, toolbox on the far left with a
 * tabbed dock beside it, the 2.5D view in the middle, layers and inspector docked on the right.
 */
@Composable
fun EditorShell(state: EditorState, onExit: () -> Unit) {
    LaunchedEffect(state.loading != null) {
        while (state.loading != null) {
            state.pollLoading()
            delay(80)
        }
    }
    Box(Modifier.fillMaxSize()) {
        EditorLayout(state, onExit)
        // Startup covers the whole window; later loading steps only cover the viewport (see ViewportOverlay).
        LoadingLayer(state, visible = state.startingUp, fullWindow = true)
    }
    StartScreen(state)
    ExportDialog(state)
}

@Composable
private fun EditorLayout(state: EditorState, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(EditorTheme.window)) {
        MenuRow(state, onExit)
        HDivider()
        Toolbar(state)
        HDivider()
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Toolbox(state)
            VDivider()
            if (state.leftDockVisible) {
                LeftDock(state, Modifier.width(state.leftDockWidth).fillMaxHeight())
                Splitter { state.leftDockWidth = (state.leftDockWidth + it).coerceIn(MIN_DOCK_WIDTH, MAX_DOCK_WIDTH) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ChunkViewport(state, Modifier.fillMaxSize())
                ViewportOverlay(state)
                ModsDrawer(state)
                HelpOverlay(state)
            }
            if (state.rightDockVisible) {
                Splitter { state.rightDockWidth = (state.rightDockWidth - it).coerceIn(MIN_DOCK_WIDTH, MAX_DOCK_WIDTH) }
                RightDock(state, Modifier.width(state.rightDockWidth).fillMaxHeight())
            }
        }
        HDivider()
        StatusBar(state)
    }
}

/** Menus only: the app icon and name are already in the native (dark-styled) title bar. */
@Composable
private fun MenuRow(state: EditorState, onExit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(EditorTheme.toolbar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuBar(editorMenus(state, onExit))
    }
}

private fun editorMenus(state: EditorState, onExit: () -> Unit): List<Menu> = listOf(
    Menu("File") {
        val world = state.activeWorld
        val recent = WorldLibrary.recentProjects().filter { it != state.projectFile }.take(10)
        listOf(
            MenuItem("New project", "Ctrl+N", !state.busy) { state.newProjectInteractive() },
            MenuItem("Open project…", "Ctrl+Shift+O", !state.busy) { state.openProjectInteractive() },
            SubMenu("Recent projects", recent.map { file ->
                MenuItem(file.fileName.toString().removeSuffix(".${ProjectFile.EXTENSION}"), file.parent?.fileName?.toString()) {
                    state.openProjectInteractive(file)
                }
            }, enabled = recent.isNotEmpty() && !state.busy),
            MenuItem("Save project", "Ctrl+S", state.worlds.isNotEmpty()) { state.saveProjectInteractive() },
            MenuItem("Save project as…", "Ctrl+Alt+S", state.worlds.isNotEmpty()) { state.saveProjectInteractive(saveAs = true) },
            MenuSeparator,
            MenuItem("Open world folder…", "Ctrl+O", !state.busy) { FileDialogs.chooseWorldFolder()?.let(state::openWorldFolder) },
            MenuItem("Open demo worlds", enabled = !state.busy) { state.openDemo() },
            MenuItem("Close world", enabled = world != null) { world?.let(state::closeWorld) },
            MenuSeparator,
            MenuItem("Export merged world…", "Ctrl+Shift+S", world != null && !state.busy) { state.exportDialogVisible = true },
            MenuSeparator,
            MenuItem("Exit", "Alt+F4", action = onExit),
        )
    },
    Menu("Edit") {
        val has = state.selection.isNotEmpty()
        listOf(
            MenuItem(state.undoLabel?.let { "Undo $it" } ?: "Undo", "Ctrl+Z", state.canUndo) { state.undo() },
            MenuItem(state.redoLabel?.let { "Redo $it" } ?: "Redo", "Ctrl+Y", state.canRedo) { state.redo() },
            MenuSeparator,
            MenuItem("Cut", "Ctrl+X", has) { state.cut() },
            MenuItem("Copy", "Ctrl+C", has) { state.copy() },
            MenuItem("Copy visible", "Ctrl+Shift+C", has) { state.copy(visible = true) },
            MenuItem("Paste as new layer", "Ctrl+V", state.clipboard != null) { state.startPaste() },
            MenuItem("Paste in place", "Ctrl+Shift+V", state.clipboard != null) { state.pasteInPlace() },
            MenuSeparator,
            MenuItem("Clear from active layer", "Del", has) { state.clearSelected() },
            MenuItem("Restore original chunks", enabled = has) { state.restoreOriginal() },
        )
    },
    Menu("Select") {
        listOf(
            MenuItem("All", "Ctrl+A") { state.selectAll() },
            MenuItem("None", "Ctrl+Shift+A", state.selection.isNotEmpty()) { state.clearSelection() },
            MenuItem("Invert", "Ctrl+I") { state.invertSelection() },
            MenuSeparator,
            MenuItem("Layer to selection") { state.activeStack?.let { state.layerToSelection(it.activeId) } },
        )
    },
    Menu("Layer") {
        val stack = state.activeStack
        val active = stack?.active
        val isBase = active is BaseLayer
        listOf(
            MenuItem("New layer", "Ctrl+Shift+N", stack != null) { state.newLayer() },
            MenuItem("Duplicate layer", "Ctrl+Shift+D", stack != null) { state.duplicateLayer() },
            MenuItem("Delete layer", enabled = stack != null && !isBase) { state.deleteLayer() },
            MenuSeparator,
            MenuItem("Raise layer", "PgUp", stack != null && !isBase) { state.raiseLayer() },
            MenuItem("Lower layer", "PgDn", stack != null && !isBase) { state.lowerLayer() },
            MenuSeparator,
            MenuItem("Merge down", "Ctrl+E", stack != null && active != null && stack.canMergeDown(active.id)) { state.mergeDown() },
            MenuItem("Flatten world", "Ctrl+Shift+E", (stack?.layers?.size ?: 0) > 1) { state.flatten() },
            MenuSeparator,
            MenuItem("Visible", checked = active?.visible == true, enabled = active != null) { active?.let { state.toggleVisible(it.id) } },
            MenuItem("Locked", checked = active?.locked == true, enabled = active != null) { active?.let { state.toggleLocked(it.id) } },
        )
    },
    Menu("View") {
        val s = state.renderSettings
        listOf(
            MenuItem("Zoom in", "+") { state.zoomBy(1.25) },
            MenuItem("Zoom out", "-") { state.zoomBy(0.8) },
            MenuItem("Fit world", "F") { state.fitToWorld() },
            MenuItem("Rotate left", ",") { state.rotate(-1) },
            MenuItem("Rotate right", ".") { state.rotate(+1) },
            MenuSeparator,
            *RenderMode.entries.mapIndexed { i, mode ->
                MenuItem("${mode.label} mode", "${i + 1}", checked = s.mode == mode) { state.setRenderMode(mode) }
            }.toTypedArray(),
            MenuSeparator,
            MenuItem("Mods & textures…", enabled = (state.activeWorld as? minecartographer.core.anvil.AnvilWorld)?.info?.forge != null) { state.modsPanelVisible = true },
            MenuSeparator,
            MenuItem("Chunk borders", checked = s.chunkBorders) { state.updateRenderSettings(s.copy(chunkBorders = !s.chunkBorders)) },
            MenuItem("Layer colors", checked = state.showLayerColors) { state.showLayerColors = !state.showLayerColors },
            MenuSeparator,
            MenuItem("Left dock", checked = state.leftDockVisible) { state.leftDockVisible = !state.leftDockVisible },
            MenuItem("Layers dock", checked = state.rightDockVisible) { state.rightDockVisible = !state.rightDockVisible },
            MenuItem("Inspector", checked = state.inspectorVisible) {
                state.inspectorVisible = !state.inspectorVisible
                if (state.inspectorVisible) state.rightDockVisible = true
            },
        )
    },
    Menu("Tools") {
        Tool.entries.map { t -> MenuItem(t.label, t.keyLabel, checked = state.tool == t) { state.selectTool(t) } }
    },
    Menu("Help") {
        listOf(
            MenuItem("Shortcuts & quick tips", "F1", checked = state.helpVisible) { state.helpVisible = !state.helpVisible },
            MenuItem("Start screen") { state.startScreenVisible = true },
        )
    },
)

/** Quick actions; everything here is also in the menus. */
@Composable
private fun Toolbar(state: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(EditorTheme.toolbar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasSelection = state.selection.isNotEmpty()
        IconButton(Icons.Undo, state.undoLabel?.let { "Undo $it (Ctrl+Z)" } ?: "Nothing to undo", enabled = state.canUndo) { state.undo() }
        IconButton(Icons.Redo, state.redoLabel?.let { "Redo $it (Ctrl+Y)" } ?: "Nothing to redo", enabled = state.canRedo) { state.redo() }
        ToolbarGap()
        IconButton(Icons.Copy, "Copy from active layer (Ctrl+C)", enabled = hasSelection) { state.copy() }
        IconButton(Icons.Cut, "Cut from active layer (Ctrl+X)", enabled = hasSelection) { state.cut() }
        IconButton(
            Icons.Paste, "Paste as new layer (Ctrl+V) · Paste in place (Ctrl+Shift+V)",
            selected = state.pasteMode, enabled = state.clipboard != null,
        ) { if (state.pasteMode) state.cancelPaste() else state.startPaste() }
        IconButton(Icons.Delete, "Clear from active layer (Del)", enabled = hasSelection) { state.clearSelected() }
        ToolbarGap()
        IconButton(Icons.ZoomOut, "Zoom out (-)") { state.zoomBy(0.8) }
        Txt("${(state.view.zoom / 2 * 100).roundToInt()}%", Modifier.width(48.dp).padding(horizontal = 4.dp), size = 12.sp, mono = true)
        IconButton(Icons.ZoomIn, "Zoom in (+)") { state.zoomBy(1.25) }
        IconButton(Icons.Fit, "Fit world (F)") { state.fitToWorld() }

        Spacer(Modifier.weight(1f))
        IconButton(Icons.Settings, "Left dock", selected = state.leftDockVisible) { state.leftDockVisible = !state.leftDockVisible }
        IconButton(Icons.Layers, "Layers dock", selected = state.rightDockVisible) { state.rightDockVisible = !state.rightDockVisible }
    }
}

@Composable
private fun ToolbarGap() {
    Spacer(Modifier.width(8.dp))
    VDivider(Modifier.height(20.dp))
    Spacer(Modifier.width(8.dp))
}

/** Vertical tool palette, like GIMP's toolbox. */
@Composable
private fun Toolbox(state: EditorState) {
    Column(
        Modifier.width(48.dp).fillMaxHeight().background(EditorTheme.navbar).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (tool in Tool.entries) {
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                if (state.tool == tool) {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp).background(EditorTheme.accent))
                }
                IconButton(toolIcon(tool), "${tool.label} (${tool.keyLabel})", selected = state.tool == tool, size = 36.dp, iconSize = 18.dp) {
                    state.selectTool(tool)
                    state.leftTab = DockTab.ToolOptions
                }
            }
        }
    }
}

private fun tabIcon(tab: DockTab) = when (tab) {
    DockTab.ToolOptions -> Icons.Settings
    DockTab.Worlds -> Icons.Worlds
    DockTab.Selection -> Icons.Selection
    DockTab.View -> Icons.Eye
}

/** Tabbed dock (icon tabs like GIMP's dockable dialogs). */
@Composable
private fun LeftDock(state: EditorState, modifier: Modifier) {
    Column(modifier.background(EditorTheme.panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (tab in DockTab.entries) {
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                        IconButton(tabIcon(tab), tab.title, selected = state.leftTab == tab, size = 30.dp, iconSize = 15.dp) { state.leftTab = tab }
                    }
                    if (state.leftTab == tab) Box(Modifier.width(22.dp).height(2.dp).background(EditorTheme.accent))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(Icons.Close, "Hide dock", size = 24.dp, iconSize = 10.dp) { state.leftDockVisible = false }
        }
        HDivider()
        Txt(state.leftTab.title.uppercase(), Modifier.padding(start = 12.dp, top = 10.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.leftTab) {
                DockTab.ToolOptions -> ToolOptionsPanel(state)
                DockTab.Worlds -> WorldsPanel(state)
                DockTab.Selection -> SelectionPanel(state)
                DockTab.View -> ViewPanel(state)
            }
        }
    }
}

@Composable
private fun RightDock(state: EditorState, modifier: Modifier) {
    Column(modifier) {
        ToolWindow("Layers", onClose = { state.rightDockVisible = false }, Modifier.fillMaxWidth().weight(1f)) {
            LayersPanel(state)
        }
        if (state.inspectorVisible) {
            HDivider()
            ToolWindow("Inspector", onClose = { state.inspectorVisible = false }, Modifier.fillMaxWidth().height(300.dp)) {
                InspectorPanel(state)
            }
        }
    }
}

@Composable
private fun BoxScope.ViewportOverlay(state: EditorState) {
    if (state.activeWorld == null) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("No world open", size = 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Txt("Open a Java Edition save (1.2 or newer) to start.", color = EditorTheme.textDim, size = 12.sp)
            Spacer(Modifier.height(14.dp))
            TextButton("Open world folder…", icon = Icons.Folder, primary = true, enabled = !state.busy) {
                FileDialogs.chooseWorldFolder()?.let(state::openWorldFolder)
            }
        }
        LoadingLayer(state, visible = !state.startingUp && state.loading != null, fullWindow = false)
        return
    }
    Compass(state.rotation, Modifier.align(Alignment.TopEnd).padding(12.dp).size(64.dp))
    RoundButton(Icons.RotateLeft, "Rotate view left (,)", Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { state.rotate(-1) }
    RoundButton(Icons.RotateRight, "Rotate view right (.)", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { state.rotate(+1) }
    RenderModeSwitcher(state, Modifier.align(Alignment.BottomStart).padding(12.dp))
    LoadingLayer(state, visible = !state.startingUp && state.loading != null, fullWindow = false)
    val clip = state.clipboard
    if (state.pasteMode && clip != null) {
        Txt(
            "Placing ${clip.size} chunks as a new layer. Click to place, Shift+click to keep placing, Esc to cancel.",
            Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(4.dp))
                .background(EditorTheme.paste).padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color(0xFF0E2A1E), size = 12.sp, weight = FontWeight.SemiBold,
        )
    } else if (state.selection.isNotEmpty()) {
        Txt(
            "${state.selection.size} chunks selected",
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(4.dp))
                .background(EditorTheme.accent).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, size = 12.sp, weight = FontWeight.SemiBold,
        )
    }
    NoticeToast(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
}

@Composable
private fun NoticeToast(state: EditorState, modifier: Modifier) {
    val notice = state.notice ?: return
    LaunchedEffect(notice) {
        delay(if (notice.warning) 5000 else 2500)
        if (state.notice == notice) state.notice = null
    }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(EditorTheme.panelRaised)
            .border(1.dp, if (notice.warning) EditorTheme.warning else EditorTheme.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(notice.text, color = if (notice.warning) EditorTheme.warning else EditorTheme.text, size = 12.sp)
    }
}

/** Shows where north (-Z) and east (+X) point on screen for the current view rotation. */
@Composable
private fun Compass(rotation: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val north = Color(0xFFE5534B)
    Box(modifier.clip(RoundedCornerShape(32.dp)).background(Color(0xAA1E1F22))) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.28f
            // A world direction projects like a block offset: u = x - z, v = (x + z) / 2.
            fun dir(dx: Double, dz: Double): Offset {
                val (x, z) = Rotation.point(rotation, dx, dz)
                return Offset((x - z).toFloat(), ((x + z) / 2).toFloat()) * (r / 1.12f)
            }
            val n = dir(0.0, -1.0)
            val e = dir(1.0, 0.0)
            drawLine(EditorTheme.textFaint, c - e, c + e, strokeWidth = 1.5f)
            drawLine(EditorTheme.textFaint, c, c - n, strokeWidth = 1.5f)
            drawLine(north, c, c + n, strokeWidth = 2.5f)
            fun label(text: String, at: Offset, color: Color) {
                val layout = measurer.measure(text, TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                drawText(layout, topLeft = at - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
            label("N", c + n * 1.45f, north)
            label("E", c + e * 1.45f, EditorTheme.textDim)
        }
    }
}

/** Round floating button used on the viewport edges. */
@Composable
private fun RoundButton(icon: EditorIcon, tooltip: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        WithTooltip(tooltip) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (hovered) EditorTheme.panelRaised else Color(0xCC2B2D30))
                    .border(1.dp, if (hovered) EditorTheme.accent else EditorTheme.border, CircleShape)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, Modifier.size(20.dp), if (hovered) EditorTheme.accent else EditorTheme.text)
            }
        }
    }
}

@Composable
private fun StatusBar(state: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(EditorTheme.toolbar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val world = state.activeWorld
        Pill(if (world == null) "No world" else "${world.name} · ${world.versionLabel}")
        VDivider(Modifier.height(14.dp))
        state.activeStack?.active?.let { layer ->
            Box(Modifier.padding(start = 8.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(layer.color)))
            Pill("Layer: ${layer.name}")
            VDivider(Modifier.height(14.dp))
        }
        val pos = state.hovered
        Pill(if (pos == null) "–" else "Chunk ${pos.x}, ${pos.z}   Block ${pos.x * 16}, ${pos.z * 16}   r.${pos.regionX}.${pos.regionZ}.mca")
        Spacer(Modifier.weight(1f))
        state.clipboard?.let {
            Pill("Clipboard: ${it.size} chunks")
            VDivider(Modifier.height(14.dp))
        }
        Pill("${state.selection.size} selected")
        VDivider(Modifier.height(14.dp))
        Pill(state.tool.label)
    }
}
