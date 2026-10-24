package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

sealed interface MenuEntry

class MenuItem(
    val label: String,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    /** Non-null shows a check mark column (toggles, current tool). */
    val checked: Boolean? = null,
    val action: () -> Unit,
) : MenuEntry

data object MenuSeparator : MenuEntry

/** Entry that opens [items] to its side while hovered, like "Recent projects ›". */
class SubMenu(val label: String, val items: List<MenuEntry>, val enabled: Boolean = items.isNotEmpty()) : MenuEntry

class Menu(val title: String, val items: () -> List<MenuEntry>)

private val MENU_BAR_HEIGHT = 30.dp

/** In-app menu bar drawn with the editor theme (a native menu bar would not match the dark UI). */
@Composable
fun MenuBar(menus: List<Menu>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<Int?>(null) }
    val barHeightPx = with(LocalDensity.current) { MENU_BAR_HEIGHT.roundToPx() }
    Row(modifier.height(MENU_BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        menus.forEachIndexed { i, menu ->
            Box {
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Box(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .background(if (open == i) EditorTheme.accentSoft else if (hovered) EditorTheme.hover else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(interaction, indication = null) { open = if (open == i) null else i }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(menu.title, size = 13.sp)
                }
                if (open == i) {
                    Popup(
                        offset = IntOffset(0, barHeightPx),
                        onDismissRequest = { open = null },
                        properties = PopupProperties(focusable = true),
                    ) {
                        MenuDropdown(menu.items()) { open = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuDropdown(entries: List<MenuEntry>, close: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    // The open submenu stays open until another row is hovered, so the pointer can travel into it.
    var openSub by remember { mutableStateOf<SubMenu?>(null) }
    Column(
        Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp)
            .clip(shape).background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, shape)
            .padding(vertical = 4.dp)
    ) {
        for (entry in entries) {
            when (entry) {
                MenuSeparator -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(EditorTheme.border))
                is MenuItem -> MenuRow(entry, close, onHover = { openSub = null })
                is SubMenu -> SubMenuRow(entry, open = openSub == entry, onHover = { openSub = entry }, close)
            }
        }
    }
}

@Composable
private fun SubMenuRow(sub: SubMenu, open: Boolean, onHover: () -> Unit, close: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered && sub.enabled) onHover() }
    var widthPx by remember { mutableStateOf(0) }
    val nudge = with(LocalDensity.current) { 4.dp.roundToPx() }
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (open || hovered && sub.enabled) EditorTheme.accentSoft else Color.Transparent)
                .hoverable(interaction)
                .clickable(interaction, indication = null, enabled = sub.enabled, onClick = onHover)
                .alpha(if (sub.enabled) 1f else 0.4f)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(22.dp))
            Txt(sub.label, Modifier.weight(1f), size = 13.sp)
            Spacer(Modifier.width(24.dp))
            Txt("›", color = EditorTheme.textDim, size = 14.sp)
        }
        if (open && sub.enabled) {
            Popup(offset = IntOffset(widthPx, -nudge)) {
                MenuDropdown(sub.items, close)
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, close: () -> Unit, onHover: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered && item.enabled) EditorTheme.accentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = item.enabled) {
                close()
                item.action()
            }
            .alpha(if (item.enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp)) {
            if (item.checked == true) Icon(Icons.Check, Modifier.size(14.dp), EditorTheme.accent)
        }
        Spacer(Modifier.width(6.dp))
        Txt(item.label, Modifier.weight(1f), size = 13.sp)
        if (item.shortcut != null) {
            Spacer(Modifier.width(24.dp))
            Txt(item.shortcut, color = EditorTheme.textFaint, size = 12.sp)
        }
    }
}
