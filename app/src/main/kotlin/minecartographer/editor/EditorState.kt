package minecartographer.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minecartographer.core.ChunkPos
import minecartographer.core.Rotation
import minecartographer.core.WorldSource
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.WorldExporter
import minecartographer.core.anvil.WorldInfo
import java.nio.file.Path
import kotlin.math.sqrt
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkClipboard
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import minecartographer.core.edit.UndoHistory
import minecartographer.viewport.ChunkTileCache
import minecartographer.viewport.IsoView
import minecartographer.textures.AssetSource
import minecartographer.textures.BlockModels
import minecartographer.textures.TextureFiles
import minecartographer.viewport.RenderMode
import minecartographer.viewport.RenderSettings

/** Short message shown over the viewport; [id] makes repeated texts re-trigger the timer. */
data class Notice(val text: String, val warning: Boolean = false, val id: Long = System.nanoTime())

/**
 * What the loading layer shows; [progress] null means indeterminate. Steps with [waitForTiles]
 * end on their own once the viewport has rendered every visible chunk; the others are ended
 * by the background task that started them.
 */
data class LoadingStep(
    val title: String,
    val detail: String = "",
    val progress: Float? = null,
    val waitForTiles: Boolean = true,
)

/** One undoable step: the whole layer stack of a world before and after. */
data class StackEdit(val world: WorldSource, val label: String, val before: LayerStack, val after: LayerStack)

/** All editor UI state. Mutated only from the UI thread, except the tile cache internals. */
class EditorState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tiles = ChunkTileCache(scope)

    // ---- Worlds and layers ----

    val worlds = mutableStateListOf<WorldSource>()
    private val stacks = mutableStateMapOf<String, LayerStack>()
    var activeWorld by mutableStateOf<WorldSource?>(null)
        private set
    val activeStack: LayerStack? get() = activeWorld?.let { stacks[it.id] }
    fun stackOf(world: WorldSource): LayerStack? = stacks[world.id]

    private val history = UndoHistory<StackEdit>()
    private var historyRevision by mutableIntStateOf(0)

    /** Stack at the start of a drag (eraser stroke, layer move), committed as one undo step on release. */
    private var strokeStart: LayerStack? = null
    private var strokeLabel = ""

    // ---- View and tools ----

    var view by mutableStateOf(IsoView())
    /** Quarter turns of the view (0..3). The camera works in rotated coordinates; layers never rotate. */
    var rotation by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf(Tool.BrushSelect)
    var selectMode by mutableStateOf(SelectMode.Replace)
    var brushSize by mutableIntStateOf(1)
    var hovered by mutableStateOf<ChunkPos?>(null)
    var selection by mutableStateOf<Set<ChunkPos>>(emptySet())
        private set
    /** Rectangle being dragged with the rectangle select tool (corner chunks). */
    var rectDrag by mutableStateOf<Pair<ChunkPos, ChunkPos>?>(null)

    var clipboard by mutableStateOf<ChunkClipboard?>(null)
        private set
    var pasteMode by mutableStateOf(false)
        private set
    /** Where the clipboard's anchor would land if pasted now (follows the cursor). */
    var pasteAnchor by mutableStateOf<ChunkPos?>(null)

    var notice by mutableStateOf<Notice?>(null)

    // ---- Loading layer ----

    /** Current loading step, or null when the editor is ready. */
    var loading by mutableStateOf<LoadingStep?>(LoadingStep("Starting editor"))
    /** True until the first world has finished rendering; the loading layer then covers the whole window. */
    var startingUp by mutableStateOf(true)
        private set
    /** Written by the viewport after every frame (plain fields: read by polling, never observed). */
    var frameCount = 0L
    var frameTilesTotal = 0
    var frameTilesMissing = 0
    private var loadingSinceFrame = 0L

    /** Stops waiting for rendering; chunks keep appearing in the background. */
    fun skipLoading() {
        if (loading?.waitForTiles == true) {
            loading = null
            startingUp = false
        }
    }

    fun beginLoading(title: String, detail: String = "") {
        loading = LoadingStep(title, detail)
        loadingSinceFrame = frameCount
    }

    /**
     * Called periodically by the loading layer: turns the viewport's frame stats into progress
     * and finishes once every visible chunk has been rendered.
     */
    fun pollLoading() {
        val step = loading ?: return
        if (!step.waitForTiles) return
        if (frameCount <= loadingSinceFrame) return // no frame drawn since the step began
        val total = frameTilesTotal
        val done = total - frameTilesMissing
        if (frameTilesMissing == 0) {
            loading = null
            startingUp = false
        } else {
            loading = step.copy(detail = "$done / $total chunks", progress = done.toFloat() / total)
        }
    }

    // ---- Docks ----

    var leftDockVisible by mutableStateOf(true)
    var leftTab by mutableStateOf(DockTab.ToolOptions)
    var rightDockVisible by mutableStateOf(true)
    var inspectorVisible by mutableStateOf(true)
    var leftDockWidth by mutableStateOf(260.dp)
    var rightDockWidth by mutableStateOf(280.dp)
    /** True while a text field (layer rename) has focus, so typing doesn't trigger shortcuts. */
    var textEditing by mutableStateOf(false)

    var renderSettings by mutableStateOf(RenderSettings())
        private set
    /** Tint chunks by their layer's color and show erased chunks as red ghosts. */
    var showLayerColors by mutableStateOf(true)

    /** Keys currently held, for continuous WASD movement in the viewport. */
    val heldKeys = HashSet<Key>()

    /** Last known viewport size in pixels; written by the viewport, not observed. */
    var viewportSize: IntSize = IntSize.Zero
        set(value) {
            val changed = value != field
            field = value
            // Refit while the camera is still where the last fit put it (window layout settling, dock resizes).
            if (value.width > 0 && (fitPending || (changed && view == lastFitView))) fitToWorld()
        }
    private var fitPending = false
    private var lastFitView: IsoView? = null

    fun addWorld(world: WorldSource) {
        if (worlds.none { it.id == world.id }) {
            worlds += world
            stacks[world.id] = LayerStack.of(world)
        }
        if (activeWorld == null) openWorld(world)
    }

    fun openWorld(world: WorldSource) {
        if (world == activeWorld) return
        activeWorld = world
        selection = emptySet()
        hovered = null
        pasteAnchor = null
        beginLoading("Rendering ${world.name}")
        fitToWorld()
    }

    // ---- Opening, closing and exporting worlds ----

    /** Saves found in launcher folders and next to recently opened worlds. */
    var foundWorlds by mutableStateOf<List<WorldInfo>>(emptyList())
        private set
    /** True while a background task (opening or exporting a world) runs; only one at a time. */
    var busy by mutableStateOf(false)
        private set
    var exportDialogVisible by mutableStateOf(false)

    fun refreshFoundWorlds() {
        scope.launch(Dispatchers.IO) {
            val found = WorldLibrary.findWorlds()
            withContext(Dispatchers.Main) { foundWorlds = found }
        }
    }

    fun isOpen(folder: Path) = worlds.any { (it as? AnvilWorld)?.folder?.toAbsolutePath()?.normalize() == folder.toAbsolutePath().normalize() }

    /** Opens a world save folder in the background, showing progress in the loading layer. */
    fun openWorldFolder(folder: Path) {
        val path = folder.toAbsolutePath().normalize()
        worlds.firstOrNull { (it as? AnvilWorld)?.folder?.toAbsolutePath()?.normalize() == path }?.let { return openWorld(it) }
        if (busy) return notify("Wait for the current task to finish", warning = true)
        busy = true
        val title = "Opening ${path.fileName}"
        loading = LoadingStep(title, "Reading level.dat", waitForTiles = false)
        val throttle = ProgressThrottle()
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                AnvilWorld.open(path) { done, total ->
                    if (throttle.ready(done == total)) scope.launch(Dispatchers.Main) {
                        if (busy) loading = LoadingStep(title, "Scanning region files $done / $total", done.toFloat() / total, waitForTiles = false)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { world ->
                    WorldLibrary.remember(path)
                    addWorld(world)
                    openWorld(world)
                    if (world.chunkCount == 0) {
                        loading = null
                        notify("\"${world.name}\" has no overworld chunks yet", warning = true)
                    } else {
                        notify("Opened ${world.name} · ${world.versionLabel} · ${world.chunkCount} chunks")
                    }
                }.onFailure {
                    loading = null
                    notify("Could not open ${path.fileName}: ${it.message}", warning = true)
                }
            }
        }
    }

    /** Closes a world unless other worlds' layers still show chunks from it. */
    fun closeWorld(world: WorldSource) {
        val usedBy = stacks.values.filter { it.world != world && it.usesWorld(world) }
        if (usedBy.isNotEmpty()) {
            return notify("Layers in ${usedBy.joinToString { "\"${it.world.name}\"" }} use chunks from \"${world.name}\". Delete or merge those first.", warning = true)
        }
        if (clipboard?.sourceWorlds?.contains(world) == true) {
            clipboard = null
            pasteMode = false
        }
        history.removeIf { it.world == world }
        historyRevision++
        worlds.remove(world)
        stacks.remove(world.id)
        (world as? AnvilWorld)?.close()
        if (activeWorld == world) {
            activeWorld = null
            selection = emptySet()
            hovered = null
            worlds.firstOrNull()?.let(::openWorld)
        }
    }

    /** Problems that block exporting the active world (empty when it can be exported). */
    fun exportProblems(): List<String> = activeStack?.let(WorldExporter::validate) ?: listOf("No world open")

    /** Writes the active world's visible layers as a new world folder. */
    fun exportActive(output: Path, levelName: String) {
        val stack = activeStack ?: return
        if (busy) return notify("Wait for the current task to finish", warning = true)
        busy = true
        exportDialogVisible = false
        val title = "Exporting $levelName"
        loading = LoadingStep(title, "Preparing", waitForTiles = false)
        val throttle = ProgressThrottle()
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                WorldExporter.export(stack, output, levelName) { step, done, total ->
                    if (throttle.ready(done == total)) scope.launch(Dispatchers.Main) {
                        if (busy) loading = LoadingStep(title, "$step $done / $total", if (total > 0) done.toFloat() / total else null, waitForTiles = false)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                busy = false
                loading = null
                result.onSuccess { r ->
                    val extra = if (r.warnings.isEmpty()) "" else " (${r.warnings.size} warnings, see log)"
                    r.warnings.forEach(System.err::println)
                    notify("Exported ${r.chunks} chunks (${r.relocated} moved) to $output$extra")
                    WorldLibrary.remember(output)
                    refreshFoundWorlds()
                }.onFailure {
                    notify("Export failed: ${it.message}", warning = true)
                }
            }
        }
    }

    fun updateRenderSettings(settings: RenderSettings) {
        renderSettings = settings
        tiles.settings = settings
        if (settings.mode == RenderMode.Textured) loadTextures()
    }

    fun setRenderMode(mode: RenderMode) = updateRenderSettings(renderSettings.copy(mode = mode))

    // ---- Textures ----

    /** Where the Textured mode's textures come from (e.g. "1.20.1.jar"), null until loaded. */
    var textureLabel by mutableStateOf<String?>(null)
        private set
    private var textureAssets: AssetSource? = null
    private var texturesLoading = false

    /** Opens the textures for the Textured mode once; without any, that mode looks like the Blocks mode. */
    private fun loadTextures(force: Boolean = false) {
        if (texturesLoading || (textureAssets != null && !force)) return
        texturesLoading = true
        scope.launch(Dispatchers.IO) {
            val files = TextureFiles.sources()
            val assets = files?.let { runCatching { AssetSource(it) }.onFailure { e -> e.printStackTrace() }.getOrNull() }
            withContext(Dispatchers.Main) {
                texturesLoading = false
                val old = textureAssets
                textureAssets = assets
                textureLabel = assets?.label
                tiles.textures = assets?.let(::BlockModels)
                old?.close()
                if (assets == null) {
                    notify("No Minecraft installation found for textures. Choose a client jar or resource pack in the View panel.", warning = true)
                }
            }
        }
    }

    /** Uses these files (resource packs and/or a client jar) for textures; empty goes back to finding a jar. */
    fun chooseTextures(files: List<Path>) {
        TextureFiles.chosen = files
        loadTextures(force = true)
    }

    fun selectTool(t: Tool) {
        tool = t
        if (pasteMode) cancelPaste()
    }

    // ---- Undo ----

    val canUndo: Boolean get() { historyRevision; return history.canUndo }
    val canRedo: Boolean get() { historyRevision; return history.canRedo }
    val undoLabel: String? get() { historyRevision; return history.nextUndo?.label }
    val redoLabel: String? get() { historyRevision; return history.nextRedo?.label }

    fun undo() {
        val edit = history.undo() ?: return
        historyRevision++
        stacks[edit.world.id] = edit.before
        if (edit.world != activeWorld) openWorld(edit.world)
        notify("Undid ${edit.label.lowercase()}")
    }

    fun redo() {
        val edit = history.redo() ?: return
        historyRevision++
        stacks[edit.world.id] = edit.after
        if (edit.world != activeWorld) openWorld(edit.world)
        notify("Redid ${edit.label.lowercase()}")
    }

    /** Applies [transform] to the active stack as one undoable step. Returns false if nothing changed. */
    private fun commit(label: String, transform: (LayerStack) -> LayerStack): Boolean {
        val world = activeWorld ?: return false
        val before = stacks[world.id] ?: return false
        val after = transform(before)
        if (after == before) return false
        stacks[world.id] = after
        history.push(StackEdit(world, label, before, after))
        historyRevision++
        return true
    }

    fun beginStroke(label: String) {
        strokeStart = activeStack
        strokeLabel = label
    }

    /** Changes the current stack without recording undo; only valid inside a stroke. */
    private fun strokeUpdate(transform: (LayerStack) -> LayerStack) {
        val world = activeWorld ?: return
        if (strokeStart == null) return
        stacks[world.id]?.let { stacks[world.id] = transform(it) }
    }

    /** Replaces the stack with [transform] applied to the stack from the start of the stroke. */
    private fun strokeFromStart(transform: (LayerStack) -> LayerStack) {
        val world = activeWorld ?: return
        val start = strokeStart ?: return
        stacks[world.id] = transform(start)
    }

    fun endStroke() {
        val before = strokeStart ?: return
        strokeStart = null
        val world = activeWorld ?: return
        val after = stacks[world.id] ?: return
        if (after != before) {
            history.push(StackEdit(world, strokeLabel, before, after))
            historyRevision++
        }
    }

    /** The active layer if it may be edited; otherwise tells the user why not. */
    private fun editableLayer(): Layer? {
        val layer = activeStack?.active ?: return null
        if (layer.locked) {
            notify("Layer \"${layer.name}\" is locked", warning = true)
            return null
        }
        return layer
    }

    // ---- Selection ----

    /** Chunks that can be selected: anything visible, plus erased original chunks (to restore them). */
    fun isSelectable(pos: ChunkPos): Boolean {
        val stack = activeStack ?: return false
        return stack.resolve(pos) != null || (stack.base.visible && stack.base.isErased(pos))
    }

    fun applySelection(positions: Collection<ChunkPos>, mode: SelectMode) {
        val valid = positions.filter(::isSelectable)
        selection = when (mode) {
            SelectMode.Replace -> valid.toSet()
            SelectMode.Add -> selection + valid
            SelectMode.Subtract -> selection - valid.toSet()
        }
    }

    fun brushAt(center: ChunkPos): List<ChunkPos> {
        val r = (brushSize - 1) / 2
        return buildList { for (dx in -r..r) for (dz in -r..r) add(ChunkPos(center.x + dx, center.z + dz)) }
    }

    fun clearSelection() { selection = emptySet() }

    fun selectAll() {
        val stack = activeStack ?: return
        val b = stack.bounds ?: return
        selection = buildSet {
            for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                val pos = ChunkPos(x, z)
                if (stack.resolve(pos) != null) add(pos)
            }
        }
    }

    fun invertSelection() {
        val old = selection
        selectAll()
        selection = selection - old
    }

    fun layerToSelection(id: Long) {
        val stack = activeStack ?: return
        selection = stack.positionsOf(id)
        notify("Selected ${selection.size} chunks of \"${stack.layer(id)?.name}\"")
    }

    // ---- Clipboard ----

    /** Copies the selection from the active layer, or from everything visible when [visible]. */
    fun copy(visible: Boolean = false): Boolean {
        val stack = activeStack ?: return false
        val layer = stack.active
        val clip = if (visible) {
            ChunkClipboard.copy(selection, "${stack.world.name} (visible)", stack::resolve)
        } else {
            ChunkClipboard.copy(selection, if (layer is BaseLayer) stack.world.name else layer.name) { stack.contentOf(layer, it) }
        }
        if (clip == null) {
            notify(if (selection.isEmpty()) "Nothing to copy: select chunks first" else "The active layer has no chunks in the selection", warning = true)
            return false
        }
        clipboard = clip
        notify("Copied ${clip.size} chunks")
        return true
    }

    fun cut() {
        if (editableLayer() == null) return
        if (copy()) clearSelected("Cut")
    }

    /** Enters placement mode: the clipboard follows the cursor until a click places it as a new layer. */
    fun startPaste() {
        val clip = clipboard ?: return notify("Clipboard is empty", warning = true)
        pasteMode = true
        pasteAnchor = hovered
        val world = activeWorld
        val versions = clip.sourceWorlds.map { it.versionLabel }.toSet()
        if (world != null && versions.any { it != world.versionLabel }) {
            notify("Pasting ${versions.joinToString()} chunks into a ${world.versionLabel} world: they will be upgraded on export", warning = true)
        }
    }

    fun cancelPaste() {
        pasteMode = false
    }

    /** Places the clipboard as a new layer with its anchor at [anchor]. */
    fun pasteAt(anchor: ChunkPos, keepPlacing: Boolean = false) {
        val clip = clipboard ?: return
        val placed = clip.placedAt(anchor)
        commit("Paste") { it.addLayer("Pasted: ${clip.sourceName}", placed) }
        selection = placed.keys
        pasteMode = keepPlacing
        notify("Pasted ${placed.size} chunks as a new layer")
    }

    /** Pastes at the coordinates the chunks were copied from. */
    fun pasteInPlace() {
        val clip = clipboard ?: return notify("Clipboard is empty", warning = true)
        pasteAt(clip.origin)
    }

    // ---- Chunk edits ----

    /** Removes the selected chunks from the active layer. */
    fun clearSelected(label: String = "Clear") {
        val layer = editableLayer() ?: return
        if (selection.isEmpty()) return
        if (commit(label) { it.clear(layer.id, selection) } && label == "Clear") {
            notify("Removed ${selection.size} chunks from \"${layer.name}\"")
        }
    }

    /** Brings back the original world's chunks under the selection (drops erase and merge overrides). */
    fun restoreOriginal() {
        val stack = activeStack ?: return
        val targets = selection.filter { it in stack.base.overrides }
        if (targets.isEmpty()) return notify("The original world is unchanged under the selection")
        commit("Restore original") { it.restore(targets) }
        notify("Restored ${targets.size} original chunks")
    }

    fun beginErase(): Boolean {
        editableLayer() ?: return false
        beginStroke("Erase")
        return true
    }

    fun eraseAt(positions: Collection<ChunkPos>) {
        val layer = activeStack?.active ?: return
        if (layer.locked) return
        strokeUpdate { it.clear(layer.id, positions) }
    }

    /** Starts dragging the active layer; returns false (with a message) when it can't move. */
    fun beginMoveLayer(): Boolean {
        val layer = editableLayer() ?: return false
        if (layer !is ChunkLayer) {
            notify("The original world can't be moved. Duplicate it or move a pasted layer.", warning = true)
            return false
        }
        beginStroke("Move layer")
        return true
    }

    /** Offset of the layer relative to where the move started. */
    fun moveLayerBy(dx: Int, dz: Int) {
        val id = activeStack?.activeId ?: return
        strokeFromStart { it.offset(id, dx, dz) }
    }

    fun pickLayerAt(pos: ChunkPos) {
        val layer = activeStack?.layerAt(pos) ?: return
        setActiveLayer(layer.id)
        notify("Active layer: ${layer.name}")
    }

    // ---- Layers ----

    /** Choosing the active layer is not an edit, so it is not recorded in undo history. */
    fun setActiveLayer(id: Long) {
        val world = activeWorld ?: return
        stacks[world.id]?.let { stacks[world.id] = it.withActive(id) }
    }

    fun newLayer() {
        val count = (activeStack?.layers?.size ?: 0)
        commit("New layer") { it.addLayer("Layer $count") }
    }

    // Layer actions act on the active layer.

    fun duplicateLayer() { commit("Duplicate layer") { it.duplicate(it.activeId) } }

    fun deleteLayer() {
        if (activeStack?.activeId == BaseLayer.BASE_ID) {
            return notify("The original world layer can't be deleted. Hide it instead.", warning = true)
        }
        commit("Delete layer") { it.remove(it.activeId) }
    }

    fun raiseLayer() { commit("Raise layer") { it.move(it.activeId, +1) } }
    fun lowerLayer() { commit("Lower layer") { it.move(it.activeId, -1) } }

    fun mergeDown() {
        val stack = activeStack ?: return
        if (!stack.canMergeDown(stack.activeId)) return notify("Nothing below to merge into", warning = true)
        commit("Merge down") { it.mergeDown(it.activeId) }
    }
    fun flatten() {
        if ((activeStack?.layers?.size ?: 0) <= 1) return
        commit("Flatten") { it.flatten() }
        notify("Flattened all visible layers into the original world layer")
    }
    fun toggleVisible(id: Long) {
        val layer = activeStack?.layer(id) ?: return
        commit(if (layer.visible) "Hide layer" else "Show layer") { it.setVisible(id, !layer.visible) }
    }
    fun toggleLocked(id: Long) {
        val layer = activeStack?.layer(id) ?: return
        commit(if (layer.locked) "Unlock layer" else "Lock layer") { it.setLocked(id, !layer.locked) }
    }
    fun renameLayer(id: Long, name: String) { commit("Rename layer") { it.rename(id, name) } }

    fun notify(text: String, warning: Boolean = false) {
        notice = Notice(text, warning)
    }

    // ---- Camera ----

    fun zoomBy(factor: Double) {
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        view = view.zoomedAt(factor, w / 2, h / 2, w, h)
    }

    /** Centers the camera on the active world and zooms so all of it fits. */
    fun fitToWorld() {
        val bounds = activeStack?.focusBounds ?: return
        if (viewportSize.width == 0) {
            fitPending = true
            return
        }
        fitPending = false
        val hs = renderSettings.heightScale.toDouble()
        val spanU = (bounds.maxX - bounds.minX + 1 + bounds.maxZ - bounds.minZ + 1) * 16.0
        val spanV = spanU / 2 + 96 * hs
        var zoom = minOf(viewportSize.width / spanU, viewportSize.height / spanV) * 0.92
        var centerX = bounds.centerBlockX
        var centerZ = bounds.centerBlockZ
        // A chunk covers 256 square iso units, so this zoom keeps about MAX_CHUNKS_ON_FIT chunks on screen.
        // Bigger worlds start around their spawn instead of rendering tens of thousands of chunks.
        val budgetZoom = sqrt(viewportSize.width.toDouble() * viewportSize.height / (256.0 * MAX_CHUNKS_ON_FIT))
        if (zoom < budgetZoom) {
            zoom = budgetZoom
            activeWorld?.spawn?.let { (x, z) -> centerX = x.toDouble(); centerZ = z.toDouble() }
        }
        val (cx, cz) = Rotation.point(rotation, centerX, centerZ)
        view = IsoView.centeredOn(cx, SURFACE_Y, cz, hs, zoom)
        lastFitView = view
    }

    /** Turns the view by [delta] quarter turns (+1 = clockwise on screen) around the point at the screen center. */
    fun rotate(delta: Int) {
        val hs = renderSettings.heightScale.toDouble()
        val wasFit = view == lastFitView
        // Screen center on the surface plane, in rotated coordinates, then back to world coordinates.
        val sum = 2 * (view.centerV + SURFACE_Y * hs)
        val (wx, wz) = Rotation.point(4 - rotation, (sum + view.centerU) / 2, (sum - view.centerU) / 2)
        rotation = Rotation.normalize(rotation + delta)
        val (rx, rz) = Rotation.point(rotation, wx, wz)
        view = IsoView.centeredOn(rx, SURFACE_Y, rz, hs, view.zoom)
        if (wasFit) lastFitView = view
        hovered = null
    }

    /** Converts between real chunk positions and the rotated positions the camera works with. */
    fun toView(pos: ChunkPos) = Rotation.chunk(rotation, pos)
    fun fromView(pos: ChunkPos) = Rotation.chunk(4 - rotation, pos)

    fun dispose() {
        scope.cancel()
        textureAssets?.close()
        worlds.forEach { (it as? AnvilWorld)?.close() }
    }

    /** Limits progress updates from background work to about 20 per second. */
    private class ProgressThrottle {
        private var last = 0L
        fun ready(force: Boolean): Boolean {
            val now = System.nanoTime()
            if (!force && now - last < 50_000_000) return false
            last = now
            return true
        }
    }

    companion object {
        /** Typical terrain height used to center the camera before chunk heights are known. */
        const val SURFACE_Y = 64.0
        private const val MAX_CHUNKS_ON_FIT = 4000
    }
}
