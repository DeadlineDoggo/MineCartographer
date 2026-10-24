package minecartographer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import minecartographer.editor.EditorState
import minecartographer.textures.BlockModels
import minecartographer.textures.ModLocator
import minecartographer.textures.Texture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

private val MIN_PANEL_WIDTH = 420.dp
private val MAX_PANEL_WIDTH = 1400.dp

/**
 * The Mods & textures panel, laid over the map next to the left dock: the mods the active world needs (found
 * where, with how many textures, or missing) and its modded blocks with the texture each is drawn with, unknown
 * ones included, and a grid to pick another. Folds into a tab on the map's edge; its width can be dragged.
 */
@Composable
fun BoxScope.ModsDrawer(state: EditorState) {
    val world = state.activeWorld as? AnvilWorld ?: return
    if (!state.hasMods(world)) return
    if (!state.modsPanelVisible) {
        Box(
            Modifier.align(Alignment.TopStart).padding(top = 52.dp).clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .clickable { state.modsPanelVisible = true }.padding(horizontal = 10.dp, vertical = 8.dp),
        ) { Txt("▸ Mods & textures", size = 12.sp) }
        return
    }
    Row(
        Modifier.align(Alignment.TopStart).fillMaxHeight()
            // Clicks and drags on the panel must not reach the map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        ModsPanel(state, world, Modifier.width(state.modsPanelWidth).fillMaxHeight())
        Splitter { state.modsPanelWidth = (state.modsPanelWidth + it).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH) }
    }
}

@Composable
private fun ModsPanel(state: EditorState, world: AnvilWorld, modifier: Modifier) {
    val forge = world.info.forge ?: return
    // Blocks show up as the map reads chunks, mods as textures load: refresh now and then.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    var tab by remember { mutableStateOf(0) }
    val loaded = state.loadedTexturesOf(world)
    val keys = remember(tick, world) { state.moddedKeysOf(world) }
    val found = forge.playedMods.size - (loaded?.missingMods?.size ?: forge.playedMods.size)
    Column(modifier.background(EditorTheme.panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("MODS & TEXTURES · ${world.name.uppercase()}", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            WithTooltip("Find mods again and rebuild every texture cache") {
                TextButton("Rescan", enabled = !state.texturesLoading) { state.rescanTextures() }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(Icons.Close, "Fold the panel", size = 24.dp, iconSize = 10.dp) { state.modsPanelVisible = false }
        }
        HDivider()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Segmented(listOf(0, 1), tab, { if (it == 0) "Mods ($found/${forge.playedMods.size})" else "Blocks (${keys.size})" }) { tab = it }
            Txt("${forge.generation.label} · ${loaded?.mods?.size ?: 0} archives with textures", Modifier.padding(top = 6.dp), color = EditorTheme.textFaint, size = 11.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) ModsTab(state, world) else BlocksTab(state, world, keys)
            }
        }
    }
}

/** A lazy list with a scrollbar on its right edge. */
@Composable
private fun ScrollList(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), list, verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

// ---- Mods ----

@Composable
private fun ColumnScope.ModsTabContent(state: EditorState, world: AnvilWorld) {
    val forge = world.info.forge ?: return
    val loaded = state.loadedTexturesOf(world)
    val assets = state.modelsOf(world)?.assets
    val manual = state.texturesOf(world).modArchives
    val missing = loaded?.missingMods.orEmpty().mapTo(HashSet()) { it.id.lowercase() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton("Find mods…", enabled = !state.busy) {
            FileDialogs.chooseFolder("Find the mods of \"${world.name}\" in (a mods folder, an instance, a backup or a drive)", world.folder.parent)
                ?.let { state.findMods(world, it) }
        }
        Txt("Looks in the folder you pick and everything below it.", Modifier.weight(1f), color = EditorTheme.textFaint, size = 11.sp, maxLines = 2)
    }
    for (folder in ModLocator.folders) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(folder.toString(), Modifier.weight(1f), color = EditorTheme.textDim, size = 10.sp, mono = true, maxLines = 2)
        IconButton(Icons.Folder, "Show in folder", size = 20.dp, iconSize = 11.dp) { FileDialogs.showInFolder(folder) }
        IconButton(Icons.Close, "Stop looking here", size = 20.dp, iconSize = 8.dp) { state.removeModFolder(folder) }
    }
    Spacer(Modifier.height(8.dp))
    ScrollList(Modifier.weight(1f).fillMaxWidth()) {
        items(forge.playedMods.sortedWith(compareBy({ it.id.lowercase() !in missing }, { it.id.lowercase() })), key = { it.id }) { mod ->
            val id = mod.id.lowercase()
            val path = loaded?.providers?.get(id)
            val textures = path?.let { p -> assets?.files?.indexOf(p)?.takeIf { it >= 0 }?.let { assets.blockTextures[it]?.size } }
            val chosen = manual[mod.id]
            val (color, status) = when {
                loaded == null -> EditorTheme.textFaint to "not looked for yet"
                id in missing -> EditorTheme.warning to "not found"
                chosen != null -> EditorTheme.accent to "picked by you"
                (textures ?: 0) > 0 -> EditorTheme.paste to "found"
                else -> EditorTheme.textDim to "found, no block textures"
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(EditorTheme.panelRaised).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
                    Spacer(Modifier.width(8.dp))
                    Txt(mod.id, Modifier.weight(1f), size = 12.sp, mono = true)
                    Txt(mod.version.ifBlank { "–" }, color = EditorTheme.textDim, size = 11.sp, mono = true)
                    Spacer(Modifier.width(10.dp))
                    Txt(textures?.let { "$it textures" } ?: "", Modifier.width(84.dp), color = EditorTheme.textDim, size = 11.sp)
                }
                Row(Modifier.padding(start = 17.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(path?.name ?: status, color = if (path == null) color else EditorTheme.text, size = 11.sp, maxLines = 2)
                        if (path != null) Txt("${path.parent} · $status", color = EditorTheme.textFaint, size = 10.sp, mono = true, maxLines = 2)
                    }
                    if (path != null) IconButton(Icons.Folder, "Show in folder", size = 22.dp, iconSize = 12.dp) { FileDialogs.showInFolder(path) }
                    WithTooltip("Pick the archive of ${mod.id} yourself") {
                        TextButton("Choose…") {
                            FileDialogs.chooseModArchive(mod.id, path?.parent ?: ModLocator.folders.firstOrNull())?.let { state.chooseModArchive(world, mod.id, it) }
                        }
                    }
                    if (chosen != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton("Auto") { state.chooseModArchive(world, mod.id, null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModsTab(state: EditorState, world: AnvilWorld) = Column(Modifier.fillMaxSize()) { ModsTabContent(state, world) }

// ---- Blocks ----

@Composable
private fun BlocksTab(state: EditorState, world: AnvilWorld, keys: List<String>) {
    val models = state.modelsOf(world)
    if (models == null) {
        Txt("Textures are not loaded for this world yet.", color = EditorTheme.warning, size = 12.sp)
        return
    }
    var filter by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(BlockFilter.All) }
    var selected by remember { mutableStateOf<String?>(null) }
    val infos = remember(keys, state.texturesOf(world).blocks) { keys.associateWith { models.info(BlockColors.stateOf(it)) } }
    val shown = keys.filter { k ->
        (filter.isBlank() || filter.lowercase() in k.lowercase() || infos[k]?.texture?.lowercase()?.contains(filter.lowercase()) == true) &&
            show.accepts(infos[k]?.source)
    }
    val thumbs = remember(models) { ThumbCache(models) }
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            TextInput(filter, { filter = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Segmented(BlockFilter.entries, show, { f -> "${f.label} ${keys.count { f.accepts(infos[it]?.source) }}" }) { show = it }
            Spacer(Modifier.height(6.dp))
            ScrollList(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it }) { key ->
                    val info = infos[key]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (key == selected) EditorTheme.accentSoft else EditorTheme.panelRaised)
                            .clickable { selected = key }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(thumbs, info?.texture, key, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(key, size = 12.sp, mono = true)
                            Txt(info?.let { "${it.source.label} · ${it.texture ?: it.detail}" } ?: "–", color = sourceColor(info?.source), size = 11.sp)
                        }
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val key = selected
            if (key == null) Txt("Pick a block to see where its texture comes from and choose another.", color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
            else BlockDetail(state, world, models, thumbs, key, infos[key])
        }
    }
    // Side by side when the panel is wide, one above the other when it is narrow.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        if (width > 860.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            list(Modifier.width(width * 0.45f).fillMaxHeight())
            detail(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list(Modifier.weight(1f).fillMaxWidth())
            HDivider()
            detail(Modifier.weight(1.2f).fillMaxWidth())
        }
    }
}

private enum class BlockFilter(val label: String) {
    All("All"), Unknown("Unknown"), Guessed("Guessed"), Yours("Yours");

    fun accepts(source: BlockModels.Source?) = when (this) {
        All -> true
        Unknown -> source == BlockModels.Source.UNKNOWN || source == null
        Guessed -> source == BlockModels.Source.GUESS
        Yours -> source == BlockModels.Source.CHOICE
    }
}

private fun sourceColor(source: BlockModels.Source?) = when (source) {
    BlockModels.Source.CHOICE -> EditorTheme.accent
    BlockModels.Source.BLOCKSTATE -> EditorTheme.paste
    BlockModels.Source.GUESS -> EditorTheme.edited
    else -> EditorTheme.warning
}

@Composable
private fun BlockDetail(state: EditorState, world: AnvilWorld, models: BlockModels, thumbs: ThumbCache, key: String, info: BlockModels.BlockInfo?) {
    val blockName = BlockColors.stateOf(key).name
    var onlyThisMod by remember(key) { mutableStateOf(true) }
    var filter by remember(key) { mutableStateOf("") }
    // Textures of the archive providing the block's mod (1.6: by its ModId; later: by namespace), or every texture.
    val loaded = state.loadedTexturesOf(world)
    val assets = models.assets
    val modId = models.legacyModId(blockName)?.lowercase()
    val ownFile = modId?.let { loaded?.providers?.get(it) }?.let { assets.files.indexOf(it) }?.takeIf { it >= 0 }
    val namespace = blockName.substringBefore(':')
    val all = remember(assets) { assets.blockTextures.values.flatten().sorted() }
    val candidates = when {
        !onlyThisMod -> all
        ownFile != null -> assets.blockTextures[ownFile].orEmpty()
        else -> all.filter { it.substringBefore(':') == namespace }
    }.filter { filter.isBlank() || filter.lowercase() in it.lowercase() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumbs, info?.texture, key, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(key, size = 13.sp, mono = true, weight = FontWeight.SemiBold, maxLines = 2)
                Txt("${info?.source?.label ?: "–"}: ${info?.texture ?: "no texture"}", color = sourceColor(info?.source), size = 12.sp, maxLines = 2)
                if (info?.detail?.isNotBlank() == true && info.source != BlockModels.Source.CHOICE) {
                    Txt(info.detail, color = EditorTheme.textFaint, size = 11.sp, maxLines = 2)
                }
            }
        }
        if (info?.source == BlockModels.Source.CHOICE) TextButton("Back to the guess", Modifier.padding(top = 6.dp)) { state.setBlockTexture(world, key, null) }
        Spacer(Modifier.height(8.dp))
        TextInput(filter, { filter = it }, Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${candidates.size} textures · click one to use it", Modifier.weight(1f), color = EditorTheme.textFaint, size = 11.sp)
            Box(Modifier.width(150.dp)) { Checkbox("Only this mod", onlyThisMod) { onlyThisMod = it } }
        }
        val grid = rememberLazyGridState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(GridCells.Adaptive(64.dp), Modifier.fillMaxSize().padding(end = 10.dp), grid) {
                items(candidates, key = { it }) { id ->
                    val current = id == info?.texture
                    WithTooltip(id) {
                        Column(
                            Modifier.padding(3.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (current) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                .border(1.dp, if (current) EditorTheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable { state.setBlockTexture(world, key, id) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Thumb(thumbs, id, null, 48.dp)
                            Txt(id.substringAfterLast('/'), color = EditorTheme.textDim, size = 9.sp)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

// ---- Thumbnails ----

/** Texture bitmaps for the panel, made off the UI thread and kept while it is shown. */
private class ThumbCache(val models: BlockModels) {
    val bitmaps = ConcurrentHashMap<String, Any>() // ImageBitmap, or NONE

    fun load(id: String): ImageBitmap? {
        bitmaps[id]?.let { return it as? ImageBitmap }
        val bitmap = models.texture(id)?.let(::toBitmap)
        bitmaps[id] = bitmap ?: NONE
        return bitmap
    }

    companion object {
        private val NONE = Any()

        fun toBitmap(t: Texture): ImageBitmap {
            val pixels = t.levelData.first()
            val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (p in pixels) bytes.putInt(p) // premultiplied ARGB in little-endian = BGRA
            return org.jetbrains.skia.Image.makeRaster(ImageInfo(t.size, t.size, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes.array(), t.size * 4)
                .toComposeImageBitmap()
        }
    }
}

/** A texture's pixels, sharp; for blocks without one, their map color with a "?". */
@Composable
private fun Thumb(cache: ThumbCache, id: String?, key: String?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, id) { value = id?.let { withContext(Dispatchers.IO) { cache.load(it) } } }
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.size(size).clip(shape).border(1.dp, EditorTheme.border, shape), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = id, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        } else if (key != null) {
            val look = BlockColors.look(BlockColors.stateOf(key))
            if (look.kind != BlockKind.SKIP) Box(Modifier.fillMaxSize().background(Color(look.color or (0xFF shl 24)).copy(alpha = 0.6f)))
            Txt("?", color = EditorTheme.text, size = 11.sp)
        }
    }
}
