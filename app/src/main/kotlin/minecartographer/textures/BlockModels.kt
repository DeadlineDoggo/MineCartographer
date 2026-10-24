package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.Rotation
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Face directions, also used as indices: down, up, north (-z), south (+z), west (-x), east (+x). */
object Dir {
    const val DOWN = 0
    const val UP = 1
    const val NORTH = 2
    const val SOUTH = 3
    const val WEST = 4
    const val EAST = 5
    val NAMES = listOf("down", "up", "north", "south", "west", "east")
    val DX = intArrayOf(0, 0, 0, 0, -1, 1)
    val DY = intArrayOf(-1, 1, 0, 0, 0, 0)
    val DZ = intArrayOf(0, 0, -1, 1, 0, 0)

    fun of(dx: Int, dy: Int, dz: Int): Int = (0 until 6).first { DX[it] == dx && DY[it] == dy && DZ[it] == dz }
}

/** A square texture with its mip levels, premultiplied ARGB. */
class Texture(val size: Int, private val levels: List<IntArray>, val opaque: Boolean) {
    val levelCount get() = levels.size

    /** The texel at ([u], [v]) in 0..16 texture units, from mip [level]. */
    fun sample(u: Float, v: Float, level: Int): Int {
        val l = level.coerceIn(0, levels.size - 1)
        val s = maxOf(1, size shr l)
        val x = ((u * s / 16).toInt()).coerceIn(0, s - 1)
        val y = ((v * s / 16).toInt()).coerceIn(0, s - 1)
        return levels[l][y * s + x]
    }

    companion object {
        /** Animated textures are vertical strips of frames; the first frame is used. */
        fun decode(png: ByteArray): Texture? {
            val image = ImageIO.read(ByteArrayInputStream(png)) ?: return null
            val size = image.width
            if (size == 0 || image.height < size) return null
            val argb = image.getRGB(0, 0, size, size, null, 0, size)
            var opaque = true
            val base = IntArray(argb.size) { i ->
                val c = argb[i]
                val a = c ushr 24
                if (a != 255) opaque = false
                premultiply(c, a)
            }
            val levels = arrayListOf(base)
            var s = size
            while (s > 1) {
                val prev = levels.last()
                val n = s / 2
                levels += IntArray(n * n) { i ->
                    val x = (i % n) * 2
                    val y = (i / n) * 2
                    average(prev[y * s + x], prev[y * s + x + 1], prev[(y + 1) * s + x], prev[(y + 1) * s + x + 1])
                }
                s = n
            }
            return Texture(size, levels, opaque)
        }

        private fun premultiply(c: Int, a: Int): Int {
            if (a == 255) return c
            if (a == 0) return 0
            return (a shl 24) or ((((c shr 16) and 0xFF) * a / 255) shl 16) or ((((c shr 8) and 0xFF) * a / 255) shl 8) or ((c and 0xFF) * a / 255)
        }

        private fun average(a: Int, b: Int, c: Int, d: Int): Int {
            fun ch(shift: Int) = ((((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) + ((c ushr shift) and 0xFF) + ((d ushr shift) and 0xFF)) / 4) shl shift
            return ch(24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/**
 * One face of a block model: a parallelogram given by three corners in block units (top-left, top-right,
 * bottom-left as the texture is laid on it) with the texture coordinates there (0..16).
 * [texture] null means a flat [color]. [cull] is the direction whose neighbor hides this face, or -1.
 */
class Quad(
    val corners: FloatArray,
    val uvs: FloatArray,
    val texture: Texture?,
    val color: Int,
    /** RGB multiplied into the texture (biome tint), or -1. */
    val tint: Int,
    val shade: Boolean,
    val cull: Int,
    val nx: Float, val ny: Float, val nz: Float,
)

/**
 * How a block state is drawn. [occludes]: a full opaque cube that hides neighbors' faces against it.
 * [fluid]: water or lava, shaded by depth and culled against itself.
 */
class BlockModel(val quads: List<Quad>, val occludes: Boolean, val fluid: Boolean) {
    /** The model seen after rotating the view by [turns] quarter turns (positions turn about the block center). */
    fun rotated(turns: Int): BlockModel {
        val t = Rotation.normalize(turns)
        if (t == 0) return this
        val quads = quads.map { q ->
            val c = q.corners.copyOf()
            for (i in 0 until 3) {
                val (x, z) = turnPoint(t, c[i * 3], c[i * 3 + 2])
                c[i * 3] = x
                c[i * 3 + 2] = z
            }
            val (nx, nz) = turnVector(t, q.nx, q.nz)
            val cull = if (q.cull < 0) -1 else turnVector(t, Dir.DX[q.cull].toFloat(), Dir.DZ[q.cull].toFloat())
                .let { (dx, dz) -> Dir.of(dx.toInt(), Dir.DY[q.cull], dz.toInt()) }
            Quad(c, q.uvs, q.texture, q.color, q.tint, q.shade, cull, nx, q.ny, nz)
        }
        return BlockModel(quads, occludes, fluid)
    }

    companion object {
        val EMPTY = BlockModel(emptyList(), occludes = false, fluid = false)

        /** Rotation.cell for a point inside a block: (x, z) -> (1 - z, x) per quarter turn. */
        private fun turnPoint(t: Int, x: Float, z: Float): Pair<Float, Float> = when (t) {
            1 -> (1 - z) to x
            2 -> (1 - x) to (1 - z)
            else -> z to (1 - x)
        }

        private fun turnVector(t: Int, x: Float, z: Float): Pair<Float, Float> = when (t) {
            1 -> -z to x
            2 -> -x to -z
            else -> z to -x
        }

        /** A unit cube with flat-colored faces. */
        fun cube(color: Int, occludes: Boolean, fluid: Boolean, texture: (Int) -> Texture? = { null }, tint: Int = -1): BlockModel {
            val quads = (0 until 6).map { dir -> ModelBuilder.face(dir, FloatArray(3), floatArrayOf(16f, 16f, 16f), null, 0, texture(dir), color, tint, true, dir, null) }
            return BlockModel(quads, occludes, fluid)
        }
    }
}

/** Provides the model of a block state for the view rotation [turns]. Implementations cache. */
interface BlockModelSource {
    fun model(state: BlockState, turns: Int): BlockModel
}

/** Every block as a flat-colored cube in its map color (the "Blocks" mode); plants and the like are left out. */
object FlatModels : BlockModelSource {
    private val cache = ConcurrentHashMap<BlockState, BlockModel>()

    override fun model(state: BlockState, turns: Int): BlockModel = cache.getOrPut(state) {
        val look = BlockColors.look(state)
        val path = state.path
        when {
            // Panes and bars are see-through for the map, but here they make up walls and towers.
            path.endsWith("glass_pane") || path == "iron_bars" ->
                BlockModel.cube(BlockColors.look(state.name.removeSuffix("_pane")).color.takeIf { path != "iron_bars" } ?: IRON_BARS_COLOR,
                    occludes = false, fluid = false)
            look.kind == BlockKind.SKIP -> BlockModel.EMPTY
            look.kind == BlockKind.WATER -> BlockModel.cube(WATER_COLOR, occludes = false, fluid = true)
            else -> BlockModel.cube(look.color, occludes = true, fluid = false)
        }
    } // a cube looks the same from every side

    const val WATER_COLOR = 0xFF4A80E0.toInt()
    private const val IRON_BARS_COLOR = 0xFF8C8C8C.toInt()
}

/** Zip files with Minecraft assets (resource packs first, then a client jar), searched in order. */
class AssetSource(val files: List<Path>) : Closeable {
    private val zips = files.map { ZipFile(it.toFile()) }

    /** e.g. "client 1.20.1.jar" or "MyPack.zip + 1.20.1.jar". */
    val label: String get() = files.joinToString(" + ") { it.name }

    fun read(path: String): ByteArray? {
        for (zip in zips) {
            val entry = zip.getEntry(path) ?: continue
            return zip.getInputStream(entry).use { it.readAllBytes() }
        }
        return null
    }

    override fun close() = zips.forEach { runCatching { it.close() } }
}

/**
 * Block models and textures from Minecraft's assets: blockstates pick a model (variants or multipart),
 * models inherit from parents, and their elements become [Quad]s. Blocks drawn by the game's entity
 * renderer (chests, beds, signs...) and blocks without assets (mods) fall back to [FlatModels].
 */
class BlockModels(private val assets: AssetSource) : BlockModelSource {
    private val unrotated = ConcurrentHashMap<BlockState, BlockModel>()
    private val rotated = Array(4) { ConcurrentHashMap<BlockState, BlockModel>() }
    private val models = ConcurrentHashMap<String, Optional<ResolvedModel>>()
    private val textures = ConcurrentHashMap<String, Optional<Texture>>()

    /** ConcurrentHashMap can't hold nulls; this remembers "not found" too. */
    private class Optional<T>(val value: T?)

    private class ResolvedModel(val textures: Map<String, String>, val elements: List<Map<String, Any?>>?)

    override fun model(state: BlockState, turns: Int): BlockModel {
        val t = Rotation.normalize(turns)
        rotated[t][state]?.let { return it }
        val base = unrotated.getOrPut(state) { runCatching { build(state) }.getOrNull() ?: FlatModels.model(state, 0) }
        return rotated[t].getOrPut(state) { base.rotated(t) }
    }

    fun texture(id: String): Texture? = textures.getOrPut(id) {
        val (ns, path) = split(id)
        Optional(assets.read("assets/$ns/textures/$path.png")?.let { runCatching { Texture.decode(it) }.getOrNull() })
    }.value

    private fun build(state: BlockState): BlockModel? {
        val look = BlockColors.look(state)
        // Fluids have no model; plants under water show as water, like in the Map mode.
        val path = state.path
        if (look.kind == BlockKind.WATER || state.properties["waterlogged"] == "true") return fluid("block/water_still", Tints.WATER)
        if (path == "lava" || path == "flowing_lava") return fluid("block/lava_still", -1)

        val (ns, _) = split(state.name)
        val json = (ALIASES[path]?.let { listOf(path, it) } ?: listOf(path)).firstNotNullOfOrNull { p ->
            assets.read("assets/$ns/blockstates/$p.json")?.let { Json.parse(String(it)) as? Map<*, *> }
        } ?: return null

        val parts = ArrayList<Pair<Map<*, *>, Boolean>>() // model reference, matched by variant or multipart
        (json["variants"] as? Map<*, *>)?.let { variants ->
            val entry = variants.entries.firstOrNull { (key, _) -> matchesVariant(key as String, state) }?.value
                ?: variants.values.firstOrNull()
            pick(entry)?.let { parts += it to true }
        }
        (json["multipart"] as? List<*>)?.forEach { part ->
            part as? Map<*, *> ?: return@forEach
            val condition = part["when"] as? Map<*, *>
            if (condition == null || matchesCondition(condition, state)) pick(part["apply"])?.let { parts += it to true }
        }
        if (parts.isEmpty()) return null

        val tint = Tints.of(path)
        val quads = ArrayList<Quad>()
        var occludes = false
        for ((ref, _) in parts) {
            val model = resolve(ref["model"] as? String ?: continue) ?: continue
            val elements = model.elements ?: continue // drawn by the game's entity renderer
            val xRot = ((ref["x"] as? Double)?.toInt() ?: 0) / 90
            val yRot = ((ref["y"] as? Double)?.toInt() ?: 0) / 90
            for (element in elements) {
                val added = ModelBuilder.element(element, xRot, yRot, tint) { variable -> resolveTexture(model, variable) }
                quads += added.first
                if (added.second) occludes = true
            }
        }
        if (quads.isEmpty()) return null
        return BlockModel(quads, occludes && look.kind != BlockKind.SKIP, fluid = false)
    }

    private fun fluid(texture: String, tint: Int): BlockModel {
        val tex = texture("minecraft:$texture")
        return BlockModel.cube(FlatModels.WATER_COLOR, occludes = false, fluid = true, texture = { tex }, tint = tint)
    }

    /** Weighted lists pick their first entry. */
    private fun pick(entry: Any?): Map<*, *>? = when (entry) {
        is Map<*, *> -> entry
        is List<*> -> entry.firstOrNull() as? Map<*, *>
        else -> null
    }

    /** "facing=east,half=bottom" matches when every listed property that the state has is equal. */
    private fun matchesVariant(key: String, state: BlockState): Boolean {
        if (key.isEmpty()) return true
        return key.split(',').all { kv ->
            val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val actual = state.properties[k]
            actual == null || actual == v
        }
    }

    private fun matchesCondition(condition: Map<*, *>, state: BlockState): Boolean {
        (condition["OR"] as? List<*>)?.let { list -> return list.any { (it as? Map<*, *>)?.let { c -> matchesCondition(c, state) } == true } }
        (condition["AND"] as? List<*>)?.let { list -> return list.all { (it as? Map<*, *>)?.let { c -> matchesCondition(c, state) } == true } }
        return condition.all { (k, v) ->
            val actual = state.properties[k as String] ?: return@all false
            val wanted = when (v) { is Double -> v.toInt().toString(); else -> v.toString() }
            actual in wanted.split('|')
        }
    }

    private fun resolve(id: String): ResolvedModel? = models.getOrPut(id) {
        Optional(runCatching { load(id, 0) }.getOrNull())
    }.value

    private fun load(id: String, depth: Int): ResolvedModel? {
        if (depth > 16) return null
        val (ns, path) = split(id)
        if (path.startsWith("builtin/")) return ResolvedModel(emptyMap(), null)
        val json = assets.read("assets/$ns/models/$path.json")?.let { Json.parse(String(it)) as? Map<*, *> } ?: return null
        val parent = (json["parent"] as? String)?.let { resolve(it) }
        val textures = LinkedHashMap(parent?.textures.orEmpty())
        (json["textures"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) textures[k] = v }
        @Suppress("UNCHECKED_CAST")
        val elements = (json["elements"] as? List<Map<String, Any?>>) ?: parent?.elements
        return ResolvedModel(textures, elements)
    }

    /** "#side" -> the model's "side" texture, following references; null when it doesn't resolve. */
    private fun resolveTexture(model: ResolvedModel, ref: String): Texture? {
        var name = ref
        repeat(8) {
            if (!name.startsWith("#")) return texture(name)
            name = model.textures[name.substring(1)] ?: return null
        }
        return null
    }

    companion object {
        /** Blocks renamed between versions, so a world and a client jar of different versions still match. */
        private val ALIASES = mapOf(
            "short_grass" to "grass", "grass" to "short_grass", "dirt_path" to "grass_path", "grass_path" to "dirt_path",
        )

        private fun split(id: String): Pair<String, String> =
            if (':' in id) id.substringBefore(':') to id.substringAfter(':') else "minecraft" to id
    }
}

/** Default biome colors for tinted blocks (Minecraft's plains values); biomes themselves are not read. */
object Tints {
    const val GRASS = 0x91BD59
    const val FOLIAGE = 0x77AB2F
    const val WATER = 0x3F76E4

    fun of(path: String): Int = when {
        path == "spruce_leaves" -> 0x619961
        path == "birch_leaves" -> 0x80A755
        path.endsWith("leaves") || path == "vine" -> FOLIAGE
        path == "lily_pad" -> 0x208030
        path == "redstone_wire" -> 0xC81010
        path.endsWith("_stem") -> 0x9AB020
        path in GRASSY -> GRASS
        else -> FOLIAGE
    }

    private val GRASSY = setOf("grass_block", "grass", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern", "sugar_cane")
}

/** Turns model JSON elements into [Quad]s. Coordinates are in model units (0..16) until the end. */
internal object ModelBuilder {
    /**
     * The quads of one element, rotated by the variant's [xRot] and [yRot] quarter turns; the second value
     * tells whether the element is a full opaque cube (then the block hides its neighbors' faces).
     */
    fun element(element: Map<String, Any?>, xRot: Int, yRot: Int, tint: Int, texture: (String) -> Texture?): Pair<List<Quad>, Boolean> {
        val from = floats(element["from"]) ?: return emptyList<Quad>() to false
        val to = floats(element["to"]) ?: return emptyList<Quad>() to false
        val rotation = element["rotation"] as? Map<*, *>
        val shade = element["shade"] as? Boolean ?: true
        val faces = element["faces"] as? Map<*, *> ?: return emptyList<Quad>() to false
        val quads = ArrayList<Quad>()
        var allOpaque = true
        for ((name, value) in faces) {
            val dir = Dir.NAMES.indexOf(name as String)
            val face = value as? Map<*, *> ?: continue
            if (dir < 0) continue
            val tex = (face["texture"] as? String)?.let(texture)
            if (tex == null || !tex.opaque) allOpaque = false
            val cull = (face["cullface"] as? String)?.let { Dir.NAMES.indexOf(it) }?.takeIf { it >= 0 } ?: -1
            val faceTint = if (face["tintindex"] != null) tint else -1
            quads += face(dir, from, to, floats(face["uv"]), ((face["rotation"] as? Double)?.toInt() ?: 0) / 90,
                tex, 0xFF8A8A8A.toInt(), faceTint, shade, cull, rotation, xRot, yRot)
        }
        val full = rotation == null && faces.size == 6 && allOpaque &&
            from.all { it == 0f } && to.all { it == 16f }
        return quads to full
    }

    /** A face of the box [from]..[to]; [uv] null means the default for that face. */
    fun face(
        dir: Int, from: FloatArray, to: FloatArray, uv: FloatArray?, uvTurns: Int, texture: Texture?, color: Int,
        tint: Int, shade: Boolean, cull: Int, rotation: Map<*, *>?, xRot: Int = 0, yRot: Int = 0,
    ): Quad {
        val (x0, y0, z0) = from.let { Triple(it[0], it[1], it[2]) }
        val (x1, y1, z1) = to.let { Triple(it[0], it[1], it[2]) }
        // Corners as the texture is laid on the face, seen from outside: top-left, top-right, bottom-left.
        val c = when (dir) {
            Dir.UP -> floatArrayOf(x0, y1, z0, x1, y1, z0, x0, y1, z1)
            Dir.DOWN -> floatArrayOf(x0, y0, z1, x1, y0, z1, x0, y0, z0)
            Dir.NORTH -> floatArrayOf(x1, y1, z0, x0, y1, z0, x1, y0, z0)
            Dir.SOUTH -> floatArrayOf(x0, y1, z1, x1, y1, z1, x0, y0, z1)
            Dir.WEST -> floatArrayOf(x0, y1, z0, x0, y1, z1, x0, y0, z0)
            else -> floatArrayOf(x1, y1, z1, x1, y1, z0, x1, y0, z1)
        }
        val box = uv ?: when (dir) {
            Dir.UP -> floatArrayOf(x0, z0, x1, z1)
            Dir.DOWN -> floatArrayOf(x0, 16 - z1, x1, 16 - z0)
            Dir.NORTH -> floatArrayOf(16 - x1, 16 - y1, 16 - x0, 16 - y0)
            Dir.SOUTH -> floatArrayOf(x0, 16 - y1, x1, 16 - y0)
            Dir.WEST -> floatArrayOf(z0, 16 - y1, z1, 16 - y0)
            else -> floatArrayOf(16 - z1, 16 - y1, 16 - z0, 16 - y0)
        }
        // Texture corners TL, TR, BR, BL; a face rotation turns the texture clockwise.
        val uvCorners = arrayOf(box[0] to box[1], box[2] to box[1], box[2] to box[3], box[0] to box[3])
        fun uvAt(corner: Int) = uvCorners[((corner - uvTurns) % 4 + 4) % 4]
        val uvs = floatArrayOf(uvAt(0).first, uvAt(0).second, uvAt(1).first, uvAt(1).second, uvAt(3).first, uvAt(3).second)

        rotation?.let { elementRotation(c, it) }
        var cullDir = cull
        repeat(xRot) {
            for (i in 0 until 3) {
                val y = c[i * 3 + 1]
                c[i * 3 + 1] = c[i * 3 + 2]
                c[i * 3 + 2] = 16 - y
            }
            if (cullDir >= 0) cullDir = Dir.of(Dir.DX[cullDir], Dir.DZ[cullDir], -Dir.DY[cullDir])
        }
        repeat(yRot) {
            for (i in 0 until 3) {
                val x = c[i * 3]
                c[i * 3] = 16 - c[i * 3 + 2]
                c[i * 3 + 2] = x
            }
            if (cullDir >= 0) cullDir = Dir.of(-Dir.DZ[cullDir], Dir.DY[cullDir], Dir.DX[cullDir])
        }
        for (i in c.indices) c[i] /= 16f

        // Normal = (BL - TL) x (TR - TL), pointing out of the face.
        val ax = c[3] - c[0]; val ay = c[4] - c[1]; val az = c[5] - c[2]
        val bx = c[6] - c[0]; val by = c[7] - c[1]; val bz = c[8] - c[2]
        var nx = by * az - bz * ay
        var ny = bz * ax - bx * az
        var nz = bx * ay - by * ax
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-9f) { nx /= len; ny /= len; nz /= len } else {
            // Zero-area face (a flat element seen edge-on); its direction still tells where it faces.
            nx = Dir.DX[dir].toFloat(); ny = Dir.DY[dir].toFloat(); nz = Dir.DZ[dir].toFloat()
        }
        return Quad(c, uvs, texture, color, tint, shade, cullDir, nx, ny, nz)
    }

    /** Rotation about an axis through an origin, by -45..45 degrees; "rescale" stretches back to full size. */
    private fun elementRotation(c: FloatArray, rotation: Map<*, *>) {
        val origin = floats(rotation["origin"]) ?: floatArrayOf(8f, 8f, 8f)
        val angle = Math.toRadians((rotation["angle"] as? Double) ?: 0.0)
        if (angle == 0.0) return
        val axis = rotation["axis"] as? String ?: "y"
        val rescale = rotation["rescale"] == true
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val scale = if (rescale) 1 / abs(cosA) else 1f
        // The two coordinates the rotation moves, by index.
        val (a, b) = when (axis) { "x" -> 1 to 2; "z" -> 0 to 1; else -> 2 to 0 }
        for (i in 0 until 3) {
            val p = c[i * 3 + a] - origin[a]
            val q = c[i * 3 + b] - origin[b]
            c[i * 3 + a] = (p * cosA - q * sinA) * scale + origin[a]
            c[i * 3 + b] = (p * sinA + q * cosA) * scale + origin[b]
        }
    }

    private fun floats(value: Any?): FloatArray? = (value as? List<*>)?.map { (it as? Double)?.toFloat() ?: 0f }?.toFloatArray()
}
