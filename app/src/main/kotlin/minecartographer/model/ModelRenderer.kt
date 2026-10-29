package minecartographer.model

import minecartographer.textures.Dir
import minecartographer.textures.ModelBuilder
import minecartographer.textures.Quad
import minecartographer.textures.Texture
import minecartographer.textures.Tints
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Orthographic camera about the middle of the block. [zoom] is pixels per model unit. */
data class ModelCamera(val yaw: Float = 35f, val pitch: Float = 28f, val zoom: Float = 18f, val panX: Float = 0f, val panY: Float = 0f) {
    private val cy get() = cos(Math.toRadians(yaw.toDouble())).toFloat()
    private val sy get() = sin(Math.toRadians(yaw.toDouble())).toFloat()
    private val cp get() = cos(Math.toRadians(pitch.toDouble())).toFloat()
    private val sp get() = sin(Math.toRadians(pitch.toDouble())).toFloat()

    /** A model point in view space: x right, y up, z toward the viewer. */
    fun view(x: Float, y: Float, z: Float): FloatArray {
        val px = x - 8f
        val py = y - 8f
        val pz = z - 8f
        val x1 = px * cy + pz * sy
        val z1 = -px * sy + pz * cy
        val y2 = py * cp - z1 * sp
        val z2 = py * sp + z1 * cp
        return floatArrayOf(x1, y2, z2)
    }

    /** Screen position (pixels) and depth (larger is nearer) of a model point in a viewport of [width] x [height]. */
    fun project(x: Float, y: Float, z: Float, width: Int, height: Int): FloatArray {
        val v = view(x, y, z)
        return floatArrayOf(width / 2f + panX + v[0] * zoom, height / 2f + panY - v[1] * zoom, v[2])
    }

    /** The direction a model axis points on screen, as a unit vector (x right, y down), and how long a unit looks in pixels. */
    fun axisOnScreen(axis: Int): Pair<FloatArray, Float> {
        val o = project(8f, 8f, 8f, 0, 0)
        val p = project(if (axis == 0) 9f else 8f, if (axis == 1) 9f else 8f, if (axis == 2) 9f else 8f, 0, 0)
        val dx = p[0] - o[0]
        val dy = p[1] - o[1]
        val len = hypot(dx, dy)
        return (if (len < 1e-4f) floatArrayOf(0f, 0f) else floatArrayOf(dx / len, dy / len)) to len
    }

    /** Whether a face looking along ([nx], [ny], [nz]) turns toward the viewer. */
    fun facesViewer(nx: Float, ny: Float, nz: Float): Boolean {
        val a = view(8f + nx, 8f + ny, 8f + nz)
        val o = view(8f, 8f, 8f)
        return a[2] - o[2] > 1e-4f
    }

    companion object {
        val ISO = ModelCamera()
        val FRONT = ModelCamera(yaw = 0f, pitch = 0f)
        val SIDE = ModelCamera(yaw = 90f, pitch = 0f)
        val TOP = ModelCamera(yaw = 0f, pitch = 90f)
    }
}

/** A quad with whom it belongs to, for picking. */
class ModelQuad(val element: Int, val dir: Int, val quad: Quad)

/** What the viewport shows: the pixels, and per pixel the element and face (`id * 8 + dir + 1`, 0 for none) for picking. */
class ModelFrame(val width: Int, val height: Int, val pixels: IntArray, val ids: IntArray) {
    /** The element and face under a pixel, or null. */
    fun pick(x: Int, y: Int): Pair<Int, Int>? {
        if (x !in 0 until width || y !in 0 until height) return null
        val v = ids[y * width + x] - 1
        return if (v < 0) null else (v shr 3) to (v and 7)
    }
}

/** A small software renderer for the model viewport: the models have a few dozen faces, so a z-buffer is plenty. */
object ModelRenderer {
    /** The quads of [doc]'s visible elements; [texture] turns a texture id into pixels (null: a flat gray face). */
    fun quads(doc: ModelDoc, hidden: Set<Int>, texture: (String) -> Texture?): List<ModelQuad> = buildList {
        for (element in doc.elements) {
            if (element.id in hidden) continue
            val json = element.toJson { it?.let(doc::resolve) }
            val faceDirs = (0 until 6).filter { it in element.faces }
            val built = ModelBuilder.element(json, 0, 0, Tints.GRASS, texture).first
            // The builder walks the faces in the order of the JSON, which is direction order.
            for ((i, quad) in built.withIndex()) add(ModelQuad(element.id, faceDirs[i], quad))
        }
    }

    /** The edges of an element's box, rotation included, as pairs of model points (each edge shows up twice). */
    fun boxEdges(element: ModelElement): List<Pair<FloatArray, FloatArray>> {
        val full = element.copy(faces = (0 until 6).associateWith { ModelFace() })
        val quads = ModelBuilder.element(full.toJson(), 0, 0, -1) { null }.first
        return quads.flatMap { q ->
            val c = q.corners
            val a = floatArrayOf(c[0] * 16, c[1] * 16, c[2] * 16)
            val b = floatArrayOf(c[3] * 16, c[4] * 16, c[5] * 16)
            val d = floatArrayOf(c[6] * 16, c[7] * 16, c[8] * 16)
            val e = floatArrayOf(b[0] + d[0] - a[0], b[1] + d[1] - a[1], b[2] + d[2] - a[2])
            listOf(a to b, b to e, e to d, d to a)
        }
    }

    /** The middle of each face of an element's box, rotation included, by direction. */
    fun faceCenters(element: ModelElement): List<FloatArray> {
        val full = element.copy(faces = (0 until 6).associateWith { ModelFace() })
        return ModelBuilder.element(full.toJson(), 0, 0, -1) { null }.first.map { q ->
            val c = q.corners
            floatArrayOf((c[3] + c[6]) * 8, (c[4] + c[7]) * 8, (c[5] + c[8]) * 8)
        }
    }

    fun render(quads: List<ModelQuad>, camera: ModelCamera, width: Int, height: Int): ModelFrame {
        // Transparent where nothing is drawn: the grid and the block outline lie under it.
        val pixels = IntArray(width * height)
        val ids = IntArray(width * height)
        val depth = FloatArray(width * height) { Float.NEGATIVE_INFINITY }
        for (q in quads) rasterize(q, camera, width, height, pixels, ids, depth)
        return ModelFrame(width, height, pixels, ids)
    }

    private fun shadeOf(q: Quad): Float {
        if (!q.shade) return 1f
        val up = if (q.ny > 0) q.ny * q.ny else q.ny * q.ny * 0.5f
        return (up + q.nx * q.nx * 0.6f + q.nz * q.nz * 0.8f).coerceAtMost(1f)
    }

    private fun rasterize(mq: ModelQuad, camera: ModelCamera, width: Int, height: Int, pixels: IntArray, ids: IntArray, depth: FloatArray) {
        val q = mq.quad
        if (!camera.facesViewer(q.nx, q.ny, q.nz)) return
        val c = q.corners
        val a = camera.project(c[0] * 16, c[1] * 16, c[2] * 16, width, height)
        val b = camera.project(c[3] * 16, c[4] * 16, c[5] * 16, width, height)
        val d = camera.project(c[6] * 16, c[7] * 16, c[8] * 16, width, height)
        val ux = b[0] - a[0]; val uy = b[1] - a[1]
        val vx = d[0] - a[0]; val vy = d[1] - a[1]
        val det = ux * vy - uy * vx
        if (abs(det) < 1e-6f) return
        val minX = max(0, floor(min(min(a[0], b[0]), min(d[0], b[0] + vx))).toInt())
        val maxX = min(width - 1, ceil(max(max(a[0], b[0]), max(d[0], b[0] + vx))).toInt())
        val minY = max(0, floor(min(min(a[1], b[1]), min(d[1], b[1] + vy))).toInt())
        val maxY = min(height - 1, ceil(max(max(a[1], b[1]), max(d[1], b[1] + vy))).toInt())
        if (minX > maxX || minY > maxY) return

        val tex = q.texture
        val uv = q.uvs
        val level = if (tex == null) 0 else {
            val texelsU = abs(uv[2] - uv[0]) * tex.size / 16
            val texelsV = abs(uv[5] - uv[1]) * tex.size / 16
            val ratio = max(texelsU / max(hypot(ux, uy), 1e-3f), texelsV / max(hypot(vx, vy), 1e-3f))
            if (ratio <= 1f) 0 else floor(log2(ratio)).toInt()
        }
        val shade = shadeOf(q)
        val flat = q.color
        val id = mq.element * 8 + mq.dir + 1
        val dB = b[2] - a[2]
        val dD = d[2] - a[2]
        for (py in minY..maxY) {
            val ry = py + 0.5f - a[1]
            for (px in minX..maxX) {
                val rx = px + 0.5f - a[0]
                val s = (rx * vy - ry * vx) / det
                if (s < 0f || s >= 1f) continue
                val t = (ux * ry - uy * rx) / det
                if (t < 0f || t >= 1f) continue
                val i = py * width + px
                val z = a[2] + s * dB + t * dD
                if (z <= depth[i]) continue
                var color = flat
                if (tex != null) {
                    val u = uv[0] + s * (uv[2] - uv[0]) + t * (uv[4] - uv[0])
                    val v = uv[1] + s * (uv[3] - uv[1]) + t * (uv[5] - uv[1])
                    val texel = tex.sample(u, v, level)
                    val alpha = texel ushr 24
                    if (alpha < 8) continue
                    color = if (q.tint != -1) tinted(texel, q.tint) else texel
                    if (alpha != 255) color = over(color, pixels[i], alpha)
                }
                depth[i] = z
                ids[i] = id
                pixels[i] = scale(color, shade)
            }
        }
    }

    /** A premultiplied texel with the RGB [tint] multiplied in. */
    private fun tinted(c: Int, tint: Int): Int {
        val r = ((c shr 16) and 0xFF) * ((tint shr 16) and 0xFF) / 255
        val g = ((c shr 8) and 0xFF) * ((tint shr 8) and 0xFF) / 255
        val bl = (c and 0xFF) * (tint and 0xFF) / 255
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
    }

    /** A premultiplied [src] of [alpha] over [dst], which is premultiplied too. */
    private fun over(src: Int, dst: Int, alpha: Int): Int {
        val keep = 255 - alpha
        fun ch(shift: Int) = min(255, ((src ushr shift) and 0xFF) + ((dst ushr shift) and 0xFF) * keep / 255) shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** Darkens the color channels of a premultiplied pixel, keeping its alpha. */
    private fun scale(c: Int, shade: Float): Int {
        val r = (((c shr 16) and 0xFF) * shade).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * shade).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * shade).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
