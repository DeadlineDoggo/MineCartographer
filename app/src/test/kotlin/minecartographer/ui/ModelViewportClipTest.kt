package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import minecartographer.core.DemoWorld
import minecartographer.editor.EditorState
import minecartographer.model.ModelCamera
import minecartographer.model.ModelEditorState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** The grid, the axes and the direction names are far bigger than the view when zoomed in: they must stop at its edge. */
class ModelViewportClipTest {
    @Test
    fun `nothing of the model view is drawn outside it`() {
        val state = EditorState().apply { addWorld(DemoWorld("Valley", seed = 1, radiusChunks = 4)) }
        val outside = Color(0xFF123456)
        try {
            val model = ModelEditorState(1, "m").apply {
                addCube()
                // Close in on the block, so the grid, the axes and the names reach far past the edges of the view.
                camera = ModelCamera(yaw = 35f, pitch = 45f, zoom = 140f)
            }
            val scene = ImageComposeScene(600, 500, Density(1f)) {
                Box(Modifier.fillMaxSize().background(outside)) {
                    ModelViewport(state, model, Modifier.padding(80.dp).fillMaxSize())
                }
            }
            try {
                repeat(3) { scene.render(it * 16_000_000L) }
                val image = scene.render(100_000_000L)
                val bitmap = Bitmap().also { it.allocPixels(ImageInfo.makeN32Premul(600, 500)) }
                image.readPixels(bitmap)
                var leaked = 0
                for (y in 0 until 500) for (x in 0 until 600) {
                    val inView = x in 80 until 520 && y in 80 until 420
                    if (!inView && bitmap.getColor(x, y) != 0xFF123456.toInt()) leaked++
                }
                assertEquals(0, leaked, "pixels drawn outside the view")
            } finally {
                scene.close()
            }
        } finally {
            state.dispose()
        }
    }
}
