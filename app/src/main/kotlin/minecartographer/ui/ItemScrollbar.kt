package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A scrollbar for a grid whose rows differ in height (headers between the cells): the standard adapter guesses the
 * content's length from the rows it has seen and its thumb can run off the track. This one goes by the index of the
 * first shown item against all items, so the thumb always stays on the track. Click or drag on the track to jump.
 */
@Composable
fun ItemScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableStateOf(0) }
    val minThumb = with(LocalDensity.current) { 28.dp.toPx() }
    val thumb = (heightPx * visible.toFloat() / total).coerceIn(minThumb, heightPx.toFloat().coerceAtLeast(minThumb))
    val travel = (heightPx - thumb).coerceAtLeast(1f)
    val lastFirst = (total - visible).coerceAtLeast(1)
    val fraction = (state.firstVisibleItemIndex.toFloat() / lastFirst).coerceIn(0f, 1f)

    // The numbers change with every step of the scroll; the gesture must outlive them, so it reads the latest ones.
    val latest by rememberUpdatedState(Metrics(thumb, travel, lastFirst, fraction))
    Box(
        modifier.width(10.dp).fillMaxHeight().onSizeChanged { heightPx = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun jump(y: Float, grab: Float) {
                        val m = latest
                        val target = ((y - grab) / m.travel).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((target * m.lastFirst).roundToInt()) }
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val m = latest
                    val top = m.fraction * m.travel
                    // On the thumb: keep the spot that was grabbed. On the track: the thumb's middle goes there.
                    val grab = if (down.position.y in top..(top + m.thumb)) down.position.y - top else m.thumb / 2
                    jump(down.position.y, grab)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToUp() || !change.pressed) break
                        jump(change.position.y, grab)
                        change.consume()
                    }
                }
            },
    ) {
        Box(
            Modifier.offset { IntOffset(0, (fraction * travel).roundToInt()) }.width(10.dp)
                .height(with(LocalDensity.current) { thumb.toDp() })
                .clip(RoundedCornerShape(5.dp)).background(EditorTheme.textFaint),
        )
    }
}

private class Metrics(val thumb: Float, val travel: Float, val lastFirst: Int, val fraction: Float)
