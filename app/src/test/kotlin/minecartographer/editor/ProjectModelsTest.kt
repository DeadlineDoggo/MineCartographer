package minecartographer.editor

import minecartographer.core.DemoWorld
import minecartographer.model.ModelShading
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectModelsTest {
    private fun EditorState.waitForOpen() {
        val deadline = System.currentTimeMillis() + 10_000
        while (busy && System.currentTimeMillis() < deadline) Thread.sleep(10)
        SwingUtilities.invokeAndWait {} // let the result reach the UI thread
    }

    @Test
    fun `open tabs, their models and the model's settings come back with the project`() {
        val state = EditorState().apply { addWorld(DemoWorld("Valley", seed = 1, radiusChunks = 8)) }
        val file = Files.createTempFile("project", ".mcproj")
        try {
            state.openAtlas()
            val tab = state.workspaces.newModel()
            val model = assertNotNull(state.workspaces.modelOf(tab))
            model.name = "bed"
            model.addTextureVariable()
            model.addCube()
            model.addCube()
            model.setAngle(1, 30f)
            model.snap = 0.5f
            model.showGrid = false
            model.shading = ModelShading.Wireframe
            model.toggleHidden(model.doc.elements.last().id)
            state.workspaces.show(tab)
            val saved = model.doc
            assertTrue(state.projectDirty, "a model in a tab is part of the project")

            assertTrue(state.saveProject(file))
            assertFalse(state.projectDirty)
            assertFalse(model.dirty, "saved with the project")

            state.newProject()
            assertEquals(listOf<Workspace>(Workspace.Map), state.workspaces.open.toList())

            state.openProject(file)
            state.waitForOpen()

            assertEquals(3, state.workspaces.open.size)
            assertEquals(Workspace.Atlas, state.workspaces.open[1])
            val back = assertNotNull(state.workspaces.activeModel(), "the model tab is the one that was shown")
            assertEquals("bed", back.name)
            assertEquals(saved, back.doc, "the same elements, faces, rotation and textures")
            assertEquals(0.5f, back.snap)
            assertFalse(back.showGrid)
            assertEquals(ModelShading.Wireframe, back.shading)
            assertEquals(setOf(back.doc.elements.last().id), back.hidden)
            assertFalse(state.projectDirty)

            // Changing the model, or closing its tab, is a change of the project.
            back.addCube()
            assertTrue(state.projectDirty)
        } finally {
            Files.deleteIfExists(file)
            state.dispose()
        }
    }

    @Test
    fun `a new project starts with just the map`() {
        val state = EditorState()
        try {
            state.workspaces.newModel()
            state.openAtlas()
            state.newProject()
            assertEquals(listOf<Workspace>(Workspace.Map), state.workspaces.open.toList())
            assertEquals(Workspace.Map, state.workspaces.active)
            assertFalse(state.projectDirty)
        } finally {
            state.dispose()
        }
    }
}
