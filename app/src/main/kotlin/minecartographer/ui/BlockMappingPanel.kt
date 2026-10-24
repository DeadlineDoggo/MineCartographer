package minecartographer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import minecartographer.core.BlockState
import minecartographer.core.WorldSource
import minecartographer.core.anvil.BlockColors
import minecartographer.core.convert.Conversion
import minecartographer.core.convert.ModdedRemap
import minecartographer.core.convert.Upgrade
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import minecartographer.editor.EditorState

/** How many of a layer's chunks are read to find the blocks it uses (spread over the layer). */
private const val SAMPLE_CHUNKS = 400

/** A block of a layer's chunks that the world they go into doesn't have, and how often it was seen. */
private class Foreign(val world: WorldSource, val state: BlockState, val key: String, val chunks: Int)

/**
 * The block mapping of a layer, laid over the map's right side: every block its chunks use that the world they go
 * into doesn't have (modded blocks of other mods or another modpack), what each becomes on export (and on the map),
 * why, and a list to choose another. Automatic guesses by color alone are marked.
 */
@Composable
fun BoxScope.BlockMappingDrawer(state: EditorState) {
    val stack = state.activeStack ?: return
    val layer = state.blockMapLayer?.let(stack::layer) ?: return
    val shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = 84.dp, bottom = 84.dp).width(460.dp).fillMaxHeight().overMap(state)
            .clip(shape).background(EditorTheme.panel).border(1.dp, EditorTheme.border, shape)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("BLOCK MAPPING · ${layer.name.uppercase()}", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Close", size = 24.dp, iconSize = 10.dp) { state.blockMapLayer = null }
        }
        HDivider()
        val chunkSet = when (layer) {
            is ChunkLayer -> layer.chunks
            else -> (layer as minecartographer.core.edit.BaseLayer).overrides.mapNotNull { (p, e) -> (e as? ChunkEdit.Place)?.let { p to it.source } }.toMap()
        }
        val found by produceState<List<Foreign>?>(null, chunkSet, stack.world) {
            value = withContext(Dispatchers.IO) { scan(state, stack, chunkSet.values) }
        }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Txt("Blocks of these chunks that \"${stack.world.name}\" (${stack.world.versionLabel}) doesn't have, and what they become. " +
                "Click one to choose another.", color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
            if (Upgrade.tables == null) Txt("No Minecraft 1.13+ jar found: old blocks are converted by how they look only.",
                Modifier.padding(top = 4.dp), color = EditorTheme.warning, size = 11.sp, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val list = found
            when {
                list == null -> Txt("Reading the layer's chunks…", color = EditorTheme.textFaint, size = 12.sp)
                list.isEmpty() -> Txt("Nothing to map: every block exists in \"${stack.world.name}\".", color = EditorTheme.textFaint, size = 12.sp, maxLines = 2)
                else -> MappingList(state, stack, layer, list)
            }
        }
    }
}

/** Reads (a sample of) the chunks and collects the foreign blocks, most used first. */
private fun scan(state: EditorState, stack: LayerStack, refs: Collection<ChunkRef>): List<Foreign> {
    val counts = HashMap<Pair<WorldSource, BlockState>, Int>()
    for ((world, chunks) in refs.groupBy { it.world }) {
        val conversion = state.conversionFor(world, stack) ?: continue
        if (!conversion.remaps) continue
        val step = maxOf(1, chunks.size / SAMPLE_CHUNKS)
        for (i in chunks.indices step step) {
            val blocks = runCatching { world.loadBlocks(chunks[i].pos) }.getOrNull() ?: continue
            for (s in blocks.palette.toSet()) if (conversion.foreign(s)) {
                val key = world to (s.properties["meta"]?.let { BlockState.of(s.name, mapOf("meta" to it)) } ?: BlockState.of(s.name))
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    }
    return counts.entries.sortedByDescending { it.value }.map { (k, n) -> Foreign(k.first, k.second, Conversion.keyOf(k.second), n) }
}

@Composable
private fun MappingList(state: EditorState, stack: LayerStack, layer: Layer, list: List<Foreign>) {
    var open by remember { mutableStateOf<String?>(null) }
    val guessed = list.count { f -> state.conversionFor(f.world, stack, layer.blockMap)?.mapped(f.state)?.sure == false }
    if (guessed > 0) Txt("⚠ $guessed of ${list.size} are guesses by color; check them.", Modifier.padding(bottom = 6.dp),
        color = EditorTheme.warning, size = 12.sp)
    val scroll = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.world.id + it.key }) { f ->
                val conversion = state.conversionFor(f.world, stack, layer.blockMap) ?: return@items
                val mapped = conversion.mapped(f.state)
                val chosen = f.key in layer.blockMap
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(if (open == f.key) EditorTheme.panelRaised else Color.Transparent)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == f.key) null else f.key }.padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Swatch(BlockColors.look(f.state).color)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(f.key, size = 12.sp, mono = true)
                            Txt("${f.chunks} chunks", color = EditorTheme.textFaint, size = 10.sp)
                        }
                        Txt("→", Modifier.padding(horizontal = 6.dp), color = EditorTheme.textDim, size = 12.sp)
                        Swatch(BlockColors.look(mapped.target).color)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(mapped.target.name.removePrefix("minecraft:"), size = 12.sp, mono = true)
                            Txt(if (chosen) "chosen by you" else mapped.reason, color = if (mapped.sure || chosen) EditorTheme.textFaint else EditorTheme.warning, size = 10.sp)
                        }
                    }
                    if (open == f.key) Picker(chosen, onPick = { target ->
                        state.mapBlock(layer.id, f.key, target?.let(Conversion::format))
                        open = null
                    })
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Blocks to choose from: vanilla blocks of the tables (with their default state), or a short list without them. */
private val allChoices: List<BlockState> by lazy {
    val fromTables = Upgrade.tables?.blocks?.values?.distinctBy { it.name }?.sortedBy { it.name }.orEmpty()
    (ModdedRemap.choices + fromTables).distinctBy { it.name }
}

@Composable
private fun Picker(chosen: Boolean, onPick: (BlockState?) -> Unit) {
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextInput(filter, { filter = it }, Modifier.weight(1f))
            TextButton("Automatic", enabled = chosen) { onPick(null) }
        }
        val matches = allChoices.filter { filter.isBlank() || filter.lowercase() in it.name }.take(80)
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp)) {
            val inner = rememberLazyListState()
            Box {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(end = 10.dp), inner) {
                    items(matches, key = { it.name }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { onPick(s) }.padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Swatch(BlockColors.look(s).color)
                            Spacer(Modifier.width(6.dp))
                            Txt(s.name.removePrefix("minecraft:"), size = 12.sp, mono = true)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(inner), Modifier.align(Alignment.CenterEnd).heightIn(max = 220.dp))
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int) = Box(
    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(argb or (0xFF shl 24))).border(1.dp, EditorTheme.border, RoundedCornerShape(3.dp))
)
