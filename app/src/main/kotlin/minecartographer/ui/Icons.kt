package minecartographer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Small line icons drawn in code, so the app needs no icon font or image assets. */
fun interface EditorIcon {
    fun DrawScope.draw(color: Color)
}

object Icons {
    private fun DrawScope.stroke() = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)

    private fun DrawScope.line(color: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
        drawLine(color, Offset(x0 * size.width, y0 * size.height), Offset(x1 * size.width, y1 * size.height),
            strokeWidth = size.minDimension * 0.09f, cap = StrokeCap.Round)

    private fun DrawScope.poly(vararg pts: Float): Path = Path().apply {
        moveTo(pts[0] * size.width, pts[1] * size.height)
        for (i in 2 until pts.size step 2) lineTo(pts[i] * size.width, pts[i + 1] * size.height)
        close()
    }

    val Worlds = EditorIcon { color ->
        val s = stroke()
        drawCircle(color, radius = size.minDimension * 0.42f, style = s)
        drawOval(color, topLeft = Offset(size.width * 0.3f, size.height * 0.08f),
            size = Size(size.width * 0.4f, size.height * 0.84f), style = s)
        line(color, 0.1f, 0.5f, 0.9f, 0.5f)
    }

    val Selection = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
            size = Size(size.width * 0.8f, size.height * 0.8f),
            style = Stroke(width = size.minDimension * 0.09f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
        drawRect(color, topLeft = Offset(size.width * 0.35f, size.height * 0.35f), size = Size(size.width * 0.3f, size.height * 0.3f))
    }

    val Settings = EditorIcon { color ->
        for ((y, knob) in listOf(0.22f to 0.3f, 0.5f to 0.68f, 0.78f to 0.42f)) {
            line(color, 0.1f, y, 0.9f, y)
            drawCircle(color, radius = size.minDimension * 0.1f, center = Offset(knob * size.width, y * size.height))
        }
    }

    val Move = EditorIcon { color ->
        line(color, 0.5f, 0.08f, 0.5f, 0.92f)
        line(color, 0.08f, 0.5f, 0.92f, 0.5f)
        drawPath(poly(0.5f, 0.02f, 0.36f, 0.2f, 0.64f, 0.2f), color)
        drawPath(poly(0.5f, 0.98f, 0.36f, 0.8f, 0.64f, 0.8f), color)
        drawPath(poly(0.02f, 0.5f, 0.2f, 0.36f, 0.2f, 0.64f), color)
        drawPath(poly(0.98f, 0.5f, 0.8f, 0.36f, 0.8f, 0.64f), color)
    }

    val Fit = EditorIcon { color ->
        line(color, 0.1f, 0.35f, 0.1f, 0.1f); line(color, 0.1f, 0.1f, 0.35f, 0.1f)
        line(color, 0.65f, 0.1f, 0.9f, 0.1f); line(color, 0.9f, 0.1f, 0.9f, 0.35f)
        line(color, 0.9f, 0.65f, 0.9f, 0.9f); line(color, 0.9f, 0.9f, 0.65f, 0.9f)
        line(color, 0.35f, 0.9f, 0.1f, 0.9f); line(color, 0.1f, 0.9f, 0.1f, 0.65f)
    }

    val ZoomIn = EditorIcon { color -> line(color, 0.2f, 0.5f, 0.8f, 0.5f); line(color, 0.5f, 0.2f, 0.5f, 0.8f) }
    val ZoomOut = EditorIcon { color -> line(color, 0.2f, 0.5f, 0.8f, 0.5f) }

    val Undo = EditorIcon { color ->
        drawArc(color, startAngle = 180f, sweepAngle = 220f, useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.22f), size = Size(size.width * 0.64f, size.height * 0.64f), style = stroke())
        drawPath(poly(0.04f, 0.5f, 0.32f, 0.5f, 0.18f, 0.28f), color)
    }

    val Redo = EditorIcon { color ->
        drawArc(color, startAngle = 140f, sweepAngle = 220f, useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.22f), size = Size(size.width * 0.64f, size.height * 0.64f), style = stroke())
        drawPath(poly(0.96f, 0.5f, 0.68f, 0.5f, 0.82f, 0.28f), color)
    }

    val Copy = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.32f, size.height * 0.32f),
            size = Size(size.width * 0.58f, size.height * 0.58f), style = stroke())
        line(color, 0.1f, 0.68f, 0.1f, 0.1f); line(color, 0.1f, 0.1f, 0.68f, 0.1f)
    }

    val Cut = EditorIcon { color ->
        drawCircle(color, radius = size.minDimension * 0.14f, center = Offset(size.width * 0.25f, size.height * 0.78f), style = stroke())
        drawCircle(color, radius = size.minDimension * 0.14f, center = Offset(size.width * 0.75f, size.height * 0.78f), style = stroke())
        line(color, 0.34f, 0.66f, 0.78f, 0.08f)
        line(color, 0.66f, 0.66f, 0.22f, 0.08f)
    }

    val Paste = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.14f, size.height * 0.16f),
            size = Size(size.width * 0.72f, size.height * 0.78f), style = stroke())
        drawRect(color, topLeft = Offset(size.width * 0.34f, size.height * 0.06f), size = Size(size.width * 0.32f, size.height * 0.18f))
        line(color, 0.32f, 0.5f, 0.68f, 0.5f); line(color, 0.32f, 0.7f, 0.6f, 0.7f)
    }

    val Delete = EditorIcon { color ->
        line(color, 0.1f, 0.22f, 0.9f, 0.22f)
        line(color, 0.38f, 0.1f, 0.62f, 0.1f)
        drawPath(poly(0.2f, 0.22f, 0.8f, 0.22f, 0.72f, 0.94f, 0.28f, 0.94f), color, style = stroke())
        line(color, 0.42f, 0.4f, 0.42f, 0.76f); line(color, 0.58f, 0.4f, 0.58f, 0.76f)
    }

    // ---- Tools ----

    val Hand = EditorIcon { color ->
        drawRoundRect(color, topLeft = Offset(size.width * 0.22f, size.height * 0.42f), size = Size(size.width * 0.58f, size.height * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.18f), style = stroke())
        for (x in listOf(0.3f, 0.44f, 0.58f, 0.72f)) line(color, x, 0.46f, x, if (x == 0.3f || x == 0.72f) 0.22f else 0.1f)
    }

    val BrushSelect = EditorIcon { color ->
        drawCircle(color, radius = size.minDimension * 0.4f,
            style = Stroke(width = size.minDimension * 0.09f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2.5f))))
        drawCircle(color, radius = size.minDimension * 0.14f)
    }

    val RectSelect = Selection

    val MoveLayer = Move

    val Eraser = EditorIcon { color ->
        drawPath(poly(0.55f, 0.1f, 0.92f, 0.47f, 0.47f, 0.92f, 0.1f, 0.55f), color, style = stroke())
        drawPath(poly(0.1f, 0.55f, 0.32f, 0.33f, 0.69f, 0.7f, 0.47f, 0.92f), color)
    }

    val Picker = EditorIcon { color ->
        // Layer stack with a pointer: "which layer is this?"
        drawPath(poly(0.4f, 0.1f, 0.75f, 0.27f, 0.4f, 0.44f, 0.05f, 0.27f), color, style = stroke())
        drawPath(poly(0.05f, 0.45f, 0.4f, 0.62f, 0.55f, 0.55f), color, style = stroke())
        drawPath(poly(0.55f, 0.5f, 0.55f, 0.98f, 0.68f, 0.85f, 0.78f, 1f, 0.86f, 0.95f, 0.76f, 0.8f, 0.95f, 0.8f), color)
    }

    // ---- Layers ----

    val Layers = EditorIcon { color ->
        drawPath(poly(0.5f, 0.1f, 0.92f, 0.32f, 0.5f, 0.54f, 0.08f, 0.32f), color, style = stroke())
        drawPath(Path().apply {
            moveTo(0.08f * size.width, 0.5f * size.height); lineTo(0.5f * size.width, 0.72f * size.height); lineTo(0.92f * size.width, 0.5f * size.height)
            moveTo(0.08f * size.width, 0.68f * size.height); lineTo(0.5f * size.width, 0.9f * size.height); lineTo(0.92f * size.width, 0.68f * size.height)
        }, color, style = stroke())
    }

    val Eye = EditorIcon { color ->
        drawOval(color, topLeft = Offset(size.width * 0.06f, size.height * 0.26f), size = Size(size.width * 0.88f, size.height * 0.48f), style = stroke())
        drawCircle(color, radius = size.minDimension * 0.13f)
    }

    val EyeOff = EditorIcon { color ->
        drawOval(color.copy(alpha = 0.5f), topLeft = Offset(size.width * 0.06f, size.height * 0.26f), size = Size(size.width * 0.88f, size.height * 0.48f), style = stroke())
        line(color, 0.12f, 0.88f, 0.88f, 0.12f)
    }

    val Lock = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.2f, size.height * 0.46f), size = Size(size.width * 0.6f, size.height * 0.46f))
        drawArc(color, 180f, 180f, false, topLeft = Offset(size.width * 0.3f, size.height * 0.12f), size = Size(size.width * 0.4f, size.height * 0.62f), style = stroke())
    }

    val Unlock = EditorIcon { color ->
        drawRect(color.copy(alpha = 0.5f), topLeft = Offset(size.width * 0.2f, size.height * 0.46f), size = Size(size.width * 0.6f, size.height * 0.46f), style = stroke())
        drawArc(color.copy(alpha = 0.5f), 180f, 140f, false, topLeft = Offset(size.width * 0.3f, size.height * 0.12f), size = Size(size.width * 0.4f, size.height * 0.62f), style = stroke())
    }

    val NewLayer = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.1f, size.height * 0.1f), size = Size(size.width * 0.8f, size.height * 0.8f), style = stroke())
        line(color, 0.32f, 0.5f, 0.68f, 0.5f); line(color, 0.5f, 0.32f, 0.5f, 0.68f)
    }

    val Duplicate = Copy

    val Raise = EditorIcon { color ->
        line(color, 0.5f, 0.15f, 0.5f, 0.88f)
        line(color, 0.2f, 0.45f, 0.5f, 0.15f); line(color, 0.8f, 0.45f, 0.5f, 0.15f)
    }

    val Lower = EditorIcon { color ->
        line(color, 0.5f, 0.12f, 0.5f, 0.85f)
        line(color, 0.2f, 0.55f, 0.5f, 0.85f); line(color, 0.8f, 0.55f, 0.5f, 0.85f)
    }

    val MergeDown = EditorIcon { color ->
        line(color, 0.5f, 0.08f, 0.5f, 0.55f)
        line(color, 0.3f, 0.36f, 0.5f, 0.55f); line(color, 0.7f, 0.36f, 0.5f, 0.55f)
        line(color, 0.12f, 0.8f, 0.88f, 0.8f)
    }

    val ToSelection = EditorIcon { color ->
        drawRect(color, topLeft = Offset(size.width * 0.1f, size.height * 0.1f), size = Size(size.width * 0.8f, size.height * 0.8f),
            style = Stroke(width = size.minDimension * 0.09f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2.5f))))
        drawPath(poly(0.5f, 0.28f, 0.74f, 0.42f, 0.5f, 0.56f, 0.26f, 0.42f), color)
    }

    /** Counter-clockwise circular arrow. */
    val RotateLeft = EditorIcon { color ->
        drawArc(color, startAngle = -60f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f), size = Size(size.width * 0.68f, size.height * 0.68f), style = stroke())
        drawPath(poly(0.62f, 0.02f, 0.86f, 0.24f, 0.56f, 0.34f), color)
    }

    /** Clockwise circular arrow. */
    val RotateRight = EditorIcon { color ->
        drawArc(color, startAngle = -120f, sweepAngle = -290f, useCenter = false,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f), size = Size(size.width * 0.68f, size.height * 0.68f), style = stroke())
        drawPath(poly(0.38f, 0.02f, 0.14f, 0.24f, 0.44f, 0.34f), color)
    }

    val Check = EditorIcon { color -> line(color, 0.18f, 0.52f, 0.42f, 0.76f); line(color, 0.42f, 0.76f, 0.84f, 0.26f) }

    val Close = EditorIcon { color -> line(color, 0.25f, 0.25f, 0.75f, 0.75f); line(color, 0.75f, 0.25f, 0.25f, 0.75f) }

    /** Two opposite arrows: blocks of one world mapped to another's. */
    val Mapping = EditorIcon { color ->
        line(color, 0.12f, 0.34f, 0.84f, 0.34f)
        drawPath(poly(0.88f, 0.34f, 0.66f, 0.18f, 0.66f, 0.5f), color)
        line(color, 0.88f, 0.68f, 0.16f, 0.68f)
        drawPath(poly(0.12f, 0.68f, 0.34f, 0.52f, 0.34f, 0.84f), color)
    }

    val Folder = EditorIcon { color ->
        drawPath(poly(0.08f, 0.2f, 0.4f, 0.2f, 0.5f, 0.32f, 0.92f, 0.32f, 0.92f, 0.82f, 0.08f, 0.82f), color, style = stroke())
    }
}
