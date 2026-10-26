package minecartographer.ui

import androidx.compose.runtime.Composable
import minecartographer.core.ChunkPos
import minecartographer.core.edit.ChunkLayer
import minecartographer.editor.EditorState
import minecartographer.editor.MapMenu
import minecartographer.editor.SelectMode

/** The right-click menu on the map: for the selection when clicked on it, else for the chunk or ground under the cursor. */
@Composable
fun MapContextMenu(state: EditorState) {
    val menu = state.mapMenu ?: return
    val entries = mapMenuEntries(state, menu)
    if (entries.isEmpty()) {
        state.mapMenu = null
        return
    }
    ContextMenu(menu.at, entries) { state.mapMenu = null }
}

private fun mapMenuEntries(state: EditorState, menu: MapMenu): List<MenuEntry> {
    val stack = state.activeStack ?: return emptyList()
    val chunk = menu.chunk
    val here = chunk ?: menu.ground
    val selection = state.selection
    val onSelection = chunk != null && chunk in selection
    val under = chunk?.let(stack::layerAt)
    val canPaste = state.clipboard != null
    return buildList {
        if (onSelection) {
            add(MenuItem("Analyze selection…") { state.analyzeSelection() })
            add(MenuSeparator)
            add(MenuItem("Cut", "Ctrl+X") { state.cut() })
            add(MenuItem("Copy", "Ctrl+C") { state.copy() })
            add(MenuItem("Copy visible", "Ctrl+Shift+C") { state.copy(visible = true) })
            add(MenuItem("Paste here", enabled = canPaste) { state.pasteAt(here) })
            add(MenuItem("Paste in place", "Ctrl+Shift+V", canPaste) { state.pasteInPlace() })
            add(MenuSeparator)
            if (stack.active is ChunkLayer) {
                add(SubMenu("Turn", listOf(
                    MenuItem("Left") { state.turnChunks(-1) },
                    MenuItem("Right") { state.turnChunks(1) },
                    MenuItem("Half turn") { state.turnChunks(2) },
                )))
                add(SubMenu("Raise or lower", listOf(
                    MenuItem("Raise 1 block") { state.raiseChunks(1) },
                    MenuItem("Raise 10 blocks") { state.raiseChunks(10) },
                    MenuItem("Lower 1 block") { state.raiseChunks(-1) },
                    MenuItem("Lower 10 blocks") { state.raiseChunks(-10) },
                )))
                add(MenuItem("Reset turn, height and cut", enabled = state.transformTargets().values.any { it.transformed }) {
                    state.resetChunkTransforms()
                })
            } else {
                add(MenuItem("Copy into a new layer") { if (state.copy()) state.pasteInPlace() })
            }
            add(MenuSeparator)
            add(MenuItem("Clear from active layer", "Del") { state.clearSelected() })
            add(MenuItem("Restore original chunks", enabled = selection.any { it in stack.base.overrides }) { state.restoreOriginal() })
        } else if (chunk != null) {
            add(MenuItem("Select this chunk") { state.applySelection(listOf(chunk), SelectMode.Replace) })
            if (selection.isNotEmpty()) add(MenuItem("Add to selection") { state.applySelection(listOf(chunk), SelectMode.Add) })
            add(MenuItem("Analyze this chunk…") {
                state.applySelection(listOf(chunk), SelectMode.Replace)
                state.analyzeSelection()
            })
            add(MenuItem("Paste here", enabled = canPaste) { state.pasteAt(here) })
        } else if (canPaste) {
            add(MenuItem("Paste here") { state.pasteAt(here) })
        }
        if (under != null) {
            add(MenuSeparator)
            add(MenuItem("Make \"${under.name}\" the active layer", enabled = under.id != stack.activeId) { state.setActiveLayer(under.id) })
            add(MenuItem("Select all of \"${under.name}\"") { state.layerToSelection(under.id) })
        }
        if (isNotEmpty()) add(MenuSeparator)
        add(SubMenu("Select", listOf(
            MenuItem("All", "Ctrl+A") { state.selectAll() },
            MenuItem("None", "Ctrl+Shift+A", selection.isNotEmpty()) { state.clearSelection() },
            MenuItem("Invert", "Ctrl+I") { state.invertSelection() },
        )))
        if (chunk != null) add(SubMenu("Copy coordinates", coordinates(state, chunk)))
    }
}

/** The chunk's coordinates in the forms people paste elsewhere: chunk, block, region file and a teleport command. */
private fun coordinates(state: EditorState, pos: ChunkPos): List<MenuEntry> {
    val x = pos.x * 16
    val z = pos.z * 16
    return listOf(
        "Chunk" to "${pos.x}, ${pos.z}",
        "Block" to "$x, $z",
        "Region file" to "r.${pos.x shr 5}.${pos.z shr 5}.mca",
        "Teleport" to "/tp @s ${x + 8} ~ ${z + 8}",
    ).map { (label, text) -> MenuItem(label, text) { state.copyText(text) } }
}
