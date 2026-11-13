package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.unit.DpSize
import minecartographer.core.color.Hsv
import minecartographer.core.color.HexColor
import minecartographer.core.color.MinecraftColors
import minecartographer.editor.EditorState

private fun rgbColor(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/**
 * A color picker for any place that needs a color: the 16 colors of Minecraft's chat as squares (what Xaero's Minimap
 * supports) or, with "Full color" ([allowFull]), a saturation/value square with a hue strip and sliders in RGB or HSV.
 * At the bottom the color as `#RRGGBB`: a field to type or paste one, and a copy button ([onCopy]). [rgb] is 0xRRGGBB.
 */
@Composable
fun ColorPicker(
    rgb: Int, onChange: (Int) -> Unit, onCopy: (String) -> Unit, modifier: Modifier = Modifier, allowFull: Boolean = true,
    /** Called when the picker switches to a layout of another size (mode, style, sliders), e.g. to fit a window to it. */
    onLayoutChange: () -> Unit = {},
    /** When set, the Full color tab has an eyedropper button that calls it (see [ScreenPicker]). */
    onEyedropper: (() -> Unit)? = null,
) {
    var full by remember { mutableStateOf(allowFull && MinecraftColors.indexOf(rgb) == null) }
    // Hue and saturation are kept apart from the rgb: a gray or black has none to read back, and dragging would lose it.
    var hsv by remember { mutableStateOf(Hsv.of(rgb)) }
    LaunchedEffect(rgb) { if (hsv.toRgb() != rgb) hsv = Hsv.of(rgb).keepingHueOf(hsv) }
    fun setHsv(next: Hsv) {
        hsv = next
        onChange(next.toRgb())
    }
    fun setRgb(next: Int) {
        hsv = Hsv.of(next).keepingHueOf(hsv)
        onChange(next and 0xFFFFFF)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowFull) Segmented(listOf(false, true), full, { if (it) "Full color" else "Minecraft 16" }) { full = it; onLayoutChange() }
        if (full && allowFull) FullPicker(hsv, ::setHsv, rgb, ::setRgb, onLayoutChange, onEyedropper) else PaletteWheel(rgb, ::setRgb)
        HexRow(rgb, ::setRgb, onCopy)
    }
}

private fun Hsv.keepingHueOf(old: Hsv) = Hsv(if (s == 0f || v == 0f) old.h else h, if (v == 0f) old.s else s, v)

// ---- The 16 colors ----

/**
 * The 16 colors as a wheel: the 12 with a hue around the outside in hue order (each light one next to its dark one) and
 * the 4 grays as slices in the middle. The picked one is outlined with a blue dot in it. Press and drag across the wheel:
 * the slice under the pointer pops out, a line runs from the middle to its dot, and letting go picks it. The name of the
 * slice under the pointer (else the picked one) is written below.
 */
@Composable
private fun PaletteWheel(rgb: Int, onPick: (Int) -> Unit) {
    val picked = MinecraftColors.indexOf(rgb)
    var hover by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val latestPick by rememberUpdatedState(onPick)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.size(WHEEL_SIZE.dp).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val at = PaletteGeometry(size.width.toFloat()).indexAt(change.position)
                        when (event.type) {
                            PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                                dragging = true
                                hover = at
                                change.consume()
                            }
                            PointerEventType.Release -> if (dragging) {
                                dragging = false
                                at?.let { latestPick(MinecraftColors.CHAT[it]) }
                                hover = at
                                change.consume()
                            }
                            PointerEventType.Exit -> if (!dragging) hover = null
                            else -> hover = at
                        }
                    }
                }
            },
        ) {
            val g = PaletteGeometry(size.width)
            // While dragging, the slice under the pointer stands out; otherwise the picked one.
            val current = if (dragging) hover else picked
            for (w in g.wedges) {
                if (dragging && w.index == current) continue // drawn last, popped out
                drawPath(w.path, rgbColor(MinecraftColors.CHAT[w.index]))
                // A faint edge, so black and dark gray stay visible on the dark background.
                drawPath(w.path, Color.White.copy(alpha = 0.18f), style = Stroke(1f))
            }
            if (!dragging) hover?.takeIf { it != picked }?.let { i -> g.wedge(i)?.let { drawPath(it.path, Color.White.copy(alpha = 0.6f), style = Stroke(2f)) } }
            current?.let { i ->
                val w = g.wedge(i) ?: return@let
                val pop = if (dragging) 1.08f else 1f
                withTransform({ scale(pop, pop, g.center) }) {
                    drawPath(w.path, rgbColor(MinecraftColors.CHAT[i]))
                    drawPath(w.path, Color.Black, style = Stroke(5f))
                    drawPath(w.path, EditorTheme.accent, style = Stroke(3f))
                }
                val dot = g.center + (w.dot - g.center) * pop
                if (dragging) {
                    drawLine(Color.Black, g.center, dot, strokeWidth = 4f)
                    drawLine(EditorTheme.accent, g.center, dot, strokeWidth = 2f)
                    drawCircle(EditorTheme.accent, 4f, g.center)
                }
                drawCircle(Color.Black, 7f, dot)
                drawCircle(EditorTheme.accent, 5.5f, dot)
                drawCircle(Color.White, 5.5f, dot, style = Stroke(1.5f))
            }
        }
        val named = hover ?: picked
        Txt(
            if (named != null) "${MinecraftColors.NAMES[named]} · ${HexColor.format(MinecraftColors.CHAT[named])}" else "Not one of the 16 colors",
            color = if (named != null) EditorTheme.text else EditorTheme.textFaint, size = 12.sp,
        )
    }
}

private const val WHEEL_SIZE = 220

/** Chat color indices around the wheel: hue order, light before dark. And the grays of the inner ring. */
private val OUTER = listOf(12, 4, 6, 14, 10, 2, 11, 3, 9, 1, 13, 5)
private val INNER = listOf(15, 7, 8, 0)

/** The wedges of the palette wheel in a canvas [side] pixels wide; angles start at the top and go clockwise. */
private class PaletteGeometry(side: Float) {
    val center = Offset(side / 2, side / 2)
    private val outer = side / 2 - 3f
    private val split = outer * 0.66f
    private val innerEdge = 0f // the grays reach the middle, there is no core

    /** A slice: its chat color index, its outline, and the spot in its middle where the blue dot goes. */
    class Wedge(val index: Int, val path: Path, val dot: Offset)

    val wedges: List<Wedge> =
        OUTER.mapIndexed { k, i -> Wedge(i, wedge(split + 2f, outer, -105f + k * 30f, 30f), spot(-90f + k * 30f, (split + outer) / 2)) } +
            INNER.mapIndexed { k, i -> Wedge(i, wedge(innerEdge, split - 2f, -135f + k * 90f, 90f), spot(-90f + k * 90f, split * 0.55f)) }

    fun wedge(index: Int): Wedge? = wedges.firstOrNull { it.index == index }

    private fun spot(deg: Float, r: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y + r * kotlin.math.sin(a).toFloat())
    }

    private fun wedge(r0: Float, r1: Float, start: Float, sweep: Float): Path {
        val gap = 1.5f // degrees between wedges
        val a = start + gap / 2
        val b = sweep - gap
        return Path().apply {
            if (r0 <= 0f) {
                // A slice reaching the middle.
                moveTo(center.x, center.y)
                arcTo(androidx.compose.ui.geometry.Rect(center, r1), a, b, false)
            } else {
                arcTo(androidx.compose.ui.geometry.Rect(center, r1), a, b, true)
                arcTo(androidx.compose.ui.geometry.Rect(center, r0), a + b, -b, false)
            }
            close()
        }
    }

    fun indexAt(p: Offset): Int? {
        val d = (p - center).getDistance()
        val deg = ((Math.toDegrees(kotlin.math.atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat() + 360f) % 360f)
        return when {
            d > outer || d < innerEdge -> null
            d >= split -> OUTER[(((deg + 105f) % 360f) / 30f).toInt() % 12]
            else -> INNER[(((deg + 135f) % 360f) / 90f).toInt() % 4]
        }
    }
}

// ---- Full color ----

/** How the hue, saturation and value are picked: a plain square, a hue ring around a square, or a hue ring around a triangle. */
private enum class FieldStyle(val label: String) { Square("Square"), RingSquare("Ring"), RingTriangle("Triangle") }

private val RAINBOW = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { rgbColor(Hsv(it, 1f, 1f).toRgb()) }

@Composable
private fun FullPicker(hsv: Hsv, onHsv: (Hsv) -> Unit, rgb: Int, onRgb: (Int) -> Unit, onLayoutChange: () -> Unit, onEyedropper: (() -> Unit)?) {
    var style by remember { mutableStateOf(FieldStyle.RingTriangle) }
    var hsvSliders by remember { mutableStateOf(false) } // false RGB, true HSV
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) { Segmented(FieldStyle.entries, style, { it.label }) { style = it; onLayoutChange() } }
            if (onEyedropper != null) IconButton(Icons.Pipette, "Pick a color from anywhere on the screen", size = 32.dp, iconSize = 18.dp) { onEyedropper() }
        }
        when (style) {
            FieldStyle.Square -> {
                SaturationValueSquare(hsv, onHsv)
                Slider("H", hsv.h, 360f, RAINBOW, "${hsv.h.toInt()}°") { onHsv(hsv.copy(h = it)) }
            }
            else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RingField(hsv, onHsv, triangle = style == FieldStyle.RingTriangle) }
        }
        Segmented(listOf(false, true), hsvSliders, { if (it) "HSV sliders" else "RGB sliders" }) { hsvSliders = it; onLayoutChange() }
        if (!hsvSliders) {
            val r = rgb shr 16 and 255
            val g = rgb shr 8 and 255
            val b = rgb and 255
            fun with(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
            Slider("R", r.toFloat(), 255f, listOf(rgbColor(with(0, g, b)), rgbColor(with(255, g, b))), "$r") { onRgb(with(it.toInt(), g, b)) }
            Slider("G", g.toFloat(), 255f, listOf(rgbColor(with(r, 0, b)), rgbColor(with(r, 255, b))), "$g") { onRgb(with(r, it.toInt(), b)) }
            Slider("B", b.toFloat(), 255f, listOf(rgbColor(with(r, g, 0)), rgbColor(with(r, g, 255))), "$b") { onRgb(with(r, g, it.toInt())) }
        } else {
            // The square style has its hue strip above already.
            if (style != FieldStyle.Square) Slider("H", hsv.h, 360f, RAINBOW, "${hsv.h.toInt()}°") { onHsv(hsv.copy(h = it)) }
            Slider("S", hsv.s, 1f, listOf(rgbColor(hsv.copy(s = 0f).toRgb()), rgbColor(hsv.copy(s = 1f).toRgb())), "${(hsv.s * 100).toInt()}%") { onHsv(hsv.copy(s = it)) }
            Slider("V", hsv.v, 1f, listOf(rgbColor(hsv.copy(v = 0f).toRgb()), rgbColor(hsv.copy(v = 1f).toRgb())), "${(hsv.v * 100).toInt()}%") { onHsv(hsv.copy(v = it)) }
        }
    }
}

/** Calls [onPoint] with the pointer's position while the left button is held, also when it moves out of the box. */
private fun Modifier.dragPoint(onPoint: (Offset, androidx.compose.ui.unit.IntSize) -> Unit) = composed {
    val latest by rememberUpdatedState(onPoint)
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press && event.type != PointerEventType.Move) continue
                if (!event.buttons.isPrimaryPressed) continue
                val change = event.changes.firstOrNull() ?: continue
                latest(change.position, size)
                change.consume()
            }
        }
    }
}

private fun DrawScope.thumb(at: Offset) {
    drawCircle(Color.Black, 7f, at, style = Stroke(3f))
    drawCircle(Color.White, 7f, at, style = Stroke(1.5f))
}

@Composable
private fun SaturationValueSquare(hsv: Hsv, onHsv: (Hsv) -> Unit) {
    val hue = rgbColor(Hsv(hsv.h, 1f, 1f).toRgb())
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp))
            .dragPoint { p, size ->
                onHsv(hsv.copy(s = (p.x / size.width).coerceIn(0f, 1f), v = 1f - (p.y / size.height).coerceIn(0f, 1f)))
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        thumb(Offset(hsv.s * size.width, (1f - hsv.v) * size.height))
    }
}

private const val RING_SIZE = 220
private const val RING_THICKNESS = 0.17f // of the outer radius

/** What a drag that started on the ring field is moving. */
private enum class Grab { None, Hue, Inner }

/**
 * The hue on a ring with the saturation and value inside it, as a square or as a triangle (its corners are the pure hue,
 * white and black, as in the color pickers of image editors). The triangle stands still while the ring turns the hue.
 */
@Composable
private fun RingField(hsv: Hsv, onHsv: (Hsv) -> Unit, triangle: Boolean) {
    var grab by remember { mutableStateOf(Grab.None) }
    val latestHsv by rememberUpdatedState(hsv)
    val latestOnHsv by rememberUpdatedState(onHsv)
    val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { RING_SIZE.dp.toPx() }
    val triangleImage = remember(triangle, hsv.h.roundToInt() % 360, sidePx) { if (triangle) renderTriangle(RingGeometry(sidePx, true), hsv.h.roundToInt() % 360) else null }
    // The triangle's pure-hue corner points at the hue's spot on the ring. The angle follows the hue by the short way
    // round (359° to 1° is 2°, not 358°) and eases there, so a click far along the ring turns the triangle smoothly.
    // Only the angle is animated: the image is drawn turned, and picking uses the corners where they are drawn.
    val unwrapped = remember { floatArrayOf(hsv.h) }
    unwrapped[0] += ((hsv.h - unwrapped[0]) % 360f + 540f) % 360f - 180f
    val turn by androidx.compose.animation.core.animateFloatAsState(
        if (triangle) unwrapped[0] - 90f else 0f, androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
    )
    val latestTurn by rememberUpdatedState(turn)
    Canvas(
        Modifier.size(RING_SIZE.dp).pointerInput(triangle) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    if (!event.buttons.isPrimaryPressed) {
                        grab = Grab.None
                        continue
                    }
                    val geometry = RingGeometry(size.width.toFloat(), triangle, latestTurn)
                    if (event.type == PointerEventType.Press) grab = geometry.grabAt(change.position)
                    if (event.type != PointerEventType.Press && event.type != PointerEventType.Move) continue
                    when (grab) {
                        Grab.Hue -> latestOnHsv(latestHsv.copy(h = geometry.hueAt(change.position)))
                        Grab.Inner -> geometry.valueAt(change.position, latestHsv)?.let(latestOnHsv)
                        Grab.None -> continue
                    }
                    change.consume()
                }
            }
        },
    ) {
        val g = RingGeometry(size.width, triangle, turn)
        // The ring: hue grows counterclockwise from the right, like in the pickers it follows.
        drawCircle(
            Brush.sweepGradient(listOf(0f, 300f, 240f, 180f, 120f, 60f, 0f).map { rgbColor(Hsv(it, 1f, 1f).toRgb()) }, g.center),
            radius = (g.outer + g.inner) / 2, center = g.center, style = Stroke(g.outer - g.inner),
        )
        val ringThumb = g.hueSpot(hsv.h)
        drawCircle(Color.Black, 9f, ringThumb, style = Stroke(3.5f))
        drawCircle(Color.White, 9f, ringThumb, style = Stroke(2f))
        val hue = rgbColor(Hsv(hsv.h, 1f, 1f).toRgb())
        if (triangle) {
            triangleImage?.let { image -> withTransform({ rotate(-turn, g.center) }) { drawImage(image) } }
        } else {
            val box = g.box
            drawRect(Brush.horizontalGradient(listOf(Color.White, hue), box.left, box.right), box.topLeft, box.size)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), box.top, box.bottom), box.topLeft, box.size)
        }
        thumb(g.spotOf(hsv))
    }
}

/** Where things are in a ring field [side] pixels wide. */
private class RingGeometry(side: Float, val triangle: Boolean, turn: Float = 0f) {
    val center = Offset(side / 2, side / 2)
    val outer = side / 2 - 2f
    val inner = outer * (1 - RING_THICKNESS)
    /** The inside of the ring that the square or triangle fills. */
    val reach = inner * 0.92f

    /**
     * Triangle corners: the pure hue, white and black. At [turn] 0 the pure hue is on top, white bottom right, black
     * bottom left; [turn] is how far the whole triangle is turned counterclockwise, in degrees.
     */
    val corners: List<Offset> = listOf(90f, -30f, 210f).map { base ->
        val a = Math.toRadians((base + turn).toDouble())
        Offset(center.x + reach * kotlin.math.cos(a).toFloat(), center.y - reach * kotlin.math.sin(a).toFloat())
    }
    val box: androidx.compose.ui.geometry.Rect = reach.let { r ->
        val half = r / 1.4142f
        androidx.compose.ui.geometry.Rect(center.x - half, center.y - half, center.x + half, center.y + half)
    }

    fun grabAt(p: Offset): Grab {
        val d = (p - center).getDistance()
        return when {
            d > outer + 4f -> Grab.None
            d >= inner -> Grab.Hue
            else -> Grab.Inner
        }
    }

    fun hueAt(p: Offset): Float {
        val deg = Math.toDegrees(kotlin.math.atan2((center.y - p.y).toDouble(), (p.x - center.x).toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    fun hueSpot(hue: Float): Offset {
        val r = (outer + inner) / 2
        val a = Math.toRadians(hue.toDouble())
        return Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y - r * kotlin.math.sin(a).toFloat())
    }

    /** The new saturation and value for a pointer at [p], kept inside the square or triangle. */
    fun valueAt(p: Offset, old: Hsv): Hsv? {
        if (!triangle) return old.copy(s = ((p.x - box.left) / box.width).coerceIn(0f, 1f), v = 1f - ((p.y - box.top) / box.height).coerceIn(0f, 1f))
        var (a, b, c) = barycentric(p)
        a = a.coerceAtLeast(0f); b = b.coerceAtLeast(0f); c = c.coerceAtLeast(0f)
        val sum = a + b + c
        if (sum == 0f) return null
        a /= sum; b /= sum
        val v = a + b
        return old.copy(s = if (v == 0f) old.s else a / v, v = v)
    }

    fun spotOf(hsv: Hsv): Offset {
        if (!triangle) return Offset(box.left + hsv.s * box.width, box.top + (1f - hsv.v) * box.height)
        val a = hsv.s * hsv.v
        val b = hsv.v * (1 - hsv.s)
        val c = 1 - hsv.v
        return Offset(
            a * corners[0].x + b * corners[1].x + c * corners[2].x,
            a * corners[0].y + b * corners[1].y + c * corners[2].y,
        )
    }

    /** Weights of the pure hue, white and black corners for the point [p] (negative outside the triangle). */
    fun barycentric(p: Offset): Triple<Float, Float, Float> {
        val (a, b, c) = corners
        val d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
        val wa = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / d
        val wb = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / d
        return Triple(wa, wb, 1f - wa - wb)
    }
}

/**
 * The saturation/value triangle for a hue: every pixel is the blend of the pure hue, white and black by its weights
 * (which is what saturation and value mean), the edge softened by a pixel.
 */
private fun renderTriangle(g: RingGeometry, hue: Int): ImageBitmap {
    val side = (g.center.x * 2).toInt()
    val pure = Hsv(hue.toFloat(), 1f, 1f).toRgb()
    val hr = pure shr 16 and 255
    val hg = pure shr 8 and 255
    val hb = pure and 255
    val altitude = g.reach * 1.5f
    val bytes = ByteArray(side * side * 4)
    for (y in 0 until side) for (x in 0 until side) {
        val (a, b, c) = g.barycentric(Offset(x + 0.5f, y + 0.5f))
        val alpha = (minOf(a, b, c) * altitude + 0.5f).coerceIn(0f, 1f)
        if (alpha == 0f) continue
        val wa = a.coerceAtLeast(0f)
        val wb = b.coerceAtLeast(0f)
        val wc = c.coerceAtLeast(0f)
        val sum = wa + wb + wc
        val r = (hr * wa + 255 * wb) / sum
        val gr = (hg * wa + 255 * wb) / sum
        val bl = (hb * wa + 255 * wb) / sum
        val i = (y * side + x) * 4
        bytes[i] = (bl * alpha).roundToInt().toByte() // BGRA, premultiplied
        bytes[i + 1] = (gr * alpha).roundToInt().toByte()
        bytes[i + 2] = (r * alpha).roundToInt().toByte()
        bytes[i + 3] = (alpha * 255).roundToInt().toByte()
    }
    return org.jetbrains.skia.Image.makeRaster(ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, side * 4).toComposeImageBitmap()
}

/** A labelled slider whose track shows the colors it passes through; [value] is in 0..[max]. */
@Composable
private fun Slider(label: String, value: Float, max: Float, track: List<Color>, text: String, onValue: (Float) -> Unit) {
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { SLIDER_PAD.dp.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(16.dp), color = EditorTheme.textDim, size = 12.sp)
        Canvas(
            Modifier.weight(1f).height(20.dp)
                .dragPoint { p, size ->
                    val pad = padPx
                    onValue(((p.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * max)
                },
        ) {
            val bar = 8.dp.toPx()
            val top = (size.height - bar) / 2
            val pad = SLIDER_PAD.dp.toPx()
            drawRoundRect(Brush.horizontalGradient(track, pad, size.width - pad), Offset(pad, top), Size(size.width - 2 * pad, bar), CornerRadius(bar / 2))
            val x = pad + (value / max).coerceIn(0f, 1f) * (size.width - 2 * pad)
            drawCircle(Color.Black, 8f, Offset(x, size.height / 2), style = Stroke(3f))
            drawCircle(Color.White, 8f, Offset(x, size.height / 2), style = Stroke(1.5f))
        }
        Txt(text, Modifier.width(48.dp).padding(start = 6.dp), size = 12.sp, mono = true)
    }
}

/** Room at both ends of a slider's track for the thumb, so it never covers the label or the value. */
private const val SLIDER_PAD = 10

// ---- The result ----

@Composable
private fun HexRow(rgb: Int, onRgb: (Int) -> Unit, onCopy: (String) -> Unit) {
    // The field shows the color as `#RRGGBB` and starts again from it whenever the color changes; typing a whole color applies it.
    var text by remember(rgb) { mutableStateOf(HexColor.format(rgb)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)).background(rgbColor(rgb)).border(1.dp, EditorTheme.border, RoundedCornerShape(5.dp)))
        TextInput(text, { typed ->
            text = typed
            HexColor.parse(typed)?.let { if (it != rgb) onRgb(it) }
        }, Modifier.weight(1f))
        IconButton(Icons.Copy, "Copy ${HexColor.format(rgb)}", size = 34.dp, iconSize = 16.dp) { onCopy(HexColor.format(rgb)) }
    }
}

/** The color picker window opened from the View menu. */
@Composable
fun ColorPickerDialog(state: EditorState) {
    if (!state.colorPickerVisible) return
    ColorPickerWindow(state, "Color picker", state.pickedColor, { state.pickedColor = it }, { state.colorPickerVisible = false })
}

/**
 * The color picker in a window of its own. Picking only changes the color shown in the window; **Select** hands it to
 * [onSelect] and closes, Cancel (or closing the window) keeps the old one. The window fits the picker when it opens
 * and when the picker switches layout; in between the user can resize it freely (the content scrolls when it is too
 * small). [allowFull] false offers only the 16 Minecraft colors.
 */
@Composable
fun ColorPickerWindow(state: EditorState, title: String, rgb: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, allowFull: Boolean = true) {
    val windowState = rememberDialogState(size = DpSize(340.dp, 600.dp))
    val density = androidx.compose.ui.platform.LocalDensity.current
    var draft by remember { mutableStateOf(rgb) }
    var contentHeight by remember { mutableStateOf(0.dp) }
    // Counts the times the window should fit the picker. The fit waits a moment: right at the start the window is not
    // shown yet and would take its first size back, and after a switch the new layout has to be measured first.
    var fitRequests by remember { mutableStateOf(0) }
    LaunchedEffect(fitRequests) {
        kotlinx.coroutines.delay(80)
        if (contentHeight > 0.dp) windowState.size = DpSize(windowState.size.width, contentHeight + TITLE_BAR)
    }
    DialogWindow(onCloseRequest = onClose, title = title, icon = AppIconPainter, state = windowState) {
        Box(Modifier.fillMaxSize().background(EditorTheme.window).verticalScroll(rememberScrollState())) {
            // Measured in the scroll area, so the height is the content's own and does not depend on the window.
            Column(
                Modifier.fillMaxWidth().onSizeChanged { size -> contentHeight = with(density) { size.height.toDp() } }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ColorPicker(
                    draft, { draft = it }, state::copyText, Modifier.fillMaxWidth(),
                    allowFull = allowFull,
                    onLayoutChange = { fitRequests++ },
                    onEyedropper = { ScreenPicker.pick({ draft = it }, { state.notify(it, warning = true) }, { state.notify(it) }) },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Before and after, side by side.
                    WithTooltip("Now ${HexColor.format(rgb)}") {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(rgbColor(rgb)).border(1.dp, EditorTheme.border, RoundedCornerShape(4.dp)))
                    }
                    Txt("→", color = EditorTheme.textDim, size = 12.sp)
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(rgbColor(draft)).border(1.dp, EditorTheme.border, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.weight(1f))
                    TextButton("Cancel") { onClose() }
                    TextButton("Select", primary = true) {
                        onSelect(draft)
                        onClose()
                    }
                }
            }
        }
    }
}

/** Room for the window's title bar on top of its content. */
private val TITLE_BAR = 36.dp
