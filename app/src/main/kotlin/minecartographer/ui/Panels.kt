package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.ForgeData
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkLayer
import minecartographer.editor.BRUSH_SIZES
import minecartographer.editor.EditorState
import minecartographer.editor.SelectMode
import minecartographer.editor.Tool
import minecartographer.textures.TextureFiles
import minecartographer.textures.ModLocator
import minecartographer.viewport.RenderMode

private val panelPadding = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun ToolOptionsPanel(state: EditorState) {
    val tool = state.tool
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(panelPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(toolIcon(tool), Modifier.size(18.dp), EditorTheme.accent)
            Spacer(Modifier.width(8.dp))
            Txt(tool.label, weight = FontWeight.SemiBold, size = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Txt(tool.hint, color = EditorTheme.textDim, size = 12.sp, maxLines = 5)

        if (tool == Tool.BrushSelect || tool == Tool.RectSelect) {
            SectionHeader("MODE")
            Segmented(SelectMode.entries, state.selectMode, { it.label }) { state.selectMode = it }
            Spacer(Modifier.height(4.dp))
            Txt("Hold Shift to add, Ctrl to subtract.", color = EditorTheme.textFaint, size = 11.sp)
        }
        if (tool == Tool.BrushSelect || tool == Tool.Eraser) {
            SectionHeader("BRUSH SIZE (CHUNKS)")
            Segmented(BRUSH_SIZES, state.brushSize, { "$it×$it" }) { state.brushSize = it }
        }
        if (tool == Tool.Eraser || tool == Tool.MoveLayer) {
            SectionHeader("ACTS ON")
            val layer = state.activeStack?.active
            if (layer != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(layer.color)))
                    Spacer(Modifier.width(8.dp))
                    Txt(layer.name + if (layer.locked) " (locked)" else "", color = if (layer.locked) EditorTheme.warning else EditorTheme.text)
                }
                if (tool == Tool.MoveLayer && layer is BaseLayer) {
                    Spacer(Modifier.height(4.dp))
                    Txt("The original world can't be moved. Pick a pasted layer in the Layers dock.",
                        color = EditorTheme.warning, size = 11.sp, maxLines = 3)
                }
            }
        }
    }
}

fun toolIcon(tool: Tool): EditorIcon = when (tool) {
    Tool.Pan -> Icons.Hand
    Tool.BrushSelect -> Icons.BrushSelect
    Tool.RectSelect -> Icons.RectSelect
    Tool.MoveLayer -> Icons.MoveLayer
    Tool.Eraser -> Icons.Eraser
    Tool.PickLayer -> Icons.Picker
}

@Composable
fun WorldsPanel(state: EditorState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(panelPadding)) {
        TextButton("Open world folder…", Modifier.fillMaxWidth(), icon = Icons.Folder, primary = true, enabled = !state.busy) {
            FileDialogs.chooseWorldFolder()?.let(state::openWorldFolder)
        }
        Spacer(Modifier.height(4.dp))
        Txt("Ctrl+O · Java Edition 1.2 and newer", color = EditorTheme.textFaint, size = 11.sp)

        SectionHeader("OPEN")
        for (world in state.worlds) {
            WorldCard(state, world, active = world == state.activeWorld, onClose = { state.closeWorld(world) }) { state.openWorld(world) }
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("SAVES ON THIS COMPUTER", Modifier.weight(1f))
            IconButton(Icons.RotateRight, "Search again", size = 24.dp, iconSize = 12.dp) { state.refreshFoundWorlds() }
        }
        val found = state.foundWorlds.filterNot { state.isOpen(it.folder) }
        if (found.isEmpty()) {
            Txt("No other saves found in the usual launcher folders. Saves next to worlds you open show up here too.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 4)
        }
        for ((folder, infos) in found.groupBy { it.folder.parent }) {
            Txt(shortPath(folder), Modifier.padding(top = 6.dp, bottom = 2.dp), color = EditorTheme.textFaint, size = 11.sp, mono = true)
            for (info in infos.sortedBy { it.name.lowercase() }) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !state.busy) { state.openWorldFolder(info.folder) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(info.name, Modifier.weight(1f), size = 12.sp)
                    info.forge?.let { ForgeChip(it) }
                    VersionChip(info.versionName)
                }
            }
        }
    }
}

/** Last few components of a path, enough to recognise the launcher instance. */
private fun shortPath(path: java.nio.file.Path?): String {
    if (path == null) return ""
    val parts = path.toList().map { it.toString() }
    return if (parts.size <= 3) path.toString() else "…\\" + parts.takeLast(3).joinToString("\\")
}

@Composable
private fun VersionChip(text: String) = Txt(
    text,
    Modifier.clip(RoundedCornerShape(3.dp)).background(EditorTheme.window).padding(horizontal = 6.dp, vertical = 2.dp),
    color = EditorTheme.textDim, size = 11.sp, mono = true,
)

/** Marks worlds played with Forge; the tooltip names the mods. */
@Composable
private fun ForgeChip(forge: ForgeData) = WithTooltip("${forge.generation.label}, ${forge.playedMods.size} mods: " +
    forge.playedMods.take(12).joinToString { it.id } + if (forge.playedMods.size > 12) ", …" else "") {
    Txt(
        "Forge",
        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(3.dp)).background(EditorTheme.window).padding(horizontal = 6.dp, vertical = 2.dp),
        color = EditorTheme.edited, size = 11.sp, mono = true,
    )
}

@Composable
private fun WorldCard(state: EditorState, world: WorldSource, active: Boolean, onClose: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) EditorTheme.accentSoft else EditorTheme.panelRaised)
            .border(1.dp, if (active) EditorTheme.accent else EditorTheme.border, shape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(world.name, Modifier.weight(1f), weight = FontWeight.SemiBold)
            (world as? AnvilWorld)?.info?.forge?.let { ForgeChip(it) }
            VersionChip(world.versionLabel)
            IconButton(Icons.Close, "Close world", size = 22.dp, iconSize = 9.dp, onClick = onClose)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val count = (world as? AnvilWorld)?.chunkCount
            val b = world.bounds
            Txt(
                when {
                    count != null -> "$count chunks"
                    b == null -> "Empty"
                    else -> "${b.maxX - b.minX + 1} × ${b.maxZ - b.minZ + 1} chunks"
                },
                Modifier.weight(1f), color = EditorTheme.textDim, size = 12.sp,
            )
            val layers = (state.stackOf(world)?.layers?.size ?: 1) - 1
            if (layers > 0) Txt("+$layers layers", Modifier.padding(end = 6.dp), color = EditorTheme.edited, size = 12.sp)
        }
        (world as? AnvilWorld)?.let {
            Txt(shortPath(it.folder), color = EditorTheme.textFaint, size = 10.sp, mono = true)
        }
    }
}

@Composable
fun SelectionPanel(state: EditorState) {
    val selection = state.selection
    val has = selection.isNotEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(panelPadding)) {
        Txt("${selection.size} chunks selected", weight = FontWeight.SemiBold)
        val regions = selection.groupingBy { it.regionX to it.regionZ }.eachCount()
        Txt("in ${regions.size} region files", color = EditorTheme.textDim, size = 12.sp)

        SectionHeader("SELECT")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("All", Modifier.weight(1f)) { state.selectAll() }
            TextButton("None", Modifier.weight(1f), enabled = has) { state.clearSelection() }
            TextButton("Invert", Modifier.weight(1f)) { state.invertSelection() }
        }

        SectionHeader("EDIT")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("Copy", Modifier.weight(1f), icon = Icons.Copy, enabled = has) { state.copy() }
            TextButton("Copy visible", Modifier.weight(1f), enabled = has) { state.copy(visible = true) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("Cut", Modifier.weight(1f), icon = Icons.Cut, enabled = has) { state.cut() }
            TextButton("Clear", Modifier.weight(1f), icon = Icons.Delete, enabled = has) { state.clearSelected() }
        }
        Spacer(Modifier.height(6.dp))
        TextButton("Restore original chunks", Modifier.fillMaxWidth(), icon = Icons.Undo, enabled = has) { state.restoreOriginal() }
        Spacer(Modifier.height(4.dp))
        Txt("Copy, Cut and Clear act on the active layer. Copy visible takes what you see.",
            color = EditorTheme.textFaint, size = 11.sp, maxLines = 3)

        SectionHeader("CLIPBOARD")
        val clip = state.clipboard
        if (clip == null) {
            Txt("Empty. Copy chunks, open another world and paste them there as a new layer.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
        } else {
            KeyValue("Chunks", "${clip.size}")
            KeyValue("From", clip.sourceName)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton("Paste", Modifier.weight(1f), icon = Icons.Paste, primary = true) { state.startPaste() }
                TextButton("In place", Modifier.weight(1f)) { state.pasteInPlace() }
            }
        }

        if (regions.isNotEmpty()) {
            SectionHeader("REGION FILES")
            for ((r, count) in regions.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).take(64)) {
                KeyValue("r.${r.first}.${r.second}.mca", "$count")
            }
            if (regions.size > 64) Txt("… ${regions.size - 64} more", color = EditorTheme.textFaint, size = 11.sp)
        }
    }
}

/**
 * Render mode, display options, textures and mods, in that order. Every section has the same shape: a header,
 * label-value rows, one row of buttons, at most one line of help. Long lists (missing mods, folders) fold.
 */
@Composable
fun ViewPanel(state: EditorState) {
    val settings = state.renderSettings
    val world = state.activeWorld
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).then(panelPadding).padding(end = 6.dp)) {
        SectionHeader("RENDER MODE", Modifier.padding(top = 0.dp))
        Segmented(RenderMode.entries, settings.mode, { it.label }) { state.setRenderMode(it) }
        Hint(settings.mode.description)

        SectionHeader("DISPLAY")
        Checkbox("Chunk borders", settings.chunkBorders) { state.updateRenderSettings(settings.copy(chunkBorders = it)) }
        Checkbox("Layer colors and erased chunks", state.showLayerColors) { state.showLayerColors = it }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Height", Modifier.width(56.dp), color = EditorTheme.textDim, size = 12.sp)
            Box(Modifier.weight(1f)) {
                Segmented(listOf(0.5f, 1f, 1.5f, 2f), settings.heightScale, { "${it}×".replace(".0×", "×") }) {
                    state.updateRenderSettings(settings.copy(heightScale = it))
                }
            }
        }

        (world as? AnvilWorld)?.takeIf { state.hasMods(it) }?.let { ModsSection(state, it) }
        if (settings.mode == RenderMode.Textured && world != null) TexturesSection(state, world)

        SectionHeader("NAVIGATION")
        for ((key, action) in listOf("WASD / arrows" to "Move", "Right / middle drag" to "Move", "Mouse wheel" to "Zoom", "F" to "Fit world")) {
            KeyValue(key, action)
        }
    }
    androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** A world's texture files and the default for the others (Textured mode). */
@Composable
private fun TexturesSection(state: EditorState, world: WorldSource) {
    SectionHeader("TEXTURES")
    val own = state.texturesOf(world).files.isNotEmpty()
    val loaded = state.loadedTexturesOf(world)
    ValueRow("This world", when {
        state.texturesLoading -> "Loading…"
        loaded == null -> "None found"
        else -> loaded.label
    }, if (own) "picked" else "default")
    ButtonRow {
        TextButton("Choose…") { FileDialogs.chooseTextureFiles()?.let { state.chooseTextures(world, it) } }
        if (own) TextButton("Use default") { state.chooseTextures(world, emptyList()) }
    }
    val defaults = TextureFiles.chosen
    ValueRow("Default", if (defaults.isEmpty()) "Found automatically" else defaults.joinToString(" + ") { it.fileName.toString() }, null)
    ButtonRow {
        TextButton("Choose…") { FileDialogs.chooseTextureFiles()?.let(state::chooseDefaultTextures) }
        if (defaults.isNotEmpty()) TextButton("Find automatically") { state.chooseDefaultTextures(emptyList()) }
    }
    Hint("Resource packs over a Minecraft client jar. Worlds from 1.13 on use the jar of their own version when it is installed. Pasted chunks keep their world's textures.")
}

/** Where a modded world's mods come from and how to find missing ones (every render mode). */
@Composable
private fun ModsSection(state: EditorState, world: AnvilWorld) {
    val forge = world.info.forge ?: return
    val loaded = state.loadedTexturesOf(world)
    val total = forge.playedMods.size
    SectionHeader("MODS")
    ValueRow("Found", when {
        loaded != null -> "${total - loaded.missingMods.size} of $total"
        state.texturesLoading -> "Looking…"
        else -> "– of $total"
    }, forge.generation.label)
    ValueRow("With textures", loaded?.mods?.size?.let { "$it archives" } ?: "–", null)
    ButtonRow {
        TextButton("Find mods…", enabled = !state.busy) {
            FileDialogs.chooseFolder("Find the mods of \"${world.name}\" in (a mods folder, an instance, a backup or a drive)", world.folder.parent)
                ?.let { state.findMods(world, it) }
        }
        TextButton("Mods & textures…") { state.modsPanelVisible = !state.modsPanelVisible }
        TextButton("Rescan", enabled = !state.texturesLoading) { state.rescanTextures() }
    }
    loaded?.missingMods?.takeIf { it.isNotEmpty() }?.let { missing ->
        Fold("Not found (${missing.size})", warning = true) {
            Txt(missing.joinToString { it.id }, color = EditorTheme.warning, size = 11.sp, maxLines = 20)
        }
    }
    val folders = ModLocator.folders
    if (folders.isNotEmpty()) {
        Fold("Folders searched (${folders.size})") {
            for (folder in folders) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(shortPath(folder), Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, mono = true)
                IconButton(Icons.Close, "Stop looking here: $folder", size = 20.dp, iconSize = 8.dp) { state.removeModFolder(folder) }
            }
        }
    }
    Hint("Find mods looks in the folder you pick and everything below it. Before 1.8 textures are guessed; see and fix them in Mods & textures.")
}

/** Label left, value right (wrapping), an optional faint note under the value. */
@Composable
private fun ValueRow(label: String, value: String, note: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Txt(label, Modifier.width(92.dp), color = EditorTheme.textDim, size = 12.sp)
        Column(Modifier.weight(1f)) {
            Txt(value, size = 12.sp, maxLines = 3)
            if (note != null) Txt(note, color = EditorTheme.textFaint, size = 11.sp)
        }
    }
}

/** Buttons under the values; they wrap to the next line rather than being cut off in a narrow dock. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(start = 92.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }

/** Faint help text; wraps instead of being cut off. */
@Composable
private fun Hint(text: String) = Txt(text, Modifier.padding(top = 6.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 6)

/** A folding list: a clickable title with a caret, the content when open. */
@Composable
private fun Fold(title: String, warning: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(if (open) "▾" else "▸", Modifier.width(16.dp), color = EditorTheme.textDim, size = 11.sp)
        Txt(title, color = if (warning) EditorTheme.warning else EditorTheme.textDim, size = 12.sp)
    }
    if (open) Column(Modifier.padding(start = 16.dp, bottom = 4.dp)) { content() }
}

@Composable
fun InspectorPanel(state: EditorState) {
    state.tiles.generation // refresh when the hovered chunk's tile arrives
    val world = state.activeWorld
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        SectionHeader("CHUNK UNDER CURSOR")
        val pos = state.hovered
        if (pos == null || world == null) {
            Txt("Hover a chunk", color = EditorTheme.textDim, size = 12.sp)
        } else {
            ChunkDetails(state, pos)
        }
        if (world != null) {
            SectionHeader("WORLD")
            KeyValue("Name", world.name)
            KeyValue("Version", world.versionLabel)
            KeyValue("DataVersion", world.dataVersion?.toString() ?: "–")
            KeyValue("Build height", "${world.minY}..${world.maxY}")
            (world as? AnvilWorld)?.info?.forge?.let { forge ->
                SectionHeader("MODS")
                KeyValue("Loader", forge.generation.label)
                KeyValue("Mods", forge.playedMods.size.toString())
                if (forge.numericIds) KeyValue("Modded block ids", forge.blockNames.size.toString())
                val loaded = state.loadedTexturesOf(world)
                KeyValue("Mod archives", when {
                    loaded != null -> "${loaded.mods.size} with textures"
                    state.texturesLoading -> "Looking…"
                    else -> "–"
                })
                loaded?.let { KeyValue("Not found", it.missingMods.size.toString()) }
                Txt(forge.playedMods.joinToString { if (it.version.isBlank()) it.id else "${it.id} ${it.version}" },
                    Modifier.padding(top = 4.dp), color = EditorTheme.textDim, size = 11.sp, maxLines = 12)
            }
        }
    }
}

@Composable
private fun ChunkDetails(state: EditorState, pos: ChunkPos) {
    val stack = state.activeStack ?: return
    KeyValue("Chunk", "${pos.x}, ${pos.z}")
    KeyValue("Blocks", "${pos.x * 16}, ${pos.z * 16}")
    KeyValue("Region file", "r.${pos.regionX}.${pos.regionZ}.mca")
    val layer = stack.layerAt(pos)
    val ref = stack.resolve(pos)
    KeyValue("Layer", layer?.name ?: if (stack.base.isErased(pos)) "(erased)" else "–")
    KeyValue("Content", when {
        ref == null -> "empty"
        ref.world == stack.world && ref.pos == pos -> "original"
        else -> "${ref.world.name} ${ref.pos.x}, ${ref.pos.z}"
    })
    val tile = ref?.let { state.tiles.peek(it.world.id, it.pos) }
    KeyValue("Surface Y", if (tile == null) "–" else "${tile.minHeight}..${tile.maxHeight}")
    val under = stack.layers.count { it is ChunkLayer && it.chunks.containsKey(pos) }
    if (under > 1) KeyValue("Stacked layers", "$under")
}

@Composable
fun Pill(text: String, color: Color = EditorTheme.textDim) =
    Txt(text, Modifier.padding(horizontal = 8.dp), color = color, size = 12.sp)
