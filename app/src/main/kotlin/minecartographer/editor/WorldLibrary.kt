package minecartographer.editor

import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.WorldInfo
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** Remembers opened worlds and finds saves in the usual launcher folders. */
object WorldLibrary {
    private val prefs = Preferences.userRoot().node("minecartographer")
    private const val RECENT_KEY = "recentWorlds"
    private const val MAX_RECENT = 12

    fun recent(): List<Path> = prefs.get(RECENT_KEY, "").split('\n').filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.exists() }

    fun remember(folder: Path) {
        val list = (listOf(folder.toAbsolutePath().normalize()) + recent().filterNot { it == folder.toAbsolutePath().normalize() }).take(MAX_RECENT)
        prefs.put(RECENT_KEY, list.joinToString("\n"))
    }

    /** Folders that usually contain saves: vanilla launcher, popular launchers, and parents of recent worlds. */
    fun savesFolders(): List<Path> {
        val appData = System.getenv("APPDATA")?.let { Path.of(it) }
        val home = Path.of(System.getProperty("user.home"))
        val candidates = buildList {
            appData?.let { add(it.resolve(".minecraft/saves")) }
            add(home.resolve(".minecraft/saves"))
            add(home.resolve("Library/Application Support/minecraft/saves"))
            appData?.let { a ->
                listOf("PrismLauncher/instances", "MultiMC/instances", "ModrinthApp/profiles", "com.modrinth.theseus/profiles")
                    .map { a.resolve(it) }.filter { it.isDirectory() }
                    .forEach { instances -> runCatching { instances.listDirectoryEntries().forEach { i -> add(i.resolve(".minecraft/saves")); add(i.resolve("saves")) } } }
            }
            add(home.resolve("curseforge/minecraft/Instances"))
            recent().mapNotNull { it.parent }.forEach { add(it) }
        }
        return candidates.map { it.toAbsolutePath().normalize() }.distinct().filter { it.isDirectory() }
    }

    /** Lists world folders (with level.dat) directly inside the saves folders. Unreadable ones are skipped. */
    fun findWorlds(): List<WorldInfo> = savesFolders().flatMap { dir ->
        runCatching { dir.listDirectoryEntries().filter { it.isDirectory() && AnvilWorld.isWorld(it) } }.getOrDefault(emptyList())
    }.distinct().mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
}
