package minecartographer.model

import minecartographer.textures.Dir
import minecartographer.textures.SourceModel

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun get(axis: Int) = when (axis) { 0 -> x; 1 -> y; else -> z }

    fun with(axis: Int, value: Float) = when (axis) { 0 -> copy(x = value); 1 -> copy(y = value); else -> copy(z = value) }

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    fun list(): List<Double> = listOf(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        val CENTER = Vec3(8f, 8f, 8f)
        fun of(value: Any?): Vec3? {
            val l = (value as? List<*>)?.map { (it as? Double)?.toFloat() ?: 0f } ?: return null
            return if (l.size >= 3) Vec3(l[0], l[1], l[2]) else null
        }
    }
}

/** Texture rectangle of a face, in texture units (0..16). */
data class Uv(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    fun list(): List<Double> = listOf(u0, v0, u1, v1).map { it.toDouble() }
}

/**
 * One face of an element. [texture] is a variable (`#top`) or a texture id; null leaves the face without one.
 * [uv] null means the default for that face. [cull] is the direction whose neighbor hides the face, or -1.
 */
data class ModelFace(val texture: String? = null, val uv: Uv? = null, val rotation: Int = 0, val cull: Int = -1, val tint: Boolean = false)

/** Rotation of an element about [axis] (0 x, 1 y, 2 z) through [origin], in model units. */
data class ElementRotation(val axis: Int, val angle: Float, val origin: Vec3, val rescale: Boolean = false)

data class ModelElement(
    val id: Int,
    val name: String,
    val from: Vec3,
    val to: Vec3,
    val rotation: ElementRotation? = null,
    val shade: Boolean = true,
    /** By direction ([Dir] order). */
    val faces: Map<Int, ModelFace> = emptyMap(),
) {
    val center get() = Vec3((from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2)
    val size get() = Vec3(to.x - from.x, to.y - from.y, to.z - from.z)

    /** The element as the model JSON the renderer and the game read; face textures go through [texture]. */
    fun toJson(texture: (String?) -> String? = { it }): Map<String, Any?> = linkedMapOf<String, Any?>().also { out ->
        // Not part of the game's format, but it ignores it and editors keep the names there.
        out["name"] = name
        out["from"] = from.list()
        out["to"] = to.list()
        rotation?.takeIf { it.angle != 0f }?.let { r ->
            out["rotation"] = linkedMapOf<String, Any?>(
                "origin" to r.origin.list(), "axis" to "xyz"[r.axis].toString(), "angle" to r.angle.toDouble(),
            ).also { if (r.rescale) it["rescale"] = true }
        }
        if (!shade) out["shade"] = false
        out["faces"] = LinkedHashMap<String, Any?>().also { faces ->
            for (dir in 0 until 6) {
                val f = this.faces[dir] ?: continue
                faces[Dir.NAMES[dir]] = linkedMapOf<String, Any?>().also { m ->
                    f.uv?.let { m["uv"] = it.list() }
                    texture(f.texture)?.let { m["texture"] = it }
                    if (f.cull >= 0) m["cullface"] = Dir.NAMES[f.cull]
                    if (f.rotation != 0) m["rotation"] = f.rotation.toDouble()
                    if (f.tint) m["tintindex"] = 0.0
                }
            }
        }
    }
}

/**
 * A block model being edited: elements and the texture variables they use. Immutable, so a change is a new
 * document and undo just swaps documents.
 */
data class ModelDoc(
    val elements: List<ModelElement> = emptyList(),
    /** Variable name (without `#`) to texture id. */
    val textures: Map<String, String> = emptyMap(),
    val nextId: Int = 1,
) {
    fun element(id: Int) = elements.firstOrNull { it.id == id }

    fun update(ids: Set<Int>, change: (ModelElement) -> ModelElement) =
        copy(elements = elements.map { if (it.id in ids) change(it) else it })

    fun remove(ids: Set<Int>) = copy(elements = elements.filter { it.id !in ids })

    /** Adds an 8-block cube standing on the floor of the block, with [texture] on every face. */
    fun withCube(texture: String? = textures.keys.firstOrNull()?.let { "#$it" }): Pair<ModelDoc, Int> {
        val faces = (0 until 6).associateWith { ModelFace(texture) }
        val cube = ModelElement(nextId, "cube $nextId", Vec3(4f, 0f, 4f), Vec3(12f, 8f, 12f), faces = faces)
        return copy(elements = elements + cube, nextId = nextId + 1) to cube.id
    }

    /** Copies of [ids] right after the last element; returns the new ids. */
    fun duplicate(ids: Set<Int>): Pair<ModelDoc, List<Int>> {
        var next = nextId
        val copies = elements.filter { it.id in ids }.map { it.copy(id = next++, name = "${it.name} copy") }
        return copy(elements = elements + copies, nextId = next) to copies.map { it.id }
    }

    /** Mirrors [ids] across the middle of the block along [axis]. The faces on that axis swap sides; the textures are not flipped. */
    fun mirror(ids: Set<Int>, axis: Int): ModelDoc = update(ids) { e ->
        val from = e.from.with(axis, 16f - e.to[axis])
        val to = e.to.with(axis, 16f - e.from[axis])
        val swapped = e.faces.mapKeys { (dir, _) -> if (Dir.axisOf(dir) == axis) dir xor 1 else dir }
            .mapValues { (_, f) -> f.copy(cull = if (f.cull >= 0 && Dir.axisOf(f.cull) == axis) f.cull xor 1 else f.cull) }
        val rotation = e.rotation?.let { r ->
            val origin = r.origin.with(axis, 16f - r.origin[axis])
            // A mirror reverses turns about the axes lying in the mirror plane.
            r.copy(origin = origin, angle = if (r.axis == axis) r.angle else -r.angle)
        }
        e.copy(from = from, to = to, faces = swapped, rotation = rotation)
    }

    /** "#top" -> the texture id behind it, following references; a plain id is itself; null when it doesn't resolve. */
    fun resolve(ref: String?): String? {
        var name = ref ?: return null
        repeat(8) {
            if (!name.startsWith("#")) return name
            name = textures[name.substring(1)] ?: return null
        }
        return null
    }

    /** The model JSON, with the textures as variables the way the game wants them. */
    fun toJson(): String {
        val out = StringBuilder("{\n")
        if (textures.isNotEmpty()) {
            out.append("  \"textures\": {\n")
            out.append(textures.entries.joinToString(",\n") { (k, v) -> "    ${quote(k)}: ${quote(v)}" })
            out.append("\n  },\n")
        }
        out.append("  \"elements\": [\n")
        out.append(elements.joinToString(",\n") { "    " + write(it.toJson()) })
        out.append("\n  ]\n}\n")
        return out.toString()
    }

    companion object {
        fun from(source: SourceModel): ModelDoc {
            var id = 1
            val elements = source.elements.mapIndexed { i, json ->
                val faces = HashMap<Int, ModelFace>()
                (json["faces"] as? Map<*, *>)?.forEach { (name, value) ->
                    val dir = Dir.NAMES.indexOf(name as? String)
                    val f = value as? Map<*, *> ?: return@forEach
                    if (dir < 0) return@forEach
                    val uv = (f["uv"] as? List<*>)?.map { (it as? Double)?.toFloat() ?: 0f }?.takeIf { it.size >= 4 }?.let { Uv(it[0], it[1], it[2], it[3]) }
                    faces[dir] = ModelFace(
                        f["texture"] as? String, uv, (((f["rotation"] as? Double)?.toInt() ?: 0) % 360 + 360) % 360,
                        (f["cullface"] as? String)?.let { Dir.NAMES.indexOf(it) } ?: -1, f["tintindex"] != null,
                    )
                }
                val rotation = (json["rotation"] as? Map<*, *>)?.let { r ->
                    ElementRotation(
                        "xyz".indexOf((r["axis"] as? String) ?: "y").coerceAtLeast(0), ((r["angle"] as? Double) ?: 0.0).toFloat(),
                        Vec3.of(r["origin"]) ?: Vec3.CENTER, r["rescale"] == true,
                    )
                }
                ModelElement(
                    id++, (json["name"] as? String) ?: "element ${i + 1}", Vec3.of(json["from"]) ?: Vec3(0f, 0f, 0f), Vec3.of(json["to"]) ?: Vec3(16f, 16f, 16f),
                    rotation, json["shade"] as? Boolean ?: true, faces,
                )
            }
            return ModelDoc(elements, source.textures, id)
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

        private fun write(value: Any?): String = when (value) {
            is Map<*, *> -> value.entries.joinToString(", ", "{ ", " }") { (k, v) -> "${quote(k.toString())}: ${write(v)}" }
            is List<*> -> value.joinToString(", ", "[", "]") { write(it) }
            is String -> quote(value)
            is Double -> num(value)
            else -> value.toString()
        }
    }
}
