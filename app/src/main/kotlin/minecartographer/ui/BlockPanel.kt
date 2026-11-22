package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.core.BlockState
import minecartographer.editor.EditorState
import minecartographer.editor.Tool

/** The Block dock: the block picked on the map with the Inspect block tool. */
@Composable
fun BlockPanel(state: EditorState) {
    val picked = state.pickedBlock?.takeIf { it.world == state.activeWorld }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (picked == null) {
            Txt("Click a block on the map with the Inspect block tool (K) to see what it is and where.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 4)
            if (state.tool != Tool.InspectBlock) {
                Spacer(Modifier.height(10.dp))
                TextButton("Inspect block", Modifier.fillMaxWidth(), icon = Icons.InspectBlock, primary = true) { state.selectTool(Tool.InspectBlock) }
            }
            return@Column
        }
        BlockTitle(picked.state)
        val properties = picked.state.properties.takeIf { picked.state.name != BlockState.COLOR_BLOCK }.orEmpty()
        if (properties.isNotEmpty()) {
            SectionHeader("PROPERTIES")
            for ((key, value) in properties.toSortedMap()) KeyValue(key, value)
        }

        SectionHeader("POSITION")
        KeyValue("X Y Z", picked.coords)
        KeyValue("Chunk", "${picked.chunk.x}, ${picked.chunk.z}")
        KeyValue("In the chunk", "${picked.x - picked.chunk.x * 16}, ${picked.z - picked.chunk.z * 16}")

        SectionHeader("SHOWN BY")
        KeyValue("Layer", picked.layerName)
        KeyValue("World", picked.world.name)

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("Copy coords", Modifier.weight(1f), icon = Icons.Copy) { state.copyText(picked.coords) }
            TextButton("Clear", Modifier.weight(1f)) { state.pickedBlock = null }
        }
        if (state.renderSettings.cave) {
            Txt("The cave view shows through the ground, but the block is picked as seen from above.",
                Modifier.padding(top = 8.dp), color = EditorTheme.warning, size = 11.sp, maxLines = 4)
        }
    }
}

/** The block's name, large, with its namespace (the mod) under it; demo terrain shows its color instead. */
@Composable
private fun BlockTitle(state: BlockState) {
    if (state.name == BlockState.COLOR_BLOCK) {
        val rgb = state.properties["rgb"]?.toIntOrNull(16) ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF000000.toInt() or rgb))
                .border(1.dp, EditorTheme.border, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Column {
                Txt("Terrain color", weight = FontWeight.SemiBold, size = 14.sp)
                Txt("#%06X · demo world, no real blocks".format(rgb), color = EditorTheme.textDim, size = 11.sp)
            }
        }
        return
    }
    Txt(state.path, weight = FontWeight.SemiBold, size = 14.sp, maxLines = 2)
    Txt(state.name.substringBefore(':'), color = EditorTheme.textDim, size = 11.sp, mono = true)
}
