package minecartographer.model

import minecartographer.textures.Dir
import minecartographer.textures.ModelBuilder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns beyond what the game's format allows. An element may only be rotated -45 to 45 degrees (in steps of 22.5)
 * about one axis, but a box turned by 90 degrees is just another box: the sizes swap, the faces move to other
 * sides and their textures turn with them. So any angle that is a multiple of 22.5 becomes quarter turns worked
 * into the box and a rotation within the limits for the rest.
 */
object QuarterTurns {
    /** The two axes a rotation about [axis] moves, in the order the renderer turns them (x: y to z, y: z to x, z: x to y). */
    private fun plane(axis: Int) = (axis + 1) % 3 to (axis + 2) % 3

    /**
     * [e] turned by [angle] degrees about [axis] through its rotation origin (its center when it has none), as an
     * element that stays within -45..45: the box and faces turned by the whole quarters, the rest as its rotation.
     * [angle] is counted from the box as it is stored, and snapped to 22.5.
     */
    fun withAngle(e: ModelElement, axis: Int, angle: Float): ModelElement {
        val total = (angle / 22.5f).roundToInt() * 22.5f
        val quarters = if (abs(total) <= 45f) 0 else (total / 90f).roundToInt()
        val residual = total - 90f * quarters
        val pivot = e.rotation?.origin ?: e.center
        val turned = turn(e, axis, quarters, pivot)
        return turned.copy(rotation = ElementRotation(axis, residual, pivot, e.rotation?.rescale ?: false))
    }

    /** The box of [e] and its faces turned by [quarters] quarter turns (right-handed) about [axis] through [pivot]. */
    fun turn(e: ModelElement, axis: Int, quarters: Int, pivot: Vec3): ModelElement {
        var current = e
        repeat(Math.floorMod(quarters, 4)) { current = once(current, axis, pivot) }
        return current
    }

    private fun once(e: ModelElement, axis: Int, pivot: Vec3): ModelElement {
        val (a, b) = plane(axis)
        val o = floatArrayOf(pivot.x, pivot.y, pivot.z)
        fun turn(p: FloatArray): FloatArray {
            val u = p[a] - o[a]
            val v = p[b] - o[b]
            return p.copyOf().also {
                it[a] = o[a] - v
                it[b] = o[b] + u
            }
        }
        val from = floatArrayOf(e.from.x, e.from.y, e.from.z)
        val to = floatArrayOf(e.to.x, e.to.y, e.to.z)
        val p = turn(from)
        val q = turn(to)
        val newFrom = FloatArray(3) { minOf(p[it], q[it]) }
        val newTo = FloatArray(3) { maxOf(p[it], q[it]) }

        fun turnDir(dir: Int): Int {
            val d = intArrayOf(Dir.DX[dir], Dir.DY[dir], Dir.DZ[dir])
            val r = d.copyOf()
            r[a] = -d[b]
            r[b] = d[a]
            return Dir.of(r[0], r[1], r[2])
        }

        val faces = HashMap<Int, ModelFace>()
        for ((dir, face) in e.faces) {
            // The face as drawn now: where each corner of its texture is, and which texel is there.
            val old = ModelBuilder.face(dir, from, to, face.uv?.let { floatArrayOf(it.u0, it.v0, it.u1, it.v1) }, face.rotation / 90, null, 0, -1, true, -1, null)
            val box = face.uv ?: defaultUv(dir, from, to)
            val oldCorners = corners(old.corners, old.uvs).map { (pos, uv) -> turn(pos) to uv }
            val newDir = turnDir(dir)
            val boxArray = floatArrayOf(box.u0, box.v0, box.u1, box.v1)
            // The face on its new side, with the same texture region: find how far its texture must be turned.
            val quarters = (0..3).firstOrNull { t ->
                val quad = ModelBuilder.face(newDir, newFrom, newTo, boxArray, t, null, 0, -1, true, -1, null)
                val candidate = corners(quad.corners, quad.uvs)
                oldCorners.all { (pos, uv) -> candidate.any { (cp, cuv) -> near(pos, cp) && near(uv, cuv) } }
            } ?: 0
            val default = defaultUv(newDir, newFrom, newTo)
            val sameAsDefault = abs(default.u0 - box.u0) + abs(default.v0 - box.v0) + abs(default.u1 - box.u1) + abs(default.v1 - box.v1) < 1e-3f
            faces[newDir] = face.copy(
                uv = if (sameAsDefault) null else box,
                rotation = quarters * 90,
                cull = if (face.cull >= 0) turnDir(face.cull) else -1,
            )
        }
        return e.copy(from = Vec3(newFrom[0], newFrom[1], newFrom[2]), to = Vec3(newTo[0], newTo[1], newTo[2]), faces = faces)
    }

    /** The texture region a face gets when it has no uv of its own. */
    private fun defaultUv(dir: Int, from: FloatArray, to: FloatArray): Uv {
        val uv = ModelBuilder.face(dir, from, to, null, 0, null, 0, -1, true, -1, null).uvs
        return Uv(uv[0], uv[1], uv[2], uv[5])
    }

    /** The four corners of a face (positions in model units) with their texture coordinates; the fourth is worked out. */
    private fun corners(c: FloatArray, uv: FloatArray): List<Pair<FloatArray, FloatArray>> {
        fun pos(i: Int) = floatArrayOf(c[i * 3] * 16, c[i * 3 + 1] * 16, c[i * 3 + 2] * 16)
        fun tex(i: Int) = floatArrayOf(uv[i * 2], uv[i * 2 + 1])
        val br = FloatArray(3) { pos(1)[it] + pos(2)[it] - pos(0)[it] }
        val brUv = FloatArray(2) { tex(1)[it] + tex(2)[it] - tex(0)[it] }
        return listOf(pos(0) to tex(0), pos(1) to tex(1), pos(2) to tex(2), br to brUv)
    }

    private fun near(a: FloatArray, b: FloatArray) = a.indices.all { abs(a[it] - b[it]) < 1e-3f }
}
