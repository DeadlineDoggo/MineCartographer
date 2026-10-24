package minecartographer.textures

import minecartographer.core.anvil.BlockKind
import minecartographer.core.anvil.BlockLook
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * Results worth keeping between sessions for one set of texture files (resource packs, mods, a client jar):
 * which archive has which asset, the map look of every block resolved so far, which variant a data value
 * stands for, and decoded textures. It never holds copies of the archives, only what was computed from them,
 * plus references to them (`pack.xml`).
 *
 * The cache's [key] is a hash of every file's path, size and modification time, so a changed, added or removed
 * file simply means another cache: there is nothing to invalidate. Unused caches are deleted after a while.
 * Deleting the whole folder only costs time.
 */
class PackCache private constructor(val dir: Path, val files: List<Path>) {
    /** Asset path -> index into [files] of the archive that provides it; null until known. */
    @Volatile var index: Map<String, Int>? = null
        private set

    /** Map looks by block key (`ns:name` or `ns:name[meta=n]`); [NO_LOOK] marks blocks the files can't color. */
    val looks = ConcurrentHashMap<String, BlockLook>()

    /** Variant a data value stands for, by block key: `property=value`, or empty when the data value picks nothing. */
    val variants = ConcurrentHashMap<String, String>()

    private val textures = ConcurrentHashMap<String, Texture>()
    @Volatile private var dirty = false

    fun texture(id: String): Texture? = textures[id]

    /** Keeps small textures only: big ones (HD packs) would make the cache larger than decoding them is slow. */
    fun putTexture(id: String, texture: Texture) {
        if (texture.size <= MAX_CACHED_TEXTURE && textures.putIfAbsent(id, texture) == null) dirty = true
    }

    fun putIndex(index: Map<String, Int>) {
        this.index = index
        dirty = true
    }

    fun putLook(key: String, look: BlockLook?) {
        looks[key] = look ?: NO_LOOK
        dirty = true
    }

    fun putVariant(key: String, variant: String) {
        if (variants.put(key, variant) != variant) dirty = true
    }

    /** Writes what changed since the last save; safe to call from any thread, often. */
    @Synchronized
    fun save() {
        if (!dirty) return
        dirty = false
        runCatching {
            Files.createDirectories(dir)
            val refs = dir.resolve("pack.xml")
            if (!refs.exists()) refs.writeText(referencesXml())
            write(dir.resolve("catalog.bin")) { out ->
                val index = index.orEmpty()
                out.writeInt(index.size)
                for ((path, file) in index) { out.writeUTF(path); out.writeShort(file) }
                out.writeInt(looks.size)
                for ((key, look) in looks) { out.writeUTF(key); out.writeByte(look.kind.ordinal); out.writeInt(look.color) }
                out.writeInt(variants.size)
                for ((key, variant) in variants) { out.writeUTF(key); out.writeUTF(variant) }
            }
            write(dir.resolve("textures.bin")) { out ->
                out.writeInt(textures.size)
                for ((id, t) in textures) {
                    out.writeUTF(id); out.writeShort(t.size); out.writeBoolean(t.opaque)
                    val levels = t.levelData
                    out.writeByte(levels.size)
                    for (level in levels) { out.writeInt(level.size); level.forEach(out::writeInt) }
                }
            }
        }.onFailure { dirty = true; it.printStackTrace() }
    }

    private fun load() {
        runCatching {
            read(dir.resolve("catalog.bin")) { input ->
                val n = input.readInt()
                val index = HashMap<String, Int>(n * 2)
                repeat(n) { index[input.readUTF()] = input.readUnsignedShort() }
                if (n > 0) this.index = index
                repeat(input.readInt()) {
                    val key = input.readUTF()
                    val kind = BlockKind.entries[input.readUnsignedByte()]
                    val color = input.readInt()
                    looks[key] = if (kind == BlockKind.SKIP && color == NO_LOOK.color) NO_LOOK else BlockLook(kind, color)
                }
                repeat(input.readInt()) { variants[input.readUTF()] = input.readUTF() }
            }
            read(dir.resolve("textures.bin")) { input ->
                repeat(input.readInt()) {
                    val id = input.readUTF()
                    val size = input.readUnsignedShort()
                    val opaque = input.readBoolean()
                    val levels = List(input.readUnsignedByte()) { IntArray(input.readInt()) { input.readInt() } }
                    textures[id] = Texture(size, levels, opaque)
                }
            }
        }.onFailure {
            // A broken or older cache is just ignored and rebuilt.
            index = null; looks.clear(); variants.clear(); textures.clear()
        }
        // Touch the folder so pruning knows it is in use.
        runCatching { Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
    }

    private fun referencesXml(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<minecartographer-pack-cache format="$FORMAT">""")
        for (f in files) {
            val size = runCatching { Files.size(f) }.getOrDefault(-1)
            val mtime = runCatching { f.getLastModifiedTime().toMillis() }.getOrDefault(-1)
            appendLine("""  <file absolute-path="${xml(f.toAbsolutePath().normalize().toString())}" size="$size" mtime="$mtime"/>""")
        }
        appendLine("</minecartographer-pack-cache>")
    }

    companion object {
        /** Bumped whenever what is stored, or how it is computed, changes; old caches are then not used. */
        const val FORMAT = 1
        private const val MAX_CACHED_TEXTURE = 64
        private const val PRUNE_AFTER_DAYS = 60L

        /** "This block has no look in these files", cached so it isn't looked up again. */
        private const val NO_LOOK_COLOR = 0x00ABCDEF
        val NO_LOOK = BlockLook(BlockKind.SKIP, NO_LOOK_COLOR)

        /** `%LOCALAPPDATA%\MineCartographer\cache` on Windows, `~/.cache/minecartographer` elsewhere. */
        val defaultRoot: Path by lazy {
            System.getProperty("minecartographer.cache")?.let { Path.of(it) }
                ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "MineCartographer", "cache") }
                ?: Path.of(System.getProperty("user.home"), ".cache", "minecartographer")
        }

        private val open = ConcurrentHashMap<String, PackCache>()

        /** The cache for exactly these [files] in this order, loaded from disk when it exists. */
        fun of(files: List<Path>, root: Path = defaultRoot): PackCache {
            val key = key(files)
            return open.computeIfAbsent("$root|$key") {
                PackCache(root.resolve("packs").resolve(key), files).also { it.load() }
            }
        }

        /** Forgets caches held in memory, so the next [of] reads them from disk (tests). */
        internal fun forgetOpen() = open.clear()

        /** Hash of the files' paths, sizes and modification times (and the format). */
        fun key(files: List<Path>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("format=$FORMAT\n".toByteArray())
            for (f in files) {
                val p = f.toAbsolutePath().normalize()
                val size = runCatching { Files.size(p) }.getOrDefault(-1)
                val mtime = runCatching { p.getLastModifiedTime().toMillis() }.getOrDefault(-1)
                md.update("$p|$size|$mtime\n".toByteArray())
            }
            return md.digest().take(12).joinToString("") { "%02x".format(it) }
        }

        /** Deletes pack caches not opened for [PRUNE_AFTER_DAYS] days. */
        fun prune(root: Path = defaultRoot) {
            val packs = root.resolve("packs").takeIf { it.isDirectory() } ?: return
            val limit = System.currentTimeMillis() - PRUNE_AFTER_DAYS * 24 * 3600 * 1000
            runCatching {
                for (dir in packs.listDirectoryEntries()) {
                    if (dir.isDirectory() && dir.getLastModifiedTime().toMillis() < limit) {
                        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                    }
                }
            }
        }

        private fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

        private fun write(file: Path, body: (DataOutputStream) -> Unit) {
            val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(tmp), 1 shl 16))).use { out ->
                    out.writeInt(MAGIC); out.writeInt(FORMAT); body(out)
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        private fun read(file: Path, body: (DataInputStream) -> Unit) {
            if (!file.exists()) return
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(file), 1 shl 16))).use { input ->
                check(input.readInt() == MAGIC && input.readInt() == FORMAT) { "not a pack cache of this format" }
                body(input)
            }
        }

        private const val MAGIC = 0x4D435043 // "MCPC"
    }
}
