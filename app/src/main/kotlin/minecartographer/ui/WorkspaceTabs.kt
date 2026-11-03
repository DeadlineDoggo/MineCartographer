package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.editor.EditorState
import minecartographer.editor.Workspace

/** The tab strip over the main area: the map (always there), the atlas and the models, and a + for a new model. */
@Composable
fun WorkspaceTabs(state: EditorState) {
    val workspaces = state.workspaces
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(EditorTheme.toolbar),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (workspace in workspaces.open.toList()) {
            val model = (workspace as? Workspace.Model)?.let(workspaces::modelOf)
            val title = when (workspace) {
                Workspace.Map -> "Map"
                Workspace.Atlas -> "Texture atlas · ${state.activeWorld?.name ?: "no world"}"
                is Workspace.Model -> (model?.name ?: "Model") + if (model?.dirty == true) " ●" else ""
            }
            Tab(title, workspace == workspaces.active, closable = workspace != Workspace.Map, onClose = { state.closeWorkspace(workspace) }) {
                workspaces.show(workspace)
            }
        }
        var menuOpen by remember { mutableStateOf(false) }
        val below = with(LocalDensity.current) { 30.dp.roundToPx() }
        Box(Modifier.height(32.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
            IconButton(Icons.ZoomIn, "Open a new tab", size = 24.dp, iconSize = 12.dp) { menuOpen = true }
            if (menuOpen) {
                val hasWorld = state.activeWorld != null
                ContextMenu(
                    IntOffset(0, below),
                    listOf(
                        MenuItem("New empty model") { workspaces.newModel() },
                        MenuItem("Open model from the world…", enabled = hasWorld) { state.modelPickerVisible = true },
                        MenuSeparator,
                        MenuItem("Texture atlas", enabled = hasWorld, checked = Workspace.Atlas in workspaces.open) { state.openAtlas() },
                    ),
                ) { menuOpen = false }
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, closable: Boolean, onClose: () -> Unit, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    Row(
        Modifier.padding(start = 4.dp, top = 4.dp).clip(shape)
            .background(if (selected) EditorTheme.panel else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = if (closable) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            Txt(label, color = if (selected) EditorTheme.text else EditorTheme.textDim, size = 12.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            // The line takes the width of the label; fillMaxWidth would stretch the whole tab across the strip.
            if (selected) Box(Modifier.matchParentSize()) { Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(EditorTheme.accent)) }
        }
        if (closable) {
            Spacer(Modifier.width(6.dp))
            IconButton(Icons.Close, "Close the tab", size = 20.dp, iconSize = 8.dp, onClick = onClose)
        }
    }
}
