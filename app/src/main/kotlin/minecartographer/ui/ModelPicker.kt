package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import minecartographer.editor.EditorState

/** Lists the block models of the active world's packs and mods; a click opens one in a new model tab. */
@Composable
fun ModelPickerDialog(state: EditorState) {
    if (!state.modelPickerVisible) return
    DialogWindow(
        onCloseRequest = { state.modelPickerVisible = false },
        title = "Open a model",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(560.dp, 680.dp)),
    ) {
        val world = state.activeWorld
        val models = world?.let(state::modelsOf)
        Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(16.dp)) {
            Txt("Open a block model", size = 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (models == null) {
                Txt(
                    if (world == null) "Open a world first: its packs and mods hold the models."
                    else "The models of \"${world.name}\" are not loaded yet. Switch to Textured mode (3), or wait for the mods to load.",
                    color = EditorTheme.textDim, size = 12.sp, maxLines = 3,
                )
            } else {
                var query by remember { mutableStateOf("") }
                Txt("Parents are folded into the copy you get; the pack itself is never changed.", color = EditorTheme.textDim, size = 12.sp, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Box {
                    TextInput(query, { query = it }, Modifier.fillMaxWidth())
                    if (query.isEmpty()) Txt("Filter by name or mod", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = EditorTheme.textFaint, size = 12.sp)
                }
                val q = query.trim().lowercase()
                val ids = remember(models, q) { models.assets.modelIds.let { all -> if (q.isEmpty()) all else all.filter { q in it.lowercase() } } }
                Txt("${ids.size} models", Modifier.padding(top = 8.dp, bottom = 4.dp), color = EditorTheme.textFaint, size = 11.sp)
                val list = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), list) {
                        items(ids, key = { it }) { id ->
                            Txt(
                                id, Modifier.fillMaxWidth().clickable { state.openModelFromAssets(id) }.padding(horizontal = 8.dp, vertical = 5.dp),
                                size = 12.sp, mono = true,
                            )
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (models == null) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("Cancel") { state.modelPickerVisible = false }
            }
        }
    }
}
