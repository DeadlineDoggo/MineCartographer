package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import minecartographer.core.color.HexColor
import minecartographer.core.markers.MarkerReader
import minecartographer.core.markers.WaypointData
import minecartographer.core.markers.ShownWaypoint
import minecartographer.core.markers.WaypointOrigin
import minecartographer.editor.EditorState

private val MIN_WIDTH = 320.dp
private val MAX_WIDTH = 900.dp

/** What a row is called inside the panel: it stays the same while the waypoint is edited. */
private fun rowId(s: ShownWaypoint) = s.ownId?.let { "own:$it" } ?: s.marker.key.toString()
private fun bannerId(m: Marker) = "banner:${m.x},${m.y},${m.z},${m.name}"

/**
 * The Waypoints panel, laid over the map next to the left dock like Mods & textures: the waypoints of the active world
 * (the minimap mods' with the project's changes applied, and the ones made here) and its map banners. A row moves the map
 * to the spot and opens to show, copy and change its details; a new one is made from the button on top. Folds into a tab on
 * the map's edge; its width can be dragged.
 */
@Composable
fun BoxScope.WaypointsDrawer(state: EditorState) {
    val world = state.activeWorld ?: return
    val waypoints = state.shownWaypoints(world)
    val banners = state.markers[world.id].orEmpty().filter { it.kind == MarkerKind.Banner }
    if (!state.waypointsVisible) {
        // The Mods & textures and Analyze panels cover the map's edge while they are open, tab and all.
        if (state.modsPanelVisible || state.analyzeVisible) return
        // Under the tabs of Mods & textures and Analyze, when those are shown.
        val above = (if (world is minecartographer.core.anvil.AnvilWorld && state.hasMods(world)) 1 else 0) +
            (if (state.analyzeRequest != null) 1 else 0)
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
        WaypointsPanel(state, world, waypoints, banners, state.deletedWaypoints(world), Modifier.width(state.waypointsWidth).fillMaxHeight())
        Splitter { state.waypointsWidth = (state.waypointsWidth + it).coerceIn(MIN_WIDTH, MAX_WIDTH) }
    }
}

@Composable
private fun WaypointsPanel(
    state: EditorState, world: WorldSource, waypoints: List<ShownWaypoint>, banners: List<Marker>, deleted: List<Marker>, modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    // The rows whose details are open, by row id.
    val open = remember { mutableStateMapOf<String, Unit>() }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    // Where the panel ends: the spot a row moves to is centered in the map that stays visible.
    val inset = with(density) { (state.waypointsWidth + 5.dp).toPx() }
    fun matches(m: Marker) = filter.isBlank() || m.name.contains(filter.trim(), ignoreCase = true) || m.source.contains(filter.trim(), ignoreCase = true)
    val shownWaypoints = waypoints.filter { matches(it.marker) }
    val shownBanners = banners.filter(::matches)
    val shownDeleted = deleted.filter(::matches)

    // A waypoint was just made: show it, open it and scroll to it.
    val focus = state.focusWaypoint
    LaunchedEffect(focus, waypoints.size) {
        if (focus == null) return@LaunchedEffect
        filter = ""
        val index = waypoints.indexOfFirst { rowId(it) == focus }
        if (index >= 0) {
            open[focus] = Unit
            list.animateScrollToItem(1 + index)
            state.focusWaypoint = null
        }
    }
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
            ScrollList(Modifier.weight(1f).fillMaxWidth(), list) {
                item { SectionTitle("Waypoints (${shownWaypoints.size})") }
                if (shownWaypoints.isEmpty()) item {
                    Txt(if (waypoints.isEmpty()) "None yet. New waypoint makes one." else "No waypoint matches.", Modifier.padding(8.dp), color = EditorTheme.textFaint, size = 12.sp)
                }
                items(shownWaypoints.size, key = { rowId(shownWaypoints[it]) }) { i ->
                    val w = shownWaypoints[i]
                    val id = rowId(w)
                    WaypointRow(state, world, w.marker, w, inset, id in open) { if (id in open) open.remove(id) else open[id] = Unit }
                }
                if (banners.isNotEmpty()) {
                    item { SectionTitle("Map banners (${shownBanners.size})") }
                    items(shownBanners.size, key = { bannerId(shownBanners[it]) }) { i ->
                        val m = shownBanners[i]
                        val id = bannerId(m)
                        WaypointRow(state, world, m, null, inset, id in open) { if (id in open) open.remove(id) else open[id] = Unit }
                    }
                }
                if (deleted.isNotEmpty()) {
                    item { SectionTitle("Deleted (${shownDeleted.size})") }
                    items(shownDeleted.size, key = { "deleted:" + shownDeleted[it].key }) { i -> DeletedRow(state, world, shownDeleted[i]) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Below the list, always in reach: the next click on the map places the waypoint.
            WithTooltip("Then click on the map where it should go (Esc cancels)") {
                TextButton(
                    if (state.placingWaypoint) "Click on the map…" else "New waypoint", Modifier.fillMaxWidth(),
                    icon = Icons.Plus, primary = !state.placingWaypoint,
                ) { state.placingWaypoint = !state.placingWaypoint }
            }
            Spacer(Modifier.height(6.dp))
            WithTooltip("Only the waypoints, for Xaero's Minimap and JourneyMap, into a folder you choose") {
                TextButton("Export waypoints…", Modifier.fillMaxWidth(), icon = Icons.Folder, enabled = waypoints.isNotEmpty()) {
                    val start = state.lastExportParent ?: java.nio.file.Path.of(System.getProperty("user.home"))
                    FileDialogs.chooseFolder("Choose where to create the waypoints folder", start)?.let { state.exportWaypoints(world, it) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Txt(text, Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)

/**
 * A waypoint or banner row ([shown] is null for a banner; a waypoint changed here says so). A click moves the map to it;
 * the chevron at the end opens its details, which can be copied and, for a waypoint, changed. Hovering outlines its pin.
 */
@Composable
private fun WaypointRow(state: EditorState, world: WorldSource, m: Marker, shown: ShownWaypoint?, inset: Float, open: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The pin on the map is outlined while the pointer is on the row.
    LaunchedEffect(hovered, m) { if (hovered) state.hoverMarker = m }
    DisposableEffect(m) { onDispose { if (state.hoverMarker == m) state.hoverMarker = null } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (hovered || open) EditorTheme.hover else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().hoverable(interaction).clickable(interaction, indication = null) { state.centerOn(m.x, m.y, m.z, inset) }
                .padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or m.rgb)).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Txt(m.name)
                val origin = shown?.origin
                val note = if (origin == null || origin == WaypointOrigin.Mod) m.source else "${m.source} · ${origin.label}"
                Txt(note, color = EditorTheme.textFaint, size = 11.sp)
            }
            Txt("${m.x} ${m.y ?: "~"} ${m.z}", color = EditorTheme.textDim, size = 12.sp, mono = true)
            Spacer(Modifier.width(4.dp))
            IconButton(if (open) Icons.ChevronUp else Icons.ChevronDown, if (open) "Hide the details" else "Show the details", size = 26.dp, iconSize = 13.dp, onClick = onToggle)
        }
        if (open) {
            if (shown != null) WaypointEditor(state, world, shown) else BannerDetails(state, m)
        }
    }
}

/** A mod's waypoint that was deleted in this project: shown dimmed with a button to bring it back. */
@Composable
private fun DeletedRow(state: EditorState, world: WorldSource, m: Marker) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or m.rgb).copy(alpha = 0.4f)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Txt(m.name, color = EditorTheme.textFaint)
            Txt("${m.source} · ${m.x} ${m.y ?: "~"} ${m.z}", color = EditorTheme.textFaint, size = 11.sp)
        }
        m.key?.let { key -> TextButton("Restore") { state.restoreWaypoint(world, key) } }
    }
}

private val LABEL_WIDTH = 96.dp

/** A banner on a map: read only, with what can be pasted elsewhere. */
@Composable
private fun BannerDetails(state: EditorState, m: Marker) {
    val position = "${m.x} ${m.y ?: "~"} ${m.z}"
    Column(Modifier.fillMaxWidth().padding(start = 26.dp, end = 6.dp, bottom = 8.dp)) {
        DetailLine("Position", position, copy = position, state = state, mono = true)
        DetailLine("Teleport", "/tp @s $position", copy = "/tp @s $position", state = state, mono = true)
        DetailLine("Name", m.name, copy = m.name, state = state)
        DetailLine("Color", HexColor.format(m.rgb), copy = HexColor.format(m.rgb), state = state, mono = true)
        DetailLine("Source", m.source, state = state)
    }
}

/** What can be changed about a waypoint (name, position, color), and what is known about where it comes from. */
@Composable
private fun WaypointEditor(state: EditorState, world: WorldSource, shown: ShownWaypoint) {
    val m = shown.marker
    var picking by remember { mutableStateOf(false) }
    fun change(f: (WaypointData) -> WaypointData) = state.editWaypoint(world, shown, f)
    val position = "${m.x} ${m.y ?: "~"} ${m.z}"
    val teleport = "/tp @s $position"
    val hex = HexColor.format(m.rgb)
    Column(Modifier.fillMaxWidth().padding(start = 26.dp, end = 6.dp, bottom = 8.dp)) {
        // Name: typing is kept as typed (also empty), the waypoint only takes names that are not blank.
        var name by remember(m.name) { mutableStateOf(m.name) }
        FieldLine("Name") {
            TextInput(name, { typed ->
                name = typed
                if (typed.isNotBlank()) change { it.copy(name = typed) }
            }, Modifier.weight(1f))
            CopyButton(state, m.name)
        }
        FieldLine("Position") {
            NumberField("X", m.x, { v -> if (v != null) change { it.copy(x = v) } }, Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            NumberField("Y", m.y, { v -> change { it.copy(y = v) } }, Modifier.weight(1f), optional = true)
            Spacer(Modifier.width(4.dp))
            NumberField("Z", m.z, { v -> if (v != null) change { it.copy(z = v) } }, Modifier.weight(1f))
            CopyButton(state, position)
        }
        FieldLine("Color") {
            WithTooltip("Change the color") {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF000000.toInt() or m.rgb))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).clickable { picking = true },
                )
            }
            Spacer(Modifier.width(8.dp))
            Txt(hex, Modifier.weight(1f), size = 12.sp, mono = true)
            CopyButton(state, hex)
        }
        DetailLine("Teleport", teleport, copy = teleport, state = state, mono = true)
        DetailLine("Source", m.source, state = state)
        m.key?.takeIf { it.file.isNotEmpty() }?.let { DetailLine("File", it.file, state = state) }
        m.modifiedMillis?.let {
            val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
            DetailLine("File changed", at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), state = state)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            if (shown.origin == WaypointOrigin.Edited) shown.marker.key?.let { key ->
                WithTooltip("Back to what ${m.source} has") { TextButton("Restore original") { state.restoreWaypoint(world, key) } }
            }
            WithTooltip(if (shown.ownId != null) "Delete this waypoint" else "Hide it in this project; the mod's file is not changed") {
                TextButton("Delete", icon = Icons.Delete) { state.deleteWaypoint(world, shown) }
            }
        }
    }
    if (picking) {
        // Xaero's Minimap only knows the 16 colors of Minecraft's chat; JourneyMap and our own waypoints take any color.
        ColorPickerWindow(
            state, "Color of ${m.name}", m.rgb, { c -> change { it.copy(rgb = c) } }, { picking = false },
            allowFull = m.source != MarkerReader.XAERO,
        )
    }
}

@Composable
private fun FieldLine(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(LABEL_WIDTH), color = EditorTheme.textDim, size = 12.sp)
        content()
    }
}

/** A whole number you can type; it is applied as soon as what is typed is a number ([optional] ones may also be empty). */
@Composable
private fun NumberField(label: String, value: Int?, onValue: (Int?) -> Unit, modifier: Modifier, optional: Boolean = false) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.padding(end = 4.dp), color = EditorTheme.textFaint, size = 11.sp)
        TextInput(text, { typed ->
            val clean = typed.filter { it.isDigit() || it == '-' }.take(9)
            text = clean
            if (clean.isEmpty()) { if (optional) onValue(null) } else clean.toIntOrNull()?.let(onValue)
        }, Modifier.weight(1f))
    }
}

@Composable
private fun DetailLine(label: String, value: String, state: EditorState, copy: String? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(LABEL_WIDTH), color = EditorTheme.textDim, size = 12.sp)
        Txt(value, Modifier.weight(1f), size = 12.sp, mono = mono)
        if (copy != null) CopyButton(state, copy)
    }
}

@Composable
private fun CopyButton(state: EditorState, text: String) =
    IconButton(Icons.Copy, "Copy", size = 22.dp, iconSize = 12.dp) { state.copyText(text) }
