package minecartographer.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import minecartographer.model.ModelEditorState
import minecartographer.textures.BlockModels
import minecartographer.textures.SourceModel
import minecartographer.ui.ThumbCache

/**
 * A tab of the main area. Each one brings its own tools, docks, menus, shortcuts and status bar;
 * only File, Help, the tab strip and the status bar's frame are shared.
 */
sealed interface Workspace {
    /** The world map. Always open. */
    data object Map : Workspace

    /** The texture atlas of the active world. */
    data object Atlas : Workspace

    /** A model being built; [id] keys its [ModelEditorState]. */
    data class Model(val id: Int) : Workspace
}

/** The open tabs, which one is shown, and the state each non-map workspace owns. */
class Workspaces {
    val open = mutableStateListOf<Workspace>(Workspace.Map)

    var active: Workspace by mutableStateOf(Workspace.Map)
        private set

    val atlas = AtlasState()

    private val models = HashMap<Int, ModelEditorState>()
    private var nextModelId = 1

    fun modelOf(id: Workspace.Model): ModelEditorState? = models[id.id]

    /** The model tab that is shown, if one is. */
    fun activeModel(): ModelEditorState? = (active as? Workspace.Model)?.let(::modelOf)

    fun show(workspace: Workspace) {
        if (workspace in open) active = workspace
    }

    fun openAtlas() {
        if (Workspace.Atlas !in open) open += Workspace.Atlas
        active = Workspace.Atlas
    }

    fun newModel(): Workspace.Model {
        val id = nextModelId++
        val workspace = Workspace.Model(id)
        models[id] = ModelEditorState(id, "Untitled model${if (id > 1) " $id" else ""}")
        open += workspace
        active = workspace
        return workspace
    }

    /** A new tab with [source] opened in it. */
    fun openModel(source: SourceModel): Workspace.Model {
        val workspace = newModel()
        models.getValue(workspace.id).also {
            it.name = source.id.substringAfterLast('/')
            it.load(source)
        }
        return workspace
    }

    /** Closes a tab; the map stays. The tab to its left (or the map) takes over when it was the shown one. */
    fun close(workspace: Workspace) {
        if (workspace == Workspace.Map) return
        val index = open.indexOf(workspace)
        if (index < 0) return
        open.removeAt(index)
        if (workspace is Workspace.Model) models.remove(workspace.id)
        if (workspace == Workspace.Atlas) atlas.clear()
        if (active == workspace) active = open.getOrElse(index - 1) { Workspace.Map }
    }
}

/**
 * What the texture atlas shows and what is picked in it. The docks and the grid all read it:
 * mods and filter on the left, the grid in the middle, the picked texture on the right.
 */
class AtlasState {
    var models: BlockModels? by mutableStateOf(null)
        private set

    /** Texture bitmaps of [models], shared by the grid and the details. */
    internal var thumbs: ThumbCache? = null
        private set

    var mod by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var sizeIndex by mutableStateOf(1)
    var selected by mutableStateOf<String?>(null)

    /** Every block texture, vanilla first. */
    val all by derivedStateOf {
        models?.assets?.blockTextures?.values?.flatten()?.distinct()
            ?.sortedWith(compareBy({ it.substringBefore(':') != "minecraft" }, { it })).orEmpty()
    }

    val byMod by derivedStateOf { all.groupBy { it.substringBefore(':') } }

    /** The textures that pass the mod and the filter. */
    val shown by derivedStateOf {
        val q = query.trim().lowercase()
        (mod?.let { byMod[it] } ?: all).let { list -> if (q.isEmpty()) list else list.filter { q in it.lowercase() } }
    }

    /** The packs load in the background: the view looks again until they are there. A new pack starts from nothing. */
    fun update(next: BlockModels?) {
        if (next === models) return
        models = next
        thumbs = next?.let(::ThumbCache)
        mod = null
        selected = null
    }

    fun clear() = update(null)
}
