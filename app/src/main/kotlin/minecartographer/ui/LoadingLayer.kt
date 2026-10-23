package minecartographer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.AppInfo
import minecartographer.editor.EditorState
import minecartographer.editor.LoadingStep

/**
 * Loading layer shown between steps (startup, opening a world). [fullWindow] is the startup
 * splash that covers and blocks the whole editor; otherwise it is a translucent layer over the
 * viewport that appears only if the step takes a moment, and does not block panning.
 */
@Composable
fun LoadingLayer(state: EditorState, visible: Boolean, fullWindow: Boolean) {
    // Keep showing the last step while fading out.
    val last = remember { mutableStateOf(LoadingStep("")) }
    state.loading?.let { last.value = it }
    val step = last.value

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, delayMillis = if (fullWindow) 0 else 250)),
        exit = fadeOut(tween(if (fullWindow) 450 else 250)),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(if (fullWindow) EditorTheme.window else EditorTheme.viewportBackground.copy(alpha = 0.72f))
                .then(if (fullWindow) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {} else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (fullWindow) {
                    Image(AppIconPainter, contentDescription = null, Modifier.size(112.dp))
                    Spacer(Modifier.height(18.dp))
                    Txt("MineCartographer", weight = FontWeight.SemiBold, size = 22.sp)
                    Spacer(Modifier.height(4.dp))
                    Txt("Merge worlds chunk by chunk", color = EditorTheme.textDim, size = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Txt(AppInfo.versionLabel, color = EditorTheme.warning, size = 12.sp, mono = true)
                    Spacer(Modifier.height(28.dp))
                }
                Spinner(Modifier.size(if (fullWindow) 26.dp else 30.dp))
                Spacer(Modifier.height(12.dp))
                Txt(step.title, size = 13.sp)
                Spacer(Modifier.height(4.dp))
                Txt(step.detail.ifEmpty { " " }, color = EditorTheme.textDim, size = 12.sp, mono = true)
                Spacer(Modifier.height(10.dp))
                ProgressBar(step.progress, Modifier.width(260.dp).height(4.dp))
                if (step.waitForTiles && step.progress != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton("Continue while it renders") { state.skipLoading() }
                }
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val angle by rememberInfiniteTransition().animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
    )
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
        drawArc(EditorTheme.border, 0f, 360f, false, style = stroke)
        drawArc(EditorTheme.accent, angle, 100f, false, style = stroke)
    }
}

/** Determinate bar; an empty track when [progress] is unknown (the spinner shows activity). */
@Composable
private fun ProgressBar(progress: Float?, modifier: Modifier) {
    val animated by animateFloatAsState(progress ?: 0f, tween(200))
    Box(modifier.clip(RoundedCornerShape(2.dp)).background(EditorTheme.border)) {
        if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(animated.coerceIn(0f, 1f)).background(EditorTheme.accent))
    }
}
