package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import minecartographer.core.BlockState
import minecartographer.core.ChunkPos
import minecartographer.core.WorldSource
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.convert.Conversion
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import minecartographer.editor.AnalyzeRequest
import minecartographer.editor.AnalyzeScope
import minecartographer.editor.EditorState
import minecartographer.editor.SelectMode
import minecartographer.textures.BlockModels
import kotlin.io.path.writeText

private val MIN_WIDTH = 420.dp
private val MAX_WIDTH = 1400.dp

/**
 * One kind of block in the analyzed chunks: from which world and layer (mapping is per layer), how many, in how many
 * chunks, and which ones ([positions] only once every chunk is read).
 */
private class Found(val world: WorldSource, val layer: Layer, val state: BlockState, val key: String, val blocks: Long, val chunks: Int,
    val positions: Set<ChunkPos>)

/** The selection counted so far: [done] of [total] chunks read. */
private class Analysis(val found: List<Found>, val done: Int, val total: Int)

private enum class Sort(val label: String) { Count("Most"), Name("Name"), Mod("Mod") }

private enum class Show(val label: String) {
    All("All"), Modded("Modded"), Problems("Problems"), Mapped("Mapped"), Vanilla("Vanilla")
}

/**
 * Analyze, laid over the map next to the left dock like Mods & textures: every block in the chunks asked for (the
 * selection or a layer; all of them, not only what shows from above), how many of each, which are modded, which have no
 * texture, and what the ones the world doesn't have become. A block opens its details: choose its texture, or what it
 * is mapped to. The chunks are read once when asked for, not again on every selection change.
 */
@Composable
fun BoxScope.AnalyzeDrawer(state: EditorState) {
    if (!state.analyzeVisible) {
        AnalyzeTab(state)
        return
    }
    val stack = state.activeStack ?: return
    Row(
        Modifier.align(Alignment.TopStart).fillMaxHeight().overMap(state)
            // Clicks and drags on the panel must not reach the map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        AnalyzePanel(state, stack, Modifier.width(state.analyzeWidth).fillMaxHeight())
        Splitter { state.analyzeWidth = (state.analyzeWidth + it).coerceIn(MIN_WIDTH, MAX_WIDTH) }
    }
}

/** The folded panel: a tab on the map's edge under the one of Mods & textures. */
@Composable
private fun BoxScope.AnalyzeTab(state: EditorState) {
    val request = state.analyzeRequest ?: return
    // The Mods & textures panel covers the edge while it is open.
    if (state.modsPanelVisible) return
    val world = state.activeWorld as? AnvilWorld
    val modsTab = world != null && state.hasMods(world)
    val shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    Row(
        Modifier.align(Alignment.TopStart).padding(top = if (modsTab) 96.dp else 52.dp).overMap(state).clip(shape)
            .background(EditorTheme.panelRaised).border(1.dp, EditorTheme.border, shape)
            .clickable { state.analyzeVisible = true }.padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("▸ Analyze · ${request.positions.size} chunks", size = 12.sp)
        Spacer(Modifier.width(4.dp))
        IconButton(Icons.Close, "Close the analysis", size = 24.dp, iconSize = 9.dp) { state.dismissAnalyze() }
    }
}

/** The last finished analysis, kept while the panel is folded so that opening it again doesn't read the chunks again. */
private var finished: Triple<AnalyzeRequest, WorldSource, Analysis>? = null

@Composable
private fun AnalyzePanel(state: EditorState, stack: LayerStack, modifier: Modifier) {
    val request = state.analyzeRequest
    // Read again only for a new request or Rescan; mapping changes don't need the chunks again.
    val kept = finished?.takeIf { it.first == request && it.second == stack.world }?.third
    val analysis by produceState(kept ?: Analysis(emptyList(), 0, request?.positions?.size ?: 0), request, stack.world) {
        if (request != null && kept == null) analyze(stack, request) {
            value = it
            if (it.done == it.total) finished = Triple(request, stack.world, it)
        }
    }
    Column(modifier.background(EditorTheme.panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = request?.let { "ANALYZE ${it.what.uppercase()} · ${it.positions.size} CHUNKS" } ?: "ANALYZE"
            Txt(title, Modifier.weight(1f), color = EditorTheme.textDim, size = 11.sp, weight = FontWeight.SemiBold)
            // One button: the selection if it is not what was analyzed, else the same chunks again.
            val newSelection = request != null && state.selection.isNotEmpty() && state.selection != request.positions
            if (newSelection) WithTooltip("Analyze the ${state.selection.size} selected chunks now") {
                TextButton("Analyze selection (${state.selection.size})", primary = true) { state.analyzeSelection() }
            } else WithTooltip("Read the same chunks again") { TextButton("Rescan", enabled = request != null) { state.reanalyze() } }
            Spacer(Modifier.width(4.dp))
            IconButton(Icons.Close, "Fold the panel into a tab on the map's edge", size = 24.dp, iconSize = 10.dp) { state.analyzeVisible = false }
        }
        HDivider()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            if (request == null || request.positions.isEmpty()) {
                Txt("Select chunks on the map, then right-click them and choose Analyze selection.", color = EditorTheme.textDim, size = 12.sp, maxLines = 2)
                if (state.selection.isNotEmpty()) TextButton("Analyze the selection", Modifier.padding(top = 8.dp), primary = true) { state.analyzeSelection() }
                return@Column
            }
            Changed(state, request)
            WithTooltip("Selected: the chunks as the map shows them (the top layer of each, what the export writes). Layer: only the active layer in them. All layers: every layer, hidden ones too") {
                Segmented(AnalyzeScope.entries, request.scope, { scopeLabel(stack, request, it) }) { state.reanalyze(it) }
            }
            Spacer(Modifier.height(8.dp))
            Progress(analysis)
            Spacer(Modifier.height(8.dp))
            Content(state, stack, request, analysis)
        }
    }
}

private fun scopeLabel(stack: LayerStack, request: AnalyzeRequest, scope: AnalyzeScope): String =
    if (scope == AnalyzeScope.Layer) "Layer \"${(stack.layer(if (request.scope == scope) request.layerId else stack.activeId) ?: stack.active).name}\""
    else scope.label

/** A note with a button when the selection is not what the panel shows. */
@Composable
private fun Changed(state: EditorState, request: AnalyzeRequest) {
    val selection = state.selection
    if (selection.isEmpty() || selection == request.positions) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(4.dp)).background(EditorTheme.panelRaised)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(if (request.fromSelection) "The selection changed since this analysis." else "Showing ${request.what}, not the selection.",
            Modifier.weight(1f), color = EditorTheme.warning, size = 12.sp)
    }
}

/** A line with the progress while chunks are read, then a summary. */
@Composable
private fun Progress(a: Analysis) {
    val total = a.found.sumOf { it.blocks }
    if (a.done < a.total) {
        Column {
            Txt("Reading chunks… ${a.done} / ${a.total}", color = EditorTheme.textDim, size = 12.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(EditorTheme.panelRaised)) {
                Box(Modifier.fillMaxWidth(a.done / a.total.coerceAtLeast(1).toFloat()).height(4.dp).background(EditorTheme.accent))
            }
        }
    } else {
        Txt("${a.found.size} kinds of blocks, ${"%,d".format(total)} blocks in ${a.total} chunks", color = EditorTheme.textDim, size = 12.sp)
    }
}

/** Counts every block of the requested chunks off the UI thread, reporting as it goes. */
private suspend fun analyze(stack: LayerStack, request: AnalyzeRequest, report: (Analysis) -> Unit) {
    data class Key(val world: WorldSource, val layer: Layer, val state: BlockState)
    val positions = request.positions.toList()
    val blocks = HashMap<Key, Long>()
    val chunks = HashMap<Key, MutableSet<ChunkPos>>()
    // While reading, the chunk sets keep changing: only the last snapshot hands them out.
    fun snapshot(done: Int) = Analysis(blocks.entries.sortedByDescending { it.value }.map { (k, n) ->
        val where = chunks[k].orEmpty()
        Found(k.world, k.layer, k.state, Conversion.keyOf(k.state), n, where.size, if (done == positions.size) where else emptySet())
    }, done, positions.size)
    val chosen = stack.layer(request.layerId)
    var last = System.nanoTime()
    withContext(Dispatchers.IO) {
        for ((i, pos) in positions.withIndex()) {
            val layers = when (request.scope) {
                AnalyzeScope.Visible -> listOfNotNull(stack.layerAt(pos))
                AnalyzeScope.Layer -> listOfNotNull(chosen)
                AnalyzeScope.All -> stack.layers
            }
            for (layer in layers) {
                val ref = stack.contentOf(layer, pos) ?: continue
                val counts = runCatching { ref.world.countBlocks(ref.pos) }.getOrNull() ?: continue
                for ((s, n) in counts) {
                    // Variants of one block (facing, half...) count together; old modded blocks keep their data value.
                    val kind = s.properties["meta"]?.let { BlockState.of(s.name, mapOf("meta" to it)) } ?: BlockState.of(s.name)
                    val key = Key(ref.world, layer, kind)
                    blocks[key] = (blocks[key] ?: 0) + n
                    chunks.getOrPut(key) { HashSet() } += pos
                }
            }
            if (System.nanoTime() - last > 150_000_000) {
                last = System.nanoTime()
                val partial = snapshot(i + 1)
                withContext(Dispatchers.Main) { report(partial) }
            }
        }
    }
    report(snapshot(positions.size))
}

/** What the panel says about a block: its texture and, for blocks the world doesn't have, what it becomes. */
private class Status(val modded: Boolean, val info: BlockModels.BlockInfo?, val mapped: minecartographer.core.convert.ModdedRemap.Mapped?) {
    val problem: Boolean get() = (mapped != null && !mapped.sure) || (modded && mapped == null &&
        (info == null || info.source == BlockModels.Source.UNKNOWN))
}

@Composable
private fun Content(state: EditorState, stack: LayerStack, request: AnalyzeRequest, analysis: Analysis) {
    // Textures arrive as mods load; refresh now and then.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val statuses by produceState(emptyMap<Found, Status>(), analysis.found, tick, stack) {
        value = withContext(Dispatchers.IO) { analysis.found.associateWith { status(state, stack, it) } }
    }
    var show by remember { mutableStateOf(Show.All) }
    var sort by remember { mutableStateOf(Sort.Count) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Found?>(null) }
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Segmented(Show.entries, show, { s -> "${s.label} ${analysis.found.count { acceptsFor(s, statuses[it]) }}" }) { show = it }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextInput(query, { query = it }, Modifier.fillMaxWidth())
                    if (query.isEmpty()) Txt("Filter by name, mod or layer", Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                        color = EditorTheme.textFaint, size = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(150.dp)) { Segmented(Sort.entries, sort, { it.label }) { sort = it } }
            }
            Spacer(Modifier.height(6.dp))
            val shown = sorted(analysis.found.filter { acceptsFor(show, statuses[it]) && matches(it, statuses[it], query) }, sort)
            val scroll = rememberLazyListState()
            val thumbs = rememberThumbs()
            val targetModels = state.modelsOf(stack.world)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.world.id + "/" + it.layer.id + "/" + it.key }) { f ->
                        val s = statuses[f]
                        val models = state.modelsOf(f.world)
                        var menuAt by remember { mutableStateOf<IntOffset?>(null) }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(if (f === selected) EditorTheme.accentSoft else EditorTheme.panelRaised)
                                .clickable { selected = f }
                                .onRightClick(f) {
                                    selected = f
                                    menuAt = it
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            menuAt?.let { at -> ContextMenu(at, blockMenuEntries(state, stack, f)) { menuAt = null } }
                            StateThumb(thumbs(models), models, f.state, 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(f.key, size = 12.sp, mono = true)
                                // Every layer is listed apart: say which one.
                                val where = if (request.scope == AnalyzeScope.All) "${f.layer.name} · " else ""
                                Txt(where + describe(s), color = statusColor(s), size = 11.sp)
                            }
                            // What it becomes, at a glance; the details pick another.
                            s?.mapped?.let { mapped ->
                                Txt("→", Modifier.padding(horizontal = 6.dp), color = EditorTheme.textDim, size = 14.sp)
                                StateThumb(thumbs(targetModels), targetModels, mapped.target, 28.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Txt("%,d".format(f.blocks), size = 12.sp, mono = true)
                                Txt("${f.chunks} chunks", color = EditorTheme.textFaint, size = 10.sp)
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("${shown.size} shown · right-click a block to select its chunks", Modifier.weight(1f), color = EditorTheme.textFaint, size = 11.sp)
                WithTooltip("Save the blocks shown, with counts, textures and mapping, as a spreadsheet") {
                    TextButton("Export CSV…", enabled = shown.isNotEmpty() && analysis.done == analysis.total) {
                        exportCsv(state, stack, shown, statuses)
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val f = selected
            if (f == null) Txt("Pick a block to choose its texture or what it becomes in \"${stack.world.name}\".",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
            else Detail(state, stack, f, statuses[f])
        }
    }
    // Side by side when the panel is wide, one above the other when it is narrow.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        if (width > 860.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            list(Modifier.width(width * 0.5f).fillMaxHeight())
            detail(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list(Modifier.weight(1f).fillMaxWidth())
            HDivider()
            detail(Modifier.weight(1.2f).fillMaxWidth())
        }
    }
}

private fun matches(f: Found, s: Status?, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || f.key.contains(q, ignoreCase = true) || f.layer.name.contains(q, ignoreCase = true) ||
        s?.mapped?.target?.name?.contains(q, ignoreCase = true) == true
}

/** The analysis comes sorted by count; by name ignores the mod, by mod keeps the biggest first within each. */
private fun sorted(found: List<Found>, sort: Sort): List<Found> = when (sort) {
    Sort.Count -> found
    Sort.Name -> found.sortedBy { it.key.substringAfter(':') }
    Sort.Mod -> found.sortedWith(compareBy<Found> { it.key.substringBefore(':') }.thenByDescending { it.blocks })
}

/** The right-click menu of a block in the list. */
private fun blockMenuEntries(state: EditorState, stack: LayerStack, f: Found): List<MenuEntry> {
    // The chunks are known once every chunk is read.
    val ready = f.positions.isNotEmpty()
    val chosen = f.key in layerOf(stack, f).blockMap
    return listOf(
        MenuItem("Select the ${f.chunks} chunks with this block", enabled = ready) { state.applySelection(f.positions, SelectMode.Replace) },
        MenuItem("Add them to the selection", enabled = ready) { state.applySelection(f.positions, SelectMode.Add) },
        MenuItem("Remove them from the selection", enabled = ready) { state.applySelection(f.positions, SelectMode.Subtract) },
        MenuSeparator,
        MenuItem("Back to automatic mapping", enabled = chosen) { state.mapBlock(f.layer.id, f.key, null) },
        MenuItem("Copy name") { state.copyText(f.key) },
    )
}

/** Saves [found] as a CSV file the user picks: one row per block with its counts, texture and mapping. */
private fun exportCsv(state: EditorState, stack: LayerStack, found: List<Found>, statuses: Map<Found, Status>) {
    val file = FileDialogs.chooseCsvToSave("blocks in ${stack.world.name}") ?: return
    fun cell(value: Any): String {
        val text = value.toString()
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
    val rows = buildList {
        add(listOf("block", "world", "layer", "blocks", "chunks", "kind", "texture", "becomes", "note"))
        for (f in found) {
            val s = statuses[f]
            add(listOf(
                f.key, f.world.name, f.layer.name, f.blocks, f.chunks,
                when { s == null -> ""; s.modded -> "modded"; else -> "vanilla" },
                s?.info?.let { it.texture ?: it.detail }.orEmpty(),
                s?.mapped?.let { Conversion.format(it.target) }.orEmpty(),
                s?.mapped?.reason ?: s?.info?.source?.label.orEmpty(),
            ))
        }
    }
    runCatching { file.writeText(rows.joinToString("\n") { row -> row.joinToString(",") { cell(it) } } + "\n") }
        .onSuccess { state.notify("Saved ${found.size} blocks to ${file.fileName}") }
        .onFailure { state.notify("Could not save ${file.fileName}: ${it.message}", warning = true) }
}

private fun acceptsFor(show: Show, s: Status?) = when (show) {
    Show.All -> true
    Show.Modded -> s?.modded == true
    Show.Problems -> s?.problem == true
    Show.Mapped -> s?.mapped != null
    Show.Vanilla -> s?.modded == false
}

private fun status(state: EditorState, stack: LayerStack, f: Found): Status {
    val ns = f.state.name.substringBefore(':')
    val modded = ns != "minecraft" && ns != "minecartographer"
    val info = if (!modded) null else state.modelsOf(f.world)?.let { runCatching { it.info(f.state) }.getOrNull() }
    val conversion = state.conversionFor(f.world, stack, layerOf(stack, f).blockMap)
    val mapped = conversion?.takeIf { it.foreign(f.state) }?.mapped(f.state)
    return Status(modded, info, mapped)
}

private fun describe(s: Status?): String = when {
    s == null -> "…"
    s.mapped != null -> "becomes ${s.mapped.target.name.removePrefix("minecraft:")} · ${s.mapped.reason}"
    !s.modded -> "vanilla"
    s.info == null -> "no textures loaded for this world"
    else -> "${s.info.source.label}: ${s.info.texture ?: s.info.detail}"
}

private fun statusColor(s: Status?): Color = when {
    s == null -> EditorTheme.textFaint
    s.mapped != null -> if (s.mapped.sure) EditorTheme.accent else EditorTheme.warning
    !s.modded -> EditorTheme.textFaint
    s.info == null || s.info.source == BlockModels.Source.UNKNOWN -> EditorTheme.warning
    s.info.source == BlockModels.Source.GUESS -> EditorTheme.edited
    s.info.source == BlockModels.Source.CHOICE -> EditorTheme.accent
    else -> EditorTheme.paste
}

/** The layer as it is now (its block mapping changes while the panel is open). */
private fun layerOf(stack: LayerStack, f: Found): Layer = stack.layer(f.layer.id) ?: f.layer

private enum class Tab(val label: String) { Texture("Texture"), Mapping("Becomes") }

/** A block's details: its texture (modded blocks with textures loaded) and what it becomes (blocks the world lacks). */
@Composable
private fun Detail(state: EditorState, stack: LayerStack, f: Found, s: Status?) {
    val world = f.world as? AnvilWorld
    val models = state.modelsOf(f.world)
    val tabs = buildList {
        if (s?.modded == true && models != null && world != null) add(Tab.Texture)
        if (s?.mapped != null) add(Tab.Mapping)
    }
    var tab by remember(f) { mutableStateOf(tabs.lastOrNull()) }
    if (tab !in tabs) tab = tabs.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        Txt("${f.world.name} · layer \"${f.layer.name}\" · ${"%,d".format(f.blocks)} blocks", color = EditorTheme.textFaint, size = 11.sp)
        Spacer(Modifier.height(6.dp))
        if (tabs.isEmpty()) {
            Txt(if (s?.modded == false) "A vanilla block: it is drawn and exported as it is." else "Load textures for this world (Textured mode) to choose this block's texture.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 3)
            return@Column
        }
        if (tabs.size > 1) {
            Segmented(tabs, tab ?: tabs.first(), { it.label }) { tab = it }
            Spacer(Modifier.height(8.dp))
        }
        when (tab) {
            Tab.Texture -> BlockDetail(state, world!!, models!!, rememberThumbs()(models)!!, f.key, s?.info)
            Tab.Mapping -> MappingDetail(state, stack, f, s!!.mapped!!)
            null -> {}
        }
    }
}

@Composable
private fun MappingDetail(state: EditorState, stack: LayerStack, f: Found, mapped: minecartographer.core.convert.ModdedRemap.Mapped) {
    val thumbs = rememberThumbs()
    val sourceModels = state.modelsOf(f.world)
    val targetModels = state.modelsOf(stack.world)
    val layer = layerOf(stack, f)
    val chosen = f.key in layer.blockMap
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateThumb(thumbs(sourceModels), sourceModels, f.state, 56.dp)
            Txt("→", Modifier.padding(horizontal = 10.dp), color = EditorTheme.textDim, size = 16.sp)
            StateThumb(thumbs(targetModels), targetModels, mapped.target, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(f.key, size = 12.sp, mono = true, weight = FontWeight.SemiBold, maxLines = 2)
                Txt("becomes ${mapped.target.name.removePrefix("minecraft:")} in \"${stack.world.name}\"", size = 12.sp, maxLines = 2)
                Txt(if (chosen) "chosen by you" else mapped.reason, color = if (chosen || mapped.sure) EditorTheme.textFaint else EditorTheme.warning, size = 11.sp, maxLines = 2)
            }
        }
        if (chosen) TextButton("Back to automatic", Modifier.padding(top = 8.dp)) { state.mapBlock(f.layer.id, f.key, null) }
        Spacer(Modifier.height(8.dp))
        BlockGrid(targetModels, mapped.target, Modifier.weight(1f).fillMaxWidth()) { target ->
            state.mapBlock(f.layer.id, f.key, Conversion.format(target))
        }
    }
}
