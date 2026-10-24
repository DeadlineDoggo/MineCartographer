package minecartographer.editor

import minecartographer.core.ChunkPos
import minecartographer.core.DemoWorld
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorStateTest {
    private val valley = DemoWorld("Valley", seed = 1, radiusChunks = 8)
    private val hills = DemoWorld("Hills", seed = 2, radiusChunks = 8)
    private val state = EditorState().apply {
        addWorld(valley)
        addWorld(hills)
    }

    @AfterTest
    fun tearDown() = state.dispose()

    private fun select(vararg positions: ChunkPos) = state.applySelection(positions.toList(), SelectMode.Replace)

    @Test
    fun `copy from one world and paste into another as a new layer`() {
        select(ChunkPos(0, 0), ChunkPos(1, 0))
        assertTrue(state.copy())

        state.openWorld(hills)
        state.pasteAt(ChunkPos(5, 5))

        val stack = state.activeStack!!
        assertEquals(2, stack.layers.size)
        val layer = stack.active as ChunkLayer
        assertEquals(ChunkRef(valley, ChunkPos(0, 0)), layer.chunks[ChunkPos(5, 5)])
        assertTrue(state.stackOf(valley)!!.layers.size == 1, "the source world is untouched")

        state.undo()
        assertEquals(1, state.activeStack!!.layers.size)
    }

    @Test
    fun `moving a layer is one undo step`() {
        select(ChunkPos(0, 0))
        state.copy()
        state.pasteInPlace()

        assertTrue(state.beginMoveLayer())
        state.moveLayerBy(1, 0)
        state.moveLayerBy(3, 2)
        state.endStroke()
        val layer = state.activeStack!!.active as ChunkLayer
        assertEquals(setOf(ChunkPos(3, 2)), layer.chunks.keys)

        state.undo()
        assertEquals(setOf(ChunkPos(0, 0)), (state.activeStack!!.active as ChunkLayer).chunks.keys)
    }

    @Test
    fun `erasing the original world only hides chunks and can be restored`() {
        val pos = ChunkPos(2, 2)
        assertTrue(state.beginErase())
        state.eraseAt(listOf(pos))
        state.endStroke()
        assertNull(state.activeStack!!.resolve(pos))

        select(pos) // erased chunks stay selectable
        state.restoreOriginal()
        assertEquals(ChunkRef(valley, pos), state.activeStack!!.resolve(pos))
    }

    @Test
    fun `locked layers refuse edits`() {
        state.toggleLocked(BaseLayer.BASE_ID)
        select(ChunkPos(0, 0))
        state.clearSelected()
        assertEquals(ChunkRef(valley, ChunkPos(0, 0)), state.activeStack!!.resolve(ChunkPos(0, 0)))
        assertTrue(state.notice!!.warning)
    }

    @Test
    fun `rotating four times returns to the same view and keeps the center`() {
        state.view = minecartographer.viewport.IsoView(centerU = 120.0, centerV = -40.0, zoom = 3.0)
        val start = state.view
        repeat(4) { state.rotate(+1) }
        assertEquals(0, state.rotation)
        assertEquals(start.centerU, state.view.centerU, 1e-9)
        assertEquals(start.centerV, state.view.centerV, 1e-9)
        assertEquals(start.zoom, state.view.zoom)
    }

    @Test
    fun `view positions round-trip through the rotation`() {
        state.rotate(+1)
        val pos = ChunkPos(7, -3)
        assertEquals(pos, state.fromView(state.toView(pos)))
        assertTrue(state.toView(pos) != pos)
    }

    @Test
    fun `loading finishes once a frame has every visible tile`() {
        state.beginLoading("Rendering")
        state.frameTilesTotal = 10
        state.frameTilesMissing = 4
        state.frameCount++
        state.pollLoading()
        assertEquals(0.6f, state.loading!!.progress)

        state.frameTilesMissing = 0
        state.frameCount++
        state.pollLoading()
        assertNull(state.loading)
        assertTrue(!state.startingUp)
    }

    @Test
    fun `merge down flattens a pasted layer into the original`() {
        select(ChunkPos(0, 0))
        state.copy()
        state.openWorld(hills)
        state.pasteAt(ChunkPos(1, 1))
        state.mergeDown()

        val stack = state.activeStack!!
        assertEquals(1, stack.layers.size)
        assertEquals(ChunkRef(valley, ChunkPos(0, 0)), stack.resolve(ChunkPos(1, 1)))
    }

    @Test
    fun `a saved project opens with its layers, selection and camera`() {
        val file = Files.createTempFile("project", ".mcproj")
        try {
            select(ChunkPos(0, 0), ChunkPos(1, 0))
            state.copy()
            state.openWorld(hills)
            state.pasteAt(ChunkPos(5, 5))
            state.rotate(+1)
            val pack = file.resolveSibling("Exotic.zip").toAbsolutePath()
            state.chooseTextures(valley, listOf(pack))
            val stack = state.activeStack!!
            val view = state.view
            assertTrue(state.projectDirty)
            assertTrue(state.saveProject(file))
            assertTrue(!state.projectDirty)

            state.newProject()
            assertTrue(state.worlds.isEmpty())

            state.openProject(file)
            val deadline = System.currentTimeMillis() + 10_000
            while (state.busy && System.currentTimeMillis() < deadline) Thread.sleep(10)
            SwingUtilities.invokeAndWait {} // let the result reach the UI thread

            assertEquals(listOf("Valley", "Hills"), state.worlds.map { it.name })
            assertEquals("Hills", state.activeWorld?.name)
            assertEquals(1, state.rotation)
            assertEquals(view, state.view)
            assertEquals(setOf(ChunkPos(5, 5), ChunkPos(6, 5)), state.selection)
            val layer = state.activeStack!!.active as ChunkLayer
            assertEquals((stack.active as ChunkLayer).name, layer.name)
            assertEquals(ChunkPos(0, 0), layer.chunks[ChunkPos(5, 5)]?.pos)
            assertEquals("Valley", layer.chunks[ChunkPos(5, 5)]?.world?.name)
            assertEquals(listOf(pack), state.texturesOf(state.worlds.first { it.name == "Valley" }).files)
            assertTrue(state.texturesOf(state.worlds.first { it.name == "Hills" }).files.isEmpty())
            assertTrue(!state.projectDirty)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
