package minecartographer.editor

import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.exists

/** Remembers opened worlds and projects. It never looks for saves on its own. */
object WorldLibrary {
    private val prefs = Preferences.userRoot().node("minecartographer")
    private const val RECENT_KEY = "recentWorlds"
    private const val MAX_RECENT = 12
    private const val RECENT_PROJECTS_KEY = "recentProjects"

    fun recent(): List<Path> = read(RECENT_KEY)

    fun remember(folder: Path) = push(RECENT_KEY, folder)

    fun recentProjects(): List<Path> = read(RECENT_PROJECTS_KEY)

    fun rememberProject(file: Path) = push(RECENT_PROJECTS_KEY, file)

    private fun read(key: String): List<Path> = prefs.get(key, "").split('\n').filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.exists() }

    private fun push(key: String, path: Path) {
        val p = path.toAbsolutePath().normalize()
        prefs.put(key, (listOf(p) + read(key).filterNot { it == p }).take(MAX_RECENT).joinToString("\n"))
    }
}
