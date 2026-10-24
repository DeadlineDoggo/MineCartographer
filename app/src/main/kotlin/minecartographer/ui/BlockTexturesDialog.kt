package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import minecartographer.editor.EditorState

/**
 * The modded blocks of the active world seen so far, with the texture each is drawn with and where that came from.
 * For 1.6 worlds most of it is a guess; typing a texture id fixes one block (or one data value of it) for good:
 * it is saved with the project.
 */
@Composable
fun BlockTexturesDialog(state: EditorState) {
    if (!state.blockTexturesDialogVisible) return
    val world = state.activeWorld ?: return
    DialogWindow(
        onCloseRequest = { state.blockTexturesDialogVisible = false },
        title = "Block textures · ${world.name}",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(820.dp, 680.dp)),
    ) {
        // Blocks show up as the viewport reads chunks; refresh the list now and then.
        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
        var filter by remember { mutableStateOf("") }
        val keys = remember(tick, filter, world) {
            state.moddedKeysOf(world).filter { filter.isBlank() || filter.lowercase() in it.lowercase() }
        }
        val models = state.modelsOf(world)
        val overrides = state.texturesOf(world).blocks

        Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(16.dp)) {
            Txt("Modded blocks of ${world.name}", size = 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Txt("Blocks appear here as their chunks are drawn. For worlds before 1.8 the textures are guessed from the mods' " +
                "classes, config and texture names. Type a texture id (as listed in the mod, e.g. extrabiomesxl:leavesfirfancy) " +
                "to choose one; it is saved with the project.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 4)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Filter", Modifier.width(48.dp), color = EditorTheme.textDim, size = 12.sp)
                TextInput(filter, { filter = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Txt("${keys.size} blocks", color = EditorTheme.textFaint, size = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (models == null) {
                Txt("Textures are not loaded for this world yet.", color = EditorTheme.warning, size = 12.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(keys, key = { it }) { key -> BlockRow(state, world, key, overrides[key], models?.describe(BlockColors.stateOf(key))) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton("Close", primary = true) { state.blockTexturesDialogVisible = false }
            }
        }
    }
}

@Composable
private fun BlockRow(state: EditorState, world: minecartographer.core.WorldSource, key: String, override: String?, source: String?) {
    var text by remember(key, override) { mutableStateOf(override.orEmpty()) }
    val look = BlockColors.look(BlockColors.stateOf(key))
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(EditorTheme.panel).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                .background(if (look.kind == BlockKind.SKIP) Color.Transparent else Color(look.color or (0xFF shl 24)))
                .border(1.dp, EditorTheme.border, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Txt(key, size = 12.sp, mono = true)
            Txt(source ?: "–", color = if (override != null) EditorTheme.accent else EditorTheme.textFaint, size = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        TextInput(text, { text = it }, Modifier.width(260.dp))
        Spacer(Modifier.width(6.dp))
        TextButton("Use", enabled = text.isNotBlank() && text != override) { state.setBlockTexture(world, key, text) }
        if (override != null) {
            Spacer(Modifier.width(4.dp))
            TextButton("Reset") { state.setBlockTexture(world, key, null) }
        }
    }
}
