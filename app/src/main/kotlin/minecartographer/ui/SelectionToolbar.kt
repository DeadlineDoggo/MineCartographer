package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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
        NumberField(dy, "Height in blocks above where the chunks were copied from: type a value and press Enter") { state.setChunkHeight(it) }
        IconButton(Icons.Raise, "Raise by 1 block (Shift: 10)") { state.raiseChunks(step()) }
        Divider()
        val cuts = refs.map { it.cut }.distinct()
        val cut = cuts.singleOrNull()
        val cutOn = cuts.any { it != null }
        IconButton(Icons.Cut, if (cutOn) "Stop cutting: take the chunks down to their bottom" else "Cut: keep the original world below Y ${EditorState.SURFACE_Y.toInt()}",
            selected = cutOn) { state.cutChunks(if (cutOn) null else EditorState.SURFACE_Y.toInt()) }
        if (cutOn) {
            IconButton(Icons.Lower, "Cut 1 block lower (Shift: 10)", enabled = cut != null) { cut?.let { state.cutChunks(it - step()) } }
            Txt("Y", Modifier.padding(start = 4.dp), color = EditorTheme.textDim, size = 12.sp, mono = true)
            NumberField(cut, "Cut height: the original world stays below it. Type a value and press Enter") { state.cutChunks(it) }
            IconButton(Icons.Raise, "Cut 1 block higher (Shift: 10)", enabled = cut != null) { cut?.let { state.cutChunks(it + step()) } }
        }
        Divider()
        IconButton(Icons.Undo, "Reset turn, height and cut", enabled = refs.any { it.transformed }) { state.resetChunkTransforms() }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(EditorTheme.border))

/**
 * A number to read and to type: Enter or leaving the field applies it, Esc puts the old value back. [value] is null
 * when the chunks differ (shown as "…"). While it has focus the map's keys are off, like in any text field.
 */
@Composable
private fun NumberField(value: Int?, tooltip: String, onCommit: (Int) -> Unit) = WithTooltip(tooltip) {
    var field by remember(value) { mutableStateOf(TextFieldValue(value?.toString().orEmpty())) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    DisposableEffect(Unit) { onDispose { if (focused) TextFocus.focused = false } }
    fun revert() { field = TextFieldValue(value?.toString().orEmpty()) }
    fun commit() {
        val typed = field.text.trim().removePrefix("+").toIntOrNull()
        if (typed != null && typed != value) onCommit(typed) else revert()
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.width(56.dp).height(28.dp).clip(shape)
            .background(if (focused) EditorTheme.panelRaised else Color.Transparent)
            .border(1.dp, if (focused) EditorTheme.accent else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = field,
            onValueChange = { new -> field = new.copy(text = new.text.filter { it.isDigit() || it == '-' || it == '+' }.take(6)) },
            singleLine = true,
            textStyle = TextStyle(color = EditorTheme.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(EditorTheme.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (field.text.isEmpty() && !focused) Txt("…", size = 12.sp, mono = true)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                .onFocusChanged {
                    if (it.isFocused && !focused) field = field.copy(selection = TextRange(0, field.text.length))
                    if (!it.isFocused && focused) commit()
                    focused = it.isFocused
                    TextFocus.focused = it.isFocused
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { commit(); focus.clearFocus(); true }
                        Key.Escape -> { revert(); focus.clearFocus(); true }
                        else -> false
                    }
                },
        )
    }
}
