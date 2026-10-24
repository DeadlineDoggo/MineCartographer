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
import androidx.compose.ui.unit.Dp
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
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import minecartographer.editor.EditorState
import minecartographer.textures.BlockModels

/** How many of a layer's chunks are read to find the blocks it uses (spread over the layer). */
private const val SAMPLE_CHUNKS = 400
private val MAPPING_WIDTH = 460.dp
private val PICKER_WIDTH = 440.dp

/** A block of a layer's chunks that the world they go into doesn't have, and in how many chunks it was seen. */
private class Foreign(val world: WorldSource, val state: BlockState, val key: String, val chunks: Int)

/**
 * The block mapping of a layer, a full-height panel on the map's right side: every block its chunks use that the
 * world they go into doesn't have (modded blocks of other mods or another modpack), what each becomes on export (and
 * on the map) and why. Clicking one opens a second panel beside it to pick another block, like the texture picker of
 * Mods & textures. Automatic guesses by color alone are marked.
 */
@Composable
fun BoxScope.BlockMappingDrawer(state: EditorState) {
    val stack = state.activeStack ?: return
    val layer = state.blockMapLayer?.let(stack::layer) ?: return
    val picked = state.blockPickerKey
    Row(
        Modifier.align(Alignment.TopEnd).fillMaxHeight().overMap(state)
            // Clicks and drags on the panels must not reach the map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        if (picked != null) BlockPicker(state, stack, layer, picked, Modifier.width(PICKER_WIDTH).fillMaxHeight())
        MappingPanel(state, stack, layer, Modifier.width(MAPPING_WIDTH).fillMaxHeight())
    }
}

@Composable
private fun MappingPanel(state: EditorState, stack: LayerStack, layer: Layer, modifier: Modifier) {
    val chunkSet = chunksOf(layer)
    val found by produceState<List<Foreign>?>(null, chunkSet, stack.world) {
        value = withContext(Dispatchers.IO) { scan(state, stack, chunkSet.values) }
    }
    Column(modifier.background(EditorTheme.panel).border(1.dp, EditorTheme.border)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("BLOCK MAPPING · ${layer.name.uppercase()}", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Close", size = 24.dp, iconSize = 10.dp) { state.closeBlockMapping() }
        }
        HDivider()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Txt("Blocks of these chunks that \"${stack.world.name}\" (${stack.world.versionLabel}) doesn't have, and what they become. " +
                "Click one to choose another block.", color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
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

private fun chunksOf(layer: Layer): Map<minecartographer.core.ChunkPos, ChunkRef> = when (layer) {
    is ChunkLayer -> layer.chunks
    is BaseLayer -> layer.overrides.mapNotNull { (p, e) -> (e as? ChunkEdit.Place)?.let { p to it.source } }.toMap()
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

/** Texture bitmaps per block models, so the lists don't decode a texture twice. */
@Composable
internal fun rememberThumbs(): (BlockModels?) -> ThumbCache? {
    val caches = remember { HashMap<BlockModels, ThumbCache>() }
    return { models -> models?.let { caches.getOrPut(it) { ThumbCache(it) } } }
}

@Composable
private fun MappingList(state: EditorState, stack: LayerStack, layer: Layer, list: List<Foreign>) {
    val thumbs = rememberThumbs()
    val targetModels = state.modelsOf(stack.world)
    val guessed = list.count { f -> state.conversionFor(f.world, stack, layer.blockMap)?.mapped(f.state)?.sure == false }
    if (guessed > 0) Txt("⚠ $guessed of ${list.size} are guesses by color; check them.", Modifier.padding(bottom = 6.dp),
        color = EditorTheme.warning, size = 12.sp)
    val scroll = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), scroll, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(list, key = { it.world.id + it.key }) { f ->
                val conversion = state.conversionFor(f.world, stack, layer.blockMap) ?: return@items
                val mapped = conversion.mapped(f.state)
                val chosen = f.key in layer.blockMap
                val open = state.blockPickerKey == f.key
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                        .background(if (open) EditorTheme.accentSoft else EditorTheme.panelRaised)
                        .clickable { state.blockPickerKey = if (open) null else f.key }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateThumb(thumbs(state.modelsOf(f.world)), state.modelsOf(f.world), f.state, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(f.key, size = 12.sp, mono = true)
                        Txt("${f.chunks} chunks", color = EditorTheme.textFaint, size = 10.sp)
                    }
                    Txt("→", Modifier.padding(horizontal = 8.dp), color = EditorTheme.textDim, size = 13.sp)
                    StateThumb(thumbs(targetModels), targetModels, mapped.target, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(mapped.target.name.removePrefix("minecraft:"), size = 12.sp, mono = true)
                        Txt(if (chosen) "chosen by you" else mapped.reason, color = if (mapped.sure || chosen) EditorTheme.textFaint else EditorTheme.warning, size = 10.sp)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** The second panel: the target world's blocks to choose from, with their textures, for the block picked in the list. */
@Composable
private fun BlockPicker(state: EditorState, stack: LayerStack, layer: Layer, key: String, modifier: Modifier) {
    val thumbs = rememberThumbs()
    val models = state.modelsOf(stack.world)
    val cache = thumbs(models)
    val source = BlockColors.stateOf(key)
    val sourceWorld = layer.let { sourceWorldsOf(state, it) }.firstOrNull { state.conversionFor(it, stack)?.foreign(source) == true }
    val conversion = sourceWorld?.let { state.conversionFor(it, stack, layer.blockMap) }
    val current = conversion?.mapped(source)
    val chosen = key in layer.blockMap
    Column(modifier.background(EditorTheme.panel).border(1.dp, EditorTheme.border)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("CHOOSE A BLOCK", Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            IconButton(Icons.Close, "Close", size = 24.dp, iconSize = 10.dp) { state.blockPickerKey = null }
        }
        HDivider()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateThumb(thumbs(sourceWorld?.let { state.modelsOf(it) }), sourceWorld?.let { state.modelsOf(it) }, source, 56.dp)
                Txt("→", Modifier.padding(horizontal = 10.dp), color = EditorTheme.textDim, size = 16.sp)
                StateThumb(cache, models, current?.target ?: BlockState.AIR, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(key, size = 12.sp, mono = true, weight = FontWeight.SemiBold, maxLines = 2)
                    Txt("becomes ${current?.target?.name?.removePrefix("minecraft:") ?: "–"}", size = 12.sp, mono = true, maxLines = 1)
                    Txt(if (chosen) "chosen by you" else current?.reason ?: "", color = if (chosen || current?.sure != false) EditorTheme.textFaint else EditorTheme.warning, size = 11.sp, maxLines = 2)
                }
            }
            if (chosen) TextButton("Back to automatic", Modifier.padding(top = 8.dp)) { state.mapBlock(layer.id, key, null) }
            Spacer(Modifier.height(8.dp))
            BlockGrid(models, current?.target, Modifier.weight(1f).fillMaxWidth()) { target ->
                state.mapBlock(layer.id, key, Conversion.format(target))
            }
        }
    }
}

/**
 * The blocks of the world [models] draws (its blockstates), with their textures and a search field;
 * clicking one picks it. [current] is marked.
 */
@Composable
internal fun BlockGrid(models: BlockModels?, current: BlockState?, modifier: Modifier, onPick: (BlockState) -> Unit) {
    val thumbs = rememberThumbs()
    val cache = thumbs(models)
    var filter by remember { mutableStateOf("") }
    val blocks = remember(models, Upgrade.tables) { vanillaBlocks(models) }
    val shown = blocks.filter { filter.isBlank() || filter.lowercase().trim() in it }
    Column(modifier) {
        TextInput(filter, { filter = it }, Modifier.fillMaxWidth())
        Txt("${shown.size} blocks · click one to use it", Modifier.padding(vertical = 4.dp), color = EditorTheme.textFaint, size = 11.sp)
        val grid = rememberLazyGridState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxSize().padding(end = 10.dp), grid) {
                items(shown, key = { it }) { name ->
                    val target = BlockState.of(name)
                    val isCurrent = target.name == current?.name
                    WithTooltip(name) {
                        Column(
                            Modifier.padding(3.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (isCurrent) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                .border(1.dp, if (isCurrent) EditorTheme.accent else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable { onPick(target) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            StateThumb(cache, models, target, 48.dp)
                            Txt(name.removePrefix("minecraft:"), color = EditorTheme.textDim, size = 9.sp)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun sourceWorldsOf(state: EditorState, layer: Layer): Set<WorldSource> = state.sourceWorldsOf(layer)

/** Names of the target world's blocks (`minecraft:oak_planks`), from its blockstates, else from Minecraft's upgrade tables. */
private fun vanillaBlocks(models: BlockModels?): List<String> {
    val states = Regex("""assets/minecraft/blockstates/([^/]+)\.json""")
    val fromAssets = models?.assets?.index?.keys?.mapNotNull { states.matchEntire(it)?.groupValues?.get(1) }.orEmpty()
    val names = fromAssets.ifEmpty {
        Upgrade.tables?.blocks?.values?.map { it.name.removePrefix("minecraft:") }.orEmpty() + ModdedRemap.choices.map { it.name.removePrefix("minecraft:") }
    }
    return (names + "air").distinct().sorted().map { "minecraft:$it" }
}

/** A block's texture in [models]' world, or its map color with a "?" when it has none. */
@Composable
internal fun StateThumb(cache: ThumbCache?, models: BlockModels?, state: BlockState, size: Dp) {
    val texture by produceState<String?>(null, models, state) {
        value = if (models == null) null else withContext(Dispatchers.IO) { runCatching { models.info(state).texture }.getOrNull() }
    }
    if (cache != null && texture != null) Thumb(cache, texture, null, size)
    else {
        val shape = RoundedCornerShape(3.dp)
        val look = BlockColors.look(state)
        Box(Modifier.size(size).clip(shape).border(1.dp, EditorTheme.border, shape).background(Color(look.color or (0xFF shl 24)).copy(alpha = if (look.color == 0) 0f else 0.7f)),
            contentAlignment = Alignment.Center) {
            if (state.isAir) Txt("∅", color = EditorTheme.textDim, size = 12.sp)
        }
    }
}
