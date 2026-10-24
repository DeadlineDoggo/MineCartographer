package minecartographer.textures

import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.ModInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Finds the mod archives a world was played with, for their textures. Looks, in order, at archives found
 * for the world before (saved in the project), the `mods` folder of the instance the save lives in
 * (`<instance>/saves/<world>`), a server's `mods` next to its world, and the folders the user added.
 * Archives are matched by the mod ids in their metadata against the world's mod list.
 */
object ModLocator {
    private val prefs = Preferences.userRoot().node("minecartographer")
    private const val FOLDERS_KEY = "modFolders"

    /** Folders with mods the user added, searched for every modded world. */
    var folders: List<Path>
        get() = prefs.get(FOLDERS_KEY, "").split('\n').filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.isDirectory() }
        set(value) = prefs.put(FOLDERS_KEY, value.distinct().joinToString("\n"))

    /**
     * What an archive provides: mod ids from its metadata, the asset namespaces it has, which loader format the
     * metadata is in ([modern]: `mods.toml`, 1.13+; else `mcmod.info`, 1.6 - 1.12) and the Minecraft version(s) it
     * declares (`mcversion`, or the `minecraft` dependency's version range), when it does.
     */
    class Archive(
        val path: Path,
        val modIds: Set<String>,
        val namespaces: Set<String>,
        val modern: Boolean?,
        val minecraft: String?,
        /** Mods bundled inside (Forge's jar-in-jar, `META-INF/jarjar/`): present whenever this archive is. */
        val bundled: Set<String> = emptySet(),
    ) {
        val hasAssets: Boolean get() = namespaces.isNotEmpty()

        /** False when the metadata says the archive is for another Minecraft version than [version]. */
        fun fits(version: String?, namedWorld: Boolean): Boolean {
            if (modern != null && modern != namedWorld) return false
            val declared = minecraft?.takeIf { '$' !in it }?.trim() ?: return true
            if (version == null) return true
            if (declared.startsWith("[") || declared.startsWith("(")) return inRange(version, declared)
            // "1.12" (or "1.12.x") covers 1.12.2: many mods declare only the major version.
            return declared.split(',').map { it.trim().removeSuffix(".x") }.any { version == it || version.startsWith("$it.") }
        }
    }

    /** [archives] with assets, in priority order; [missing] mods no archive provides. */
    class Found(val archives: List<Path>, val missing: List<ModInfo>)

    private val archiveCache = ConcurrentHashMap<String, Archive>()

    /** Mod archives for [world] (empty when it was played without mods); [known] are the ones found before. */
    fun locate(world: AnvilWorld, known: List<Path> = emptyList(), extraFolders: List<Path> = folders): Found {
        val forge = world.info.forge
        val mods = forge?.playedMods.orEmpty()
        if (forge == null || mods.isEmpty()) return Found(emptyList(), emptyList())
        // The game version the mods were for: listed as a mod since 1.8, else the save's version when it has one.
        val version = forge.mods.firstOrNull { it.id.equals("minecraft", true) }?.version?.takeIf { it.isNotBlank() }
            ?: world.info.versionName.takeIf { RELEASE.matches(it) }
        val wanted = mods.mapTo(HashSet()) { it.id.lowercase() }
        val own = listOfNotNull(instanceMods(world.folder), world.folder.parent?.resolve("mods")).filter { it.isDirectory() }
        val candidates = (known.filter { it.isRegularFile() }.mapNotNull(::archive) +
            (own + extraFolders).distinct().flatMap(::archivesIn)).distinctBy { it.path.toAbsolutePath().normalize() }

        val provided = HashSet<String>()
        val chosen = ArrayList<Archive>()
        for (a in candidates) {
            if (!a.fits(version, namedWorld = !forge.numericIds)) continue
            val ids = a.modIds.mapTo(HashSet()) { it.lowercase() }
            val fromOwnFolder = own.any { a.path.startsWith(it) } || a.path in known
            val matches = ids.any { it in wanted } ||
                (ids.isEmpty() && (fromOwnFolder || a.namespaces.any { it in wanted }))
            if (!matches) continue
            // The same mod twice (several versions in one folder): the first one wins.
            if (ids.isNotEmpty() && ids.all { it in provided }) continue
            provided += ids
            a.bundled.mapTo(provided) { it.lowercase() }
            if (ids.isEmpty()) provided += a.namespaces
            if (a.hasAssets) chosen += a
        }
        return Found(chosen.map { it.path }, mods.filter { it.id.lowercase() !in provided })
    }

    /** `<instance>/mods` for a save in `<instance>/saves/<world>`. */
    private fun instanceMods(world: Path): Path? {
        val saves = world.toAbsolutePath().normalize().parent ?: return null
        return if (saves.name.equals("saves", ignoreCase = true)) saves.parent?.resolve("mods") else null
    }

    /** Archives in [folder] and one level below (`mods/1.6.4/`, `mods/<version>/`). */
    private fun archivesIn(folder: Path): List<Archive> = runCatching {
        val top = folder.listDirectoryEntries()
        val files = top.filter { it.isRegularFile() } + top.filter { it.isDirectory() }.flatMap { d -> runCatching { d.listDirectoryEntries().filter { it.isRegularFile() } }.getOrDefault(emptyList()) }
        files.filter { it.name.endsWith(".jar", true) || it.name.endsWith(".zip", true) }.sortedBy { it.name.lowercase() }.mapNotNull(::archive)
    }.getOrDefault(emptyList())

    /** Reads an archive's metadata once per path, size and modification time. */
    fun archive(path: Path): Archive? {
        val key = runCatching { "${path.toAbsolutePath().normalize()}|${Files.size(path)}|${Files.getLastModifiedTime(path).toMillis()}" }.getOrNull() ?: return null
        archiveCache[key]?.let { return it }
        val archive = runCatching { ZipFile(path.toFile()).use { read(path, it) } }.getOrNull() ?: return null
        archiveCache[key] = archive
        return archive
    }

    private fun read(path: Path, zip: ZipFile): Archive {
        val ids = LinkedHashSet<String>()
        var modern: Boolean? = null
        var minecraft: String? = null
        // 1.6 - 1.12: mcmod.info, a JSON list (or {"modList": [...]}) that is often not quite valid JSON.
        zip.getEntry("mcmod.info")?.let { e ->
            val text = zip.getInputStream(e).use { String(it.readAllBytes()) }
            Regex(""""modid"\s*:\s*"([^"]+)"""").findAll(text).mapTo(ids) { it.groupValues[1] }
            minecraft = Regex(""""mcversion"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            modern = false
        }
        // 1.13+ Forge and NeoForge; the minecraft dependency's range follows its modId line.
        for (name in listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) zip.getEntry(name)?.let { e ->
            val text = zip.getInputStream(e).use { String(it.readAllBytes()) }
            val toml = modsToml(text)
            ids += toml.first
            minecraft = toml.second
            modern = true
        }
        val namespaces = HashSet<String>()
        val bundled = HashSet<String>()
        for (e in zip.entries()) {
            val n = e.name
            if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                runCatching { bundled += nestedModIds(zip.getInputStream(e).use { it.readAllBytes() }) }
                continue
            }
            if (!n.startsWith("assets/")) continue
            val slash = n.indexOf('/', 7)
            if (slash > 7 && (n.endsWith(".png") || n.endsWith(".json"))) namespaces += n.substring(7, slash)
        }
        return Archive(path, ids, namespaces, modern, minecraft, bundled)
    }

    /** Mod ids of a jar stored inside another one. */
    private fun nestedModIds(jar: ByteArray): List<String> {
        ZipInputStream(jar.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return emptyList()
                if (e.name == "META-INF/mods.toml") return modsToml(String(zip.readAllBytes())).first
            }
        }
    }

    /**
     * Mod ids declared in a mods.toml (`[[mods]]` tables, or an inline `mods = [{ modId = '...' }]` array),
     * and the version range of its `minecraft` dependency. Ids under `[[dependencies...]]` are mods this one
     * needs, not ones it provides. Comments and either quote style are allowed, as TOML does.
     */
    internal fun modsToml(text: String): Pair<List<String>, String?> {
        val ids = ArrayList<String>()
        var range: String? = null
        var table = ""
        var dependency: String? = null
        var inModsArray = false
        val header = Regex("""^\[\[?\s*([^\]]+?)\s*]]?""")
        val pair = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.isEmpty()) continue
            val h = header.find(line)
            if (h != null) {
                table = h.groupValues[1]
                dependency = null
                inModsArray = false
                continue
            }
            if (table == "" && Regex("""^mods\s*=\s*\[""").containsMatchIn(line)) inModsArray = true
            for (m in pair.findAll(line)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                when {
                    key == "modId" && (table == "mods" || inModsArray) -> ids += value
                    key == "modId" && table.startsWith("dependencies") -> dependency = value
                    key == "versionRange" && table.startsWith("dependencies") && dependency == "minecraft" -> range = value
                }
            }
            if (inModsArray && line.endsWith("]")) inModsArray = false
        }
        return ids to range
    }

    private val RELEASE = Regex("""\d+(\.\d+)+""")

    /** Maven-style range like `[1.20.1,1.21)` or `[1.20,)`; a bare `[1.12.2]` means exactly that version. */
    internal fun inRange(version: String, range: String): Boolean {
        val lowInclusive = range.startsWith("[")
        val highInclusive = range.endsWith("]")
        val parts = range.trim('[', ']', '(', ')').split(',').map { it.trim() }
        val low = parts.getOrNull(0).orEmpty()
        val high = if (parts.size == 1) low else parts.getOrNull(1).orEmpty()
        val v = key(version)
        if (low.isNotEmpty() && (if (lowInclusive) v < key(low) else v <= key(low))) return false
        if (high.isNotEmpty() && (if (highInclusive) v > key(high) else v >= key(high))) return false
        return true
    }

    /** "1.20.1" -> comparable number; missing parts count as 0. */
    private fun key(version: String): Long =
        Regex("""\d+""").findAll(version).map { it.value.toLong().coerceAtMost(9_999) }.take(3).toList().let { p ->
            (p + List(3 - p.size) { 0L }).fold(0L) { acc, n -> acc * 10_000 + n }
        }
}
