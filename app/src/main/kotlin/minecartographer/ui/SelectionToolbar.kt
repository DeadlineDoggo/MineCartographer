package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.edit.ChunkLayer
import minecartographer.editor.EditorState

private val BAR_SHAPE = RoundedCornerShape(8.dp)

/**
 * Icons along the bottom of the map while chunks are selected: turn, raise or lower, and cut the selected chunks
 * of the active pasted layer. On the original world it offers to lift the selection into a layer first.
 */
@Composable
fun SelectionToolbar(state: EditorState, modifier: Modifier) {
    if (state.selection.isEmpty() || state.pasteMode) return
    val layer = state.activeStack?.active ?: return
    Row(
        modifier.overMap(state).clip(BAR_SHAPE).background(Color(0xE62B2D30)).border(1.dp, EditorTheme.border, BAR_SHAPE).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer !is ChunkLayer) {
            IconButton(Icons.NewLayer, "Copy the selection into a new layer, to turn or raise it there") {
                if (state.copy()) state.pasteInPlace()
            }
            Txt("Lift to a layer to turn or raise", Modifier.padding(start = 4.dp, end = 8.dp), color = EditorTheme.textDim, size = 12.sp)
            return@Row
        }
        val refs = state.transformTargets().values
        // Shift makes the steps bigger.
        fun step() = if (Key.ShiftLeft in state.heldKeys || Key.ShiftRight in state.heldKeys) 10 else 1

        IconButton(Icons.RotateLeft, "Turn the selected chunks left") { state.turnChunks(-1) }
        IconButton(Icons.RotateRight, "Turn the selected chunks right") { state.turnChunks(1) }
        Divider()
        IconButton(Icons.Lower, "Lower by 1 block (Shift: 10)") { state.raiseChunks(-step()) }
        val dy = refs.map { it.dy }.distinct().singleOrNull()
        Label(dy?.let { if (it > 0) "+$it" else "$it" } ?: "…", "Height")
        IconButton(Icons.Raise, "Raise by 1 block (Shift: 10)") { state.raiseChunks(step()) }
        Divider()
        val cuts = refs.map { it.cut }.distinct()
        val cut = cuts.singleOrNull()
        val cutOn = cuts.any { it != null }
        IconButton(Icons.Cut, if (cutOn) "Stop cutting: take the chunks down to their bottom" else "Cut: keep the original world below Y ${EditorState.SURFACE_Y.toInt()}",
            selected = cutOn) { state.cutChunks(if (cutOn) null else EditorState.SURFACE_Y.toInt()) }
        if (cutOn) {
            IconButton(Icons.Lower, "Cut 1 block lower (Shift: 10)", enabled = cut != null) { cut?.let { state.cutChunks(it - step()) } }
            Label(cut?.let { "Y $it" } ?: "…", "Cut at")
            IconButton(Icons.Raise, "Cut 1 block higher (Shift: 10)", enabled = cut != null) { cut?.let { state.cutChunks(it + step()) } }
        }
        Divider()
        IconButton(Icons.Undo, "Reset turn, height and cut", enabled = refs.any { it.transformed }) { state.resetChunkTransforms() }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(EditorTheme.border))

@Composable
private fun Label(value: String, tooltip: String) = WithTooltip(tooltip) {
    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { Txt(value, size = 12.sp, mono = true) }
}
