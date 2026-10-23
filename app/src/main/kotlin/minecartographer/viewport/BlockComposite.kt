package minecartographer.viewport

import minecartographer.core.ChunkPos
import org.jetbrains.skia.Image
import kotlin.math.roundToInt

/** One chunk of a block: its tile, where it sits (view space) and how it is tinted (ARGB, 0 for none). */
data class BlockPart(val tile: ChunkTile, val viewPos: ChunkPos, val tint: Int, val alpha: Float)

/**
 * Several chunk tiles merged into one image, so a zoomed-out view is a few hundred draws instead of
 * tens of thousands (the GPU cost is per draw, not per pixel). [originU]/[originV] place the image
 * like [ChunkTile.originU]/[ChunkTile.originV]; [lod] is its pixels per iso unit.
 */
class BlockComposite(val image: Image, val originU: Double, val originV: Double, val lod: Int) : AutoCloseable {
    val byteSize: Long get() = image.width.toLong() * image.height * 4

    override fun close() = image.close()

    companion object {
        /** Draws [parts] (back to front, all at one LOD and with pixels kept) into a new image. */
        fun of(parts: List<BlockPart>, hs: Double): BlockComposite {
            val lod = parts.first().tile.lod
            val originU = parts.minOf { it.tile.originU(it.viewPos) }
            val originV = parts.minOf { it.tile.originV(it.viewPos, hs) }
            val offsets = IntArray(parts.size * 2)
            var width = 0
            var height = 0
            for ((i, part) in parts.withIndex()) {
                val x = ((part.tile.originU(part.viewPos) - originU) * lod).roundToInt()
                val y = ((part.tile.originV(part.viewPos, hs) - originV) * lod).roundToInt()
                offsets[2 * i] = x
                offsets[2 * i + 1] = y
                width = maxOf(width, x + part.tile.width)
                height = maxOf(height, y + part.tile.height)
            }
            val pixels = IntArray(width * height)
            for ((i, part) in parts.withIndex()) blit(part, pixels, width, offsets[2 * i], offsets[2 * i + 1])
            return BlockComposite(imageOf(pixels, width, height), originU, originV, lod)
        }

        /** Source-over of the tile's premultiplied pixels, tinted (source-atop) and faded like a single tile draw. */
        private fun blit(part: BlockPart, dst: IntArray, dstWidth: Int, x0: Int, y0: Int) {
            val tile = part.tile
            val src = tile.pixels ?: return
            val w = tile.width
            val ta = part.tint ushr 24
            val tr = (part.tint shr 16) and 0xFF
            val tg = (part.tint shr 8) and 0xFF
            val tb = part.tint and 0xFF
            val fade = (part.alpha * 255).roundToInt()
            val plain = ta == 0 && fade == 255
            for (y in 0 until tile.height) {
                val row = (y0 + y) * dstWidth + x0
                for (x in 0 until w) {
                    val s = src[y * w + x]
                    var a = s ushr 24
                    if (a == 0) continue
                    if (plain && a == 255) {
                        dst[row + x] = s
                        continue
                    }
                    var r = (s shr 16) and 0xFF
                    var g = (s shr 8) and 0xFF
                    var b = s and 0xFF
                    if (ta != 0) {
                        r = (tr * ta / 255 * a / 255) + r * (255 - ta) / 255
                        g = (tg * ta / 255 * a / 255) + g * (255 - ta) / 255
                        b = (tb * ta / 255 * a / 255) + b * (255 - ta) / 255
                    }
                    if (fade != 255) {
                        a = a * fade / 255
                        r = r * fade / 255
                        g = g * fade / 255
                        b = b * fade / 255
                    }
                    val d = dst[row + x]
                    val keep = 255 - a
                    dst[row + x] = ((a + (d ushr 24) * keep / 255) shl 24) or
                        ((r + ((d shr 16) and 0xFF) * keep / 255) shl 16) or
                        ((g + ((d shr 8) and 0xFF) * keep / 255) shl 8) or
                        (b + (d and 0xFF) * keep / 255)
                }
            }
        }
    }
}
