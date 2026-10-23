package minecartographer.core.anvil

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Real saves for integration tests, found under the folder in the `MINECARTOGRAPHER_WORLDS` environment
 * variable (searched a few levels deep, so a launcher folder with several instances works).
 * Tests using it are skipped when the variable is not set. The worlds are only ever read.
 */
object TestWorlds {
    private val root: Path? = System.getenv("MINECARTOGRAPHER_WORLDS")?.let { Path.of(it) }?.takeIf { it.isDirectory() }

    /** One world per game version (the one with the fewest region files), oldest first. */
    val byVersion: List<WorldInfo> by lazy {
        val folders = root?.let { r ->
            Files.walk(r, 4).use { paths -> paths.filter { it.name == "level.dat" }.map { it.parent }.toList() }
        }.orEmpty()
        folders.mapNotNull { runCatching { WorldInfo.read(it) }.getOrNull() }
            .filter { regionCount(it.folder) > 0 }
            .groupBy { it.versionName }
            .map { (_, worlds) -> worlds.minBy { regionCount(it.folder) } }
            .sortedBy { it.dataVersion ?: 0 }
    }

    fun require(): List<WorldInfo> {
        assumeTrue(byVersion.isNotEmpty(), "Set MINECARTOGRAPHER_WORLDS to a folder containing Minecraft saves to run this test")
        return byVersion
    }

    /** The newest world, and the oldest one matching [older]; skips the test if there is none. */
    fun newestAnd(older: (WorldInfo) -> Boolean): Pair<WorldInfo, WorldInfo> {
        val worlds = require()
        val newest = worlds.last()
        val old = worlds.firstOrNull { it != newest && older(it) }
        assumeTrue(old != null, "No suitable older world under MINECARTOGRAPHER_WORLDS")
        return newest to old!!
    }

    private fun regionCount(folder: Path): Int = runCatching {
        folder.resolve("region").listDirectoryEntries("r.*.mca").size
    }.getOrDefault(0)
}
