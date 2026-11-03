package minecartographer.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import minecartographer.core.edit.UndoHistory
import minecartographer.textures.Dir
import minecartographer.textures.SourceModel
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ModelTool(val label: String, val key: Key, val keyLabel: String, val hint: String) {
    Select("Select", Key.Q, "Q", "Click an element or a face to pick it. Shift+click adds to the selection."),
    Move("Move", Key.W, "W", "Drag an arrow to move the selection along that axis."),
    Rotate("Rotate", Key.E, "E", "Drag a ring handle to turn the selection about that axis, in steps of 22.5°."),
    Resize("Resize", Key.R, "R", "Drag a handle on a face to push that side in or out."),
}

/** How the viewport draws the model: the faces, the edges of every element, or both. */
enum class ModelShading(val label: String) {
    Wireframe("Wireframe"),
    Solid("Solid"),
    SolidWire("Solid + wire"),
}

/** One model tab: the document, what is picked, the camera, and the undo history of the document. */
class ModelEditorState(val id: Int, name: String) {
    var name by mutableStateOf(name)

    /** The model this one was opened from (`ns:block/name`), if any. */
    var origin by mutableStateOf<String?>(null)
        private set

    var doc by mutableStateOf(ModelDoc())
        private set

    /** Picked elements by id. */
    var selection by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** The face picked in the viewport or the inspector, as a direction; null when a whole element is. */
    var face by mutableStateOf<Int?>(null)

    /** Elements switched off in the outliner: not drawn, but still in the model. */
    var hidden by mutableStateOf<Set<Int>>(emptySet())

    var tool by mutableStateOf(ModelTool.Select)
    var snap by mutableStateOf(1f)
    var camera by mutableStateOf(ModelCamera.ISO)
    var shading by mutableStateOf(ModelShading.Solid)

    /** The floor grid, the block outlines and the axes at the origin. */
    var showGrid by mutableStateOf(true)

    /** The names of the world directions lying on the floor beside each side of the grid. */
    var showSides by mutableStateOf(true)

    /**
     * Unlocked: element rotations may have any angle, as some loaders read them. Locked (the default): only what the
     * game's own parser takes, multiples of 22.5 within -45..45, and more is worked into the box ([QuarterTurns]).
     */
    var freeAngles by mutableStateOf(false)

    /** The texture variable whose texture is being chosen from the atlas, while that window is open. */
    var pickingTexture by mutableStateOf<String?>(null)

    /** Unsaved changes: shown as a dot on the tab and asked about when it is closed. */
    var dirty by mutableStateOf(false)

    private class Entry(val label: String, val before: ModelDoc, val after: ModelDoc, val selBefore: Set<Int>, val selAfter: Set<Int>)

    private val history = UndoHistory<Entry>()

    // The history is not observable by itself.
    private var revision by mutableStateOf(0)

    val canUndo get() = revision.let { history.canUndo }
    val canRedo get() = revision.let { history.canRedo }
    val undoLabel get() = revision.let { history.nextUndo?.label }
    val redoLabel get() = revision.let { history.nextRedo?.label }

    /** Replaces the document with [source] and starts a fresh history. Parents are folded into the model. */
    fun load(source: SourceModel) {
        doc = ModelDoc.from(source)
        origin = source.id
        // A model that already has other angles keeps them: editing it must not round them away.
        freeAngles = doc.elements.any { e -> e.rotation?.angle?.let { !isGameAngle(it) } == true }
        selection = emptySet()
        face = null
        hidden = emptySet()
        camera = ModelCamera.ISO
        dirty = false
        history.removeIf { true }
        revision++
    }

    /** The view and tool settings of this tab as plain text, for a project file. */
    fun settings(): Map<String, String> = linkedMapOf(
        "camera" to listOf(camera.yaw, camera.pitch, camera.zoom, camera.panX, camera.panY).joinToString(","),
        "tool" to tool.name,
        "snap" to snap.toString(),
        "shading" to shading.name,
        "grid" to showGrid.toString(),
        "sides" to showSides.toString(),
        "free-angles" to freeAngles.toString(),
        // By position in the list, which a saved model keeps.
        "hidden" to doc.elements.withIndex().filter { it.value.id in hidden }.joinToString(",") { it.index.toString() },
    )

    /** Puts a saved model back: its [document], where it came from and the [settings] that [settings] wrote. Nothing is unsaved. */
    fun restore(document: ModelDoc, origin: String?, settings: Map<String, String>) {
        doc = document
        this.origin = origin
        selection = emptySet()
        face = null
        history.removeIf { true }
        settings["camera"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 }?.let {
            camera = ModelCamera(it[0], it[1], it[2], it[3], it[4])
        }
        ModelTool.entries.firstOrNull { it.name == settings["tool"] }?.let { tool = it }
        settings["snap"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { snap = it }
        ModelShading.entries.firstOrNull { it.name == settings["shading"] }?.let { shading = it }
        settings["grid"]?.toBooleanStrictOrNull()?.let { showGrid = it }
        settings["sides"]?.toBooleanStrictOrNull()?.let { showSides = it }
        freeAngles = settings["free-angles"]?.toBooleanStrictOrNull() ?: document.elements.any { e -> e.rotation?.angle?.let { !isGameAngle(it) } == true }
        hidden = settings["hidden"].orEmpty().split(',').mapNotNull { it.toIntOrNull() }.mapNotNull { document.elements.getOrNull(it)?.id }.toSet()
        dirty = false
        revision++
    }

    // ---- selection ----

    fun select(id: Int, add: Boolean = false, face: Int? = null) {
        selection = if (add) (if (id in selection) selection - id else selection + id) else setOf(id)
        this.face = face
    }

    fun selectAll() {
        selection = doc.elements.mapTo(HashSet()) { it.id }
        face = null
    }

    fun clearSelection() {
        selection = emptySet()
        face = null
    }

    val selected get() = doc.elements.filter { it.id in selection }

    fun toggleHidden(id: Int) {
        hidden = if (id in hidden) hidden - id else hidden + id
    }

    // ---- history ----

    /** Makes [next] the document as one undo step. Nothing happens when nothing changed. */
    fun commit(label: String, next: ModelDoc, selectionAfter: Set<Int> = selection) {
        if (next == doc && selectionAfter == selection) return
        history.push(Entry(label, doc, next, selection, selectionAfter))
        apply(next, selectionAfter)
        revision++
    }

    fun undo() {
        val e = history.undo() ?: return
        apply(e.before, e.selBefore)
        revision++
    }

    fun redo() {
        val e = history.redo() ?: return
        apply(e.after, e.selAfter)
        revision++
    }

    private fun apply(next: ModelDoc, sel: Set<Int>) {
        doc = next
        selection = sel.filterTo(HashSet()) { id -> next.element(id) != null }
        if (selection.isEmpty()) face = null
        dirty = true
    }

    private var gestureBase: ModelDoc? = null

    /** Starts a drag: the document changes live and becomes one undo step in [endGesture]. */
    fun beginGesture() {
        gestureBase = doc
    }

    fun endGesture(label: String) {
        val base = gestureBase ?: return
        gestureBase = null
        if (base == doc) return
        // Put the base back and commit, so the history holds the whole drag as one entry.
        val result = doc
        doc = base
        commit(label, result)
    }

    private fun live(next: ModelDoc) {
        doc = next
    }

    // ---- editing ----

    /** Applies [change] to every picked element as one undo step. */
    fun edit(label: String, change: (ModelElement) -> ModelElement) {
        if (selection.isEmpty()) return
        commit(label, doc.update(selection, change))
    }

    fun addCube() {
        val (next, id) = doc.withCube()
        commit("Add cube", next, setOf(id))
        face = null
    }

    fun deleteSelected() {
        if (selection.isEmpty()) return
        commit("Delete", doc.remove(selection), emptySet())
    }

    fun duplicateSelected() {
        if (selection.isEmpty()) return
        val (next, ids) = doc.duplicate(selection)
        commit("Duplicate", next, ids.toSet())
    }

    fun mirrorSelected(axis: Int) {
        if (selection.isEmpty()) return
        commit("Mirror ${"XYZ"[axis]}", doc.mirror(selection, axis))
    }

    /** Moves the picked elements by [delta] model units along [axis]. */
    fun nudge(axis: Int, delta: Float) = edit("Move") { it.shifted(axis, delta) }

    fun moveElement(id: Int, toIndex: Int) {
        val list = doc.elements.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val e = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), e)
        commit("Reorder", doc.copy(elements = list))
    }

    /** Rounds [value] to the grid. */
    fun snapped(value: Float) = if (snap <= 0f) value else (value / snap).roundToInt() * snap

    // Drags: [amount] is the total since the drag began, from the base document.

    fun dragMove(axis: Int, amount: Float) {
        val base = gestureBase ?: return
        live(base.update(selection) { it.shifted(axis, snapped(amount)) })
    }

    /** Pushes the side [dir] of the picked elements by [amount], keeping every element at least [MIN_SIZE] thick. */
    fun dragResize(dir: Int, amount: Float) {
        val base = gestureBase ?: return
        val axis = Dir.axisOf(dir)
        val positive = dir and 1 == 1
        live(base.update(selection) { e ->
            if (positive) e.copy(to = e.to.with(axis, (snapped(e.to[axis] + amount)).coerceIn(e.from[axis] + MIN_SIZE, MAX_COORD)))
            else e.copy(from = e.from.with(axis, (snapped(e.from[axis] + amount)).coerceIn(MIN_COORD, e.to[axis] - MIN_SIZE)))
        })
    }

    /**
     * Turns the picked elements about [axis] by [degrees] from where the drag began, in steps of 22.5. The game only
     * takes -45..45 on one axis, so what goes beyond is worked into the box (see [QuarterTurns]).
     */
    fun dragRotate(axis: Int, degrees: Float) {
        val base = gestureBase ?: return
        live(base.update(selection) { e ->
            val old = e.rotation?.takeIf { it.axis == axis }
            val total = (old?.angle ?: 0f) + degrees
            // Unlocked, the drag goes in half degrees.
            if (freeAngles) e.withAngle(axis, (total * 2).roundToInt() / 2f) else QuarterTurns.withAngle(e, axis, total)
        })
    }

    /** Sets the angle of the picked elements' rotation about [axis]; locked, an angle beyond 45 turns the box itself. */
    fun setAngle(axis: Int, angle: Float) = edit("Rotate") { if (freeAngles) it.withAngle(axis, angle) else QuarterTurns.withAngle(it, axis, angle) }

    private fun ModelElement.withAngle(axis: Int, angle: Float) =
        copy(rotation = ElementRotation(axis, angle, rotation?.origin ?: center, rotation?.rescale ?: false))

    // ---- textures and faces ----

    fun setTexture(variable: String, textureId: String) = commit("Texture", doc.copy(textures = doc.textures + (variable to textureId)))

    fun renameTexture(variable: String, to: String) {
        val clean = to.trim().removePrefix("#")
        if (clean.isEmpty() || clean == variable || clean in doc.textures) return
        val textures = LinkedHashMap<String, String>()
        for ((k, v) in doc.textures) textures[if (k == variable) clean else k] = v
        val elements = doc.elements.map { e ->
            e.copy(faces = e.faces.mapValues { (_, f) -> if (f.texture == "#$variable") f.copy(texture = "#$clean") else f })
        }
        commit("Rename texture", doc.copy(textures = textures, elements = elements))
    }

    fun addTextureVariable() {
        var n = 1
        while ("texture$n" in doc.textures) n++
        commit("Add texture", doc.copy(textures = doc.textures + ("texture$n" to "minecraft:block/stone")))
    }

    fun removeTextureVariable(variable: String) {
        val elements = doc.elements.map { e ->
            e.copy(faces = e.faces.mapValues { (_, f) -> if (f.texture == "#$variable") f.copy(texture = null) else f })
        }
        commit("Remove texture", doc.copy(textures = doc.textures - variable, elements = elements))
    }

    /** Changes one face of every picked element that has it. */
    fun editFace(dir: Int, label: String = "Face", change: (ModelFace) -> ModelFace) =
        edit(label) { e -> e.faces[dir]?.let { e.copy(faces = e.faces + (dir to change(it))) } ?: e }

    /** Adds the face to the picked elements that lack it, or takes it away from those that have it. */
    fun toggleFace(dir: Int) {
        val first = selected.firstOrNull() ?: return
        val add = dir !in first.faces
        val texture = doc.textures.keys.firstOrNull()?.let { "#$it" }
        edit(if (add) "Add face" else "Remove face") { e ->
            e.copy(faces = if (add) e.faces + (dir to ModelFace(texture)) else e.faces - dir)
        }
    }

    fun resetView() {
        camera = ModelCamera.ISO
    }

    companion object {
        /** An angle the game's parser takes: -45, -22.5, 0, 22.5 or 45. */
        fun isGameAngle(angle: Float) = abs(angle) <= 45f && abs(angle / 22.5f - (angle / 22.5f).roundToInt()) < 1e-3f

        const val MIN_COORD = -16f
        const val MAX_COORD = 32f
        const val MIN_SIZE = 0f
    }
}

/** The element moved by [delta] along [axis], with its rotation origin. */
fun ModelElement.shifted(axis: Int, delta: Float): ModelElement {
    val lo = ModelEditorState.MIN_COORD
    val hi = ModelEditorState.MAX_COORD
    // Stay inside the limits without squashing the element.
    val d = delta.coerceIn(lo - from[axis], hi - to[axis])
    return copy(from = from.with(axis, from[axis] + d), to = to.with(axis, to[axis] + d), rotation = rotation?.copy(origin = rotation.origin.with(axis, rotation.origin[axis] + d)))
}
