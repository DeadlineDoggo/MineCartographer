package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.editor.EditorState
import java.nio.file.Path
import kotlin.io.path.exists

/** Settings and summary for writing the active world's layers to a new world folder. */
@Composable
fun ExportDialog(state: EditorState) {
    if (!state.exportDialogVisible) return
    val stack = state.activeStack ?: return
    val base = stack.world
    DialogWindow(
        onCloseRequest = { state.exportDialogVisible = false },
        title = "Export merged world",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(560.dp, 440.dp)),
    ) {
        var name by remember { mutableStateOf("${base.name} merged") }
        var parent by remember { mutableStateOf((base as? AnvilWorld)?.folder?.parent ?: Path.of(System.getProperty("user.home"))) }
        val target = parent.resolve(folderName(name))
        val problems = remember(stack) { state.exportProblems() }
        val exists = target.exists()

        val visibleLayers = stack.layers.filterIsInstance<ChunkLayer>().filter { it.visible }
        val sources = (visibleLayers.flatMap { it.chunks.values } +
            stack.base.overrides.values.filterIsInstance<ChunkEdit.Place>().map { it.source })
            .groupingBy { it.world }.eachCount()
        val erased = stack.base.overrides.values.count { it == ChunkEdit.Delete }
        val older = sources.keys.filter { it != base && (it.dataVersion ?: 0) < (base.dataVersion ?: 0) }

        // What is worth knowing before exporting: opened from the info button, over the dialog's content.
        val notes = buildList {
            add("The original saves are only read, never changed.")
            if (older.isNotEmpty()) add("Chunks from ${older.joinToString { it.versionLabel }} keep their own version. Minecraft ${base.versionLabel} upgrades them the first time the merged world is opened.")
            addAll(minecartographer.core.anvil.WorldExporter.notes(stack))
            if (erased > 0) add("Erased chunks are left out, so Minecraft generates fresh terrain there when you visit.")
            add("Moved chunks get their lighting recalculated, and structure references (villages, strongholds…) are removed from them.")
            add("Only the overworld is merged; the Nether, the End, player data and maps come from \"${base.name}\".")
        }
        var infoOpen by remember { mutableStateOf(false) }
        val folder = folderName(name)

        Box(Modifier.fillMaxSize().background(EditorTheme.window)) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Txt("Export merged world", Modifier.height(28.dp), size = 16.sp, weight = FontWeight.SemiBold)

                    SectionHeader("WORLD NAME")
                    TextInput(name, { name = it }, Modifier.fillMaxWidth())

                    SectionHeader("SAVE IN")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(parent.toString(), Modifier.weight(1f), size = 12.sp, mono = true)
                        Spacer(Modifier.width(8.dp))
                        TextButton("Browse…", icon = Icons.Folder) {
                            FileDialogs.chooseFolder("Choose where to create the merged world", parent)?.let { parent = it }
                        }
                    }
                    // The folder that will be made, only when it is not simply the name typed above.
                    if (exists) Txt("A folder named \"$folder\" already exists there. Choose another name.", Modifier.padding(top = 4.dp), color = EditorTheme.warning, size = 12.sp, maxLines = 2)
                    else if (folder != name.trim()) Txt("Folder name: $folder", Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, mono = true)

                    SectionHeader("CONTENTS")
                    KeyValue("Base world", "${base.name} · ${base.versionLabel}")
                    KeyValue("Visible layers", "${visibleLayers.size + 1}")
                    for ((world, count) in sources.filterKeys { it != base }) {
                        KeyValue("From ${world.name} (${world.versionLabel})", "$count chunks")
                    }
                    if (erased > 0) KeyValue("Erased chunks", "$erased")

                    if (problems.isNotEmpty()) {
                        SectionHeader("CAN'T EXPORT")
                        problems.forEach { Txt("• $it", Modifier.padding(vertical = 2.dp), color = EditorTheme.deleted, size = 12.sp, maxLines = 4) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton("Cancel") { state.exportDialogVisible = false }
                    TextButton(
                        "Export", primary = true,
                        enabled = problems.isEmpty() && !exists && name.isNotBlank() && !state.busy,
                    ) { state.exportActive(target, name.trim()) }
                }
            }

            // Top right, at a fixed place: the notes for this export.
            Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)) {
                IconButton(Icons.Info, "Good to know before exporting", selected = infoOpen, size = 32.dp, iconSize = 18.dp) { infoOpen = !infoOpen }
            }

            if (infoOpen) {
                // Dim what is behind and close on a click outside the card.
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { infoOpen = false })
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 20.dp, end = 20.dp, bottom = 20.dp).fillMaxWidth()
                        .clip(shape).background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, shape),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt("GOOD TO KNOW", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
                        IconButton(Icons.Close, "Close", size = 26.dp, iconSize = 10.dp) { infoOpen = false }
                    }
                    HDivider()
                    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
                        notes.forEach { Txt("• $it", Modifier.padding(vertical = 3.dp), color = EditorTheme.textDim, size = 12.sp, maxLines = 6) }
                    }
                }
            }
        }
    }
}

/** World names may contain characters Windows doesn't allow in folder names. */
private fun folderName(name: String) = name.trim().replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_").trimEnd('.', ' ').ifEmpty { "Merged world" }
