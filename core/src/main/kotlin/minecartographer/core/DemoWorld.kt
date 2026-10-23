package minecartographer.core

import kotlin.math.floor

/**
 * Procedurally generated sample world, shown at startup and used in tests.
 * Deterministic for a given [seed]. Can't be exported.
 */
class DemoWorld(
    override val name: String,
    private val seed: Long,
    radiusChunks: Int = 24,
) : WorldSource {
    override val id = "demo-$seed"
    override val versionLabel = "Demo"
    override val dataVersion: Int? = null
    override val minY = -64
    override val maxY = 319
    override val bounds = ChunkBounds(-radiusChunks, -radiusChunks, radiusChunks - 1, radiusChunks - 1)

    override fun hasChunk(pos: ChunkPos) = pos in bounds

    override fun loadSurface(pos: ChunkPos): ChunkSurface? {
        if (!hasChunk(pos)) return null
        val heights = IntArray(ChunkSurface.COLUMNS)
        val colors = IntArray(ChunkSurface.COLUMNS)
        for (lz in 0 until 16) for (lx in 0 until 16) {
            val bx = pos.x * 16 + lx
            val bz = pos.z * 16 + lz
            val terrain = terrainHeight(bx, bz)
            val i = lz * 16 + lx
            heights[i] = maxOf(terrain, SEA_LEVEL)
            colors[i] = colorFor(terrain, bx, bz)
        }
        return ChunkSurface(pos, heights, colors)
    }

    private fun terrainHeight(x: Int, z: Int): Int {
        val continent = fbm(x / 320.0, z / 320.0, 3)            // broad land/sea shape
        val hills = fbm(x / 72.0 + 100, z / 72.0 + 100, 4)       // local relief
        val mountain = fbm(x / 180.0 - 50, z / 180.0 - 50, 2)
        val mountainBoost = ((mountain - 0.55).coerceAtLeast(0.0) * 220)
        return (48 + continent * 34 + hills * 18 + mountainBoost).toInt()
    }

    private fun colorFor(terrain: Int, x: Int, z: Int): Int {
        val jitter = (hash(x, z, 7) * 14 - 7).toInt()
        return when {
            terrain < SEA_LEVEL -> {
                val depth = (SEA_LEVEL - terrain).coerceAtMost(20)
                rgb(52 - depth, 92 - depth * 2, 196 - depth * 3)
            }
            terrain <= SEA_LEVEL + 1 -> rgb(219 + jitter, 207 + jitter, 160 + jitter)
            terrain < 92 -> rgb(98 + jitter, 152 + jitter, 62 + jitter / 2)
            terrain < 108 -> rgb(86 + jitter, 122 + jitter, 64 + jitter)
            terrain < 126 -> rgb(128 + jitter, 128 + jitter, 128 + jitter)
            else -> rgb(242 + jitter / 2, 246 + jitter / 2, 250)
        }
    }

    /** Fractal value noise in roughly 0..1. */
    private fun fbm(x: Double, z: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 0.5
        var freq = 1.0
        var norm = 0.0
        repeat(octaves) { o ->
            sum += valueNoise(x * freq, z * freq, o) * amp
            norm += amp
            amp *= 0.5
            freq *= 2.0
        }
        return sum / norm
    }

    private fun valueNoise(x: Double, z: Double, octave: Int): Double {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fz = smooth(z - z0)
        val a = hash(x0, z0, octave)
        val b = hash(x0 + 1, z0, octave)
        val c = hash(x0, z0 + 1, octave)
        val d = hash(x0 + 1, z0 + 1, octave)
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz)
    }

    private fun hash(x: Int, z: Int, salt: Int): Double {
        var h = seed xor (x.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (z.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong()) xor salt.toLong()
        h = (h xor (h ushr 31)) * 0x94D049BB133111EBuL.toLong()
        h = h xor (h ushr 29)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private companion object {
        const val SEA_LEVEL = 62
    }
}
