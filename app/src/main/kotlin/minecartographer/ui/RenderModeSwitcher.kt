package minecartographer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.editor.EditorState
import minecartographer.viewport.RenderMode

private val PANEL_SHAPE = RoundedCornerShape(8.dp)
private val PREVIEW_SHAPE = RoundedCornerShape(6.dp)
private val PANEL_BACKGROUND = Color(0xCC2B2D30)

/**
 * Google Maps-like map type switcher for the viewport's bottom-left corner: a button showing the
 * current render mode, which slides out a tile per mode while hovered. Clicking the button itself
 * steps to the next mode.
 */
@Composable
fun RenderModeSwitcher(state: EditorState, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val current = state.renderSettings.mode
    Row(modifier.hoverable(interaction), verticalAlignment = Alignment.Bottom) {
        val buttonInteraction = remember { MutableInteractionSource() }
        val buttonHovered by buttonInteraction.collectIsHoveredAsState()
        Row(
            Modifier.height(52.dp).clip(PANEL_SHAPE)
                .background(if (buttonHovered) EditorTheme.panelRaised else PANEL_BACKGROUND)
                .border(1.dp, if (buttonHovered) EditorTheme.accent else EditorTheme.border, PANEL_SHAPE)
                .hoverable(buttonInteraction)
                .clickable(buttonInteraction, indication = null) {
                    state.setRenderMode(RenderMode.entries[(current.ordinal + 1) % RenderMode.entries.size])
                }
                .padding(start = 5.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModePreview(current, Modifier.size(42.dp).clip(PREVIEW_SHAPE))
            Spacer(Modifier.width(10.dp))
            Column {
                Txt("Render", color = EditorTheme.textDim, size = 10.sp)
                Txt(current.label, size = 13.sp, weight = FontWeight.SemiBold)
            }
        }
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            // The start padding sits inside the hovered row, so crossing it keeps the tiles open.
            Row(
                Modifier.padding(start = 8.dp).clip(PANEL_SHAPE).background(PANEL_BACKGROUND)
                    .border(1.dp, EditorTheme.border, PANEL_SHAPE).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((i, mode) in RenderMode.entries.withIndex()) {
                    ModeTile(mode, "${i + 1}", selected = mode == current) { state.setRenderMode(mode) }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(mode: RenderMode, key: String, selected: Boolean, onClick: () -> Unit) = WithTooltip("${mode.description} ($key)") {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.width(64.dp).clip(PREVIEW_SHAPE)
            .background(if (hovered) EditorTheme.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModePreview(
            mode,
            Modifier.size(48.dp).clip(PREVIEW_SHAPE).border(
                if (selected) 2.dp else 1.dp,
                when {
                    selected -> EditorTheme.accent
                    hovered -> EditorTheme.textFaint
                    else -> EditorTheme.border
                },
                PREVIEW_SHAPE,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Txt(mode.label, color = if (selected) EditorTheme.accent else EditorTheme.text, size = 11.sp,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---- Previews: a tiny iso scene drawn the way each mode draws chunks ----

private val Grass = Color(0xFF6DAA45)
private val Sand = Color(0xFFDBCB8E)
private val Water = Color(0xFF3F76E4)
private val Dirt = Color(0xFF8B6440)

/** Column heights of the preview patch, indexed [x][z]; 0 is water. */
private val HEIGHTS = arrayOf(intArrayOf(2, 2, 1), intArrayOf(2, 1, 0), intArrayOf(1, 0, 0))

private fun topColor(height: Int) = when (height) {
    0 -> Water
    1 -> Sand
    else -> Grass
}

@Composable
private fun ModePreview(mode: RenderMode, modifier: Modifier) = Canvas(modifier.background(EditorTheme.viewportBackground)) {
    when (mode) {
        RenderMode.Map -> patch(outlined = false)
        RenderMode.Blocks -> patch(outlined = true)
        RenderMode.Textured -> texturedBlock()
    }
}

private fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

private fun DrawScope.quad(color: Color, a: Offset, b: Offset, c: Offset, d: Offset, outline: Color? = null) {
    val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }
    drawPath(path, color)
    if (outline != null) drawPath(path, outline, style = Stroke(width = 1f))
}

/** Iso box whose top face is centred on ([x], [y]), half [u] wide and [h] tall. */
private fun DrawScope.box(x: Float, y: Float, u: Float, h: Float, top: Color, side: Color = top, outline: Boolean = false) {
    val edge = if (outline) Color(0x66000000) else null
    val l = Offset(x - u, y); val r = Offset(x + u, y)
    val t = Offset(x, y - u / 2); val b = Offset(x, y + u / 2)
    val down = Offset(0f, h)
    quad(side.shade(0.78f), l, b, b + down, l + down, edge)
    quad(side.shade(0.62f), b, r, r + down, b + down, edge)
    quad(top, l, t, r, b, edge)
}

/** 3x3 columns. Map mode: one flat-shaded column per spot; Blocks mode: every block as a cube. */
private fun DrawScope.patch(outlined: Boolean) {
    val u = size.width / 6.4f
    val ox = size.width / 2
    val oy = size.height / 2 - u / 2
    // Back to front, bottom to top, so nearer blocks paint over farther ones.
    for (sum in 0..4) for (x in 0..2) {
        val z = sum - x
        if (z !in 0..2) continue
        val height = HEIGHTS[x][z]
        val cx = ox + (x - z) * u
        val cy = oy + (x + z) * u / 2
        if (outlined) {
            for (y in 0..height) {
                val top = if (y == height) topColor(height) else Dirt
                box(cx, cy - y * u, u, u, top, if (y == height && height > 0) Dirt else top, outline = true)
            }
        } else {
            box(cx, cy - height * u, u, (height + 1) * u, topColor(height))
        }
    }
}

/** One grass block with pixel-art faces, like Minecraft's own texture. */
private fun DrawScope.texturedBlock() {
    val u = size.width * 0.36f
    val h = u * 1.15f
    val x = size.width / 2
    val y = size.height / 2 - h / 2
    val n = 4
    fun noise(i: Int, j: Int, face: Int) = ((i * 73 + j * 151 + face * 37) * 2654435761L ushr 7 and 7).toFloat() / 7f
    fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    // Maps face coordinates (a across, c down/along) in [0, 1] to screen points.
    val top = { a: Float, c: Float -> Offset(x + (a - c) * u, y - u / 2 + (a + c) * u / 2) }
    val left = { a: Float, c: Float -> lerp(Offset(x - u, y), Offset(x, y + u / 2), a) + Offset(0f, c * h) }
    val right = { a: Float, c: Float -> lerp(Offset(x, y + u / 2), Offset(x + u, y), a) + Offset(0f, c * h) }
    for ((face, map) in listOf(top, left, right).withIndex()) {
        val light = when (face) { 0 -> 1f; 1 -> 0.78f; else -> 0.62f }
        for (i in 0 until n) for (j in 0 until n) {
            val a0 = i / n.toFloat(); val a1 = (i + 1) / n.toFloat()
            val c0 = j / n.toFloat(); val c1 = (j + 1) / n.toFloat()
            // Side faces: a grass fringe along the top, then dirt.
            val base = if (face == 0 || j == 0 || (j == 1 && noise(i, j, face) > 0.6f)) Grass else Dirt
            val color = base.shade(light * (0.85f + 0.2f * noise(i, j, face)))
            quad(color, map(a0, c0), map(a1, c0), map(a1, c1), map(a0, c1))
        }
    }
}
