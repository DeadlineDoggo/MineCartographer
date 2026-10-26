package minecartographer.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
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
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.Layer
import minecartographer.core.DemoWorld
import minecartographer.core.edit.LayerStack
import minecartographer.core.anvil.ChunkTransform
import minecartographer.core.anvil.TransformedChunk
import minecartographer.core.convert.Conversion
import minecartographer.core.convert.MinecraftTables
import minecartographer.core.convert.Upgrade
import minecartographer.core.edit.UndoHistory
import minecartographer.core.project.ProjectDocument
import minecartographer.core.project.ProjectFile
import minecartographer.core.project.ProjectSnapshot
import minecartographer.core.project.WorldLocation
import minecartographer.core.project.WorldTextures
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import minecartographer.AppInfo
import minecartographer.viewport.ChunkTileCache
import minecartographer.viewport.IsoView
import minecartographer.textures.AssetSource
import minecartographer.textures.BlockModels
import minecartographer.textures.TextureFiles
import minecartographer.textures.ModLocator
import minecartographer.textures.PackCache
import minecartographer.textures.LegacyTextures
import minecartographer.textures.FlatModels
import minecartographer.core.anvil.ModInfo
import minecartographer.core.anvil.BlockColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
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
            changed()
            refreshTextures()
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

    /** True while a background task (opening or exporting a world) runs; only one at a time. */
    var busy by mutableStateOf(false)
        private set
    var exportDialogVisible by mutableStateOf(false)

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
        changed()
        worlds.remove(world)
        stacks.remove(world.id)
        worldTextures.remove(world.id)
        refreshTextures()
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
        val textures = worlds.associateWith(::texturesOf)
        val title = "Exporting $levelName"
        loading = LoadingStep(title, "Preparing", waitForTiles = false)
        val throttle = ProgressThrottle()
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                WorldExporter.export(stack, output, levelName) { step, done, total ->
                    if (throttle.ready(done == total)) scope.launch(Dispatchers.Main) {
                        if (busy) loading = LoadingStep(title, "$step $done / $total", if (total > 0) done.toFloat() / total else null, waitForTiles = false)
                    }
                }.also { ProjectFile.writeSources(output, stack, textures, AppInfo.version) }
            }
            withContext(Dispatchers.Main) {
                busy = false
                loading = null
                result.onSuccess { r ->
                    val extra = if (r.warnings.isEmpty()) "" else " (${r.warnings.size} warnings, see log)"
                    r.warnings.forEach(System.err::println)
                    notify("Exported ${r.chunks} chunks (${r.relocated} moved) to $output$extra")
                    WorldLibrary.remember(output)
                }.onFailure {
                    notify("Export failed: ${it.message}", warning = true)
                }
            }
        }
    }

    fun updateRenderSettings(settings: RenderSettings) {
        val enteringTextured = settings.mode == RenderMode.Textured && renderSettings.mode != RenderMode.Textured
        renderSettings = settings
        tiles.settings = settings
        if (enteringTextured) refreshTextures()
    }

    fun setRenderMode(mode: RenderMode) = updateRenderSettings(renderSettings.copy(mode = mode))

    /** Switches between the isometric view and the 2D view from above, keeping the same spot in the middle. */
    fun setFlat(flat: Boolean) {
        if (flat == renderSettings.flat) return
        val wasFit = view == lastFitView
        val hs = renderSettings.heightScale.toDouble()
        val (x, z) = view.ground(view.centerU, view.centerV, SURFACE_Y, hs)
        updateRenderSettings(renderSettings.copy(flat = flat))
        view = IsoView.centeredOn(x, SURFACE_Y, z, hs, view.zoom, flat)
        if (wasFit) fitToWorld()
    }

    // ---- Textures ----

    /** Texture files picked per world id; worlds without an entry use the default ([TextureFiles.chosen]). */
    private val worldTextures = mutableStateMapOf<String, WorldTextures>()
    /** What each world's textures were last loaded from, by world id. */
    private val loadedTextures = mutableStateMapOf<String, LoadedTextures>()
    /** True while textures are being (re)loaded. */
    var texturesLoading by mutableStateOf(false)
        private set

    /**
     * Files a world is drawn with: everything read, in priority order, the client jar among them, and for a
     * world played with mods the [mods] archives found and the mods no archive was found for.
     */
    class LoadedTextures(
        val files: List<Path>,
        val coreJar: Path?,
        val mods: List<Path> = emptyList(),
        val missingMods: List<ModInfo> = emptyList(),
        /** Lowercase mod id -> archive providing it (see [ModLocator.Found.providers]). */
        val providers: Map<String, Path> = emptyMap(),
    ) {
        val label: String get() {
            val others = files.filter { it !in mods }.map { it.fileName.toString() }
            return (others.dropLast(1) + listOfNotNull(if (mods.isEmpty()) null else "${mods.size} mods") + others.takeLast(1)).joinToString(" + ")
        }
    }

    /** Open textures by file list, shared by worlds that use the same files. Only used while holding [textureLock]. */
    private val textureSets = HashMap<List<Path>, Pair<AssetSource, BlockModels>>()
    private val textureLock = Mutex()
    @Volatile private var textureRequest = 0

    /** The files picked for [world] (empty: the default), the core jar it was last drawn with and its mod archives. */
    fun texturesOf(world: WorldSource): WorldTextures {
        val picked = worldTextures[world.id] ?: WorldTextures()
        val loaded = loadedTextures[world.id]
        return picked.copy(coreJar = loaded?.coreJar ?: picked.coreJar, mods = loaded?.mods ?: picked.mods)
    }

    /** What [world] is drawn with right now; null before textures were needed or when nothing was found. */
    fun loadedTexturesOf(world: WorldSource): LoadedTextures? = loadedTextures[world.id]

    /** Draws [world] with these files (resource packs, top first, and/or a client jar); empty goes back to the default. */
    fun chooseTextures(world: WorldSource, files: List<Path>) {
        val current = texturesOf(world)
        worldTextures[world.id] = WorldTextures(files, coreJar = current.coreJar, mods = current.mods)
        changed()
        refreshTextures(force = true)
    }

    /** Sets the default textures for worlds without their own; empty finds a client jar automatically. */
    fun chooseDefaultTextures(files: List<Path>) {
        TextureFiles.chosen = files
        refreshTextures(force = true)
    }

    /**
     * Searches [root] (a drive or a backup folder) for the mods of [world] in the background and adds the `mods`
     * folders that provide the most of them (the picked folder itself included); remembered across sessions.
     */
    fun findMods(world: AnvilWorld, root: Path) {
        if (busy) return notify("Wait for the current task to finish", warning = true)
        busy = true
        val title = "Searching for mods of ${world.name}"
        loading = LoadingStep(title, "Looking for mods folders in $root", waitForTiles = false)
        val known = texturesOf(world).mods
        val throttle = ProgressThrottle()
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                ModLocator.search(world, root, known, progress = { folders, read ->
                    if (throttle.ready(false)) scope.launch(Dispatchers.Main) {
                        if (busy) loading = LoadingStep(title, if (read == 0) "$folders mods folders found" else "Reading mods folder $read / $folders",
                            if (read == 0) null else read.toFloat() / folders, waitForTiles = false)
                    }
                })
            }
            val after = result.getOrNull()?.let { ModLocator.locate(world, known, ModLocator.folders + it) }
            ModLocator.saveArchives()
            withContext(Dispatchers.Main) {
                busy = false
                loading = null
                result.onSuccess { folders ->
                    val mods = world.info.forge?.playedMods.orEmpty()
                    if (folders.isEmpty()) {
                        notify("No mods of \"${world.name}\" found under $root", warning = true)
                    } else {
                        ModLocator.folders = ModLocator.folders + folders
                        missingNotified.clear()
                        notify("Found ${mods.size - (after?.missing?.size ?: 0)} of ${mods.size} mods in ${folders.size} folder" +
                            (if (folders.size == 1) "" else "s") + ": " + folders.joinToString { it.toString() })
                        refreshTextures(force = true)
                    }
                }.onFailure { notify("Searching for mods failed: ${it.message}", warning = true) }
            }
        }
    }

    /**
     * Starts textures and mods from scratch: deletes the pack caches and the archive metadata, forgets learned colors,
     * finds every world's mods again and redraws. For when files changed in a way their size and date don't show,
     * or the results look stale.
     */
    fun rescanTextures() {
        scope.launch(Dispatchers.IO) {
            textureLock.withLock {
                for ((assets, models) in textureSets.values) {
                    models.cache?.let(PackCache::delete)
                    assets.close()
                }
                textureSets.clear()
                ModLocator.forgetArchives()
            }
            withContext(Dispatchers.Main) {
                BlockColors.unlearnAll()
                FlatModels.forgetAll()
                unresolvedColors.clear()
                retryColors.clear()
                missingNotified.clear()
                loadedTextures.clear()
                tiles.textures = emptyMap()
                refreshTextures(force = true)
                tiles.reloadWorlds(worlds.mapTo(HashSet()) { it.id })
                notify("Rescanning mods and textures")
            }
        }
    }

    /** Uses [archive] for the mod [modId] of [world] (whatever its metadata says), or the found one again when null. */
    fun chooseModArchive(world: WorldSource, modId: String, archive: Path?) {
        val current = worldTextures[world.id] ?: WorldTextures()
        val archives = if (archive == null) current.modArchives - modId else current.modArchives + (modId to archive.toAbsolutePath().normalize())
        worldTextures[world.id] = current.copy(modArchives = archives)
        changed()
        refreshTextures(force = true)
    }

    /** Stops looking for mods in [folder] (one added by [findMods]). */
    fun removeModFolder(folder: Path) {
        ModLocator.folders = ModLocator.folders.filter { it != folder }
        refreshTextures(force = true)
    }


    /** True for worlds played with mods: their assets are loaded in every render mode, for the map colors. */
    fun hasMods(world: WorldSource) = (world as? AnvilWorld)?.info?.forge?.playedMods?.isNotEmpty() == true

    /**
     * Loads textures in the background: for every open world in the Textured mode, and always for worlds
     * played with mods (their blocks get map colors from the mods' textures). Each world gets its picked
     * files (or the default), then its mod archives, then a client jar: the one it used before, else the jar
     * of its own version, else the newest. Without textures the Textured mode looks like Blocks.
     */
    private fun refreshTextures(force: Boolean = false) {
        val textured = renderSettings.mode == RenderMode.Textured
        if (!textured && !force && worlds.none(::hasMods)) return
        val request = ++textureRequest
        class Wanted(val world: WorldSource, val picked: WorldTextures, val version: String?)
        val wanted = worlds.filter { textured || hasMods(it) }.map { w ->
            Wanted(w, texturesOf(w), w.versionLabel.takeIf { (w.dataVersion ?: 0) >= FLATTENING_DATA_VERSION })
        }
        texturesLoading = true
        scope.launch(Dispatchers.IO) {
            textureLock.withLock {
                if (request != textureRequest) return@withLock
                val loaded = HashMap<String, LoadedTextures>()
                for (w in wanted) {
                    val found = (w.world as? AnvilWorld)?.takeIf(::hasMods)?.let { ModLocator.locate(it, w.picked.mods, manual = w.picked.modArchives) }
                    val base = TextureFiles.sources(w.picked.files, w.picked.coreJar, w.version).orEmpty()
                    val jar = TextureFiles.coreJarOf(base)
                    val mods = found?.archives.orEmpty()
                    // Resource packs over mods over the vanilla jar, as in the game.
                    val files = (base.filter { it != jar } + mods + listOfNotNull(jar)).distinct()
                    if (files.isNotEmpty() || found != null) loaded[w.world.id] = LoadedTextures(files, jar, mods, found?.missing.orEmpty(), found?.providers.orEmpty())
                }
                val models = HashMap<String, BlockModels>()
                for ((id, l) in loaded) {
                    if (l.files.isEmpty()) continue
                    val set = textureSets[l.files] ?: runCatching {
                        // The pack cache knows which archive has which asset, so archives open only when read from.
                        val cache = PackCache.of(l.files)
                        val assets = AssetSource(l.files, cache.index)
                        if (cache.index == null) cache.putIndex(assets.index)
                        assets to BlockModels(assets, cache)
                    }.onFailure { it.printStackTrace() }.getOrNull()?.also { textureSets[l.files] = it }
                    set?.let { models[id] = it.second }
                }
                // 1.6 worlds: their blocks and the mods' config files are the clues for guessing textures.
                for (w in wanted) {
                    val m = models[w.world.id] ?: continue
                    val anvil = w.world as? AnvilWorld
                    val legacy = anvil?.info?.forge?.legacyBlocks.orEmpty()
                    if (anvil != null && legacy.isNotEmpty()) {
                        m.registerLegacy(legacy.values, LegacyTextures.configKeys(configFolders(anvil, loaded[w.world.id]?.mods.orEmpty())))
                    }
                    m.overrides.putAll(w.picked.blocks)
                }
                val applied = withContext(Dispatchers.Main) {
                    if (request != textureRequest) return@withContext false
                    tiles.textures = models
                    loadedTextures.clear()
                    loadedTextures.putAll(loaded)
                    texturesLoading = false
                    val noJar = if (textured) worlds.filter { loaded[it.id]?.coreJar == null } else emptyList()
                    val missing = worlds.firstNotNullOfOrNull { w ->
                        loaded[w.id]?.missingMods?.takeIf { it.isNotEmpty() && w.id !in missingNotified }?.let { w to it }
                    }
                    when {
                        noJar.isNotEmpty() ->
                            notify("No Minecraft client jar found for textures of ${noJar.joinToString { "\"${it.name}\"" }}. Choose one in the View panel.", warning = true)
                        missing != null -> {
                            val (w, mods) = missing
                            missingNotified += w.id
                            notify("${mods.size} mods of \"${w.name}\" not found (${mods.take(4).joinToString { it.id }}${if (mods.size > 4) ", …" else ""}). " +
                                "Find them in the View tab: Mods, Find mods…", warning = true)
                        }
                    }
                    true
                }
                if (applied) {
                    val used = loaded.values.map { it.files }.toSet()
                    textureSets.keys.filter { it !in used }.forEach { files ->
                        textureSets.remove(files)?.let { (assets, m) -> m.cache?.save(); assets.close() }
                    }
                    ModLocator.saveArchives()
                    retryColors += unresolvedColors
                    unresolvedColors.clear()
                    restoreCachedLooks(models.values.toSet())
                    if (models.isNotEmpty()) {
                        startColorLearner()
                        startCacheSaver()
                    }
                }
            }
        }
    }

    /** `config` folders next to the `mods` folders the archives came from, and the save's own instance. */
    private fun configFolders(world: AnvilWorld, archives: List<Path>): List<Path> {
        val mods = archives.mapNotNull { a -> generateSequence(a.parent) { it.parent }.take(3).firstOrNull { it.fileName?.toString().equals("mods", true) } }
        val instance = world.folder.toAbsolutePath().normalize().parent?.takeIf { it.fileName?.toString().equals("saves", true) }?.parent
        return (mods.mapNotNull { it.parent?.resolve("config") } + listOfNotNull(instance?.resolve("config"), world.folder.parent?.resolve("config"))).distinct()
    }

    /** The start screen over the editor: shown at launch until something is opened. */
    var startScreenVisible by mutableStateOf(true)

    /** Two generated worlds, for trying the editor without a save. */
    fun openDemo() {
        addWorld(DemoWorld("Demo Valley", seed = 1))
        addWorld(DemoWorld("Demo Highlands", seed = 2024, radiusChunks = 16))
        markSaved()
    }

    /** Controls laid over the map, in root coordinates (see `overMap`); the map ignores the pointer on them. */
    val mapBlockers = HashMap<Any, androidx.compose.ui.geometry.Rect>()

    /** The shortcuts and tips card over the map. */
    var helpVisible by mutableStateOf(false)

    // The Mods & textures and Analyze selection panels share the place next to the left dock: one at a time.
    private var modsShown by mutableStateOf(false)
    private var analyzeShown by mutableStateOf(false)

    /** The Mods & textures panel over the map, and its width. */
    var modsPanelVisible: Boolean
        get() = modsShown
        set(value) { modsShown = value; if (value) analyzeShown = false }
    var modsPanelWidth by mutableStateOf(640.dp)

    /** The Analyze selection panel over the map, and its width. */
    var analyzeVisible: Boolean
        get() = analyzeShown
        set(value) { analyzeShown = value; if (value) modsShown = false }
    var analyzeWidth by mutableStateOf(720.dp)

    /** What the Analyze panel counts. Fixed when asked for: changing the selection later doesn't read the chunks again. */
    var analyzeRequest by mutableStateOf<AnalyzeRequest?>(null)
        private set
    private var analyzeRuns = 0

    /** Analyzes the selected chunks and shows the panel. */
    fun analyzeSelection(scope: AnalyzeScope = analyzeRequest?.scope ?: AnalyzeScope.Visible) {
        val stack = activeStack ?: return
        if (selection.isEmpty()) return notify("Select chunks to analyze first", warning = true)
        analyzeRequest = AnalyzeRequest(selection, scope, stack.activeId, "selection", fromSelection = true, run = ++analyzeRuns)
        analyzeVisible = true
    }

    /** Analyzes every chunk of the layer [id] (making it the active one) and shows the panel. */
    fun analyzeLayer(id: Long) {
        val stack = activeStack ?: return
        val layer = stack.layer(id) ?: return
        setActiveLayer(id)
        analyzeRequest = AnalyzeRequest(stack.positionsOf(id), AnalyzeScope.Layer, id, "layer \"${layer.name}\"", fromSelection = false, run = ++analyzeRuns)
        analyzeVisible = true
    }

    /** Reads the same chunks again, with [scope] (Layer means the layer active now). */
    fun reanalyze(scope: AnalyzeScope? = null) {
        val request = analyzeRequest ?: return
        analyzeRequest = request.copy(scope = scope ?: request.scope, layerId = activeStack?.activeId ?: request.layerId, run = ++analyzeRuns)
    }

    /** The toolbar and menu button: hides the panel, or analyzes the selection. */
    fun toggleAnalyze() {
        if (analyzeVisible) analyzeVisible = false else analyzeSelection()
    }

    /** What the main area shows: the map, or the texture atlas that opens beside it as a tab. */
    var mainTab by mutableStateOf(MainTab.Map)
        private set

    /** The Texture atlas tab is open (the Map tab always is). */
    var atlasOpen by mutableStateOf(false)
        private set

    fun openAtlas() {
        atlasOpen = true
        mainTab = MainTab.Atlas
    }

    fun closeAtlas() {
        atlasOpen = false
        mainTab = MainTab.Map
    }

    fun showMainTab(tab: MainTab) {
        if (tab == MainTab.Map || atlasOpen) mainTab = tab
    }

    /** The right-click menu on the map, while it is open. */
    var mapMenu by mutableStateOf<MapMenu?>(null)

    /** Puts [text] on the system clipboard. */
    fun copyText(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
            .onSuccess { notify("Copied $text") }
            .onFailure { notify("Could not copy: ${it.message}", warning = true) }
    }

    /** The pack [world] is drawn with, once its textures are loaded. */
    fun modelsOf(world: WorldSource): BlockModels? = tiles.textures[world.id] as? BlockModels

    /** Modded block keys seen in [world] so far, sorted. */
    fun moddedKeysOf(world: WorldSource): List<String> {
        val forge = (world as? AnvilWorld)?.info?.forge ?: return emptyList()
        val names = forge.blockNames.values.toSet()
        val mods = forge.playedMods.mapTo(HashSet()) { it.id.lowercase() }
        val packNamespaces = modelsOf(world)?.assets?.namespaces.orEmpty()
        // Before 1.13 every block carries its data value: the bare name is the same block again.
        return BlockColors.seenKeys().filter { key ->
            val name = BlockColors.stateOf(key).name
            if (forge.numericIds) name in names && "[meta=" in key else name.substringBefore(':').let { it in mods || it in packNamespaces }
        }.sorted()
    }

    /**
     * Uses [texture] (a texture id like `extrabiomesxl:leavesfirfancy`) for the block [key] of [world], or the guess
     * again when null. Saved with the project; the world is redrawn.
     */
    fun setBlockTexture(world: WorldSource, key: String, texture: String?) {
        val current = worldTextures[world.id] ?: WorldTextures()
        val value = texture?.trim()?.takeIf { it.isNotEmpty() }
        worldTextures[world.id] = current.copy(blocks = if (value == null) current.blocks - key else current.blocks + (key to value))
        changed()
        val base = BlockColors.stateOf(key).name
        modelsOf(world)?.let { m ->
            if (value == null) m.overrides.remove(key) else m.overrides[key] = value
            m.cache?.looks?.remove(key)
            m.forget(setOf(base))
        }
        FlatModels.forget(setOf(base))
        BlockColors.unlearn(key)
        retryColors += key
        tiles.reloadWorlds(setOf(world.id))
    }

    /** Worlds already warned about missing mods in this session. */
    private val missingNotified = HashSet<String>()
    private var colorLearner: Job? = null
    /** Modded names no loaded pack could color yet; tried again after the next texture refresh. */
    private val unresolvedColors: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retryColors: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Gives modded blocks their map colors from the mods' textures, as chunks name them: every few hundred
     * milliseconds it takes the modded names the chunk reader met, looks them up in the loaded packs and,
     * once some were found, reloads the chunks of the worlds played with mods.
     */
    /**
     * Colors learned in earlier sessions, from the pack caches: applied at once, so a modded world shows its
     * real colors right after its textures load instead of after the learner went through every block again.
     */
    private suspend fun restoreCachedLooks(models: Set<BlockModels>) {
        val restored = HashSet<String>()
        for (m in models) m.cache?.looks?.forEach { (key, look) ->
            if (look !== PackCache.NO_LOOK && BlockColors.learnedLook(key) == null) {
                BlockColors.learn(key, look)
                restored += key
            }
        }
        if (restored.isEmpty()) return
        val blocks = restored.mapTo(HashSet()) { BlockColors.stateOf(it).name }
        FlatModels.forget(blocks)
        models.forEach { it.forget(blocks) }
        withContext(Dispatchers.Main) { tiles.reloadWorlds(worlds.filter(::hasMods).mapTo(HashSet()) { it.id }) }
    }

    private var cacheSaver: Job? = null

    /** Writes pack caches (looks, variants, decoded textures) every few seconds while they change. */
    private fun startCacheSaver() {
        if (cacheSaver?.isActive == true) return
        cacheSaver = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(5000)
                val caches = textureLock.withLock { textureSets.values.mapNotNull { it.second.cache } }
                caches.forEach { it.save() }
                ModLocator.saveArchives()
            }
        }
    }

    private fun startColorLearner() {
        if (colorLearner?.isActive == true) return
        colorLearner = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(300)
                val retry = retryColors.toSet().also { retryColors.removeAll(it) }
                val names = BlockColors.takeWanted() + retry
                if (names.isEmpty()) continue
                val packs = textureLock.withLock { textureSets.values.map { it.second } }
                val learned = HashSet<String>()
                for (name in names) {
                    val ns = name.substringBefore(':')
                    // 1.6 blocks are named after the mod id (tconstruct:...), their textures live elsewhere (tinker:...).
                    val candidates = packs.filter { ns in it.assets.namespaces || it.knows(name) }
                    val cached = candidates.firstNotNullOfOrNull { it.cache?.looks?.get(name) }
                    val look = when {
                        cached === PackCache.NO_LOOK -> null
                        cached != null -> cached
                        else -> candidates.firstNotNullOfOrNull { p ->
                            // A user's choice belongs to one world, not to the cache shared by every world using these files.
                            runCatching { p.mapLook(BlockColors.stateOf(name)) }.getOrNull()?.also { if (!p.isOverridden(name)) p.cache?.putLook(name, it) }
                        }.also { found -> if (found == null) candidates.forEach { it.cache?.putLook(name, null) } }
                    }
                    if (look == null) unresolvedColors += name else {
                        BlockColors.learn(name, look)
                        learned += name
                    }
                }
                if (learned.isEmpty()) continue
                val blocks = learned.mapTo(HashSet()) { BlockColors.stateOf(it).name }
                FlatModels.forget(blocks)
                packs.forEach { it.forget(blocks) }
                withContext(Dispatchers.Main) { tiles.reloadWorlds(worlds.filter(::hasMods).mapTo(HashSet()) { it.id }) }
            }
        }
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
        changed()
        stacks[edit.world.id] = edit.before
        if (edit.world != activeWorld) openWorld(edit.world)
        notify("Undid ${edit.label.lowercase()}")
    }

    fun redo() {
        val edit = history.redo() ?: return
        historyRevision++
        changed()
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
        changed()
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
            changed()
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

    fun selectAll() = selectInBackground("Selecting all chunks") { stack, _ -> allChunks(stack) }

    fun invertSelection() = selectInBackground("Inverting selection") { stack, old -> allChunks(stack) - old }

    /** Every position that shows a chunk. Walks the whole world area, so never on the UI thread. */
    private fun allChunks(stack: LayerStack): Set<ChunkPos> {
        val b = stack.bounds ?: return emptySet()
        return buildSet {
            for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                val pos = ChunkPos(x, z)
                if (stack.resolve(pos) != null) add(pos)
            }
        }
    }

    /** Works out a selection off the UI thread, with the loading layer over the map meanwhile. */
    private fun selectInBackground(title: String, compute: (LayerStack, Set<ChunkPos>) -> Set<ChunkPos>) {
        val stack = activeStack ?: return
        val before = selection
        if (loading != null) return
        loading = LoadingStep(title, waitForTiles = false)
        scope.launch(Dispatchers.Default) {
            val result = runCatching { compute(stack, before) }
            withContext(Dispatchers.Main) {
                loading = null
                result.onSuccess { selection = it }.onFailure { notify("Selection failed: ${it.message}", warning = true) }
            }
        }
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

    // ---- Turning, raising and cutting pasted chunks ----

    private data class ViewKey(val ref: ChunkRef, val pos: ChunkPos, val under: WorldSource?, val conversion: Conversion?)
    private val transformedViews = HashMap<ViewKey, TransformedChunk>()

    /**
     * What to draw for [ref] shown at [pos] by a layer with [blockMap]: its own world and position, or a view of
     * the chunk as it will be exported, for chunks turned, raised or cut (over the original world's chunk there,
     * unless erased) or with blocks the world they go into doesn't have.
     */
    fun drawSource(ref: ChunkRef, pos: ChunkPos, stack: LayerStack, blockMap: Map<String, String> = emptyMap()): Pair<WorldSource, ChunkPos> {
        if (!ChunkTransform.supports(ref.world)) return ref.world to ref.pos
        val conversion = conversionFor(ref.world, stack, blockMap)
        if (!ref.transformed && conversion?.remaps != true) return ref.world to ref.pos
        val under = (stack.world as? AnvilWorld)?.takeIf { ref.layered && !stack.base.isErased(pos) }
        val view = transformedViews.getOrPut(ViewKey(ref, pos, under, conversion)) { TransformedChunk(ref, pos, under, conversion) }
        return view to ref.pos
    }

    // ---- Block mapping between worlds ----

    private val conversions = java.util.concurrent.ConcurrentHashMap<Triple<WorldSource, WorldSource, Map<String, String>>, Conversion>()

    /** How chunks of [source] become chunks of [stack]'s world with [blockMap]; null for its own chunks and demo worlds. */
    fun conversionFor(source: WorldSource, stack: LayerStack, blockMap: Map<String, String> = emptyMap()): Conversion? {
        val base = stack.world as? AnvilWorld ?: return null
        val world = source as? AnvilWorld ?: return null
        if (world == base) return null
        return conversions.getOrPut(Triple(world, base, blockMap)) { Conversion(world, base, blockMap) }
    }

    /** The layer whose block mapping panel is open; null when it is closed. */
    var blockMapLayer by mutableStateOf<Long?>(null)

    /** The block of that panel whose replacement is being picked (second panel); null when none. */
    var blockPickerKey by mutableStateOf<String?>(null)

    fun closeBlockMapping() {
        blockPickerKey = null
        blockMapLayer = null
    }

    /** The worlds a layer's chunks come from. */
    fun sourceWorldsOf(layer: Layer): Set<WorldSource> = when (layer) {
        is ChunkLayer -> layer.chunks.values.mapTo(HashSet()) { it.world }
        is BaseLayer -> layer.overrides.values.mapNotNullTo(HashSet()) { (it as? ChunkEdit.Place)?.source?.world }
    }

    /** True when some of the layer's chunks come from a world with blocks [stack]'s world doesn't have. */
    fun needsBlockMap(layer: Layer, stack: LayerStack): Boolean =
        sourceWorldsOf(layer).any { conversionFor(it, stack)?.remaps == true }

    /** Maps a block of [layerId]'s chunks to [target] (`minecraft:oak_planks`), or back to automatic for null. */
    fun mapBlock(layerId: Long, key: String, target: String?) {
        commit(if (target == null) "Automatic block mapping" else "Map block") { it.mapBlock(layerId, key, target) }
    }

    /** Reads the tables Minecraft upgrades old worlds with from the newest installed jar, then redraws converted chunks. */
    fun loadUpgradeTables() {
        scope.launch(Dispatchers.IO) {
            val tables = TextureFiles.findClientJar()?.let { MinecraftTables.read(it) } ?: return@launch
            Upgrade.tables = tables
            withContext(Dispatchers.Main) {
                transformedViews.clear()
                tiles.reloadWorlds(worlds.mapTo(HashSet()) { it.id })
            }
        }
    }

    /** The chunks turning, raising and cutting act on: the selection in the active pasted layer, or all of it. */
    fun transformTargets(): Map<ChunkPos, ChunkRef> {
        val layer = activeStack?.active as? ChunkLayer ?: return emptyMap()
        val selected = layer.chunks.filterKeys { it in selection }
        return selected.ifEmpty { layer.chunks }
    }

    /** [transformTargets] if they may be changed this way, otherwise null after telling the user why. */
    private fun checkedTargets(layered: Boolean): Pair<ChunkLayer, Map<ChunkPos, ChunkRef>>? {
        val layer = editableLayer() ?: return null
        if (layer !is ChunkLayer) {
            notify("Turning, raising and cutting work on pasted layers: copy the chunks and paste them as a layer first.", warning = true)
            return null
        }
        val targets = transformTargets()
        if (targets.isEmpty()) return null
        val base = activeStack!!.world
        targets.values.firstOrNull { !ChunkTransform.supports(it.world) }?.let {
            notify("\"${it.world.name}\" is a demo world; its chunks can't be turned, raised or cut.", warning = true)
            return null
        }
        if (layered) targets.values.firstOrNull { (it.world.dataVersion ?: 0) > (base.dataVersion ?: 0) }?.let {
            notify("Chunks of ${it.world.versionLabel} can't be laid over \"${base.name}\" (${base.versionLabel}): worlds only upgrade, never downgrade.", warning = true)
            return null
        }
        return layer to targets
    }

    /** Turns the chunks by [turns] quarter turns clockwise (seen from above) around the middle of their area. */
    fun turnChunks(turns: Int) {
        val (layer, targets) = checkedTargets(layered = false) ?: return
        val t = Rotation.normalize(turns)
        if (t == 0) return
        // The middle of the area as a point (chunk corners are whole numbers, so it can be half a chunk off a cell),
        // not a chunk: turning the cells around a chunk's corner would swing the whole area sideways.
        val cx = (targets.keys.minOf { it.x } + targets.keys.maxOf { it.x } + 1) / 2.0
        val cz = (targets.keys.minOf { it.z } + targets.keys.maxOf { it.z } + 1) / 2.0
        fun turned(pos: ChunkPos): ChunkPos {
            var dx = pos.x + 0.5 - cx
            var dz = pos.z + 0.5 - cz
            repeat(t) { val nx = -dz; dz = dx; dx = nx }
            // A bounding box with one odd and one even side lands half a chunk off the grid: round the same way everywhere.
            return ChunkPos(Math.floor(cx + dx).toInt(), Math.floor(cz + dz).toInt())
        }
        val wasSelected = targets.keys.filterTo(HashSet()) { it in selection }
        if (commit("Turn chunks") { it.reshape(layer.id, targets.keys) { pos, ref -> turned(pos) to ref.copy(turns = Rotation.normalize(ref.turns + t)) } }) {
            selection = selection - wasSelected + wasSelected.map(::turned)
        }
    }

    /** Moves the chunks [delta] blocks up (or down); the original world shows below them. */
    fun raiseChunks(delta: Int) {
        val (layer, targets) = checkedTargets(layered = true) ?: return
        val world = activeStack!!.world
        val limit = world.maxY - world.minY
        commit(if (delta > 0) "Raise chunks" else "Lower chunks") {
            it.reshape(layer.id, targets.keys) { pos, ref -> pos to ref.copy(dy = (ref.dy + delta).coerceIn(-limit, limit)) }
        }
    }

    /** Sets the height of the chunks to [dy] blocks above where they were copied from. */
    fun setChunkHeight(dy: Int) {
        val (layer, targets) = checkedTargets(layered = dy != 0 || targets().any { it.layered }) ?: return
        val limit = activeStack!!.world.let { it.maxY - it.minY }
        commit("Set height") { it.reshape(layer.id, targets.keys) { pos, ref -> pos to ref.copy(dy = dy.coerceIn(-limit, limit)) } }
    }

    private fun targets() = transformTargets().values

    /** Keeps the original world below height [y] under the chunks; null takes the chunks down to their bottom again. */
    fun cutChunks(y: Int?) {
        val (layer, targets) = checkedTargets(layered = y != null) ?: return
        commit(if (y == null) "Remove cut" else "Cut chunks at $y") {
            it.reshape(layer.id, targets.keys) { pos, ref -> pos to ref.copy(cut = y) }
        }
    }

    /** Undoes turning, raising and cutting of the chunks (they stay where they are). */
    fun resetChunkTransforms() {
        val (layer, targets) = checkedTargets(layered = false) ?: return
        commit("Reset chunks") { it.reshape(layer.id, targets.keys) { pos, ref -> pos to ref.plain } }
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

    // ---- Projects ----

    /** File the project was last saved to or opened from; null for a new, unsaved project. */
    var projectFile by mutableStateOf<Path?>(null)
        private set
    private var changeCount by mutableIntStateOf(0)
    private var savedChangeCount by mutableIntStateOf(0)

    /** True when worlds or layers changed since the project was last saved, opened or created. */
    val projectDirty: Boolean get() = changeCount != savedChangeCount
    val projectName: String get() = projectFile?.fileName?.toString()?.removeSuffix(".${ProjectFile.EXTENSION}") ?: "Untitled"

    private fun changed() { changeCount++ }

    /** Treats the current worlds and layers as saved (e.g. the demo worlds shown at startup). */
    fun markSaved() { savedChangeCount = changeCount }

    /** Writes the project to [file]; returns false (with a message) when that fails. */
    fun saveProject(file: Path): Boolean {
        val snapshot = ProjectSnapshot(worlds.mapNotNull { stacks[it.id] }, activeWorld, selection, editorSettings(), worlds.associateWith(::texturesOf))
        return runCatching { ProjectFile.write(file, snapshot, AppInfo.version) }
            .onSuccess {
                projectFile = file
                markSaved()
                WorldLibrary.rememberProject(file)
                notify("Saved project ${file.fileName}")
            }
            .onFailure { notify("Could not save ${file.fileName}: ${it.message}", warning = true) }
            .isSuccess
    }

    /** Closes every world and starts an empty, unsaved project. */
    fun newProject() {
        replaceWorlds(emptyList())
        projectFile = null
        loading = null
        markSaved()
    }

    /**
     * Opens a project in the background: its worlds (reusing ones already open), layers,
     * selection, camera and settings. Worlds that can't be found are left out with a warning.
     */
    fun openProject(file: Path) {
        if (busy) return notify("Wait for the current task to finish", warning = true)
        busy = true
        val title = "Opening ${file.fileName}"
        loading = LoadingStep(title, "Reading project", waitForTiles = false)
        val open = worlds.associateBy { WorldLocation.of(it) }
        val throttle = ProgressThrottle()
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val doc = ProjectFile.read(file)
                val failed = ArrayList<String>()
                val opened = doc.worlds.mapIndexed { i, location ->
                    open[location] ?: runCatching {
                        location.open { done, total ->
                            if (throttle.ready(done == total)) scope.launch(Dispatchers.Main) {
                                if (busy) loading = LoadingStep(title, "World ${i + 1} / ${doc.worlds.size}: scanning region files $done / $total",
                                    done.toFloat() / total, waitForTiles = false)
                            }
                        }
                    }.onFailure { failed += ((location as? WorldLocation.Folder)?.path?.fileName ?: location).toString() }.getOrNull()
                }
                Triple(doc, opened, failed)
            }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { (doc, opened, failed) -> applyProject(file, doc, opened, failed) }
                    .onFailure {
                        loading = null
                        notify("Could not open ${file.fileName}: ${it.message}", warning = true)
                    }
            }
        }
    }

    private fun applyProject(file: Path, doc: ProjectDocument, opened: List<WorldSource?>, failed: List<String>) {
        val restored = doc.stacks(opened)
        replaceWorlds(opened.filterNotNull().map { w -> restored.stacks.firstOrNull { it.world == w } ?: LayerStack.of(w) })
        for ((world, textures) in opened.zip(doc.textures)) if (world != null && !textures.isDefault) worldTextures[world.id] = textures
        applyEditorSettings(doc.editor)
        refreshTextures()
        projectFile = file
        WorldLibrary.rememberProject(file)
        for (world in opened) (world as? AnvilWorld)?.folder?.let(WorldLibrary::remember)
        val active = doc.activeWorld?.let(opened::getOrNull)?.takeIf { it in worlds } ?: worlds.firstOrNull()
        if (active == null) loading = null else {
            openWorld(active)
            selection = doc.selection.filterTo(HashSet(), ::isSelectable)
            restoreCamera(doc.editor)
        }
        markSaved()
        notify(
            when {
                failed.isNotEmpty() -> "Opened ${projectName}, but could not open ${failed.joinToString()}" +
                    if (restored.missingChunks > 0) " (${restored.missingChunks} chunks from it left out)" else ""
                else -> "Opened project ${projectName}"
            },
            warning = failed.isNotEmpty(),
        )
    }

    /** Swaps every open world for [next] (worlds in both stay open) and forgets undo, clipboard and selection. */
    private fun replaceWorlds(next: List<LayerStack>) {
        val keep = next.mapTo(HashSet()) { it.world }
        for (world in worlds) if (world !in keep) (world as? AnvilWorld)?.close()
        history.removeIf { true }
        historyRevision++
        clipboard = null
        pasteMode = false
        selection = emptySet()
        hovered = null
        activeWorld = null
        worlds.clear()
        stacks.clear()
        worldTextures.clear()
        loadedTextures.clear()
        for (stack in next) if (worlds.none { it.id == stack.world.id }) {
            worlds += stack.world
            stacks[stack.world.id] = stack
        }
    }

    /** View and editor preferences stored in a project, as XML attribute names and values. */
    private fun editorSettings(): Map<String, String> = linkedMapOf(
        "render-mode" to renderSettings.mode.name,
        "height-scale" to renderSettings.heightScale.toString(),
        "chunk-borders" to renderSettings.chunkBorders.toString(),
        "flat" to renderSettings.flat.toString(),
        "layer-colors" to showLayerColors.toString(),
        "rotation" to rotation.toString(),
        "center-u" to view.centerU.toString(),
        "center-v" to view.centerV.toString(),
        "zoom" to view.zoom.toString(),
        "tool" to tool.name,
        "select-mode" to selectMode.name,
        "brush-size" to brushSize.toString(),
        "left-dock" to leftDockVisible.toString(),
        "left-tab" to leftTab.name,
        "left-dock-width" to leftDockWidth.value.toString(),
        "right-dock" to rightDockVisible.toString(),
        "right-dock-width" to rightDockWidth.value.toString(),
        "inspector" to inspectorVisible.toString(),
    )

    /** Applies stored settings; missing or unreadable values keep their current setting. */
    private fun applyEditorSettings(s: Map<String, String>) {
        fun bool(key: String) = s[key]?.toBooleanStrictOrNull()
        fun float(key: String) = s[key]?.toFloatOrNull()
        updateRenderSettings(renderSettings.copy(
            mode = s["render-mode"]?.let { m -> RenderMode.entries.firstOrNull { it.name == m } } ?: renderSettings.mode,
            heightScale = float("height-scale") ?: renderSettings.heightScale,
            chunkBorders = bool("chunk-borders") ?: renderSettings.chunkBorders,
            flat = bool("flat") ?: renderSettings.flat,
        ))
        showLayerColors = bool("layer-colors") ?: showLayerColors
        s["rotation"]?.toIntOrNull()?.let { rotation = Rotation.normalize(it) }
        s["tool"]?.let { t -> Tool.entries.firstOrNull { it.name == t } }?.let { tool = it }
        s["select-mode"]?.let { m -> SelectMode.entries.firstOrNull { it.name == m } }?.let { selectMode = it }
        s["brush-size"]?.toIntOrNull()?.takeIf { it in BRUSH_SIZES }?.let { brushSize = it }
        leftDockVisible = bool("left-dock") ?: leftDockVisible
        s["left-tab"]?.let { t -> DockTab.entries.firstOrNull { it.name == t } }?.let { leftTab = it }
        float("left-dock-width")?.let { leftDockWidth = it.dp.coerceIn(200.dp, 520.dp) }
        rightDockVisible = bool("right-dock") ?: rightDockVisible
        float("right-dock-width")?.let { rightDockWidth = it.dp.coerceIn(200.dp, 520.dp) }
        inspectorVisible = bool("inspector") ?: inspectorVisible
    }

    /** Puts the camera back where it was saved, instead of the fit [openWorld] just did. */
    private fun restoreCamera(s: Map<String, String>) {
        val u = s["center-u"]?.toDoubleOrNull() ?: return
        val v = s["center-v"]?.toDoubleOrNull() ?: return
        val zoom = s["zoom"]?.toDoubleOrNull() ?: return
        view = IsoView(u, v, zoom.coerceIn(IsoView.MIN_ZOOM, IsoView.MAX_ZOOM), flat = renderSettings.flat)
        fitPending = false
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
        val flat = renderSettings.flat
        val sizeX = bounds.maxX - bounds.minX + 1
        val sizeZ = bounds.maxZ - bounds.minZ + 1
        // From above a chunk is a 32-unit square; a quarter turn swaps which side is across the screen.
        val spanU = if (flat) 32.0 * (if (rotation % 2 == 0) sizeX else sizeZ) else (sizeX + sizeZ) * 16.0
        val spanV = if (flat) 32.0 * (if (rotation % 2 == 0) sizeZ else sizeX) else spanU / 2 + 96 * hs
        var zoom = minOf(viewportSize.width / spanU, viewportSize.height / spanV) * 0.92
        var centerX = bounds.centerBlockX
        var centerZ = bounds.centerBlockZ
        // A chunk covers 256 square iso units (1024 from above), so this zoom keeps about MAX_CHUNKS_ON_FIT chunks
        // on screen. Bigger worlds start around their spawn instead of rendering tens of thousands of chunks.
        val chunkArea = if (flat) 1024.0 else 256.0
        val budgetZoom = sqrt(viewportSize.width.toDouble() * viewportSize.height / (chunkArea * MAX_CHUNKS_ON_FIT))
        if (zoom < budgetZoom) {
            zoom = budgetZoom
            activeWorld?.spawn?.let { (x, z) -> centerX = x.toDouble(); centerZ = z.toDouble() }
        }
        val (cx, cz) = Rotation.point(rotation, centerX, centerZ)
        view = IsoView.centeredOn(cx, SURFACE_Y, cz, hs, zoom, flat)
        lastFitView = view
    }

    /** Turns the view by [delta] quarter turns (+1 = clockwise on screen) around the point at the screen center. */
    fun rotate(delta: Int) {
        val hs = renderSettings.heightScale.toDouble()
        val wasFit = view == lastFitView
        // Screen center on the surface plane, in rotated coordinates, then back to world coordinates.
        val (vx, vz) = view.ground(view.centerU, view.centerV, SURFACE_Y, hs)
        val (wx, wz) = Rotation.point(4 - rotation, vx, vz)
        rotation = Rotation.normalize(rotation + delta)
        val (rx, rz) = Rotation.point(rotation, wx, wz)
        view = IsoView.centeredOn(rx, SURFACE_Y, rz, hs, view.zoom, view.flat)
        if (wasFit) lastFitView = view
        hovered = null
    }

    /** Converts between real chunk positions and the rotated positions the camera works with. */
    fun toView(pos: ChunkPos) = Rotation.chunk(rotation, pos)
    fun fromView(pos: ChunkPos) = Rotation.chunk(4 - rotation, pos)

    fun dispose() {
        scope.cancel()
        textureSets.values.forEach { (assets, models) ->
            models.cache?.save()
            assets.close()
        }
        ModLocator.saveArchives()
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
        /** 1.13: block names from here on match the client jar of the world's own version. Older worlds need a newer jar. */
        private const val FLATTENING_DATA_VERSION = 1519
    }
}

/** The tabs over the main area. The map can't be closed. */
enum class MainTab { Map, Atlas }

/** Which layers the Analyze panel reads in each chunk. */
enum class AnalyzeScope(val label: String) {
    /** The top visible layer: what the map shows and the export writes. */
    Visible("Selected"),
    Layer("Active layer"),
    /** Every layer with a chunk there, hidden ones too. */
    All("All layers"),
}

/** Chunks to analyze, with [layerId] for [AnalyzeScope.Layer]; [what] names them ("selection", "layer ..."). */
data class AnalyzeRequest(
    val positions: Set<ChunkPos>,
    val scope: AnalyzeScope,
    val layerId: Long,
    val what: String,
    val fromSelection: Boolean,
    val run: Int,
)

/** A right-click on the map at [at] (pixels in the map): the [chunk] under it, or null on empty ground, and the [ground] position. */
class MapMenu(val at: IntOffset, val chunk: ChunkPos?, val ground: ChunkPos)
