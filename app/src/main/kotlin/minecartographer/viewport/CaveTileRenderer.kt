package minecartographer.viewport

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import minecartographer.textures.BlockModelSource
import minecartographer.textures.Dir
import minecartographer.textures.Quad
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Cave view: the ground is see-through. A ray goes from the viewer through the chunk and passes everything
 * solid without drawing it; once it comes into a cave (air that has ground above it) it keeps going through the
 * air until it meets a block, and that block's face, the one turned toward the viewer from inside the cave, is what
 * the pixel shows. So a cave is seen as from within: its floor and its far walls, however deep it lies.
 *
 * The faces have the colors of the blocks, or with block models their textures (the Textured mode).
 *
 * Where no ray finds a cave the pixel is left empty. With [RenderSettings.caveSurface] the ground is drawn as a faint,
 * see-through trace there, so the caves stay the picture and the surface only shows where they are not.
 * A chunk is traced on its own: a cave that runs on into the next chunk is drawn from the part in each.
 */
object CaveTileRenderer {
    private const val EAST_SHADE = 0.80f
    private const val SOUTH_SHADE = 0.64f

    /** How many blocks below the surface a cave is at its darkest. */
    private const val FULL_DEPTH = 64f
    private const val DARKEST = 0.45f

    /** The surface, when shown, is a faint see-through trace of the ground: this transparent, and this dark. */
    private const val SURFACE_ALPHA = 56
    private const val SURFACE_SHADE = 0.55f

    private const val WATER_TINT = 0xFF3A68C8.toInt()
    private const val WATER_AMOUNT = 0.4f

    /** What a block is to a ray. */
    private class Kinds(val palette: Array<BlockState>, private val models: BlockModelSource?, private val turns: Int) {
        val solid = BooleanArray(palette.size)
        val water = BooleanArray(palette.size)
        /** Solid blocks that make no ceiling: trees. A cave is air under the ground, not under a canopy. */
        val growth = BooleanArray(palette.size)
        /** Light of their own: not darkened with the depth. */
        val glowing = BooleanArray(palette.size)
        val color = IntArray(palette.size)

        val textured = models != null
        private val faces = arrayOfNulls<Array<Quad?>>(palette.size)

        /** The texture quad of block [index] that a ray meets going in at its [axis] (0 x, 1 y, 2 z) side; null without models or such a face. */
        fun face(index: Int, axis: Int): Quad? {
            val models = models ?: return null
            val all = faces[index] ?: run {
                val quads = models.model(palette[index], turns).quads
                Array<Quad?>(3) { a ->
                    val dir = when (a) { 0 -> Dir.EAST; 1 -> Dir.UP; else -> Dir.SOUTH }
                    quads.lastOrNull { it.cull == dir } ?: quads.lastOrNull { q ->
                        q.nx * Dir.DX[dir] + q.ny * Dir.DY[dir] + q.nz * Dir.DZ[dir] > 0.99f
                    }
                }.also { faces[index] = it }
            }
            return all[axis]
        }

        init {
            for ((i, state) in palette.withIndex()) {
                if (i == 0 || state.isAir) continue
                val look = BlockColors.look(state)
                solid[i] = look.kind == BlockKind.SOLID
                water[i] = look.kind == BlockKind.WATER
                color[i] = look.color or (0xFF shl 24)
                val n = state.path
                growth[i] = solid[i] && (n.endsWith("leaves") || n.endsWith("log") || n.endsWith("wood") || n.endsWith("stem") || n == "mushroom_block" || n.endsWith("_mushroom_block"))
                glowing[i] = n.contains("lava") || n.contains("magma") || n.contains("glowstone") || n.contains("shroomlight") ||
                    n.contains("sea_lantern") || n.contains("froglight")
            }
        }
    }

    /**
     * The highest block of every column that is ground, indexed `lz * 16 + lx`; [ChunkBlocks.minY] - 1 for none.
     * Air below it has ground above, which is what makes it a cave.
     */
    private fun roofs(blocks: ChunkBlocks, kinds: Kinds): IntArray {
        val roof = IntArray(256) { blocks.minY - 1 }
        for (lz in 0..15) for (lx in 0..15) {
            var y = blocks.maxY
            while (y >= blocks.minY) {
                val i = blocks.index(lx, y, lz)
                if (kinds.solid[i] && !kinds.growth[i]) {
                    roof[lz * 16 + lx] = y
                    break
                }
                y--
            }
        }
        return roof
    }

    /** [models] give the faces their textures; without them they are flat colors. [turns] is how the view is turned, as the models need it. */
    fun render(blocks: ChunkBlocks, lod: Int, settings: RenderSettings, models: BlockModelSource? = null, turns: Int = 0): ChunkTile {
        val kinds = Kinds(blocks.palette, models, turns)
        val roof = roofs(blocks, kinds)
        return if (settings.flat) renderFlat(blocks, kinds, roof, lod, settings.caveSurface)
        else renderIso(blocks, kinds, roof, lod, settings.heightScale.toDouble(), settings.caveSurface)
    }

    /** What a ray has found of one pixel's column; the color is 0 while it has found nothing. */
    private class Trace {
        var color = 0
        var surface = 0

        fun clear() { color = 0; surface = 0 }
    }

    /** The color of the face a ray found at height [y] of the column with the roof at [roofY]: lit by its direction, darker the deeper. */
    private fun lit(kinds: Kinds, index: Int, axis: Int, y: Int, roofY: Int, wet: Boolean, lx: Float = 0.5f, ly: Float = 0.5f, lz: Float = 0.5f): Int {
        var f = when (axis) {
            0 -> EAST_SHADE
            2 -> SOUTH_SHADE
            else -> 1f
        }
        if (!kinds.glowing[index]) f *= 1f - (1f - DARKEST) * ((roofY - y).coerceIn(0, FULL_DEPTH.toInt()) / FULL_DEPTH)
        else f = 1f
        val base = texel(kinds, index, axis, lx, ly, lz)
        var r = ((base shr 16) and 0xFF) * f
        var g = ((base shr 8) and 0xFF) * f
        var b = (base and 0xFF) * f
        if (wet) {
            r += (((WATER_TINT shr 16) and 0xFF) - r) * WATER_AMOUNT
            g += (((WATER_TINT shr 8) and 0xFF) - g) * WATER_AMOUNT
            b += ((WATER_TINT and 0xFF) - b) * WATER_AMOUNT
        }
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /**
     * The color of the face of block [index] that a ray enters by, at ([lx], [ly], [lz]) in the block (0..1): the
     * texture there, or the block's flat color.
     */
    private fun texel(kinds: Kinds, index: Int, axis: Int, lx: Float, ly: Float, lz: Float): Int {
        val q = kinds.face(index, axis) ?: return kinds.color[index]
        val tex = q.texture ?: return kinds.color[index]
        val c = q.corners
        val p = floatArrayOf(lx, ly, lz)
        // The two axes in the plane of the face.
        val i = if (axis == 0) 1 else 0
        val j = if (axis == 2) 1 else 2
        val ux = c[3 + i] - c[i]; val uy = c[3 + j] - c[j]
        val vx = c[6 + i] - c[i]; val vy = c[6 + j] - c[j]
        val det = ux * vy - uy * vx
        if (det > -1e-6f && det < 1e-6f) return kinds.color[index]
        val rx = p[i] - c[i]
        val ry = p[j] - c[j]
        val s = ((rx * vy - ry * vx) / det).coerceIn(0f, 0.999f)
        val t = ((ux * ry - uy * rx) / det).coerceIn(0f, 0.999f)
        val uv = q.uvs
        val level = (31 - Integer.numberOfLeadingZeros(maxOf(1, tex.size / 16))).coerceAtLeast(0)
        var texel = tex.sample(uv[0] + s * (uv[2] - uv[0]) + t * (uv[4] - uv[0]), uv[1] + s * (uv[3] - uv[1]) + t * (uv[5] - uv[1]), level)
        val a = texel ushr 24
        if (a == 0) return kinds.color[index]
        if (a != 255) {
            fun ch(shift: Int) = minOf(255, ((texel shr shift) and 0xFF) * 255 / a) shl shift
            texel = (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
        if (q.tint != -1) {
            val r = ((texel shr 16) and 0xFF) * ((q.tint shr 16) and 0xFF) / 255
            val g = ((texel shr 8) and 0xFF) * ((q.tint shr 8) and 0xFF) / 255
            val b = (texel and 0xFF) * (q.tint and 0xFF) / 255
            texel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return texel
    }

    /** The surface as the first block a ray meets shows it, lit by its direction like the Blocks mode; water over it tints it. */
    private fun surfaceFace(kinds: Kinds, index: Int, axis: Int, wet: Boolean): Int = lit(kinds, index, axis, 0, 0, wet)

    /** What a pixel shows: a cave as it is, else the faint trace of the surface (premultiplied, so tiles in front and behind show through). */
    private fun mix(cave: Int, surface: Int): Int {
        if (cave != 0 || surface == 0) return cave
        fun ch(shift: Int) = (((surface shr shift) and 0xFF) * SURFACE_SHADE * SURFACE_ALPHA / 255f).toInt() shl shift
        return (SURFACE_ALPHA shl 24) or ch(16) or ch(8) or ch(0)
    }

    /**
     * The isometric view. Pixel (X, Y) in blocks is on the line where `lx - lz = X - 16` and
     * `(lx + lz) / 2 - y * hs + top * hs = Y`. With u = lx + lz, which grows toward the viewer, the line is
     * lx = (u + s) / 2, lz = (u - s) / 2, y = (u / 2 - Y + top * hs) / hs: a straight line, walked here
     * from the viewer's end block by block.
     */
    private fun renderIso(blocks: ChunkBlocks, kinds: Kinds, roof: IntArray, lod: Int, hs: Double, surface: Boolean): ChunkTile {
        val top = blocks.maxY + 1
        val base = blocks.minY
        val width = 32 * lod
        val height = ceil((16 + (top - base) * hs) * lod).toInt() + 1
        val pixels = IntArray(width * height)
        val hsf = hs.toFloat()
        val span = ((top - base) * hs).toFloat()

        for (py in 0 until height) {
            val sy = (py + 0.5f) / lod
            for (px in 0 until width) {
                val s = (px + 0.5f) / lod - 16f
                val reach = abs(s)
                val uHigh = min(32f - reach, 2f * sy)
                val uLow = max(reach, 2f * (sy - span))
                if (uHigh - uLow < 0.01f) continue

                // The start, just inside the chunk on the viewer's side.
                val u = uHigh - 0.002f
                val fx = (u + s) / 2f
                val fz = (u - s) / 2f
                val fy = (u / 2f - sy + top * hsf) / hsf
                var ix = floor(fx).toInt().coerceIn(0, 15)
                var iz = floor(fz).toInt().coerceIn(0, 15)
                var iy = floor(fy).toInt().coerceIn(base, top - 1)
                // How far the walk goes (in u) before the next block along each axis, and between two of them.
                var tx = 2f * (fx - ix)
                var tz = 2f * (fz - iz)
                var ty = 2f * hsf * (fy - iy)
                val dx = 2f
                val dz = 2f
                val dy = 2f * hsf
                val length = u - uLow
                var axis = 1
                var crossed = 0f
                var inCave = false
                var wet = false
                var surfaceColor = 0
                var skyWater = false
                var color = 0
                while (true) {
                    val index = blocks.index(ix, iy, iz)
                    if (!inCave) {
                        if (kinds.solid[index]) {
                            if (surface && surfaceColor == 0) surfaceColor = surfaceFace(kinds, index, axis, skyWater)
                        } else {
                            if (kinds.water[index] && surfaceColor == 0) skyWater = true
                            // Water is no cave: lakes, rivers and seas under ice or an overhang are not what is looked for.
                            if (iy < roof[iz * 16 + ix] && !kinds.water[index]) inCave = true
                        }
                    } else if (kinds.solid[index]) {
                        // Where the ray went in, in the block: the face it entered by is the block's far side (1).
                        val uc = u - crossed
                        val lx = if (axis == 0) 1f else ((uc + s) / 2f - ix).coerceIn(0f, 1f)
                        val lz = if (axis == 2) 1f else ((uc - s) / 2f - iz).coerceIn(0f, 1f)
                        val ly = if (axis == 1) 1f else ((uc / 2f - sy + top * hsf) / hsf - iy).coerceIn(0f, 1f)
                        color = lit(kinds, index, axis, iy, roof[iz * 16 + ix], wet, lx, ly, lz)
                        break
                    } else if (kinds.water[index]) {
                        wet = true
                    }
                    // On to the next block: the one whose border the line crosses first.
                    if (tx <= ty && tx <= tz) {
                        if (tx > length) break
                        crossed = tx; tx += dx; ix--; axis = 0
                        if (ix < 0) break
                    } else if (tz <= ty) {
                        if (tz > length) break
                        crossed = tz; tz += dz; iz--; axis = 2
                        if (iz < 0) break
                    } else {
                        if (ty > length) break
                        crossed = ty; ty += dy; iy--; axis = 1
                        if (iy < base) break
                    }
                }
                pixels[py * width + px] = mix(color, surfaceColor)
            }
        }
        return tile(blocks, pixels, width, height, lod, top, flat = false)
    }

    /** The 2D view: straight down every column, past the ground, to the floor of the first cave. */
    private fun renderFlat(blocks: ChunkBlocks, kinds: Kinds, roof: IntArray, lod: Int, surface: Boolean): ChunkTile {
        val size = 32 * lod
        val cell = 2 * lod
        val pixels = IntArray(size * size)
        val trace = Trace()
        for (lz in 0..15) for (lx in 0..15) {
            trace.clear()
            var hit = -1
            var hitY = 0
            var hitWet = false
            val roofY = roof[lz * 16 + lx]
            var inCave = false
            var wet = false
            var skyWater = false
            var y = blocks.maxY
            while (y >= blocks.minY) {
                val index = blocks.index(lx, y, lz)
                if (!inCave) {
                    if (kinds.solid[index]) {
                        if (surface && trace.surface == 0) trace.surface = surfaceFace(kinds, index, 1, skyWater)
                    } else {
                        if (kinds.water[index] && trace.surface == 0) skyWater = true
                        if (y < roofY && !kinds.water[index]) inCave = true
                    }
                } else if (kinds.solid[index]) {
                    hit = index
                    hitY = y
                    hitWet = wet
                    break
                } else if (kinds.water[index]) {
                    wet = true
                }
                y--
            }
            if (hit >= 0 && kinds.textured) {
                // The texture of the floor, a texel per pixel.
                for (j in 0 until cell) for (i in 0 until cell) {
                    pixels[(lz * cell + j) * size + lx * cell + i] = lit(kinds, hit, 1, hitY, roofY, hitWet, (i + 0.5f) / cell, 1f, (j + 0.5f) / cell)
                }
                continue
            }
            val pixel = if (hit >= 0) lit(kinds, hit, 1, hitY, roofY, hitWet) else mix(0, trace.surface)
            if (pixel == 0) continue
            for (row in lz * cell until (lz + 1) * cell) java.util.Arrays.fill(pixels, row * size + lx * cell, row * size + (lx + 1) * cell, pixel)
        }
        return tile(blocks, pixels, size, size, lod, blocks.maxY + 1, flat = true)
    }

    private fun tile(blocks: ChunkBlocks, pixels: IntArray, width: Int, height: Int, lod: Int, top: Int, flat: Boolean): ChunkTile {
        val mask = BitSet(pixels.size)
        for (i in pixels.indices) if (pixels[i] ushr 24 != 0) mask.set(i)
        return ChunkTile(
            lod = lod,
            image = imageOf(pixels, width, height),
            minHeight = blocks.minY,
            maxHeight = top - 1,
            mask = mask,
            pixels = if (lod <= ChunkTileCache.MAX_BLOCK_LOD) pixels else null,
            flat = flat,
        )
    }
}
