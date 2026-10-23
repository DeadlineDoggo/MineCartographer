package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = EditorTheme.text,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = 1,
) = BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    ),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun Icon(icon: EditorIcon, modifier: Modifier = Modifier, color: Color = EditorTheme.text) =
    Canvas(modifier) { with(icon) { draw(color) } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTooltip(text: String, content: @Composable () -> Unit) = TooltipArea(
    tooltip = {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).background(EditorTheme.panelRaised)
                .border(1.dp, EditorTheme.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) { Txt(text, size = 12.sp) }
    },
    delayMillis = 450,
    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(8.dp, 16.dp)),
    content = content,
)

@Composable
fun IconButton(
    icon: EditorIcon,
    tooltip: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    iconSize: Dp = 16.dp,
    onClick: () -> Unit,
) = WithTooltip(tooltip) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> EditorTheme.accentSoft
                    hovered && enabled -> EditorTheme.hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, Modifier.size(iconSize), if (selected) EditorTheme.accent else EditorTheme.text)
    }
}

@Composable
fun TextButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: EditorIcon? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (primary) EditorTheme.accent else EditorTheme.panelRaised
    Row(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered && enabled) base.copy(alpha = 0.85f) else base)
            .border(1.dp, if (primary) Color.Transparent else EditorTheme.border, RoundedCornerShape(5.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, Modifier.size(14.dp))
        Txt(text, size = 12.sp, color = if (primary) Color.White else EditorTheme.text)
    }
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = EditorTheme.text, fontSize = 13.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(EditorTheme.accent),
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(EditorTheme.panelRaised)
            .border(1.dp, EditorTheme.border, RoundedCornerShape(5.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun Checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .clickable { onChange(!checked) }.padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                .background(if (checked) EditorTheme.accent else Color.Transparent)
                .border(1.dp, if (checked) EditorTheme.accent else EditorTheme.textFaint, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(8.dp))
        Txt(label)
    }
}

/** Row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .border(1.dp, EditorTheme.border, RoundedCornerShape(5.dp))
    ) {
        for (option in options) {
            Box(
                Modifier.weight(1f)
                    .background(if (option == selected) EditorTheme.accentSoft else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(label(option), size = 12.sp, color = if (option == selected) EditorTheme.accent else EditorTheme.text)
            }
        }
    }
}

/** Dockable side window: title bar with a close button and scrollable-by-caller content. */
@Composable
fun ToolWindow(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(EditorTheme.panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(title.uppercase(), Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Close", size = 24.dp, iconSize = 10.dp, onClick = onClose)
        }
        HDivider()
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) =
    Txt(text, modifier.padding(top = 12.dp, bottom = 6.dp), color = EditorTheme.textFaint, size = 11.sp, weight = FontWeight.SemiBold)

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Txt(key, Modifier.weight(1f), color = EditorTheme.textDim, size = 12.sp)
        Txt(value, size = 12.sp, mono = true)
    }
}

@Composable
fun HDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(EditorTheme.border))

@Composable
fun VDivider(modifier: Modifier = Modifier) = Box(modifier.width(1.dp).fillMaxHeight().background(EditorTheme.border))

/** Vertical drag handle that resizes the adjacent panel; [onResize] gets the delta in dp. */
@Composable
fun Splitter(onResize: (Dp) -> Unit) {
    val density = LocalDensity.current
    val state = rememberDraggableState { px -> onResize(with(density) { px.toDp() }) }
    Box(
        Modifier.width(5.dp).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(state, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        VDivider()
    }
}
