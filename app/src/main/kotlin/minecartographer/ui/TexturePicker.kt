package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import minecartographer.editor.EditorState
import minecartographer.model.ModelEditorState

private val SIZES = listOf(48, 64, 96)
private val SIZE_LABELS = listOf("S", "M", "L")

/**
 * The texture atlas of the active world as a chooser for one texture variable of a model: every block texture of its
 * packs and mods, grouped by mod, with a filter. A click sets the variable and closes the window.
 */
@Composable
fun TexturePickerDialog(state: EditorState, model: ModelEditorState) {
    val variable = model.pickingTexture ?: return
    DialogWindow(
        onCloseRequest = { model.pickingTexture = null },
        title = "Texture for #$variable",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(860.dp, 720.dp)),
    ) {
        val world = state.activeWorld
        val models = world?.let(state::modelsOf)
        val thumbs = rememberThumbs()(models)
        // A click only marks a texture; Choose (or a double-click) applies it.
        var picked by remember(variable) { mutableStateOf(model.doc.textures[variable]) }
        val confirm = {
            picked?.let { model.setTexture(variable, it) }
            model.pickingTexture = null
        }
        Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(16.dp)) {
            Txt("Choose the texture of #$variable", size = 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (models == null || thumbs == null) {
                Txt(
                    if (world == null) "Open a world first: its packs and mods hold the textures."
                    else "The textures of \"${world.name}\" are not loaded yet. Switch to Textured mode (3), or wait for the mods to load.",
                    color = EditorTheme.textDim, size = 12.sp, maxLines = 3,
                )
                Spacer(Modifier.weight(1f))
            } else {
                Picker(models.assets.blockTextures.values.flatten().distinct()
                    .sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it })), thumbs, picked, { picked = it }, confirm)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(picked.orEmpty(), Modifier.weight(1f), color = EditorTheme.textDim, size = 12.sp, mono = true)
                TextButton("Cancel") { model.pickingTexture = null }
                TextButton("Choose", primary = true, enabled = picked != null) { confirm() }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.layout.ColumnScope.Picker(
    all: List<String>, thumbs: ThumbCache, picked: String?, onPick: (String) -> Unit, onConfirm: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sizeIndex by remember { mutableStateOf(1) }
    var mod by remember { mutableStateOf<String?>(null) }
    val byMod = remember(all) { all.groupBy { it.substringBefore(':') } }
    val q = query.trim().lowercase()
    val shown = (mod?.let { byMod[it] } ?: all).let { list -> if (q.isEmpty()) list else list.filter { q in it.lowercase() } }
    val groups = shown.groupBy { it.substringBefore(':') }

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextInput(query, { query = it }, Modifier.fillMaxWidth())
            if (query.isEmpty()) Txt("Filter by name or mod", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = EditorTheme.textFaint, size = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(120.dp)) { Segmented(SIZES.indices.toList(), sizeIndex, { SIZE_LABELS[it] }) { sizeIndex = it } }
    }
    Spacer(Modifier.height(6.dp))
    // The mods, to narrow the page to one of them.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModChip("All", mod == null) { mod = null }
        for (name in byMod.keys.take(8)) ModChip(name, mod == name) { mod = if (mod == name) null else name }
    }
    Txt("${shown.size} textures", Modifier.padding(top = 8.dp, bottom = 4.dp), color = EditorTheme.textFaint, size = 11.sp)
    val cell = SIZES[sizeIndex].dp
    val grid = rememberLazyGridState()
    Box(Modifier.weight(1f).fillMaxWidth()) {
        // Start at the texture the variable has now.
        LaunchedEffect(Unit) {
            var index = 0
            for ((_, ids) in groups) {
                val at = ids.indexOf(picked)
                if (at >= 0) {
                    grid.scrollToItem(index + 1 + at)
                    break
                }
                index += 1 + ids.size
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(cell + 14.dp), Modifier.fillMaxSize().padding(end = 10.dp), grid) {
            for ((name, ids) in groups) {
                item(key = "mod:$name", span = { GridItemSpan(maxLineSpan) }) {
                    Txt("${name.uppercase()} · ${ids.size}", Modifier.padding(top = 10.dp, bottom = 4.dp, start = 3.dp), color = EditorTheme.textFaint, size = 11.sp, weight = FontWeight.SemiBold)
                }
                items(ids, key = { it }) { id ->
                    val chosen = id == picked
                    WithTooltip(id) {
                        Column(
                            Modifier.padding(3.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (chosen) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                .border(1.dp, if (chosen) EditorTheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                                .combinedClickable(onDoubleClick = {
                                    onPick(id)
                                    onConfirm()
                                }) { onPick(id) }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Thumb(thumbs, id, null, cell)
                            Txt(id.substringAfterLast('/'), Modifier.width(cell), color = EditorTheme.textDim, size = 9.sp)
                        }
                    }
                }
            }
        }
        ItemScrollbar(grid, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun ModChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) EditorTheme.accentSoft else EditorTheme.panelRaised)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Txt(label, color = if (selected) EditorTheme.accent else EditorTheme.textDim, size = 11.sp)
    }
}
