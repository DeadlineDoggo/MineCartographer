package minecartographer.textures

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Finds Minecraft's block textures. They can't be shipped with the app, so they are read from the
 * player's own installation: a client jar from the usual launcher folders (newest release first),
 * optionally with resource packs on top that the user picked.
 */
object TextureFiles {
    private val prefs = Preferences.userRoot().node("minecartographer")
    private const val CHOSEN_KEY = "textureFiles"

    /** Default files for worlds without their own (resource packs first, then a jar), or empty to find a jar automatically. */
    var chosen: List<Path>
        get() = prefs.get(CHOSEN_KEY, "").split('\n').filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.exists() }
        set(value) = prefs.put(CHOSEN_KEY, value.joinToString("\n"))

    /**
     * What to read textures from: [picked] files (the default [chosen] when empty) on top of a client jar,
     * which is the first usable of: one among [picked], [coreJar] (the one used last time), the jar of
     * [version], the newest release. Null if nothing usable was found.
     */
    fun sources(picked: List<Path> = emptyList(), coreJar: Path? = null, version: String? = null): List<Path>? {
        val files = picked.ifEmpty { chosen }.filter { it.exists() }
        // A resource pack alone may lack blockstates and models; the client jar fills those in.
        if (files.any(::isClientJar)) return files
        val jar = coreJar?.takeIf { it.isRegularFile() && isClientJar(it) } ?: findClientJar(version)
        return (files + listOfNotNull(jar)).ifEmpty { null }
    }

    /** The client jar among [files] (the vanilla assets under any resource packs), if there is one. */
    fun coreJarOf(files: List<Path>): Path? = files.lastOrNull(::isClientJar)

    /** The client jar of [version] (e.g. "1.20.1") if installed, else the newest release found. */
    fun findClientJar(version: String? = null): Path? {
        val jars = candidates().filter(::isClientJar)
        if (version != null) jars.firstOrNull { jarVersion(it) == version }?.let { return it }
        return jars.sortedWith(compareByDescending<Path> { isRelease(it) }.thenByDescending { versionKey(it) }).firstOrNull()
    }

    private fun jarVersion(jar: Path) = jar.name.removeSuffix(".jar").removePrefix("minecraft-").removeSuffix("-client")

    /** True for a zip that has the vanilla block assets (a client jar; mod loader jars don't). */
    fun isClientJar(file: Path): Boolean {
        // Cached by path and modification time: every world looks through all installed jars.
        val key = file.toAbsolutePath().normalize().toString() + "@" + runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0)
        return clientJars.getOrPut(key) {
            runCatching {
                ZipFile(file.toFile()).use { it.getEntry("assets/minecraft/blockstates/stone.json") != null && it.getEntry("assets/minecraft/models/block/cube_all.json") != null }
            }.getOrDefault(false)
        }
    }

    private val clientJars = ConcurrentHashMap<String, Boolean>()

    private fun candidates(): List<Path> {
        val appData = System.getenv("APPDATA")?.let { Path.of(it) }
        val home = Path.of(System.getProperty("user.home"))
        val versionDirs = listOfNotNull(
            appData?.resolve(".minecraft/versions"),
            home.resolve(".minecraft/versions"),
            home.resolve("Library/Application Support/minecraft/versions"),
            home.resolve("curseforge/minecraft/Install/versions"),
            appData?.resolve("ModrinthApp/meta/versions"),
            appData?.resolve("com.modrinth.theseus/meta/versions"),
        )
        val jars = ArrayList<Path>()
        for (dir in versionDirs.filter { it.isDirectory() }) {
            runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList()).forEach { version ->
                version.resolve("${version.name}.jar").takeIf { it.isRegularFile() }?.let(jars::add)
            }
        }
        // Prism Launcher and MultiMC keep client jars in their libraries folder.
        listOfNotNull(appData?.resolve("PrismLauncher/libraries"), appData?.resolve("MultiMC/libraries"), home.resolve(".local/share/PrismLauncher/libraries"))
            .map { it.resolve("com/mojang/minecraft") }.filter { it.isDirectory() }.forEach { dir ->
                runCatching { Files.walk(dir, 2).use { s -> s.filter { it.name.endsWith("-client.jar") }.forEach(jars::add) } }
            }
        return jars
    }

    private val RELEASE = Regex("""\d+(\.\d+)+""")

    private fun isRelease(jar: Path) = RELEASE.matches(jarVersion(jar))

    /** Orders "1.20.1" below "1.21.11" below "26.1.2"; non-numeric parts count as 0. */
    private fun versionKey(jar: Path): Long {
        val parts = Regex("""\d+""").findAll(jar.name).map { it.value.toLongOrNull() ?: 0 }.take(3).toList()
        return parts.fold(0L) { acc, n -> acc * 10_000 + n.coerceAtMost(9_999) } * 10_000L.pow(3 - parts.size)
    }

    private fun Long.pow(n: Int): Long = (0 until n).fold(1L) { acc, _ -> acc * this }
}
