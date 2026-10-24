package minecartographer.viewport

import minecartographer.core.ChunkPos
import minecartographer.core.ChunkSurface
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How chunks are drawn, from fastest to most detailed. */
enum class RenderMode(val label: String, val description: String) {
    Map("Map", "Top block of each column, sides filled down. Fastest."),
    Blocks("Blocks", "Every block that can be seen, as colored cubes."),
    Textured("Textured", "Minecraft's own block models and textures."),
}

data class RenderSettings(
    /** Vertical exaggeration: 1.0 draws blocks as cubes. */
    val heightScale: Float = 1f,
    val chunkBorders: Boolean = true,
    val mode: RenderMode = RenderMode.Map,
)

/**
 * A chunk pre-rendered as an isometric image at [lod] pixels per iso unit.
 *
 * The image does not depend on where the chunk sits, so the same tile can be drawn
 * at any position (that is how pasted chunks are shown); see [originU]/[originV].
 *
 * [image] is created once and drawn as is every frame, so the GPU keeps its texture.
 * It holds native memory: the tile cache [close]s it once the tile is evicted and no longer drawn.
 * At low LODs the [pixels] are kept too, for compositing tiles into blocks.
 */
class ChunkTile(
    val lod: Int,
    val image: Image,
    val minHeight: Int,
    val maxHeight: Int,
    private val mask: BitSet,
    /** Premultiplied ARGB, row by row; null when not kept. */
    val pixels: IntArray?,
) : AutoCloseable {
    val width: Int get() = image.width
    val height: Int get() = image.height
    val byteSize: Long get() = width.toLong() * height * (if (pixels != null) 8 else 4)

    /** Iso coordinates of the image's top-left corner when drawn at [pos]. */
    fun originU(pos: ChunkPos) = 16.0 * (pos.x - pos.z) - 16
    fun originV(pos: ChunkPos, hs: Double) = 8.0 * (pos.x + pos.z) - (maxHeight + 1) * hs

    /** True when the pixel at ([px], [py]) belongs to this chunk (pixel-exact picking). */
    fun hit(px: Int, py: Int) = px in 0 until width && py in 0 until height && mask[py * width + px]

    override fun close() = image.close()
}

/**
 * Draws a [ChunkSurface] as a block of columns: top faces plus visible east and south sides.
 * Rasterizes in software straight into an int array, which is much faster than going through
 * Skia paths for the ~800 small polygons of a chunk and allocates no native objects per polygon.
 */
object ChunkTileRenderer {
    /** How many blocks the chunk's sides extend below its lowest column. */
    private const val SKIRT = 3
    private const val EAST_SHADE = 0.80f
    private const val SOUTH_SHADE = 0.64f
    private const val BORDER_COLOR = 0x48000000

    fun render(surface: ChunkSurface, lod: Int, settings: RenderSettings): ChunkTile {
        val hs = settings.heightScale.toDouble()
        val top = surface.maxHeight + 1
        val base = surface.minHeight - SKIRT
        val width = 32 * lod
        val height = ceil((16 + (top - base) * hs) * lod).toInt() + 1
        val raster = Raster(width, height)

        // Chunk-local block coordinates -> image pixels.
        fun px(lx: Int, lz: Int) = ((lx - lz + 16) * lod).toFloat()
        fun py(lx: Int, y: Int, lz: Int) = (((lx + lz) / 2.0 - y * hs + top * hs) * lod).toFloat()

        fun quad(color: Int, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int,
                 x2: Int, y2: Int, z2: Int, x3: Int, y3: Int, z3: Int) {
            raster.fillQuad(
                px(x0, z0), py(x0, y0, z0), px(x1, z1), py(x1, y1, z1),
                px(x2, z2), py(x2, y2, z2), px(x3, z3), py(x3, y3, z3), color,
            )
        }

        // Back to front: columns with a larger lx + lz are closer to the viewer.
        for (d in 0..30) {
            for (lx in maxOf(0, d - 15)..minOf(15, d)) {
                val lz = d - lx
                val h = surface.height(lx, lz)
                val t = h + 1
                val color = surface.color(lx, lz)

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
                    if (lx == 0) raster.line(px(0, lz), py(0, t, lz), px(0, lz + 1), py(0, t, lz + 1), BORDER_COLOR)
                    if (lz == 0) raster.line(px(lx, 0), py(lx, t, 0), px(lx + 1, 0), py(lx + 1, t, 0), BORDER_COLOR)
                }
            }
        }

        val pixels = raster.pixels
        val mask = BitSet(pixels.size)
        for (i in pixels.indices) if (pixels[i] ushr 24 != 0) mask.set(i)

        return ChunkTile(
            lod = lod,
            image = imageOf(pixels, width, height),
            minHeight = surface.minHeight,
            maxHeight = surface.maxHeight,
            mask = mask,
            pixels = if (lod <= ChunkTileCache.MAX_BLOCK_LOD) pixels else null,
        )
    }

    private fun shade(argb: Int, f: Float): Int {
        fun ch(shift: Int) = (((argb shr shift) and 0xFF) * f).toInt().coerceIn(0, 255) shl shift
        return (argb and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    /** ARGB pixels, non-antialiased: a pixel is covered when its center is inside the shape. */
    private class Raster(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)
        private val xs = FloatArray(4)
        private val ys = FloatArray(4)

        fun fillQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
            xs[0] = x0; xs[1] = x1; xs[2] = x2; xs[3] = x3
            ys[0] = y0; ys[1] = y1; ys[2] = y2; ys[3] = y3
            val minY = min(min(y0, y1), min(y2, y3))
            val maxY = max(max(y0, y1), max(y2, y3))
            val firstRow = max(0, ceil(minY - 0.5f).toInt())
            val lastRow = min(height - 1, ceil(maxY - 0.5f).toInt() - 1)
            for (row in firstRow..lastRow) {
                val yc = row + 0.5f
                var left = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                for (i in 0 until 4) {
                    val j = (i + 1) and 3
                    val ya = ys[i]
                    val yb = ys[j]
                    if ((yc >= ya && yc < yb) || (yc >= yb && yc < ya)) {
                        val x = xs[i] + (yc - ya) * (xs[j] - xs[i]) / (yb - ya)
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
                if (left > right) continue
                val from = max(0, ceil(left - 0.5f).toInt())
                val to = min(width - 1, ceil(right - 0.5f).toInt() - 1)
                if (from <= to) pixels.fill(color, row * width + from, row * width + to + 1)
            }
        }

        /** One pixel wide line, blended over what is there. */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
            val steps = max(1, ceil(max(abs(x1 - x0), abs(y1 - y0))).toInt())
            for (s in 0 until steps) {
                val f = (s + 0.5f) / steps
                val x = (x0 + (x1 - x0) * f).toInt()
                val y = (y0 + (y1 - y0) * f).toInt()
                if (x in 0 until width && y in 0 until height) blend(y * width + x, color)
            }
        }

        private fun blend(i: Int, color: Int) {
            val a = color ushr 24
            val dst = pixels[i]
            fun ch(shift: Int) = ((((color shr shift) and 0xFF) * a + ((dst shr shift) and 0xFF) * (255 - a)) / 255) shl shift
            val outA = a + (dst ushr 24) * (255 - a) / 255
            pixels[i] = (outA shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/** An image from premultiplied ARGB ints (tile pixels are already premultiplied: fills are opaque, borders black). */
internal fun imageOf(pixels: IntArray, width: Int, height: Int): Image {
    val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(pixels)
    return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes.array(), width * 4)
}
