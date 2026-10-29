package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field that keeps what is typed to itself and hands it over on Enter or when the focus leaves,
 * so one edit is one undo step rather than one per keystroke. Esc puts the old value back.
 */
@Composable
fun CommitInput(value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (focused) TextFocus.focused = false } }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = EditorTheme.text, fontSize = 12.sp, fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default),
        cursorBrush = SolidColor(EditorTheme.accent),
        modifier = modifier
            .onFocusChanged {
                if (focused && !it.isFocused && text != value) onCommit(text)
                focused = it.isFocused
                TextFocus.focused = it.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (text != value) onCommit(text)
                        true
                    }
                    Key.Escape -> {
                        text = value
                        true
                    }
                    else -> false
                }
            }
            .clip(RoundedCornerShape(4.dp)).background(EditorTheme.panelRaised)
            .border(1.dp, if (focused) EditorTheme.accent else EditorTheme.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

/** Shows a number the way model files do: no trailing ".0". */
fun formatNumber(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat()) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

/** A labeled row of number fields, e.g. the x, y and z of a corner. */
@Composable
fun NumberRow(label: String, labels: List<String>, values: List<Float>, enabled: Boolean = true, onCommit: (index: Int, value: Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(52.dp), color = EditorTheme.textDim, size = 11.sp)
        for ((i, v) in values.withIndex()) {
            if (i > 0) Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Txt(labels.getOrElse(i) { "" }, Modifier.width(10.dp), color = EditorTheme.textFaint, size = 10.sp)
                if (enabled) {
                    CommitInput(formatNumber(v), { text -> text.replace(',', '.').toFloatOrNull()?.let { onCommit(i, it) } }, Modifier.weight(1f), mono = true)
                } else {
                    Txt(formatNumber(v), Modifier.weight(1f), color = EditorTheme.textFaint, size = 12.sp, mono = true)
                }
            }
        }
    }
}
