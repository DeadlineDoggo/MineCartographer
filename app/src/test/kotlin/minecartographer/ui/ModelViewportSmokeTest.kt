package minecartographer.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import minecartographer.core.DemoWorld
import minecartographer.editor.EditorState
import minecartographer.model.ModelCamera
import minecartographer.model.ModelEditorState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Draws the model view off screen: the grid, the axes and the direction names each change the picture, and nothing throws. */
class ModelViewportSmokeTest {
    private fun render(state: EditorState, model: ModelEditorState): ByteArray {
        val scene = ImageComposeScene(900, 700, Density(1f)) { ModelViewport(state, model) }
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
            return scene.render(100_000_000L).encodeToData()!!.bytes
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the grid, the axes and the side names are drawn and can be switched off`() {
        val state = EditorState().apply { addWorld(DemoWorld("Valley", seed = 1, radiusChunks = 4)) }
        try {
            val model = ModelEditorState(1, "m").apply {
                addCube()
                camera = ModelCamera(yaw = 35f, pitch = 50f, zoom = 14f)
            }
            val all = render(state, model)
            model.showSides = false
            val noSides = render(state, model)
            model.showGrid = false
            val bare = render(state, model)
            assertFalse(all.contentEquals(noSides), "the direction names show")
            assertFalse(noSides.contentEquals(bare), "the grid and the axes show")
            assertTrue(all.size > 1000)
        } finally {
            state.dispose()
        }
    }
}
