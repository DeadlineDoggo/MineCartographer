package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import minecartographer.editor.EditorState
import minecartographer.editor.MainTab
import minecartographer.textures.BlockModels

/** The tab strip over the main area while the atlas is open: the map (always there) and the closable atlas. */
@Composable
fun MainTabs(state: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(EditorTheme.toolbar),
        verticalAlignment = Alignment.Bottom,
    ) {
        Tab("Map", state.mainTab == MainTab.Map, onClose = null) { state.showMainTab(MainTab.Map) }
        Tab("Texture atlas · ${state.activeWorld?.name ?: "no world"}", state.mainTab == MainTab.Atlas, onClose = { state.closeAtlas() }) {
            state.showMainTab(MainTab.Atlas)
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClose: (() -> Unit)?, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    Row(
        Modifier.padding(start = 4.dp, top = 4.dp).clip(shape)
            .background(if (selected) EditorTheme.panel else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = if (onClose == null) 12.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            Txt(label, color = if (selected) EditorTheme.text else EditorTheme.textDim, size = 12.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            // The line takes the width of the label; fillMaxWidth would stretch the whole tab across the strip.
            if (selected) Box(Modifier.matchParentSize()) { Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(EditorTheme.accent)) }
        }
        if (onClose != null) {
            Spacer(Modifier.width(6.dp))
            IconButton(Icons.Close, "Close the atlas", size = 20.dp, iconSize = 8.dp, onClick = onClose)
        }
    }
}

private val SIZES = listOf(48, 64, 96, 128)
private val SIZE_LABELS = listOf("S", "M", "L", "XL")

/**
 * Every block texture of the active world's packs and mods on one page, grouped by mod, filled to the map's place.
 * Click one for its details; right-click for export.
 */
@Composable
fun AtlasView(state: EditorState) {
    val world = state.activeWorld
    // The packs load in the background: look again until they are there.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(world) { while (true) { delay(1000); tick++ } }
    val models = remember(tick, world) { world?.let(state::modelsOf) }
    Column(Modifier.fillMaxSize().background(EditorTheme.panel)) {
        if (models == null) {
            Txt(
                if (world == null) "Open a world to see its textures." else "The textures of \"${world.name}\" are not loaded yet. Switch to Textured mode (3), or wait for the mods to load.",
                Modifier.padding(16.dp), color = EditorTheme.textDim, size = 13.sp, maxLines = 3,
            )
            return@Column
        }
        Atlas(state, models, world!!.name)
    }
}

@Composable
private fun Atlas(state: EditorState, models: BlockModels, worldName: String) {
    val assets = models.assets
    val all = remember(models) { assets.blockTextures.values.flatten().distinct().sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it })) }
    val byMod = remember(all) { all.groupBy { it.substringBefore(':') } }
    var mod by remember(models) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sizeIndex by remember { mutableStateOf(1) }
    var selected by remember(models) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val thumbs = rememberThumbs()(models)!!

    val q = query.trim().lowercase()
    val shown = (mod?.let { byMod[it] } ?: all).let { list -> if (q.isEmpty()) list else list.filter { q in it.lowercase() } }
    val groups = shown.groupBy { it.substringBefore(':') }

    Row(Modifier.fillMaxSize()) {
        // The mods, with their number of textures.
        Column(Modifier.width(220.dp).fillMaxHeight().background(EditorTheme.toolbar)) {
            Txt("MODS", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp), color = EditorTheme.textFaint, size = 11.sp, weight = FontWeight.SemiBold)
            val list = rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), list) {
                    item { ModRow("All", all.size, mod == null) { mod = null } }
                    items(byMod.keys.toList()) { name -> ModRow(name, byMod.getValue(name).size, mod == name) { mod = name } }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("${shown.size} textures · $worldName", Modifier.padding(end = 12.dp), color = EditorTheme.textDim, size = 12.sp)
                Box(Modifier.weight(1f)) {
                    TextInput(query, { query = it }, Modifier.fillMaxWidth())
                    if (query.isEmpty()) Txt("Filter by name or mod", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = EditorTheme.textFaint, size = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(160.dp)) { Segmented(SIZES.indices.toList(), sizeIndex, { SIZE_LABELS[it] }) { sizeIndex = it } }
                Spacer(Modifier.width(8.dp))
                WithTooltip("Save the ${shown.size} textures shown into a folder, laid out like a resource pack") {
                    TextButton("Export shown…", enabled = shown.isNotEmpty()) { TextureExport.exportMany(state, scope, models, shown) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val cell = SIZES[sizeIndex].dp
            val grid = rememberLazyGridState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(GridCells.Adaptive(cell + 14.dp), Modifier.fillMaxSize().padding(end = 10.dp), grid) {
                    for ((name, ids) in groups) {
                        item(key = "mod:$name", span = { GridItemSpan(maxLineSpan) }) {
                            Txt("${name.uppercase()} · ${ids.size}", Modifier.padding(top = 10.dp, bottom = 4.dp, start = 3.dp), color = EditorTheme.textFaint, size = 11.sp, weight = FontWeight.SemiBold)
                        }
                        items(ids, key = { it }) { id ->
                            var menuAt by remember { mutableStateOf<IntOffset?>(null) }
                            val current = id == selected
                            WithTooltip(id) {
                                Column(
                                    Modifier.padding(3.dp).clip(RoundedCornerShape(4.dp))
                                        .background(if (current) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                        .border(1.dp, if (current) EditorTheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                                        .clickable { selected = id }
                                        .onRightClick(id) {
                                            selected = id
                                            menuAt = it
                                        }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    menuAt?.let { at -> ContextMenu(at, textureMenu(state, scope, models, id, shown, { mod = id.substringBefore(':') })) { menuAt = null } }
                                    Thumb(thumbs, id, null, cell)
                                    Txt(id.substringAfterLast('/'), Modifier.width(cell), color = EditorTheme.textDim, size = 9.sp)
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            selected?.let { Details(state, models, thumbs, it) }
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

/** The picked texture, big, with where it is from and what to do with it. */
@Composable
private fun Details(state: EditorState, models: BlockModels, thumbs: ThumbCache, id: String) {
    val assets = models.assets
    val texture = remember(id) { models.texture(id) }
    val archive = assets.index["assets/${id.substringBefore(':')}/textures/${id.substringBefore('#').substringAfter(':')}.png"]?.let { assets.files[it].fileName.toString() }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(EditorTheme.panelRaised).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbs, id, null, 96.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(id, size = 13.sp, mono = true, weight = FontWeight.SemiBold)
            Txt(listOfNotNull(texture?.let { "${it.size}×${it.size} px" }, archive?.let { "from $it" }).joinToString(" · "), color = EditorTheme.textDim, size = 12.sp)
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            TextButton("Export PNG…") { TextureExport.exportOne(state, models, id) }
            TextButton("Export ×${TextureExport.ENLARGED}…") { TextureExport.exportOne(state, models, id, TextureExport.ENLARGED) }
            TextButton("Copy name") { state.copyText(id) }
        }
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
