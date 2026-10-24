package minecartographer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import minecartographer.core.project.ProjectFile
import minecartographer.editor.EditorState
import minecartographer.textures.PackCache
import minecartographer.editor.confirmCloseProject
import minecartographer.editor.handleKey
import minecartographer.ui.AppIconPainter
import minecartographer.ui.EditorShell
import minecartographer.ui.EditorTheme
import minecartographer.ui.WindowsTitleBar
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import java.awt.SplashScreen
import java.nio.file.Path

/** Command-line arguments are world folders or a project file to open after startup. */
fun main(args: Array<String>) = application {
    val state = remember {
        // Pack caches not used for two months go; it only costs a little time once.
        kotlin.concurrent.thread(isDaemon = true, name = "prune-caches") { PackCache.prune() }
        EditorState().apply {
            // Nothing opens by itself: the start screen offers the ways in (a path on the command line skips it).
            skipLoading()
            loadUpgradeTables()
            startScreenVisible = args.isEmpty()
        }
    }
    val exit = {
        if (state.confirmCloseProject()) {
            state.dispose()
            exitApplication()
        }
    }
    Window(
        onCloseRequest = exit,
        title = "${state.projectName}${if (state.projectDirty) " *" else ""} · ${AppInfo.NAME} ${AppInfo.versionLabel}",
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
                val path = Path.of(arg)
                if (path.toString().endsWith(".${ProjectFile.EXTENSION}")) state.openProject(path) else state.openWorldFolder(path)
            }
        }
        EditorShell(state, onExit = exit)
    }
}
