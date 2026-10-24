package minecartographer.textures

import java.nio.file.Files
import java.nio.file.Path
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

    /** Files the user picked (resource packs first, then a jar), or empty to find a jar automatically. */
    var chosen: List<Path>
        get() = prefs.get(CHOSEN_KEY, "").split('\n').filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.exists() }
        set(value) = prefs.put(CHOSEN_KEY, value.joinToString("\n"))

    /** What to read textures from: the user's choice, else the newest client jar found; null if nothing usable. */
    fun sources(): List<Path>? {
        val picked = chosen
        if (picked.isNotEmpty()) {
            // A resource pack alone may lack blockstates and models; the client jar fills those in.
            return if (picked.any(::isClientJar)) picked else picked + listOfNotNull(findClientJar())
        }
        return findClientJar()?.let(::listOf)
    }

    fun findClientJar(): Path? = candidates()
        .filter(::isClientJar)
        .sortedWith(compareByDescending<Path> { isRelease(it) }.thenByDescending { versionKey(it) })
        .firstOrNull()

    /** True for a zip that has the vanilla block assets (a client jar; mod loader jars don't). */
    fun isClientJar(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { it.getEntry("assets/minecraft/blockstates/stone.json") != null && it.getEntry("assets/minecraft/models/block/cube_all.json") != null }
    }.getOrDefault(false)

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

    private fun isRelease(jar: Path) = RELEASE.matches(jar.name.removeSuffix(".jar").removePrefix("minecraft-").removeSuffix("-client"))

    /** Orders "1.20.1" below "1.21.11" below "26.1.2"; non-numeric parts count as 0. */
    private fun versionKey(jar: Path): Long {
        val parts = Regex("""\d+""").findAll(jar.name).map { it.value.toLongOrNull() ?: 0 }.take(3).toList()
        return parts.fold(0L) { acc, n -> acc * 10_000 + n.coerceAtMost(9_999) } * 10_000L.pow(3 - parts.size)
    }

    private fun Long.pow(n: Int): Long = (0 until n).fold(1L) { acc, _ -> acc * this }
}
