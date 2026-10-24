package minecartographer.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import minecartographer.ui.FileDialogs
import minecartographer.viewport.RenderMode

/** Window-level keyboard handling, so shortcuts work whatever has focus. Menu labels mirror these. */
fun EditorState.handleKey(e: KeyEvent): Boolean {
    when (e.type) {
        KeyEventType.KeyUp -> {
            heldKeys -= e.key
            return false
        }
        KeyEventType.KeyDown -> if (!textEditing) heldKeys += e.key
        else -> return false
    }
    if (textEditing) return false

    val ctrl = e.isCtrlPressed
    val shift = e.isShiftPressed
    if (ctrl) {
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
