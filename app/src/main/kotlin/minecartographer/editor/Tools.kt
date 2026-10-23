package minecartographer.editor

import androidx.compose.ui.input.key.Key

enum class Tool(val label: String, val key: Key, val keyLabel: String, val hint: String) {
    Pan("Move view", Key.H, "H", "Drag to move around. Right or middle drag and WASD work with every tool."),
    BrushSelect("Brush select", Key.B, "B", "Click or paint over chunks to select them."),
    RectSelect("Rectangle select", Key.R, "R", "Drag a rectangle to select every chunk inside it."),
    MoveLayer("Move layer", Key.M, "M", "Drag to move the active layer by whole chunks."),
    Eraser("Eraser", Key.E, "E", "Paint to remove chunks from the active layer. On the original world this hides chunks; the save is never touched."),
    PickLayer("Pick layer", Key.I, "I", "Click a chunk to make the layer it comes from active."),
}

enum class SelectMode(val label: String) {
    Replace("Replace"),
    Add("Add"),
    Subtract("Subtract"),
}

enum class DockTab(val title: String) {
    ToolOptions("Tool options"),
    Worlds("Worlds"),
    Selection("Selection"),
    View("View"),
}

val BRUSH_SIZES = listOf(1, 3, 5, 9)
