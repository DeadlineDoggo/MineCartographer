package minecartographer.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter

/**
 * The app icon: a blueprint sheet with a dashed block plan and a ruler, and a grass block in front.
 * Drawn in code so the window icon, the installer .ico and the splash all come from one source
 * (see `IconGenerator` for the files).
 */
object AppIconPainter : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() = drawAppIcon()
}

private val BLUEPRINT_LIGHT = Color(0xFF2F74C8)
private val BLUEPRINT_DARK = Color(0xFF143A7E)
private val RULER = Color(0xFFF4C542)
private val RULER_EDGE = Color(0xFFB8860B)
private val GRASS_TOP = Color(0xFF6DBA45)
private val GRASS_TOP_LIGHT = Color(0xFF86CF5A)
private val GRASS_TOP_DARK = Color(0xFF58A236)
private val GRASS_LEFT = Color(0xFF5DA03A)
private val GRASS_RIGHT = Color(0xFF4A862C)
private val DIRT_LEFT = Color(0xFF8B5A2B)
private val DIRT_RIGHT = Color(0xFF6B4421)
private val DIRT_SPECK = Color(0xFF5A3818)
private val OUTLINE = Color(0xFF162410)

fun DrawScope.drawAppIcon() {
    val s = size.minDimension
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun poly(pts: List<Offset>) = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
    val detailed = s >= 40f // tiny sizes skip fine detail that would only turn into noise

    // Blueprint sheet with a grid.
    val sheet = RoundRect(Rect(p(0.04f, 0.04f), p(0.96f, 0.96f)), CornerRadius(0.14f * s))
    val sheetPath = Path().apply { addRoundRect(sheet) }
    drawPath(sheetPath, Brush.linearGradient(listOf(BLUEPRINT_LIGHT, BLUEPRINT_DARK), p(0f, 0f), p(0.6f, 1f)))
    clipPath(sheetPath) {
        val cells = if (detailed) 16 else 4
        for (i in 1 until cells) {
            val t = i / cells.toFloat()
            val major = detailed && i % 4 == 0
            val c = Color.White.copy(alpha = if (major || !detailed) 0.22f else 0.09f)
            val w = if (major) 0.006f * s else 0.003f * s
            drawLine(c, p(t, 0f), p(t, 1f), strokeWidth = w)
            drawLine(c, p(0f, t), p(1f, t), strokeWidth = w)
        }

        if (detailed) {
            // Dashed plan of a block (what gets built) and a dimension line above it.
            val plan = Color.White.copy(alpha = 0.8f)
            val dash = Stroke(width = 0.012f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.03f * s, 0.022f * s)), join = StrokeJoin.Round)
            val cx = 0.33f; val y0 = 0.17f; val w = 0.16f; val dh = w / 2; val h = 0.18f
            drawPath(poly(listOf(p(cx, y0), p(cx + w, y0 + dh), p(cx + w, y0 + dh + h), p(cx, y0 + 2 * dh + h), p(cx - w, y0 + dh + h), p(cx - w, y0 + dh))), plan, style = dash)
            drawPath(Path().apply {
                moveTo(p(cx - w, y0 + dh).x, p(cx - w, y0 + dh).y); lineTo(p(cx, y0 + 2 * dh).x, p(cx, y0 + 2 * dh).y)
                lineTo(p(cx + w, y0 + dh).x, p(cx + w, y0 + dh).y)
                moveTo(p(cx, y0 + 2 * dh).x, p(cx, y0 + 2 * dh).y); lineTo(p(cx, y0 + 2 * dh + h).x, p(cx, y0 + 2 * dh + h).y)
            }, plan, style = dash)
            val dim = Color.White.copy(alpha = 0.6f)
            drawLine(dim, p(0.12f, 0.1f), p(0.12f, 0.16f), strokeWidth = 0.008f * s)
            drawLine(dim, p(0.54f, 0.1f), p(0.54f, 0.16f), strokeWidth = 0.008f * s)
            drawLine(dim, p(0.12f, 0.13f), p(0.54f, 0.13f), strokeWidth = 0.008f * s)
        }
    }

    // Ruler lying across the sheet, behind the block.
    rotate(-32f, pivot = p(0.44f, 0.64f)) {
        val left = 0.0f; val right = 0.9f; val top = 0.58f; val bottom = 0.7f
        drawRoundRect(RULER, p(left, top), Size((right - left) * s, (bottom - top) * s), CornerRadius(0.015f * s))
        drawRect(RULER_EDGE, p(left, bottom - 0.018f), Size((right - left) * s, 0.018f * s))
        if (detailed) {
            var i = 0
            var x = left + 0.04f
            while (x < right - 0.02f) {
                val len = if (i % 5 == 0) 0.05f else 0.027f
                drawLine(RULER_EDGE, p(x, top), p(x, top + len), strokeWidth = 0.008f * s, cap = StrokeCap.Butt)
                x += 0.035f
                i++
            }
        }
    }

    // Grass block.
    val cx = 0.63f; val y0 = 0.36f; val w = 0.27f; val dh = w / 2; val h = 0.29f
    val top = poly(listOf(p(cx, y0), p(cx + w, y0 + dh), p(cx, y0 + 2 * dh), p(cx - w, y0 + dh)))
    val left = poly(listOf(p(cx - w, y0 + dh), p(cx, y0 + 2 * dh), p(cx, y0 + 2 * dh + h), p(cx - w, y0 + dh + h)))
    val right = poly(listOf(p(cx, y0 + 2 * dh), p(cx + w, y0 + dh), p(cx + w, y0 + dh + h), p(cx, y0 + 2 * dh + h)))

    clipPath(sheetPath) {
        drawOval(Color.Black.copy(alpha = 0.28f), p(cx - w * 1.05f, y0 + 2 * dh + h - 0.06f), Size(w * 2.1f * s, 0.12f * s))
    }
    drawPath(left, DIRT_LEFT)
    drawPath(right, DIRT_RIGHT)

    // Grass hanging over the sides with a jagged edge.
    fun fringe(a: Offset, b: Offset, color: Color, depth: Float) {
        val steps = if (detailed) 8 else 4
        val path = Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y)
            for (i in steps downTo 0) {
                val t = i / steps.toFloat()
                val d = if (i % 2 == 0) depth else depth * 0.55f
                lineTo(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t + d * s)
            }
            close()
        }
        drawPath(path, color)
    }
    fringe(p(cx - w, y0 + dh), p(cx, y0 + 2 * dh), GRASS_LEFT, 0.085f)
    fringe(p(cx, y0 + 2 * dh), p(cx + w, y0 + dh), GRASS_RIGHT, 0.085f)
    drawPath(top, GRASS_TOP)

    if (detailed) {
        // A few pixel-texture specks so it reads as a Minecraft block.
        val px = 0.034f
        fun speckTop(u: Float, v: Float, c: Color) {
            // (u, v) in 0..1 across the top face's two axes.
            val o = Offset(cx + (u - v) * w, y0 + (u + v) * dh)
            drawPath(poly(listOf(p(o.x, o.y), p(o.x + px, o.y + px / 2), p(o.x, o.y + px), p(o.x - px, o.y + px / 2))), c)
        }
        speckTop(0.2f, 0.3f, GRASS_TOP_LIGHT); speckTop(0.6f, 0.15f, GRASS_TOP_DARK); speckTop(0.45f, 0.6f, GRASS_TOP_LIGHT)
        speckTop(0.75f, 0.55f, GRASS_TOP_DARK); speckTop(0.3f, 0.8f, GRASS_TOP_DARK); speckTop(0.85f, 0.2f, GRASS_TOP_LIGHT)
        fun speckSide(x: Float, y: Float, c: Color) = drawRect(c, p(x, y), Size(px * 0.8f * s, px * 0.8f * s))
        speckSide(cx - w * 0.7f, y0 + dh + 0.18f, DIRT_SPECK); speckSide(cx - w * 0.35f, y0 + dh + 0.27f, DIRT_SPECK)
        speckSide(cx + w * 0.3f, y0 + dh + 0.22f, DIRT_SPECK); speckSide(cx + w * 0.65f, y0 + dh + 0.14f, DIRT_SPECK)
    }

    val outline = Stroke(width = 0.014f * s, join = StrokeJoin.Round)
    drawPath(top, OUTLINE.copy(alpha = 0.55f), style = outline)
    drawPath(left, OUTLINE.copy(alpha = 0.55f), style = outline)
    drawPath(right, OUTLINE.copy(alpha = 0.55f), style = outline)
    // Light catching the front edges.
    drawLine(Color.White.copy(alpha = 0.35f), p(cx - w, y0 + dh), p(cx, y0 + 2 * dh), strokeWidth = 0.01f * s)
    drawLine(Color.White.copy(alpha = 0.2f), p(cx, y0 + 2 * dh), p(cx + w, y0 + dh), strokeWidth = 0.01f * s)
}
