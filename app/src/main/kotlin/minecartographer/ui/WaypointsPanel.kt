package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.WorldSource
import minecartographer.core.markers.Marker
import minecartographer.core.markers.MarkerKind
import minecartographer.core.markers.WaypointOrigin
import minecartographer.editor.EditorState

private val MIN_WIDTH = 320.dp
private val MAX_WIDTH = 900.dp

/**
 * The Waypoints panel, laid over the map next to the left dock like Mods & textures: the waypoints of the active world
 * (minimap mods' and, later, your own, with the project's changes applied) and its map banners. A row moves the map to
 * the spot. Folds into a tab on the map's edge; its width can be dragged.
 */
@Composable
fun BoxScope.WaypointsDrawer(state: EditorState) {
    val world = state.activeWorld ?: return
    val waypoints = state.shownWaypoints(world)
    val banners = state.markers[world.id].orEmpty().filter { it.kind == MarkerKind.Banner }
    if (waypoints.isEmpty() && banners.isEmpty()) return
    if (!state.waypointsVisible) {
        // Under the tabs of Mods & textures and Analyze, when those are shown.
        val above = (if (world is minecartographer.core.anvil.AnvilWorld && state.hasMods(world) && !state.analyzeVisible) 1 else 0) +
            (if (state.analyzeRequest != null && !state.modsPanelVisible && !state.analyzeVisible) 1 else 0)
        val shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
        Box(
            Modifier.align(Alignment.TopStart).padding(top = 52.dp + 44.dp * above).overMap(state).clip(shape)
                .background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, shape)
                .clickable { state.waypointsVisible = true }.padding(horizontal = 10.dp, vertical = 8.dp),
        ) { Txt("▸ Waypoints (${waypoints.size})", size = 12.sp) }
        return
    }
    Row(
        Modifier.align(Alignment.TopStart).fillMaxHeight().overMap(state)
            // Clicks and drags on the panel must not reach the map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        WaypointsPanel(state, world, waypoints.map { it.marker to it.origin }, banners, Modifier.width(state.waypointsWidth).fillMaxHeight())
        Splitter { state.waypointsWidth = (state.waypointsWidth + it).coerceIn(MIN_WIDTH, MAX_WIDTH) }
    }
}

@Composable
private fun WaypointsPanel(
    state: EditorState, world: WorldSource, waypoints: List<Pair<Marker, WaypointOrigin>>, banners: List<Marker>, modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    val density = LocalDensity.current
    // Where the panel ends: the spot a row moves to is centered in the map that stays visible.
    val inset = with(density) { (state.waypointsWidth + 5.dp).toPx() }
    fun matches(m: Marker) = filter.isBlank() || m.name.contains(filter.trim(), ignoreCase = true) || m.source.contains(filter.trim(), ignoreCase = true)
    val shownWaypoints = waypoints.filter { matches(it.first) }
    val shownBanners = banners.filter(::matches)
    Column(modifier.background(EditorTheme.panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("WAYPOINTS · ${world.name.uppercase()}", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Fold the panel", size = 24.dp, iconSize = 10.dp) { state.waypointsVisible = false }
        }
        HDivider()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Filter", color = EditorTheme.textDim, size = 12.sp)
                Spacer(Modifier.width(8.dp))
                TextInput(filter, { filter = it }, Modifier.weight(1f))
            }
            Txt(
                "${waypoints.size} waypoints · ${banners.size} map banners" + if (filter.isBlank()) "" else " · ${shownWaypoints.size + shownBanners.size} match",
                Modifier.padding(top = 6.dp, bottom = 8.dp), color = EditorTheme.textFaint, size = 11.sp,
            )
            ScrollList(Modifier.weight(1f).fillMaxWidth()) {
                item { SectionTitle("Waypoints (${shownWaypoints.size})") }
                if (shownWaypoints.isEmpty()) item { Txt(if (waypoints.isEmpty()) "None found." else "No waypoint matches.", Modifier.padding(8.dp), color = EditorTheme.textFaint, size = 12.sp) }
                items(shownWaypoints.size) { i -> WaypointRow(state, shownWaypoints[i].first, shownWaypoints[i].second, inset) }
                if (banners.isNotEmpty()) {
                    item { SectionTitle("Map banners (${shownBanners.size})") }
                    items(shownBanners.size) { i -> WaypointRow(state, shownBanners[i], null, inset) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Txt(text, Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)

/** [origin] is null for a banner; a waypoint changed in the app says so. */
@Composable
private fun WaypointRow(state: EditorState, m: Marker, origin: WaypointOrigin?, inset: Float) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { state.centerOn(m.x, m.y, m.z, inset) }.padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or m.rgb)).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Txt(m.name)
            val note = if (origin == null || origin == WaypointOrigin.Mod) m.source else "${m.source} · ${origin.label}"
            Txt(note, color = EditorTheme.textFaint, size = 11.sp)
        }
        Txt("${m.x} ${m.y ?: "~"} ${m.z}", color = EditorTheme.textDim, size = 12.sp, mono = true)
    }
}
