package minecartographer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import minecartographer.editor.EditorState

/**
 * Marks a control laid over the map (toolbars, switchers, panels): clicks, drags and the wheel on it never reach
 * the map underneath, which would otherwise select, paint or erase there as well.
 */
@Composable
fun Modifier.overMap(state: EditorState): Modifier {
    val key = remember { Any() }
    DisposableEffect(key) { onDispose { state.mapBlockers.remove(key) } }
    return onGloballyPositioned { state.mapBlockers[key] = it.boundsInRoot() }
}
