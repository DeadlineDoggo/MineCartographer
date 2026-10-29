package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import minecartographer.editor.AtlasState
import minecartographer.editor.EditorState
import minecartographer.textures.BlockModels

private val SIZES = listOf(48, 64, 96, 128)
private val SIZE_LABELS = listOf("S", "M", "L", "XL")

/**
 * The middle of the atlas workspace: every block texture of the active world's packs and mods on one page,
 * grouped by mod. Click one for its details (right dock); right-click for export.
 */
@Composable
fun AtlasView(state: EditorState) {
    val atlas = state.workspaces.atlas
    val world = state.activeWorld
    // The packs load in the background: look again until they are there.
    LaunchedEffect(world) {
        while (true) {
            atlas.update(world?.let(state::modelsOf))
            delay(1000)
        }
    }
    val models = atlas.models
    Column(Modifier.fillMaxSize().background(EditorTheme.panel)) {
        if (models == null) {
            Txt(atlasMissingText(world?.name), Modifier.padding(16.dp), color = EditorTheme.textDim, size = 13.sp, maxLines = 3)
            return@Column
        }
        AtlasGrid(state, atlas, models, world!!.name)
    }
}

private fun atlasMissingText(worldName: String?) =
    if (worldName == null) "Open a world to see its textures."
    else "The textures of \"$worldName\" are not loaded yet. Switch to Textured mode (3), or wait for the mods to load."

@Composable
private fun AtlasGrid(state: EditorState, atlas: AtlasState, models: BlockModels, worldName: String) {
    val shown = atlas.shown
    val groups = shown.groupBy { it.substringBefore(':') }
    val thumbs = atlas.thumbs ?: return
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("${shown.size} textures · $worldName", Modifier.weight(1f).padding(end = 12.dp), color = EditorTheme.textDim, size = 12.sp)
            Box(Modifier.width(160.dp)) { Segmented(SIZES.indices.toList(), atlas.sizeIndex, { SIZE_LABELS[it] }) { atlas.sizeIndex = it } }
            Spacer(Modifier.width(8.dp))
            WithTooltip("Save the ${shown.size} textures shown into a folder, laid out like a resource pack") {
                TextButton("Export shown…", enabled = shown.isNotEmpty()) { TextureExport.exportMany(state, scope, models, shown) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val cell = SIZES[atlas.sizeIndex].dp
        val grid = rememberLazyGridState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(GridCells.Adaptive(cell + 14.dp), Modifier.fillMaxSize().padding(end = 10.dp), grid) {
                for ((name, ids) in groups) {
                    item(key = "mod:$name", span = { GridItemSpan(maxLineSpan) }) {
                        Txt("${name.uppercase()} · ${ids.size}", Modifier.padding(top = 10.dp, bottom = 4.dp, start = 3.dp), color = EditorTheme.textFaint, size = 11.sp, weight = FontWeight.SemiBold)
                    }
                    items(ids, key = { it }) { id ->
                        var menuAt by remember { mutableStateOf<IntOffset?>(null) }
                        val current = id == atlas.selected
                        WithTooltip(id) {
                            Column(
                                Modifier.padding(3.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (current) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                    .border(1.dp, if (current) EditorTheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                                    .clickable { atlas.selected = id }
                                    .onRightClick(id) {
                                        atlas.selected = id
                                        menuAt = it
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                menuAt?.let { at -> ContextMenu(at, textureMenu(state, scope, models, id, shown, { atlas.mod = id.substringBefore(':') })) { menuAt = null } }
                                Thumb(thumbs, id, null, cell)
                                Txt(id.substringAfterLast('/'), Modifier.width(cell), color = EditorTheme.textDim, size = 9.sp)
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Left dock of the atlas workspace: the filter and the mods with their number of textures. */
@Composable
fun AtlasLeftDock(state: EditorState, modifier: Modifier) {
    val atlas = state.workspaces.atlas
    Column(modifier.background(EditorTheme.panel)) {
        Txt("FILTER", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
        Box(Modifier.padding(horizontal = 10.dp)) {
            TextInput(atlas.query, { atlas.query = it }, Modifier.fillMaxWidth())
            if (atlas.query.isEmpty()) Txt("Filter by name or mod", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = EditorTheme.textFaint, size = 12.sp)
        }
        Txt("MODS", Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
        val list = rememberLazyListState()
        val byMod = atlas.byMod
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), list) {
                item { ModRow("All", atlas.all.size, atlas.mod == null) { atlas.mod = null } }
                items(byMod.keys.toList()) { name -> ModRow(name, byMod.getValue(name).size, atlas.mod == name) { atlas.mod = name } }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Right dock of the atlas workspace: the picked texture, big, with where it is from and what to do with it. */
@Composable
fun AtlasRightDock(state: EditorState, modifier: Modifier) {
    val atlas = state.workspaces.atlas
    ToolWindow("Texture", onClose = { state.rightDockVisible = false }, modifier) {
        val models = atlas.models
        val thumbs = atlas.thumbs
        val id = atlas.selected
        if (models == null || thumbs == null || id == null) {
            Txt("Pick a texture to see where it is from.", Modifier.padding(12.dp), color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
        } else {
            Details(state, models, thumbs, id)
        }
    }
}

@Composable
private fun ModRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) EditorTheme.accentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(name, Modifier.weight(1f), color = if (selected) EditorTheme.accent else EditorTheme.text, size = 12.sp)
        Txt(count.toString(), color = EditorTheme.textFaint, size = 11.sp, mono = true)
    }
}

@Composable
private fun Details(state: EditorState, models: BlockModels, thumbs: ThumbCache, id: String) {
    val assets = models.assets
    val texture = remember(id) { models.texture(id) }
    val archive = assets.index["assets/${id.substringBefore(':')}/textures/${id.substringBefore('#').substringAfter(':')}.png"]?.let { assets.files[it].fileName.toString() }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Thumb(thumbs, id, null, 128.dp)
        Spacer(Modifier.height(4.dp))
        Txt(id, size = 13.sp, mono = true, weight = FontWeight.SemiBold, maxLines = 3)
        Txt(listOfNotNull(texture?.let { "${it.size}×${it.size} px" }, archive?.let { "from $it" }).joinToString(" · "), color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
        Spacer(Modifier.height(6.dp))
        TextButton("Export PNG…") { TextureExport.exportOne(state, models, id) }
        TextButton("Export ×${TextureExport.ENLARGED}…") { TextureExport.exportOne(state, models, id, TextureExport.ENLARGED) }
        TextButton("Copy name") { state.copyText(id) }
    }
}

/** The right-click menu of a texture in the atlas. */
private fun textureMenu(
    state: EditorState, scope: kotlinx.coroutines.CoroutineScope, models: BlockModels, id: String, shown: List<String>, onlyThisMod: () -> Unit,
): List<MenuEntry> = listOf(
    MenuItem("Export texture as PNG…") { TextureExport.exportOne(state, models, id) },
    MenuItem("Export enlarged (×${TextureExport.ENLARGED})…") { TextureExport.exportOne(state, models, id, TextureExport.ENLARGED) },
    MenuItem("Copy name") { state.copyText(id) },
    MenuSeparator,
    MenuItem("Show only \"${id.substringBefore(':')}\"", action = onlyThisMod),
    MenuItem("Export the ${shown.size} shown…", enabled = shown.size > 1) { TextureExport.exportMany(state, scope, models, shown) },
)
