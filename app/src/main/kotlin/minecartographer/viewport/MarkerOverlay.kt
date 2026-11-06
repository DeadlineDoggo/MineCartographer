package minecartographer.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import minecartographer.core.Rotation
import minecartographer.core.markers.Marker
import minecartographer.editor.EditorState
import minecartographer.ui.EditorTheme

private const val PIN_HEIGHT = 16f
private const val PIN_RADIUS = 6f

/** Names are written when the view is this close, or when there are few pins. */
private const val LABEL_ZOOM = 1.5
private const val FEW = 24

/**
 * Pins for the markers of a world (waypoints of the minimap mods, banners on maps): a stem at the spot, a
 * colored head on top, and the name beside it. [rotation] is the view's turn; the pins stand at their height, or
 * at the typical surface height when it is not known.
 */
internal fun DrawScope.drawMarkers(
    markers: List<Marker>, view: IsoView, hs: Double, rotation: Int, measurer: TextMeasurer, labels: MutableMap<String, TextLayoutResult>,
) {
    if (markers.isEmpty()) return
    val named = view.zoom >= LABEL_ZOOM || markers.size <= FEW
    val style = TextStyle(color = EditorTheme.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    for (m in markers) {
        val (rx, rz) = Rotation.point(rotation, m.x + 0.5, m.z + 0.5)
        val y = m.y?.toDouble() ?: EditorState.SURFACE_Y
        val sx = view.toScreenX(if (view.flat) 2 * rx else IsoView.u(rx, rz), size.width)
        val sy = view.toScreenY(if (view.flat) 2 * rz else IsoView.v(rx, y + 1, rz, hs), size.height)
        if (sx < -80 || sy < -40 || sx > size.width + 80 || sy > size.height + PIN_HEIGHT + 40) continue
        val head = Offset(sx, sy - PIN_HEIGHT)
        val color = Color(0xFF000000.toInt() or m.rgb)
        drawLine(Color(0xCC000000), Offset(sx, sy), head, strokeWidth = 3f)
        drawLine(color, Offset(sx, sy), head, strokeWidth = 1.5f)
        drawCircle(Color(0xFF14151A), PIN_RADIUS + 1.5f, head)
        drawCircle(color, PIN_RADIUS, head)
        drawCircle(Color.White.copy(alpha = 0.85f), PIN_RADIUS, head, style = Stroke(1.2f))
        if (named) {
            val label = labels.getOrPut(m.name) { measurer.measure(m.name, style) }
            val at = Offset(head.x + PIN_RADIUS + 5f, head.y - label.size.height / 2f)
            drawRoundRect(
                Color(0xCC1E1F22), at - Offset(4f, 1f), Size(label.size.width + 8f, label.size.height + 2f),
                androidx.compose.ui.geometry.CornerRadius(4f),
            )
            drawText(label, topLeft = at)
        }
    }
}
