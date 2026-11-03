package minecartographer.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelTool
import minecartographer.ui.FileDialogs
import minecartographer.ui.TextFocus
import minecartographer.viewport.RenderMode

/** Window-level keyboard handling, so shortcuts work whatever has focus. Menu labels mirror these. */
fun EditorState.handleKey(e: KeyEvent): Boolean {
    val typing = textEditing || TextFocus.focused
    when (e.type) {
        KeyEventType.KeyUp -> {
            heldKeys -= e.key
            return false
        }
        KeyEventType.KeyDown -> if (!typing) heldKeys += e.key
        else -> return false
    }
    if (e.key == Key.F1) {
        helpVisible = !helpVisible
        return true
    }
    if (startScreenVisible) {
        if (e.key == Key.Escape) startScreenVisible = false
        return true
    }
    if (typing) return false
    if (helpVisible && e.key == Key.Escape) {
        helpVisible = false
        return true
    }

    val ctrl = e.isCtrlPressed
    val shift = e.isShiftPressed
    // File commands work in every workspace; the rest is the map's until a workspace brings its own.
    val map = workspaces.active == Workspace.Map
    if (ctrl) {
        val file = e.key == Key.O || e.key == Key.S || (e.key == Key.N && !shift)
        if (!map && !file) return workspaces.activeModel()?.let { handleModelKey(it, e) } ?: false
        when (e.key) {
            Key.O -> if (shift) openProjectInteractive() else FileDialogs.chooseWorldFolder()?.let(::openWorldFolder)
            Key.S -> when {
                shift -> exportDialogVisible = true
                e.isAltPressed -> saveProjectInteractive(saveAs = true)
                else -> saveProjectInteractive()
            }
            Key.Z -> if (shift) redo() else undo()
            Key.Y -> redo()
            Key.C -> copy(visible = shift)
            Key.X -> cut()
            Key.V -> if (shift) pasteInPlace() else startPaste()
            Key.A -> if (shift) clearSelection() else selectAll()
            Key.I -> invertSelection()
            Key.N -> if (shift) newLayer() else newProjectInteractive()
            Key.D -> if (shift) duplicateLayer() else return false
            Key.E -> if (shift) flatten() else mergeDown()
            else -> return false
        }
        return true
    }
    if (!map) return workspaces.activeModel()?.let { handleModelKey(it, e) } ?: false
    Tool.entries.firstOrNull { it.key == e.key }?.let {
        selectTool(it)
        return true
    }
    when (e.key) {
        Key.Delete, Key.Backspace -> clearSelected()
        Key.Escape -> if (pasteMode) cancelPaste() else clearSelection()
        Key.F -> fitToWorld()
        Key.One -> setRenderMode(RenderMode.Map)
        Key.Two -> setRenderMode(RenderMode.Blocks)
        Key.Three -> setRenderMode(RenderMode.Textured)
        Key.V -> setFlat(!renderSettings.flat)
        Key.Comma -> rotate(-1)
        Key.Period -> rotate(+1)
        Key.Equals, Key.NumPadAdd -> zoomBy(1.25)
        Key.Minus, Key.NumPadSubtract -> zoomBy(0.8)
        Key.PageUp -> raiseLayer()
        Key.PageDown -> lowerLayer()
        else -> return false
    }
    return true
}

/** Keys of a model tab: tools, adding and deleting elements, and nudging the picked ones by the snap step. */
private fun handleModelKey(model: ModelEditorState, e: KeyEvent): Boolean {
    val shift = e.isShiftPressed
    if (e.isCtrlPressed) {
        when (e.key) {
            Key.Z -> if (shift) model.redo() else model.undo()
            Key.Y -> model.redo()
            Key.D -> model.duplicateSelected()
            Key.A -> model.selectAll()
            else -> return false
        }
        return true
    }
    ModelTool.entries.firstOrNull { it.key == e.key }?.let {
        model.tool = it
        return true
    }
    when (e.key) {
        Key.N -> model.addCube()
        Key.Delete, Key.Backspace -> model.deleteSelected()
        Key.Escape -> model.clearSelection()
        Key.F -> model.resetView()
        Key.DirectionLeft -> model.nudge(0, -model.snap)
        Key.DirectionRight -> model.nudge(0, model.snap)
        Key.DirectionUp -> model.nudge(2, -model.snap)
        Key.DirectionDown -> model.nudge(2, model.snap)
        Key.PageUp -> model.nudge(1, model.snap)
        Key.PageDown -> model.nudge(1, -model.snap)
        else -> return false
    }
    return true
}
