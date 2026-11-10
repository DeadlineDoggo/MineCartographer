package minecartographer.viewport

import androidx.compose.ui.geometry.CornerRadius
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
import kotlin.math.hypot

private const val PIN_HEIGHT = 16f
private const val PIN_RADIUS = 6f

/** Names are written when the view is this close, or when there are few pins. */
private const val LABEL_ZOOM = 1.5
private const val FEW = 24

/** How far from a pin a click still counts as on it, in pixels. */
private const val PICK_SLACK = 4f

private val LABEL_STYLE = TextStyle(color = EditorTheme.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

/** Where a pin is on screen: [base] is the spot on the ground, [head] the colored circle above it. */
private class Pin(val base: Offset, val head: Offset)

/** The pin of [m] in a view of [width] x [height] pixels; the pins stand at their height, or at the typical surface height when it is not known. */
private fun pinOf(m: Marker, view: IsoView, hs: Double, rotation: Int, width: Float, height: Float): Pin {
    val (rx, rz) = Rotation.point(rotation, m.x + 0.5, m.z + 0.5)
    val y = m.y?.toDouble() ?: EditorState.SURFACE_Y
    val sx = view.toScreenX(if (view.flat) 2 * rx else IsoView.u(rx, rz), width)
    val sy = view.toScreenY(if (view.flat) 2 * rz else IsoView.v(rx, y + 1, rz, hs), height)
    return Pin(Offset(sx, sy), Offset(sx, sy - PIN_HEIGHT))
}

private fun label(m: Marker, measurer: TextMeasurer, labels: MutableMap<String, TextLayoutResult>) =
    labels.getOrPut(m.name) { measurer.measure(m.name, LABEL_STYLE) }

/** The rectangle of the name bubble beside a pin's head: top left and size. */
private fun bubble(pin: Pin, label: TextLayoutResult): Pair<Offset, Size> =
    Offset(pin.head.x + PIN_RADIUS + 5f - 4f, pin.head.y - label.size.height / 2f - 1f) to Size(label.size.width + 8f, label.size.height + 2f)

private fun showsNames(markers: List<Marker>, view: IsoView) = view.zoom >= LABEL_ZOOM || markers.size <= FEW

/**
 * Pins for the markers of a world (waypoints of the minimap mods, banners on maps): a stem at the spot, a
 * colored head on top, and the name beside it. [rotation] is the view's turn.
 * [menu] is the marker whose right-click menu is open and [hover] the one under the pointer: their name bubble is
 * outlined in blue (and written even when names are hidden at this zoom).
 */
internal fun DrawScope.drawMarkers(
    markers: List<Marker>, view: IsoView, hs: Double, rotation: Int, measurer: TextMeasurer, labels: MutableMap<String, TextLayoutResult>,
    hover: Marker? = null, menu: Marker? = null,
) {
    if (markers.isEmpty()) return
    val named = showsNames(markers, view)
    for (m in markers) {
        val pin = pinOf(m, view, hs, rotation, size.width, size.height)
        val (sx, sy) = pin.base
        if (sx < -80 || sy < -40 || sx > size.width + 80 || sy > size.height + PIN_HEIGHT + 40) continue
        val head = pin.head
        val color = Color(0xFF000000.toInt() or m.rgb)
        drawLine(Color(0xCC000000), pin.base, head, strokeWidth = 3f)
        drawLine(color, pin.base, head, strokeWidth = 1.5f)
        drawCircle(Color(0xFF14151A), PIN_RADIUS + 1.5f, head)
        drawCircle(color, PIN_RADIUS, head)
        drawCircle(Color.White.copy(alpha = 0.85f), PIN_RADIUS, head, style = Stroke(1.2f))
        val outlined = m == menu || m == hover
        if (named || outlined) {
            val text = label(m, measurer, labels)
            val (at, bubbleSize) = bubble(pin, text)
            drawRoundRect(Color(0xCC1E1F22), at, bubbleSize, CornerRadius(4f))
            if (outlined) {
                drawRoundRect(EditorTheme.accent, at, bubbleSize, CornerRadius(4f), style = Stroke(if (m == menu) 2.5f else 1.5f))
                drawCircle(EditorTheme.accent, PIN_RADIUS + 3f, head, style = Stroke(if (m == menu) 2.5f else 1.5f))
            }
            drawText(text, topLeft = at + Offset(4f, 1f))
        }
    }
}

/**
 * The marker at [at] (pixels in the map), the one drawn on top when pins overlap: a click on the colored head, the
 * stem or, while names are shown, the name bubble counts.
 */
internal fun pickMarker(
    markers: List<Marker>, at: Offset, view: IsoView, hs: Double, rotation: Int, width: Float, height: Float,
    measurer: TextMeasurer, labels: MutableMap<String, TextLayoutResult>,
): Marker? {
    val named = showsNames(markers, view)
    for (m in markers.asReversed()) {
        val pin = pinOf(m, view, hs, rotation, width, height)
        if (hypot(at.x - pin.head.x, at.y - pin.head.y) <= PIN_RADIUS + PICK_SLACK) return m
        if (at.x in pin.head.x - PICK_SLACK..pin.head.x + PICK_SLACK && at.y in pin.head.y..pin.base.y + PICK_SLACK) return m
        if (named) {
            val (corner, bubbleSize) = bubble(pin, label(m, measurer, labels))
            if (at.x in corner.x..corner.x + bubbleSize.width && at.y in corner.y..corner.y + bubbleSize.height) return m
        }
    }
    return null
}
