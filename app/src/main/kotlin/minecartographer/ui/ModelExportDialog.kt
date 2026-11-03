package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minecartographer.core.anvil.AnvilWorld
import minecartographer.editor.EditorState
import minecartographer.editor.Notice
import minecartographer.model.ExportLayout
import minecartographer.model.ExportOptions
import minecartographer.model.ExportPlan
import minecartographer.model.ModelEditorState
import minecartographer.model.ModelExport
import java.nio.file.Path
import kotlin.io.path.name

/** Model > Export: what to write, where, and what happens to the textures; with a preview of the files. */
@Composable
fun ModelExportDialog(state: EditorState) {
    val model = state.workspaces.activeModel() ?: return
    if (!state.modelExportVisible) return
    DialogWindow(
        onCloseRequest = { state.modelExportVisible = false },
        title = "Export model",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(660.dp, 800.dp)),
    ) { Content(state, model) }
}

private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_.-]+"), "_").trim('_', '.').ifEmpty { "model" }

@Composable
private fun Content(state: EditorState, model: ModelEditorState) {
    val world = state.activeWorld
    val models = world?.let(state::modelsOf)
    val scope = rememberCoroutineScope()
    val home = Path.of(System.getProperty("user.home"))

    var namespace by remember { mutableStateOf(model.origin?.substringBefore(':')?.takeIf { it != "minecraft" } ?: "mymod") }
    var name by remember { mutableStateOf(sanitize(model.name)) }
    var layout by remember { mutableStateOf(ExportLayout.ResourcePack) }
    var zip by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(home) }
    var zipFile by remember { mutableStateOf(home.resolve("${sanitize(model.name)}.zip")) }
    var copyTextures by remember { mutableStateOf(models != null) }
    var ownNamespace by remember { mutableStateOf(false) }
    var skipVanilla by remember { mutableStateOf(true) }
    var packFormat by remember { mutableStateOf(ModelExport.packFormatFor(world?.dataVersion)) }
    var versionMenu by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }

    val options = ExportOptions(
        namespace.trim(), name.trim(), layout, zip = zip && layout == ExportLayout.ResourcePack, textures = copyTextures,
        ownNamespace = ownNamespace && copyTextures, skipVanilla = skipVanilla, packFormat = packFormat, description = description.trim(),
    )
    val doc = model.doc
    val target: Path = if (options.zip) zipFile else folder
    val problems = ModelExport.problems(options, doc).toMutableList().also { list ->
        val worldFolder = (world as? AnvilWorld)?.folder?.toAbsolutePath()?.normalize()
        if (worldFolder != null && target.toAbsolutePath().normalize().startsWith(worldFolder)) list += "Don't write into a world's folder: choose another place."
        if (options.zip && !zipFile.name.endsWith(".zip")) list += "The file must end with .zip."
    }
    // Reading the textures out of the packs takes a moment: the preview follows the options in the background.
    val plan by produceState<ExportPlan?>(null, options, doc, models) {
        value = if (ModelExport.problems(options, doc).isEmpty()) withContext(Dispatchers.IO) {
            runCatching { ModelExport.plan(doc, options) { path -> models?.assets?.read(path) } }.getOrNull()
        } else null
    }
    val existing = plan?.let { if (options.zip) (if (java.nio.file.Files.exists(zipFile)) listOf(zipFile.name) else emptyList()) else ModelExport.existing(it, folder) }.orEmpty()

    Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Txt("Export model", size = 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Txt("Writes \"${model.name}\" as a block model, and optionally the textures it uses. Packs and mods are only read, never changed.", color = EditorTheme.textDim, size = 12.sp, maxLines = 3)

            SectionHeader("NAME")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Namespace", Modifier.width(90.dp), color = EditorTheme.textDim, size = 12.sp)
                TextInput(namespace, { namespace = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Model name", Modifier.width(90.dp), color = EditorTheme.textDim, size = 12.sp)
                TextInput(name, { name = it }, Modifier.weight(1f))
            }
            Txt("Becomes ${namespace.trim()}:block/${name.trim()}", Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, mono = true)

            SectionHeader("FORMAT")
            Segmented(ExportLayout.entries, layout, { it.label }) { layout = it }
            Txt(
                if (layout == ExportLayout.ResourcePack) "pack.mcmeta and assets/<namespace>/...: the game can load the folder or zip as a resource pack."
                else "The model JSON with a textures folder beside it, to take the parts elsewhere.",
                Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 2,
            )

            SectionHeader("SAVE AS")
            if (layout == ExportLayout.ResourcePack) {
                Segmented(listOf(false, true), zip, { if (it) "Zip file" else "Folder" }) { zip = it }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(target.toString(), Modifier.weight(1f), size = 12.sp, mono = true, maxLines = 2)
                Spacer(Modifier.width(8.dp))
                TextButton("Browse…", icon = Icons.Folder) {
                    if (options.zip) FileDialogs.chooseFileToSave("Export the resource pack as", "zip", "Zip archives", zipFile.name)?.let { zipFile = it }
                    else FileDialogs.chooseFolder("Choose where to write the model", folder)?.let { folder = it }
                }
            }
            if (existing.isNotEmpty()) {
                Txt(
                    if (options.zip) "This file exists and will be replaced." else "${existing.size} existing files in that folder will be replaced.",
                    Modifier.padding(top = 4.dp), color = EditorTheme.warning, size = 12.sp,
                )
            }

            SectionHeader("TEXTURES")
            val used = ModelExport.textureIds(doc)
            if (models == null) {
                Txt("Open a world with its textures loaded to copy the textures the model uses. The model refers to them by id either way.", color = EditorTheme.warning, size = 12.sp, maxLines = 3)
            }
            Checkbox("Copy the ${used.size} textures the model uses", copyTextures && models != null) { if (models != null) copyTextures = it }
            Checkbox("Move them into the namespace (${namespace.trim()}:block/...)", ownNamespace && copyTextures) { if (copyTextures) ownNamespace = it }
            Checkbox("Skip vanilla textures (minecraft:...)", skipVanilla) { skipVanilla = it }
            Txt(
                if (ownNamespace && copyTextures) "The model's texture ids are rewritten to the new ones, so it can't clash with the mods the textures come from."
                else "The textures keep their own namespaces, so they land where their mods expect them.",
                Modifier.padding(top = 2.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 2,
            )

            if (layout == ExportLayout.ResourcePack) {
                SectionHeader("PACK")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("For Minecraft", Modifier.width(90.dp), color = EditorTheme.textDim, size = 12.sp)
                    Box {
                        val label = ModelExport.PACK_VERSIONS.firstOrNull { it.second == packFormat }?.first ?: "pack format $packFormat"
                        TextButton("$label  ▾") { versionMenu = true }
                        if (versionMenu) {
                            ContextMenu(
                                IntOffset(0, with(LocalDensity.current) { 34.dp.roundToPx() }),
                                ModelExport.PACK_VERSIONS.map { (version, format) -> MenuItem(version, checked = format == packFormat) { packFormat = format } },
                            ) { versionMenu = false }
                        }
                    }
                }
                Txt(
                    "The game only loads a pack that says which version it is for (pack_format $packFormat). It starts from the version of your world; change it only to make the pack for another one.",
                    Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Description", Modifier.width(90.dp), color = EditorTheme.textDim, size = 12.sp)
                    Box(Modifier.weight(1f)) {
                        TextInput(description, { description = it }, Modifier.fillMaxWidth())
                        if (description.isEmpty()) Txt("Shown in the game's pack list (optional)", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = EditorTheme.textFaint, size = 12.sp)
                    }
                }
            }

            SectionHeader("WILL WRITE")
            val p = plan
            if (p == null) {
                Txt(if (problems.isEmpty()) "Reading the textures…" else "–", color = EditorTheme.textFaint, size = 12.sp)
            } else {
                KeyValue("Files", "${p.files.size}")
                KeyValue("Textures", "${p.textureCount}")
                KeyValue("Size", humanSize(p.size))
                Spacer(Modifier.height(4.dp))
                for (file in p.files.take(14)) Txt(file.path, color = EditorTheme.textDim, size = 11.sp, mono = true)
                if (p.files.size > 14) Txt("… and ${p.files.size - 14} more", color = EditorTheme.textFaint, size = 11.sp)
                if (p.missing.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Txt("Not found in the packs, so not copied: ${p.missing.joinToString()}", color = EditorTheme.warning, size = 12.sp, maxLines = 4)
                }
            }
            if (problems.isNotEmpty()) {
                SectionHeader("CAN'T EXPORT")
                problems.forEach { Txt("• $it", Modifier.padding(vertical = 2.dp), color = EditorTheme.deleted, size = 12.sp, maxLines = 3) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton("Cancel") { state.modelExportVisible = false }
            TextButton("Export", primary = true, enabled = problems.isEmpty() && plan != null && !writing) {
                val p = plan ?: return@TextButton
                writing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { if (options.zip) ModelExport.writeZip(p, zipFile) else ModelExport.writeFolder(p, folder) }
                    }
                    writing = false
                    result.onSuccess { count ->
                        state.modelExportVisible = false
                        state.notice = Notice("Exported $count files to $target")
                    }.onFailure { state.notice = Notice("Export failed: ${it.message}", warning = true) }
                }
            }
        }
    }
}

private fun humanSize(bytes: Long) = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}
