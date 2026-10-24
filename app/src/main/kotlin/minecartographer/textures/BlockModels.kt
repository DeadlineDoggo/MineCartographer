package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.Rotation
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockLook
import minecartographer.core.anvil.ForgeData
import minecartographer.core.anvil.BlockKind
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
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

    /** The mip levels, largest first, for [PackCache]. */
    internal val levelData: List<IntArray> get() = levels

    /** Average color as opaque RGB (the 1x1 mip level, alpha undone); null when the texture is mostly transparent. */
    val averageRgb: Int?
        get() {
            val c = levels.last()[0]
            val a = c ushr 24
            if (a < 0x40) return null
            fun ch(shift: Int) = (((c shr shift) and 0xFF) * 255 / a).coerceAtMost(255)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

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
        /**
         * Animated textures are vertical strips of frames; the first frame is used. Some 1.6 mods (Railcraft) put a
         * block's variants side by side in one wide strip instead; [frame] picks one of those.
         */
        fun decode(png: ByteArray, frame: Int = 0): Texture? {
            val image = ImageIO.read(ByteArrayInputStream(png)) ?: return null
            val size = minOf(image.width, image.height)
            if (size == 0) return null
            val frames = if (image.width > image.height && image.width % image.height == 0) image.width / image.height else 1
            val argb = image.getRGB(Math.floorMod(frame, frames) * size, 0, size, size, null, 0, size)
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

    /** Drops cached cubes of blocks whose color was learned since. */
    fun forget(names: Set<String>) {
        cache.keys.removeIf { it.name in names }
    }

    fun forgetAll() = cache.clear()

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

/**
 * Zip files with Minecraft assets (resource packs first, then mod archives, then a client jar) as one tree:
 * every `assets/` entry is indexed once, the first file having it wins, so a read is one lookup however
 * many archives there are (a modpack has dozens). With the index from a [PackCache] no archive is opened
 * until something is read from it.
 */
class AssetSource(val files: List<Path>, cachedIndex: Map<String, Int>? = null) : Closeable {
    private val zips = arrayOfNulls<ZipFile>(files.size)
    @Volatile private var closed = false

    /** Asset path -> index into [files]. */
    val index: Map<String, Int> = cachedIndex ?: HashMap<String, Int>().also { index ->
        for (i in files.indices) for (entry in zip(i).entries()) {
            if (!entry.isDirectory && entry.name.startsWith("assets/")) index.putIfAbsent(entry.name, i)
        }
    }

    private fun zip(i: Int): ZipFile {
        zips[i]?.let { return it }
        synchronized(zips) {
            check(!closed) { "asset source closed" }
            return zips[i] ?: ZipFile(files[i].toFile()).also { zips[i] = it }
        }
    }

    fun has(path: String) = path in index

    /** Block texture ids (`ns:blocks/x` before 1.13, `ns:block/x` after) per index into [files], sorted. */
    val blockTextures: Map<Int, List<String>> by lazy {
        val out = HashMap<Int, MutableList<String>>()
        for ((path, file) in index) {
            val m = BLOCK_PNG.matchEntire(path) ?: continue
            out.getOrPut(file) { ArrayList() } += "${m.groupValues[1]}:${m.groupValues[2]}/${m.groupValues[3]}"
        }
        out.mapValues { it.value.sorted() }
    }

    private companion object {
        // models/: blocks drawn by the mod's own renderer (BiblioCraft) have their textures there.
        val BLOCK_PNG = Regex("""assets/([^/]+)/textures/(blocks?|models)/(.+)\.png""")
    }

    /** The archive at [i] in [files], opened when first asked (class scans of 1.6 mods read their classes). */
    internal fun zipAt(i: Int): ZipFile = zip(i)

    /** Asset namespaces present (`minecraft`, mod ids...). */
    val namespaces: Set<String> = index.keys.mapTo(HashSet()) { it.substring(7, it.indexOf('/', 7).coerceAtLeast(7)) }

    /** e.g. "client 1.20.1.jar" or "MyPack.zip + 1.20.1.jar". */
    val label: String get() = files.joinToString(" + ") { it.name }

    fun read(path: String): ByteArray? {
        val zip = zip(index[path] ?: return null)
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readAllBytes() }
    }

    private val enums = ConcurrentHashMap<ZipFile, Map<String, List<EnumConstants.Constant>>>()

    /** Enum constants in the classes of the archive that provides [path] (read once per archive, when first asked). */
    fun enumConstants(path: String): Map<String, List<EnumConstants.Constant>> {
        val zip = zip(index[path] ?: return emptyMap())
        return enums.computeIfAbsent(zip) { EnumConstants.index(it) }
    }

    override fun close() = synchronized(zips) {
        closed = true
        zips.forEach { runCatching { it?.close() } }
    }
}

/**
 * Block models and textures from Minecraft's assets: blockstates pick a model (variants or multipart),
 * models inherit from parents, and their elements become [Quad]s. Blocks drawn by the game's entity
 * renderer (chests, beds, signs...) and blocks without assets (mods) fall back to [FlatModels].
 */
class BlockModels(val assets: AssetSource, val cache: PackCache? = null) : BlockModelSource {
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

    // ---- 1.6 blocks: no blockstates, textures guessed from clues (LegacyTextures), and the user's own choices ----

    private val legacyBlocks = ConcurrentHashMap<String, Pair<ForgeData.LegacyBlock, List<String>>>()
    private val legacyTextures by lazy { LegacyTextures(assets) }

    /** Texture chosen by the user, by block key (`ns:name[meta=n]`, or `ns:name` for every data value). Wins over everything. */
    val overrides = ConcurrentHashMap<String, String>()

    /** True when this pack can say something about [key] (`ns:name` or `ns:name[meta=n]`) without a blockstate. */
    fun knows(key: String): Boolean {
        val name = BlockColors.stateOf(key).name
        return legacyBlocks.containsKey(name) || overrides.containsKey(key) || overrides.containsKey(name)
    }

    /** True when the user picked a texture for [key] (`ns:name[meta=n]`) or its whole block. */
    fun isOverridden(key: String) = overrides.containsKey(key) || overrides.containsKey(BlockColors.stateOf(key).name)


    /** Makes these 1.6 blocks (and their keys in the mods' config files) known, so their textures can be guessed. */
    fun registerLegacy(blocks: Collection<ForgeData.LegacyBlock>, configKeys: Map<Int, List<String>>) {
        for (b in blocks) legacyBlocks.putIfAbsent(b.name, b to configKeys[b.id].orEmpty())
    }

    /** What a block is drawn with when not from a blockstate: top and side texture ids, and where they come from. */
    data class TextureChoice(val top: String, val side: String, val source: String)

    /** The user's choice for [state], else for a registered 1.6 block the best guess; null otherwise. */
    fun textureChoice(state: BlockState): TextureChoice? {
        val meta = state.properties["meta"]
        (meta?.let { overrides[BlockColors.keyOf(state.name, it)] } ?: overrides[state.name])?.let {
            val id = textureId(it)
            return TextureChoice(id, id, "your choice")
        }
        val (block, keys) = legacyBlocks[state.name] ?: return null
        val choice = cache?.legacy?.get(state.name) ?: legacyTextures.choose(block, keys)?.also { cache?.putLegacy(state.name, it) } ?: return null
        val v = choice.variant(meta?.toIntOrNull() ?: 0)
        return TextureChoice(textureId(v.top), textureId(v.side), "${choice.source} (${choice.score})")
    }

    /** `extrabiomesxl:leavesfirfancy` (a 1.6 icon name) -> `extrabiomesxl:blocks/leavesfirfancy`; full ids pass through. */
    private fun textureId(icon: String): String {
        val (ns, path) = split(icon)
        return if (path.startsWith("blocks/") || path.startsWith("block/") || path.startsWith("models/")) "$ns:$path" else "$ns:blocks/$path"
    }

    private fun isPlant(texture: String) = PLANT_WORDS.any { it in texture.lowercase() }

    /** Look from a [TextureChoice]: the top texture's color, nothing for plants, biome color for gray leaves. */
    private fun lookOf(choice: TextureChoice): BlockLook? {
        if (isPlant(choice.top)) return BlockLook.SKIP
        val texture = texture(choice.top) ?: return null
        val rgb = texture.averageRgb ?: return BlockLook.SKIP // nearly see-through (clear glass): look further down
        val leaves = "leaves" in choice.top.lowercase() || "leaf" in choice.top.lowercase()
        return BlockColors.solidLook(if (leaves && isGrayish(rgb)) multiply(rgb, Tints.FOLIAGE) else rgb)
    }

    /** A cube with the chosen textures (or nothing for plants), for blocks without a blockstate. */
    private fun cubeOf(choice: TextureChoice): BlockModel? {
        if (isPlant(choice.top)) return BlockModel.EMPTY
        val top = texture(choice.top) ?: return null
        val side = texture(choice.side) ?: top
        val leaves = "leaves" in choice.top.lowercase()
        val tint = if (leaves && top.averageRgb?.let(::isGrayish) == true) Tints.FOLIAGE else -1
        return BlockModel.cube(top.averageRgb ?: 0x808080, occludes = top.opaque && !leaves, fluid = false,
            texture = { dir -> if (dir == Dir.UP || dir == Dir.DOWN) top else side }, tint = tint)
    }

    /** Drops cached models of blocks whose fallback color was learned since. */
    fun forget(names: Set<String>) {
        unrotated.keys.removeIf { it.name in names }
        rotated.forEach { m -> m.keys.removeIf { it.name in names } }
    }

    /**
     * How [state] looks from above according to its assets: the average color of its top texture, or SKIP for
     * plant-like (cross) models. Reads only the blockstate, models and one texture, so it works without the
     * vanilla jar too. Null when the pack has no blockstate for it.
     */
    fun mapLook(state: BlockState): BlockLook? {
        val (ns, path) = split(state.name)
        val overridden = state.properties["meta"]?.let { overrides[BlockColors.keyOf(state.name, it)] } ?: overrides[state.name]
        if (overridden != null) return textureChoice(state)?.let(::lookOf)
        val json = blockstate(ns, path) ?: return textureChoice(state)?.let(::lookOf)
        val textures = LinkedHashMap<String, String>()
        for (part in parts(expandMeta(state, json), json, firstOnly = true)) {
            part.model?.let { resolve(it) }?.textures?.forEach { (k, v) -> textures.putIfAbsent(k, v) }
            textures.putAll(part.textures)
        }
        if (textures.isEmpty()) return null
        if ("cross" in textures || "plant" in textures) return BlockLook.SKIP
        val rgb = topTextureId(textures, json)?.let { texture(it) }?.averageRgb ?: return null
        // Leaves and grass textures are gray and colored by the biome; mods often ship them colored already.
        return BlockColors.solidLook(if (Tints.biomeColored(path) && isGrayish(rgb)) multiply(rgb, Tints.of(path)) else rgb)
    }

    /**
     * The texture on top of a block given its model textures: the top-like variable, resolved. Connected-texture mods
     * (Chisel) name textures that only exist as CTM sheets; any other texture of the same blockstate (the same family
     * of blocks) is better than none.
     */
    private fun topTextureId(textures: Map<String, String>, json: Map<*, *>): String? {
        val model = ResolvedModel(textures, null)
        val key = TOP_TEXTURES.firstOrNull { it in textures } ?: textures.keys.firstOrNull() ?: return null
        return resolveTextureId(model, "#$key")?.takeIf { texture(it) != null }
            ?: textureNames(json).firstOrNull { texture(it)?.averageRgb != null }
    }

    /** Where a block's look comes from, for the mods window. */
    enum class Source(val label: String) { CHOICE("your choice"), BLOCKSTATE("blockstate"), GUESS("guessed"), UNKNOWN("unknown") }

    /** How [state] is drawn: where from, the texture id on top (null when unknown) and a detail line. */
    data class BlockInfo(val source: Source, val texture: String?, val detail: String)

    fun info(state: BlockState): BlockInfo {
        val (ns, path) = split(state.name)
        if (isOverridden(BlockColors.keyOf(state.name, state.properties["meta"] ?: "0")) || overrides.containsKey(state.name)) {
            val top = textureChoice(state)?.top
            return BlockInfo(Source.CHOICE, top, top ?: "")
        }
        val json = blockstate(ns, path)
        if (json != null) {
            val textures = LinkedHashMap<String, String>()
            val expanded = expandMeta(state, json)
            for (part in parts(expanded, json, firstOnly = true)) {
                part.model?.let { resolve(it) }?.textures?.forEach { (k, v) -> textures.putIfAbsent(k, v) }
                textures.putAll(part.textures)
            }
            val variant = expanded.properties.entries.firstOrNull { it.key != "meta" }?.let { "${it.key}=${it.value}" }
            return BlockInfo(Source.BLOCKSTATE, topTextureId(textures, json), listOfNotNull("blockstate", variant).joinToString(" · "))
        }
        val choice = textureChoice(state) ?: return BlockInfo(Source.UNKNOWN, null, "no texture found; color guessed from the name")
        return BlockInfo(Source.GUESS, choice.top, choice.source)
    }

    /** The mod id a 1.6 block belongs to as level.dat says it (`TConstruct`), if it was registered. */
    fun legacyModId(name: String): String? = legacyBlocks[name]?.first?.modId

    /** Every texture id a blockstate names directly (Forge blockstates list textures inline), in file order. */
    private fun textureNames(json: Any?): Sequence<String> = sequence {
        when (json) {
            is Map<*, *> -> for ((k, v) in json) {
                if (k == "textures" && v is Map<*, *>) v.values.forEach { t -> if (t is String && !t.startsWith("#")) yield(t) }
                else yieldAll(textureNames(v))
            }
            is List<*> -> json.forEach { yieldAll(textureNames(it)) }
        }
    }

    private fun isGrayish(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) < 24
    }

    private fun multiply(a: Int, b: Int): Int {
        fun ch(shift: Int) = ((a shr shift) and 0xFF) * ((b shr shift) and 0xFF) / 255
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The blockstate json for `ns:path`; Chisel-style numbered blocks (`marble2`) fall back to the base name. */
    private fun blockstate(ns: String, path: String): Map<*, *>? =
        blockstatePath(ns, path)?.let { p -> assets.read(p)?.let { Json.parse(String(it)) as? Map<*, *> } }

    private fun blockstatePath(ns: String, path: String): String? =
        listOfNotNull(path, ALIASES[path], path.trimEnd { it.isDigit() }.takeIf { it != path && it.isNotEmpty() })
            .map { "assets/$ns/blockstates/$it.json" }.firstOrNull { assets.has(it) }

    fun texture(id: String): Texture? = textures.getOrPut(id) {
        cache?.texture(id)?.let { return@getOrPut Optional(it) }
        // `ns:path#2` is the third frame of a strip of variants (see LegacyTextures).
        val (ns, path) = split(id.substringBefore('#'))
        val frame = id.substringAfter('#', "0").toIntOrNull() ?: 0
        val decoded = assets.read("assets/$ns/textures/$path.png")?.let { runCatching { Texture.decode(it, frame) }.getOrNull() }
        decoded?.let { cache?.putTexture(id, it) }
        Optional(decoded)
    }.value

    private fun build(state: BlockState): BlockModel? {
        val look = BlockColors.look(state)
        // Fluids have no model; plants under water show as water, like in the Map mode.
        val path = state.path
        if (look.kind == BlockKind.WATER || state.properties["waterlogged"] == "true") return fluid("block/water_still", Tints.WATER)
        if (path == "lava" || path == "flowing_lava") return fluid("block/lava_still", -1)

        val (ns, _) = split(state.name)
        val overridden = state.properties["meta"]?.let { overrides[BlockColors.keyOf(state.name, it)] } ?: overrides[state.name]
        if (overridden != null) return textureChoice(state)?.let(::cubeOf)
        val json = blockstate(ns, path) ?: return textureChoice(state)?.let(::cubeOf)
        val parts = parts(expandMeta(state, json), json, firstOnly = false)
        if (parts.isEmpty()) return null

        val tint = Tints.of(path)
        val quads = ArrayList<Quad>()
        var occludes = false
        for (part in parts) {
            val resolved = resolve(part.model ?: continue) ?: continue
            val elements = resolved.elements ?: continue // drawn by the game's entity renderer
            val model = if (part.textures.isEmpty()) resolved else ResolvedModel(resolved.textures + part.textures, elements)
            for (element in elements) {
                val added = ModelBuilder.element(element, part.x, part.y, tint) { variable -> resolveTexture(model, variable) }
                quads += added.first
                if (added.second) occludes = true
            }
        }
        if (quads.isEmpty()) return null
        return BlockModel(quads, occludes && look.kind != BlockKind.SKIP, fluid = false)
    }

    /** [state] with its data value turned into the variant it stands for (see [expandMeta]); for tests and the inspector. */
    fun variantOf(state: BlockState): BlockState {
        val (ns, path) = split(state.name)
        return blockstate(ns, path)?.let { expandMeta(state, it) } ?: state
    }

    /** One model of a block: its id, quarter turns about x and y, and textures that replace the model's own (Forge). */
    private class Part(val model: String?, val x: Int, val y: Int, val textures: Map<String, String>)

    /**
     * The models [state] is drawn with: the matching variant, or every multipart piece whose condition holds
     * ([firstOnly]: just the first piece, enough for a map color). Forge's format (1.8 - 1.12, `"forge_marker": 1`)
     * is merged into one part instead: `defaults`, then a full variant key (`"facing=north,type=copper"` or
     * `normal`), then one entry per property table (`"type": {"copper": {...}}`); later ones override the model,
     * rotation and single textures.
     */
    private fun parts(state: BlockState, json: Map<*, *>, firstOnly: Boolean): List<Part> {
        val variants = json["variants"] as? Map<*, *> ?: emptyMap<Any, Any>()
        if ((json["forge_marker"] as? Double)?.toInt() == 1) {
            var model: String? = null
            var x = 0
            var y = 0
            val textures = LinkedHashMap<String, String>()
            fun apply(entry: Any?) {
                val m = pick(entry) ?: return
                (m["model"] as? String)?.let { model = it }
                (m["x"] as? Double)?.let { x = it.toInt() / 90 }
                (m["y"] as? Double)?.let { y = it.toInt() / 90 }
                (m["textures"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) textures[k] = v }
            }
            apply(json["defaults"])
            val full = variants.entries.firstOrNull { (k, _) -> k is String && '=' in k && matchesVariant(k, state) }?.value ?: variants["normal"]
            apply(full)
            var tables = 0
            for ((k, v) in variants) {
                if (k !is String || '=' in k || k == "normal" || k == "inventory" || v !is Map<*, *> || !isPropertyTable(v)) continue
                apply(v[state.properties[k] ?: v.keys.firstOrNull()])
                tables++
            }
            // Chisel-style: variants named by the mod (`array`, `braid`...), chosen in code by the data value;
            // `marble2` is the second block of the same family, so it continues with the next 16.
            if (full == null && tables == 0) {
                val named = variants.entries.filter { (k, v) -> k is String && '=' !in k && k != "inventory" && !(v is Map<*, *> && isPropertyTable(v)) }
                if (named.isNotEmpty()) {
                    val meta = state.properties["meta"]?.toIntOrNull() ?: 0
                    val block = state.path.takeLastWhile { it.isDigit() }.toIntOrNull()?.takeIf { it >= 2 }?.minus(1) ?: 0
                    apply(named[(block * 16 + meta) % named.size].value)
                }
            }
            // OBJ and B3D models need loaders this renderer doesn't have.
            if (model == null || model!!.endsWith(".obj") || model!!.endsWith(".b3d")) return emptyList()
            return listOf(Part(model, x, y, textures))
        }
        val parts = ArrayList<Part>()
        fun add(entry: Any?) {
            val m = pick(entry) ?: return
            parts += Part(m["model"] as? String, ((m["x"] as? Double)?.toInt() ?: 0) / 90, ((m["y"] as? Double)?.toInt() ?: 0) / 90, emptyMap())
        }
        if (variants.isNotEmpty()) add(variants.entries.firstOrNull { (key, _) -> matchesVariant(key as String, state) }?.value ?: variants.values.firstOrNull())
        (json["multipart"] as? List<*>)?.forEach { part ->
            if (firstOnly && parts.isNotEmpty()) return@forEach
            part as? Map<*, *> ?: return@forEach
            val condition = part["when"] as? Map<*, *>
            if (condition == null || matchesCondition(condition, state) || firstOnly) add(part["apply"])
        }
        return parts
    }

    /** `"type": {"copper": {...}, "lead": {...}}`: a Forge table of one property's values, not a variant itself. */
    private fun isPropertyTable(map: Map<*, *>) = map.isNotEmpty() && map.values.all { it is Map<*, *> || it is List<*> }

    /**
     * Blocks before 1.13 store a 4-bit data value instead of properties, and only the mod's code knows what it
     * means. Guess: it numbers the values of the block's main property in the order the blockstate lists them
     * (`type` of an ore block: copper, aluminum, lead...). Properties that are worked out from neighbors or
     * placement (facing, connections, true/false flags) are not candidates. Modded blocks only; vanilla ones
     * come with real properties from [minecartographer.core.anvil.LegacyBlocks].
     */
    private fun expandMeta(state: BlockState, json: Map<*, *>): BlockState {
        if (state.name.startsWith("minecraft:")) return state
        val metaText = state.properties["meta"] ?: return state
        val key = BlockColors.keyOf(state.name, metaText)
        cache?.variants?.get(key)?.let { cached ->
            if (cached.isEmpty()) return state
            val (k, v) = cached.split('=', limit = 2)
            return BlockState.of(state.name, mapOf(k to v))
        }
        val expanded = expandMetaUncached(state, json, metaText.toIntOrNull() ?: 0)
        cache?.putVariant(key, expanded.properties.entries.firstOrNull { it.key != "meta" }?.let { "${it.key}=${it.value}" } ?: "")
        return expanded
    }

    private fun expandMetaUncached(state: BlockState, json: Map<*, *>, meta: Int): BlockState {
        val values = LinkedHashMap<String, LinkedHashSet<String>>()
        val variants = json["variants"] as? Map<*, *> ?: return state
        for ((k, v) in variants) {
            if (k !is String) continue
            if ('=' in k) {
                for (kv in k.split(',')) {
                    val (name, value) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    values.getOrPut(name) { LinkedHashSet() } += value
                }
            } else if (k != "normal" && k != "inventory" && v is Map<*, *> && isPropertyTable(v)) {
                v.keys.forEach { value -> values.getOrPut(k) { LinkedHashSet() } += value.toString() }
            }
        }
        val main = values.entries
            .filter { (name, vs) -> vs.size >= 2 && name !in NOT_FROM_META && !vs.all { it == "true" || it == "false" } }
            .maxByOrNull { it.value.size } ?: return state
        // Some mods give each variant an explicit data value in an enum (Galacticraft: ORE_COPPER("ore_copper_gc", 5)),
        // and machines keep their facing in the low bits: take the variant with the highest value not above meta.
        val (ns, path) = split(state.name)
        val explicit = blockstatePath(ns, path)?.let { EnumConstants.dataValues(assets.enumConstants(it), main.value) }
        val value = explicit?.entries?.filter { it.value <= meta }?.maxByOrNull { it.value }?.key
            ?: main.value.elementAt(meta % main.value.size)
        return BlockState.of(state.name, mapOf(main.key to value))
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
        // Before 1.13 (and in Forge blockstates) a model name without a folder means models/block/.
        val json = (assets.read("assets/$ns/models/$path.json") ?: if ('/' !in path) assets.read("assets/$ns/models/block/$path.json") else null)
            ?.let { Json.parse(String(it)) as? Map<*, *> } ?: return null
        val parent = (json["parent"] as? String)?.let { resolve(it) }
        val textures = LinkedHashMap(parent?.textures.orEmpty())
        (json["textures"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) textures[k] = v }
        @Suppress("UNCHECKED_CAST")
        val elements = (json["elements"] as? List<Map<String, Any?>>) ?: parent?.elements
        return ResolvedModel(textures, elements)
    }

    /** "#side" -> the model's "side" texture, following references; null when it doesn't resolve. */
    private fun resolveTextureId(model: ResolvedModel, ref: String): String? {
        var name = ref
        repeat(8) {
            if (!name.startsWith("#")) return name
            name = model.textures[name.substring(1)] ?: return null
        }
        return null
    }

    private fun resolveTexture(model: ResolvedModel, ref: String): Texture? {
        var name = ref
        repeat(8) {
            if (!name.startsWith("#")) return texture(name)
            name = model.textures[name.substring(1)] ?: return null
        }
        return null
    }

    companion object {
        /** Properties a block gets from its surroundings or placement, so the data value doesn't pick them. */
        private val NOT_FROM_META = setOf(
            "facing", "axis", "rotation", "half", "shape", "direction", "hinge", "attached", "connected", "in_wall",
            "north", "south", "east", "west", "up", "down",
        )

        /** Texture names of plants: drawn as nothing rather than as a cube (1.6 blocks have no cross models). */
        private val PLANT_WORDS = listOf("sapling", "flower", "tallgrass", "cattail", "reed", "mushroom", "crops/")

        /** Texture variables that show the top of a block, best first. */
        private val TOP_TEXTURES = listOf("top", "up", "end", "all", "texture", "side", "particle")

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

    /** Blocks whose texture the game colors by biome (leaves, grass, vines), as opposed to a tinted model face. */
    fun biomeColored(path: String) = path.endsWith("leaves") || path == "vine" || path in GRASSY

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
