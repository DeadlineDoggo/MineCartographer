package minecartographer.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import java.util.BitSet
import kotlin.math.ceil

data class RenderSettings(
    /** Vertical exaggeration: 1.0 draws blocks as cubes. */
    val heightScale: Float = 1f,
    val chunkBorders: Boolean = true,
)

/**
 * A chunk pre-rendered as an isometric image at [lod] pixels per iso unit.
 *
 * The image does not depend on where the chunk sits, so the same tile can be drawn
 * at any position (that is how pasted chunks are shown); see [originU]/[originV].
 */
class ChunkTile(
    val lod: Int,
    val image: ImageBitmap,
    val minHeight: Int,
    val maxHeight: Int,
    private val mask: BitSet,
) {
    val byteSize: Long get() = image.width.toLong() * image.height * 4

    /** Iso coordinates of the image's top-left corner when drawn at [pos]. */
    fun originU(pos: ChunkPos) = 16.0 * (pos.x - pos.z) - 16
    fun originV(pos: ChunkPos, hs: Double) = 8.0 * (pos.x + pos.z) - (maxHeight + 1) * hs

    /** True when the pixel at ([px], [py]) belongs to this chunk (pixel-exact picking). */
    fun hit(px: Int, py: Int) =
        px in 0 until image.width && py in 0 until image.height && mask[py * image.width + px]
}

/** Draws a [ChunkSurface] as a block of columns: top faces plus visible east and south sides. */
object ChunkTileRenderer {
    /** How many blocks the chunk's sides extend below its lowest column. */
    private const val SKIRT = 3
    private const val EAST_SHADE = 0.80f
    private const val SOUTH_SHADE = 0.64f
    private val BORDER_COLOR = Color(0x48000000)

    fun render(surface: ChunkSurface, lod: Int, settings: RenderSettings): ChunkTile {
        val hs = settings.heightScale.toDouble()
        val top = surface.maxHeight + 1
        val base = surface.minHeight - SKIRT
        val width = 32 * lod
        val height = ceil((16 + (top - base) * hs) * lod).toInt() + 1

        val image = ImageBitmap(width, height)
        val canvas = Canvas(image)
        val paint = Paint().apply { isAntiAlias = false }
        val path = Path()

        // Chunk-local block coordinates -> image pixels.
        fun px(lx: Int, lz: Int) = ((lx - lz + 16) * lod).toFloat()
        fun py(lx: Int, y: Int, lz: Int) = (((lx + lz) / 2.0 - y * hs + top * hs) * lod).toFloat()

        fun quad(color: Color, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int,
                 x2: Int, y2: Int, z2: Int, x3: Int, y3: Int, z3: Int) {
            path.reset()
            path.moveTo(px(x0, z0), py(x0, y0, z0))
            path.lineTo(px(x1, z1), py(x1, y1, z1))
            path.lineTo(px(x2, z2), py(x2, y2, z2))
            path.lineTo(px(x3, z3), py(x3, y3, z3))
            path.close()
            paint.color = color
            canvas.drawPath(path, paint)
        }

        // Back to front: columns with a larger lx + lz are closer to the viewer.
        for (d in 0..30) {
            for (lx in maxOf(0, d - 15)..minOf(15, d)) {
                val lz = d - lx
                val h = surface.height(lx, lz)
                val t = h + 1
                val color = Color(surface.color(lx, lz))

                // Map-style relief: brighter when higher than the west/north neighbours.
                val west = if (lx > 0) surface.height(lx - 1, lz) else h
                val north = if (lz > 0) surface.height(lx, lz - 1) else h
                val relief = (1f + (2 * h - west - north) * 0.04f).coerceIn(0.85f, 1.15f)

                quad(shade(color, relief), lx, t, lz, lx + 1, t, lz, lx + 1, t, lz + 1, lx, t, lz + 1)

                val eastBottom = if (lx < 15) surface.height(lx + 1, lz) + 1 else base
                if (eastBottom < t) {
                    quad(shade(color, EAST_SHADE),
                        lx + 1, t, lz, lx + 1, t, lz + 1, lx + 1, eastBottom, lz + 1, lx + 1, eastBottom, lz)
                }
                val southBottom = if (lz < 15) surface.height(lx, lz + 1) + 1 else base
                if (southBottom < t) {
                    quad(shade(color, SOUTH_SHADE),
                        lx, t, lz + 1, lx + 1, t, lz + 1, lx + 1, southBottom, lz + 1, lx, southBottom, lz + 1)
                }

                if (settings.chunkBorders && (lx == 0 || lz == 0)) {
                    paint.color = BORDER_COLOR
                    if (lx == 0) canvas.drawLine(Offset(px(0, lz), py(0, t, lz)), Offset(px(0, lz + 1), py(0, t, lz + 1)), paint)
                    if (lz == 0) canvas.drawLine(Offset(px(lx, 0), py(lx, t, 0)), Offset(px(lx + 1, 0), py(lx + 1, t, 0)), paint)
                }
            }
        }

        val pixels = IntArray(width * height)
        image.readPixels(pixels)
        val mask = BitSet(pixels.size)
        for (i in pixels.indices) if (pixels[i] ushr 24 != 0) mask.set(i)

        return ChunkTile(
            lod = lod,
            image = image,
            minHeight = surface.minHeight,
            maxHeight = surface.maxHeight,
            mask = mask,
        )
    }

    private fun shade(c: Color, f: Float) = Color(
        red = (c.red * f).coerceIn(0f, 1f),
        green = (c.green * f).coerceIn(0f, 1f),
        blue = (c.blue * f).coerceIn(0f, 1f),
        alpha = c.alpha,
    )
}
