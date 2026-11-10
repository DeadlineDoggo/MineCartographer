package minecartographer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import minecartographer.core.markers.Marker
import minecartographer.editor.EditorState

/** The right-click menu of a pin on the map. The pin's name bubble stays outlined in blue while it is open. */
@Composable
fun MarkerContextMenu(state: EditorState) {
    val menu = state.markerMenu ?: return
    val inset = panelInset(state)
    ContextMenu(menu.at, markerMenuEntries(state, menu.marker, inset)) {
        state.markerMenu = null
        state.menuMarker = null
    }
}

/** Width of the Waypoints panel while it covers the map's left edge, in pixels, else 0. */
@Composable
fun panelInset(state: EditorState): Float {
    val density = LocalDensity.current
    return if (state.waypointsVisible) with(density) { (state.waypointsWidth + 5.dp).toPx() } else 0f
}

/** The menu of a waypoint or map banner: go to it and copy its name, coordinates or a teleport command. */
fun markerMenuEntries(state: EditorState, m: Marker, leftInsetPx: Float): List<MenuEntry> {
    fun short(text: String) = if (text.length > 28) text.take(27) + "…" else text
    val coordinates = "${m.x} ${m.y ?: "~"} ${m.z}"
    val teleport = "/tp @s ${m.x} ${m.y ?: "~"} ${m.z}"
    return listOf(
        MenuItem("Go to") { state.centerOn(m.x, m.y, m.z, leftInsetPx) },
        MenuSeparator,
        MenuItem("Copy name", short(m.name)) { state.copyText(m.name) },
        MenuItem("Copy coordinates", coordinates) { state.copyText(coordinates) },
        MenuItem("Copy teleport command", short(teleport)) { state.copyText(teleport) },
        MenuSeparator,
        MenuItem(m.source, enabled = false) {},
    )
}
