package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.WorldSource
import minecartographer.core.markers.MarkerKind
import minecartographer.core.markers.OwnWaypoint
import minecartographer.editor.EditorState

/**
 * In the status bar: how many waypoint issues wait to be resolved (or a check mark when none); a click opens or closes
 * the Issues panel.
 */
@Composable
fun IssuesStatus(state: EditorState) {
    val count = state.waypointIssues().size
    val color = if (count > 0) EditorTheme.warning else EditorTheme.textDim
    WithTooltip(if (count > 0) "Show what has to be resolved before exporting" else "Nothing to resolve") {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp))
                .clickable { if (state.issuesVisible) state.issuesVisible = false else state.showIssues() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (count > 0) Icons.Warning else Icons.Check, Modifier.size(13.dp), color)
            Spacer(Modifier.width(5.dp))
            Txt(if (count > 0) "Unresolved Issues: $count" else "No unresolved issues", color = color, size = 12.sp,
                weight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/**
 * The Issues panel: waypoints whose change lost its mod waypoint (the mod's file no longer has it). Until resolved each
 * one is kept as the project's own waypoint and the export waits. Resolve by going to it and then keeping it, giving the
 * change to another of the mod's waypoints, or deleting it.
 */
@Composable
fun IssuesPanel(state: EditorState) {
    val issues = state.waypointIssues()
    if (issues.isEmpty()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Check, Modifier.size(14.dp), EditorTheme.textDim)
            Spacer(Modifier.width(6.dp))
            Txt("No unresolved issues", color = EditorTheme.textDim, size = 12.sp)
        }
        return
    }
    val manyWorlds = issues.map { it.first }.distinct().size > 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(
            "These waypoints were changed here, but the minimap mod no longer has them. Each is kept as your own waypoint " +
                "until you decide; exporting waits until then.",
            color = EditorTheme.textDim, size = 11.sp, maxLines = 5,
        )
        for ((world, own) in issues) IssueCard(state, world, own, manyWorlds)
    }
}

@Composable
private fun IssueCard(state: EditorState, world: WorldSource, own: OwnWaypoint, showWorld: Boolean) {
    val d = own.data
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or d.rgb)).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Txt(d.name, weight = FontWeight.SemiBold)
                Txt((if (showWorld) "${world.name} · " else "") + "${d.x} ${d.y ?: "~"} ${d.z}", color = EditorTheme.textFaint, size = 11.sp, mono = !showWorld)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("Go to", Modifier.weight(1f)) {
                if (state.activeWorld != world) state.openWorld(world)
                state.centerOn(d.x, d.y, d.z)
            }
            AssignButton(state, world, own, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // A weight only works on the row's direct children, so no tooltip wrapper here.
            TextButton("Keep as my own", Modifier.weight(1f), primary = true) { state.keepIssue(world, own.id) }
            TextButton("Delete", Modifier.weight(1f), icon = Icons.Delete) { state.deleteIssue(world, own.id) }
        }
    }
}

/** "Assign to…": a list of the mod's waypoints in this world, nearest first, to give the change to instead. */
@Composable
private fun AssignButton(state: EditorState, world: WorldSource, own: OwnWaypoint, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val below = with(LocalDensity.current) { 34.dp.roundToPx() }
    Box(modifier) {
        WithTooltip("Give this change to another waypoint of the minimap mod") {
            TextButton("Assign to…", Modifier.fillMaxWidth()) { open = true }
        }
        if (open) {
            val d = own.data
            val candidates = state.markers[world.id].orEmpty()
                .filter { it.kind == MarkerKind.Waypoint && it.key != null }
                .sortedBy { (it.x - d.x).toLong() * (it.x - d.x) + (it.z - d.z).toLong() * (it.z - d.z) }
                .take(40)
            val entries: List<MenuEntry> = if (candidates.isEmpty()) listOf(MenuItem("No minimap waypoints in this world", enabled = false) {})
            else candidates.map { m ->
                MenuItem("${m.name} · ${m.x} ${m.y ?: "~"} ${m.z}", m.source) { state.assignIssue(world, own.id, m.key!!) }
            }
            ContextMenu(IntOffset(0, below), entries) { open = false }
        }
    }
}
