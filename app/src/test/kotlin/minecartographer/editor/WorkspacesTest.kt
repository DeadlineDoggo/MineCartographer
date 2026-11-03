package minecartographer.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspacesTest {
    private val tabs = Workspaces()

    @Test
    fun `starts on the map, which cannot be closed`() {
        assertEquals(listOf<Workspace>(Workspace.Map), tabs.open.toList())
        assertEquals(Workspace.Map, tabs.active)
        tabs.close(Workspace.Map)
        assertEquals(listOf<Workspace>(Workspace.Map), tabs.open.toList())
    }

    @Test
    fun `opening the atlas twice keeps one tab and closing it returns to the map`() {
        tabs.openAtlas()
        tabs.openAtlas()
        assertEquals(listOf(Workspace.Map, Workspace.Atlas), tabs.open.toList())
        assertEquals(Workspace.Atlas, tabs.active)
        tabs.close(Workspace.Atlas)
        assertEquals(Workspace.Map, tabs.active)
    }

    @Test
    fun `new models get their own state and closing the shown tab selects its left neighbour`() {
        tabs.openAtlas()
        val first = tabs.newModel()
        val second = tabs.newModel()
        assertTrue(first.id != second.id)
        assertEquals("Untitled model", tabs.modelOf(first)?.name)
        assertEquals(second, tabs.active)

        tabs.close(second)
        assertEquals(first, tabs.active)
        assertNull(tabs.modelOf(second))

        // Closing a tab that is not shown leaves the shown one alone.
        tabs.show(Workspace.Atlas)
        tabs.close(first)
        assertEquals(Workspace.Atlas, tabs.active)
    }

    @Test
    fun `a tab that is not open cannot be shown`() {
        tabs.show(Workspace.Atlas)
        assertEquals(Workspace.Map, tabs.active)
    }
}
