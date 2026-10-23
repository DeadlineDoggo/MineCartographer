package minecartographer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import minecartographer.core.DemoWorld
import minecartographer.editor.EditorState
import minecartographer.editor.handleKey
import minecartographer.ui.AppIconPainter
import minecartographer.ui.EditorShell
import minecartographer.ui.EditorTheme
import minecartographer.ui.WindowsTitleBar
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import java.awt.SplashScreen
import java.nio.file.Path

/** Command-line arguments are world folders to open after startup. */
fun main(args: Array<String>) = application {
    val state = remember {
        EditorState().apply {
            beginLoading("Loading worlds")
            addWorld(DemoWorld("Demo Valley", seed = 1))
            addWorld(DemoWorld("Demo Highlands", seed = 2024, radiusChunks = 16))
            refreshFoundWorlds()
        }
    }
    val exit = {
        state.dispose()
        exitApplication()
    }
    Window(
        onCloseRequest = exit,
        title = "${AppInfo.NAME} ${AppInfo.versionLabel}",
        icon = AppIconPainter,
        state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
        onKeyEvent = state::handleKey,
    ) {
        LaunchedEffect(Unit) {
            WindowsTitleBar.applyDark(window.windowHandle, caption = EditorTheme.toolbar, text = EditorTheme.text)
            // The JVM splash (-splash:) has done its job once our own loading layer is on screen.
            runCatching { SplashScreen.getSplashScreen()?.close() }
            for (arg in args) {
                snapshotFlow { state.busy }.first { !it }
                state.openWorldFolder(Path.of(arg))
            }
        }
        EditorShell(state, onExit = exit)
    }
}
