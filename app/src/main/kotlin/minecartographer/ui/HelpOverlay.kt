package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.editor.EditorState
import minecartographer.editor.Tool

private class Entry(val keys: String, val what: String)

private class Group(val title: String, val entries: List<Entry>)

private val TIPS = listOf(
    "Your save is never changed. Edits live in layers; export writes a new world.",
    "Right or middle drag moves the view with every tool, so you never have to switch to Move view.",
    "Shift adds to a selection, Ctrl subtracts from it while you brush or drag a rectangle.",
    "Paste always makes a new layer: move it, hide it or delete it without touching the original.",
    "Selection tab: turn pasted chunks, raise or lower them, and cut them at a height to keep the original ground below.",
    "Textured mode draws real block models. Modded worlds before 1.8 are guessed; fix blocks in View → Mods & textures.",
    "Rescan (View tab) rebuilds every texture cache if results look stale.",
    "Save project keeps your layers, texture picks and mod folders; export is the finished world.",
)

private fun groups(): List<Group> = listOf(
    Group("MOVING AROUND", listOf(
        Entry("W A S D / arrows", "Move the view"),
        Entry("Right / middle drag", "Move the view"),
        Entry("Mouse wheel", "Zoom"),
        Entry("+ / -", "Zoom in / out"),
        Entry(", / .", "Rotate left / right"),
        Entry("F", "Fit the world"),
        Entry("1 / 2 / 3", "Map / Blocks / Textured mode"),
        Entry("V", "2D from above / 3D"),
    )),
    Group("TOOLS", Tool.entries.map { Entry(it.keyLabel, "${it.label}: ${it.hint}") }),
    Group("SELECTING", listOf(
        Entry("Shift + click / drag", "Add to the selection"),
        Entry("Ctrl + click / drag", "Subtract from the selection"),
        Entry("Ctrl+A / Ctrl+Shift+A", "Select all / none"),
        Entry("Ctrl+I", "Invert the selection"),
        Entry("Esc", "Clear the selection or cancel a paste"),
    )),
    Group("EDITING", listOf(
        Entry("Ctrl+Z / Ctrl+Y", "Undo / redo"),
        Entry("Ctrl+C / Ctrl+X", "Copy / cut the selection"),
        Entry("Ctrl+Shift+C", "Copy everything visible"),
        Entry("Ctrl+V", "Paste as a new layer, click to place"),
        Entry("Ctrl+Shift+V", "Paste in place"),
        Entry("Shift + click", "Keep placing the paste again"),
        Entry("Delete", "Clear the selection from the active layer"),
    )),
    Group("LAYERS", listOf(
        Entry("Ctrl+Shift+N", "New layer"),
        Entry("Ctrl+Shift+D", "Duplicate layer"),
        Entry("PgUp / PgDn", "Raise / lower the layer"),
        Entry("Ctrl+E", "Merge down"),
        Entry("Ctrl+Shift+E", "Flatten the world"),
    )),
    Group("FILES", listOf(
        Entry("Ctrl+O", "Open a world folder"),
        Entry("Ctrl+N", "New project"),
        Entry("Ctrl+Shift+O", "Open a project"),
        Entry("Ctrl+S", "Save the project"),
        Entry("Ctrl+Alt+S", "Save the project as…"),
        Entry("Ctrl+Shift+S", "Export the merged world"),
        Entry("F1", "This help"),
    )),
)

/** Shortcuts and quick tips in a card at the top of the map; folds with ×, Esc or F1. */
@Composable
fun BoxScope.HelpOverlay(state: EditorState) {
    if (!state.helpVisible) return
    val shape = RoundedCornerShape(8.dp)
    val scroll = rememberScrollState()
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp).overMap(state)
            .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 560.dp)
            .clip(shape).background(EditorTheme.panel).border(1.dp, EditorTheme.border, shape)
            // Clicks and drags on the card must not reach the map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("SHORTCUTS & QUICK TIPS", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Close (Esc)", size = 24.dp, iconSize = 10.dp) { state.helpVisible = false }
        }
        HDivider()
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(scroll).padding(start = 14.dp, end = 18.dp, bottom = 12.dp)) {
                SectionHeader("QUICK TIPS")
                for (tip in TIPS) Row(Modifier.padding(vertical = 3.dp)) {
                    Txt("•", Modifier.width(14.dp), color = EditorTheme.accent, size = 12.sp)
                    Txt(tip, size = 12.sp, maxLines = 4)
                }
                for (group in groups()) {
                    SectionHeader(group.title)
                    for (e in group.entries) Row(Modifier.padding(vertical = 3.dp)) {
                        Txt(e.keys, Modifier.width(150.dp), color = EditorTheme.text, size = 12.sp, mono = true)
                        Txt(e.what, Modifier.weight(1f), color = EditorTheme.textDim, size = 12.sp, maxLines = 4)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
