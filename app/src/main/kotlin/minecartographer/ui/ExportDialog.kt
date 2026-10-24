package minecartographer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        state = rememberDialogState(size = DpSize(600.dp, 620.dp)),
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

        Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Txt("Export merged world", size = 16.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Txt("Creates a new world folder with everything visible in the layers of \"${base.name}\". The original saves are only read, never changed.",
                    color = EditorTheme.textDim, size = 12.sp, maxLines = 3)

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
                Spacer(Modifier.height(4.dp))
                Txt("→ $target", color = if (exists) EditorTheme.warning else EditorTheme.textFaint, size = 11.sp, mono = true, maxLines = 2)
                if (exists) Txt("A folder with this name already exists. Choose another name.", color = EditorTheme.warning, size = 12.sp)

                SectionHeader("CONTENTS")
                KeyValue("Base world", "${base.name} · ${base.versionLabel}")
                KeyValue("Visible layers", "${visibleLayers.size + 1}")
                for ((world, count) in sources.filterKeys { it != base }) {
                    KeyValue("From ${world.name} (${world.versionLabel})", "$count chunks")
                }
                if (erased > 0) KeyValue("Erased chunks", "$erased")

                SectionHeader("GOOD TO KNOW")
                val notes = buildList {
                    if (older.isNotEmpty()) add("Chunks from ${older.joinToString { it.versionLabel }} keep their own version. Minecraft ${base.versionLabel} upgrades them the first time the merged world is opened.")
                    addAll(minecartographer.core.anvil.WorldExporter.notes(stack))
                    if (erased > 0) add("Erased chunks are left out, so Minecraft generates fresh terrain there when you visit.")
                    add("Moved chunks get their lighting recalculated, and structure references (villages, strongholds…) are removed from them.")
                    add("Only the overworld is merged; the Nether, the End, player data and maps come from \"${base.name}\".")
                }
                notes.forEach { Txt("• $it", Modifier.padding(vertical = 2.dp), color = EditorTheme.textDim, size = 12.sp, maxLines = 4) }

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
    }
}

/** World names may contain characters Windows doesn't allow in folder names. */
private fun folderName(name: String) = name.trim().replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_").trimEnd('.', ' ').ifEmpty { "Merged world" }
