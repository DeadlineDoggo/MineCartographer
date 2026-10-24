package minecartographer.viewport

import minecartographer.core.BlockState
import minecartographer.core.ChunkBlocks
import minecartographer.core.Rotation
import minecartographer.textures.BlockModel
import minecartographer.textures.BlockModelSource
import minecartographer.textures.Connections
import minecartographer.textures.Dir
import minecartographer.textures.Quad
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Draws every visible block of a chunk (the Blocks and Textured modes), using [BlockModelSource] for
 * what each block looks like. Works front to back: a pixel is only filled while it is not yet opaque,
 * so hidden faces cost little and see-through texels (leaves, plants, rails) show what is behind them.
 *
 * The chunk comes already rotated for the view; [turns] rotates the block models to match.
 */
object BlockTileRenderer {
    private const val EAST_SHADE = 0.80f
    private const val SOUTH_SHADE = 0.64f
    private const val BOTTOM_SHADE = 0.5f
    private const val BORDER_ALPHA = 0x48

    fun render(blocks: ChunkBlocks, turns: Int, lod: Int, settings: RenderSettings, models: BlockModelSource): ChunkTile {
        val hs = settings.heightScale.toDouble()
        // One block of headroom: some models (fences, walls) reach a little above their block.
        val top = blocks.maxY + 2
        val base = blocks.minY
        val width = 32 * lod
        val height = ceil((16 + (top - base) * hs) * lod).toInt() + 1
        val raster = Raster(width, height)

        val palette = blocks.palette
        val blockModels = Array(palette.size) { if (it == 0) BlockModel.EMPTY else models.model(palette[it], turns) }
        val occludes = BooleanArray(palette.size) { blockModels[it].occludes }
        val fluid = BooleanArray(palette.size) { blockModels[it].fluid }
        // Old worlds leave out properties that depend on neighbors; those blocks get their model per position.
        val derived = BooleanArray(palette.size) { Connections.needed(palette[it]) || palette[it].properties["half"] == "upper" }

        /** The block at a world-space offset from (lx, y, lz); null outside this chunk. */
        fun neighbor(lx: Int, y: Int, lz: Int, dx: Int, dy: Int, dz: Int): BlockState? {
            val (vx, vz) = turnOffset(turns, dx, dz)
            val x = lx + vx
            val z = lz + vz
            if (x !in 0..15 || z !in 0..15) return null
            return blocks.state(x, y + dy, z)
        }
        // Faces nearest to the viewer first; for coplanar faces the later element (an overlay) wins.
        val order = Array(palette.size) { i -> frontToBack(blockModels[i].quads) }

        val projection = Projection(lod, hs, top)
        for (d in 30 downTo 0) {
            for (lx in maxOf(0, d - 15)..minOf(15, d)) {
                val lz = d - lx
                val depth = blocks.waterDepth[lz * 16 + lx].toInt()
                for (y in blocks.maxY downTo blocks.minY) {
                    val index = blocks.index(lx, y, lz)
                    if (index == 0) continue
                    var model = blockModels[index]
                    var quads = order[index]
                    if (derived[index]) {
                        var state = Connections.upperHalf(palette[index], blocks.state(lx, y - 1, lz))
                        if (Connections.needed(state)) {
                            state = Connections.derive(state, { dx, dy, dz -> neighbor(lx, y, lz, dx, dy, dz) }) { models.model(it, 0).occludes }
                        }
                        if (state !== palette[index]) {
                            model = models.model(state, turns)
                            quads = frontToBack(model.quads)
                        }
                    }
                    for (quad in quads) {
                        // Faces turned away from the camera (down, north, west) are never seen.
                        if (quad.nx + quad.ny / hs + quad.nz <= 1e-4) continue
                        if (quad.cull >= 0) {
                            val nx = lx + Dir.DX[quad.cull]
                            val ny = y + Dir.DY[quad.cull]
                            val nz = lz + Dir.DZ[quad.cull]
                            // Neighbors in other chunks are unknown; their tiles are drawn over this one anyway.
                            if (nx in 0..15 && nz in 0..15) {
                                val n = if (ny < blocks.minY) -1 else blocks.index(nx, ny, nz)
                                // Water is drawn opaque, so it hides faces against it as well as its own inner faces.
                                if (n == -1 || occludes[n] || fluid[n]) continue
                            }
                        }
                        val shade = shadeOf(quad) * (if (model.fluid) waterShade(depth) else 1f)
                        val border = settings.chunkBorders && quad.ny > 0.99f &&
                            ((lx == 0 && quad.corners[0] == 0f) || (lz == 0 && quad.corners[2] == 0f))
                        raster.quad(projection, lx, y, lz, quad, shade, if (border) borderEdges(lx, lz, quad) else 0, opaque = model.fluid)
                    }
                }
            }
        }

        val pixels = raster.pixels
        val mask = BitSet(pixels.size)
        for (i in pixels.indices) if (pixels[i] ushr 24 != 0) mask.set(i)
        return ChunkTile(
            lod = lod,
            image = imageOf(pixels, width, height),
            minHeight = blocks.minY + 1,
            maxHeight = top - 1,
            mask = mask,
            pixels = if (lod <= ChunkTileCache.MAX_BLOCK_LOD) pixels else null,
        )
    }

    /** A world-space horizontal offset as seen after [turns] quarter turns of the view. */
    private fun turnOffset(turns: Int, dx: Int, dz: Int): Pair<Int, Int> = when (Rotation.normalize(turns)) {
        0 -> dx to dz
        1 -> -dz to dx
        2 -> -dx to -dz
        else -> dz to -dx
    }

    /** Which edges of an up face lie on the chunk's west (1) and north (2) border. */
    private fun borderEdges(lx: Int, lz: Int, quad: Quad): Int =
        (if (lx == 0 && quad.corners[0] == 0f) 1 else 0) or (if (lz == 0 && quad.corners[2] == 0f) 2 else 0)

    private fun frontToBack(quads: List<Quad>): List<Quad> {
        if (quads.size < 2) return quads
        fun depth(q: Quad): Float {
            val c = q.corners
            // Center of the parallelogram (TL + TR + BL + BR) / 4 = (TR + BL) / 2.
            return (c[3] + c[6] + c[4] + c[7] + c[5] + c[8]) / 2
        }
        return quads.withIndex().sortedWith(compareByDescending<IndexedValue<Quad>> { depth(it.value) }.thenByDescending { it.index }).map { it.value }
    }

    /** Light from above and the viewer's left, like the Map mode: up 1, east 0.8, south 0.64, down 0.5. */
    private fun shadeOf(q: Quad): Float {
        if (!q.shade) return 1f
        val up = if (q.ny > 0) q.ny * q.ny else q.ny * q.ny * BOTTOM_SHADE
        return (up + q.nx * q.nx * EAST_SHADE + q.nz * q.nz * SOUTH_SHADE).coerceAtMost(1f)
    }

    /** Deeper water is darker, matching the Map mode's water colors. */
    private fun waterShade(depth: Int): Float = 1f - 0.55f * ((depth.coerceIn(1, 16) - 1) / 15f)

    /** Block coordinates (chunk-local, in view space) to image pixels. */
    private class Projection(val lod: Int, val hs: Double, val top: Int) {
        fun x(x: Float, z: Float) = (x - z + 16) * lod
        fun y(x: Float, y: Float, z: Float) = (((x + z) / 2.0 - y * hs + top * hs) * lod).toFloat()
    }

    /** Premultiplied ARGB, composited "under" what is already there (front-to-back drawing). */
    private class Raster(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)

        /** [opaque] draws see-through texels solid (water: what lies under it is not loaded). */
        fun quad(p: Projection, bx: Int, by: Int, bz: Int, q: Quad, shade: Float, border: Int, opaque: Boolean) {
            val c = q.corners
            // Screen positions of the corners: A = top-left, B = top-right, C = bottom-left of the texture.
            val ax = p.x(bx + c[0], bz + c[2]); val ay = p.y(bx + c[0], by + c[1], bz + c[2])
            val bxs = p.x(bx + c[3], bz + c[5]); val bys = p.y(bx + c[3], by + c[4], bz + c[5])
            val cx = p.x(bx + c[6], bz + c[8]); val cy = p.y(bx + c[6], by + c[7], bz + c[8])
            val ux = bxs - ax; val uy = bys - ay
            val vx = cx - ax; val vy = cy - ay
            val det = ux * vy - uy * vx
            if (det > -1e-6f && det < 1e-6f) return // seen edge-on
            val dx = ux + vx; val dy = uy + vy // A + u + v is the fourth corner
            val minX = max(0, floor(min(min(ax, ax + ux), min(ax + vx, ax + dx))).toInt())
            val maxX = min(width - 1, ceil(max(max(ax, ax + ux), max(ax + vx, ax + dx))).toInt())
            val minY = max(0, floor(min(min(ay, ay + uy), min(ay + vy, ay + dy))).toInt())
            val maxY = min(height - 1, ceil(max(max(ay, ay + uy), max(ay + vy, ay + dy))).toInt())
            if (minX > maxX || minY > maxY) return

            val tex = q.texture
            val uv = q.uvs
            // Mip level from how many texels fall on one pixel along the longer face edge.
            val level = if (tex == null) 0 else {
                val texelsU = kotlin.math.abs(uv[2] - uv[0]) * tex.size / 16
                val texelsV = kotlin.math.abs(uv[5] - uv[1]) * tex.size / 16
                val ratio = max(texelsU / max(hypot(ux, uy), 1e-3f), texelsV / max(hypot(vx, vy), 1e-3f))
                if (ratio <= 1f) 0 else floor(log2(ratio)).toInt()
            }
            val tint = q.tint
            val flat = if (tex == null) scale(q.color, shade, -1) else 0
            // Border lines: one pixel along the s = 0 (west) or t = 0 (north) edge.
            val sEdge = if (border and 1 != 0) 1f / max(hypot(ux, uy), 1f) else -1f
            val tEdge = if (border and 2 != 0) 1f / max(hypot(vx, vy), 1f) else -1f

            for (py in minY..maxY) {
                val ry = py + 0.5f - ay
                val row = py * width
                for (px in minX..maxX) {
                    val i = row + px
                    if (pixels[i] ushr 24 == 255) continue
                    val rx = px + 0.5f - ax
                    val s = (rx * vy - ry * vx) / det
                    if (s < 0f || s >= 1f) continue
                    val t = (ux * ry - uy * rx) / det
                    if (t < 0f || t >= 1f) continue
                    var color = if (tex == null) flat else {
                        val u = uv[0] + s * (uv[2] - uv[0]) + t * (uv[4] - uv[0])
                        val v = uv[1] + s * (uv[3] - uv[1]) + t * (uv[5] - uv[1])
                        var texel = tex.sample(u, v, level)
                        if (texel ushr 24 == 0) continue
                        if (opaque) texel = unpremultiply(texel)
                        scale(texel, shade, tint)
                    }
                    if (s < sEdge || t < tEdge) color = darken(color)
                    under(i, color)
                }
            }
        }

        private fun under(i: Int, src: Int) {
            val dst = pixels[i]
            val da = dst ushr 24
            if (da == 0) {
                pixels[i] = src
                return
            }
            val keep = 255 - da
            fun ch(shift: Int) = min(255, ((dst ushr shift) and 0xFF) + ((src ushr shift) and 0xFF) * keep / 255) shl shift
            pixels[i] = ch(24) or ch(16) or ch(8) or ch(0)
        }

        /** Multiplies a premultiplied color by [shade] and, unless -1, the RGB [tint]. */
        private fun scale(c: Int, shade: Float, tint: Int): Int {
            var r = ((c shr 16) and 0xFF) * shade
            var g = ((c shr 8) and 0xFF) * shade
            var b = (c and 0xFF) * shade
            if (tint != -1) {
                r = r * ((tint shr 16) and 0xFF) / 255
                g = g * ((tint shr 8) and 0xFF) / 255
                b = b * (tint and 0xFF) / 255
            }
            return (c and 0xFF000000.toInt()) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }

        private fun unpremultiply(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            fun ch(shift: Int) = min(255, ((c shr shift) and 0xFF) * 255 / a) shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        private fun darken(c: Int): Int {
            val f = 255 - BORDER_ALPHA
            fun ch(shift: Int) = (((c shr shift) and 0xFF) * f / 255) shl shift
            return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
        }
    }
}
