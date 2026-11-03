package minecartographer.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import minecartographer.core.DemoWorld
import minecartographer.editor.EditorState
import minecartographer.textures.TextureFiles
import minecartographer.viewport.RenderMode
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Textures load in the background, for instance after a project is opened. What shows them (the previews in a model
 * tab's list, the pickers) must pick them up when they arrive, without a click on another tab and back.
 */
class ModelTexturesArriveTest {
    @Test
    fun `the texture previews of a model tab appear by themselves once the textures are loaded`() {
        TextureFiles.findClientJar() ?: return // needs a client jar to load textures from
        val state = EditorState().apply { addWorld(DemoWorld("Valley", seed = 1, radiusChunks = 8)) }
        try {
            state.setRenderMode(RenderMode.Blocks) // no textures yet
            val tab = state.workspaces.newModel()
            val model = assertNotNull(state.workspaces.modelOf(tab))
            model.addTextureVariable() // minecraft:block/stone
            val scene = ImageComposeScene(300, 400, Density(1f)) { ModelLeftDock(state, model, Modifier.width(300.dp).fillMaxHeight()) }
            try {
                var t = 0L
                fun picture(): Int {
                    t += 100_000_000L
                    SwingUtilities.invokeAndWait {}
                    return scene.render(t).encodeToData()!!.bytes.contentHashCode()
                }
                repeat(3) { picture() }
                val before = picture()
                // Textured mode loads the textures in the background; nothing recomposes the dock on purpose.
                state.setRenderMode(RenderMode.Textured)
                var after = before
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 10_000 && after == before) {
                    Thread.sleep(100)
                    after = picture()
                }
                assertTrue(after != before, "the dock stayed as it was drawn before the textures loaded")
            } finally {
                scene.close()
            }
        } finally {
            state.dispose()
        }
    }
}
