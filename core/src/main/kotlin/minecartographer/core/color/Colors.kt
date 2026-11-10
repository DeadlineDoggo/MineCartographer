package minecartographer.core.color

import kotlin.math.abs
import kotlin.math.roundToInt

/** The 16 colors of Minecraft's chat, which Xaero's Minimap uses for its waypoints. Colors are 0xRRGGBB. */
object MinecraftColors {
    val NAMES = listOf(
        "Black", "Dark blue", "Dark green", "Dark aqua", "Dark red", "Dark purple", "Gold", "Gray",
        "Dark gray", "Blue", "Green", "Aqua", "Red", "Light purple", "Yellow", "White",
    )
    val CHAT = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )

    /** Index in [CHAT] of [rgb], or null when it is not one of the 16. */
    fun indexOf(rgb: Int): Int? = CHAT.indexOf(rgb and 0xFFFFFF).takeIf { it >= 0 }

    /** Index of the color closest to [rgb]. */
    fun nearestIndex(rgb: Int): Int = CHAT.indices.minBy { distance(CHAT[it], rgb) }

    fun nearest(rgb: Int): Int = CHAT[nearestIndex(rgb)]

    private fun distance(a: Int, b: Int): Int {
        val r = (a shr 16 and 255) - (b shr 16 and 255)
        val g = (a shr 8 and 255) - (b shr 8 and 255)
        val bl = (a and 255) - (b and 255)
        return r * r + g * g + bl * bl
    }
}

/** A color as hue (0..360), saturation and value (0..1). */
data class Hsv(val h: Float, val s: Float, val v: Float) {
    /** The color as 0xRRGGBB. */
    fun toRgb(): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1 - abs((hue / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            hue < 60 -> Triple(c, x, 0f)
            hue < 120 -> Triple(x, c, 0f)
            hue < 180 -> Triple(0f, c, x)
            hue < 240 -> Triple(0f, x, c)
            hue < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    companion object {
        fun of(rgb: Int): Hsv {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val max = maxOf(r, g, b)
            val delta = max - minOf(r, g, b)
            val h = when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta + 6f) % 6f)
                max == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return Hsv(h, if (max == 0f) 0f else delta / max, max)
        }
    }
}

object HexColor {
    fun format(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

    /** `#RRGGBB`, `RRGGBB` or `#RGB`, any case; null when it is not a color. */
    fun parse(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (t.length) {
            6 -> t.toInt(16)
            3 -> t.fold(0) { acc, c -> (acc shl 8) or (c.digitToInt(16) * 17) }
            else -> null
        }
    }
}
