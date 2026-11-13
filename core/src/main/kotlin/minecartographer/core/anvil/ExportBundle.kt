package minecartographer.core.anvil

import minecartographer.core.WorldSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists

/**
 * An export is a folder of its own, so its parts stay together:
 * ```
 * <parent>/<World>_Export[ (n)]/
 *   README.md                     where to copy what, and for which Minecraft
 *   minecartographer-sources.xml  which worlds the chunks came from (see ProjectFile.writeSources)
 *   <World>/                      the world -> the game's saves folder
 * ```
 * Further parts (the minimap mods' waypoints) add their folders and a README section of their own.
 */
object ExportBundle {
    const val README = "README.md"

    /** The folder name for a world called [levelName]: characters Windows doesn't allow become `_`. */
    fun folderName(levelName: String): String =
        levelName.trim().replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_").trimEnd('.', ' ').ifEmpty { "Merged world" }

    /** The bundle folder for [levelName] in [parent]: `<name>_Export`, or `<name>_Export (1)`, `(2)`... when taken. */
    fun folderFor(parent: Path, levelName: String): Path {
        val base = folderName(levelName) + "_Export"
        var candidate = parent.resolve(base)
        var n = 1
        while (candidate.exists()) candidate = parent.resolve("$base (${n++})")
        return candidate
    }

    /** A part of the README beyond the world: a heading and its markdown. */
    class Section(val title: String, val markdown: String)

    /** The README of a bundle that holds the world [levelName] in the folder [worldFolder], made from [base]. */
    fun readme(levelName: String, worldFolder: String, base: WorldSource, appVersion: String, extra: List<Section> = emptyList()): String = buildString {
        val version = base.versionLabel
        append("# $levelName\n\n")
        append("Exported with MineCartographer $appVersion on ${LocalDate.now()}.\n\n")
        append("For **Minecraft: Java Edition $version**")
        base.dataVersion?.let { append(" (DataVersion $it)") }
        append(".\n\n")

        append("## What is in here\n\n")
        append("| | |\n|---|---|\n")
        append("| `$worldFolder/` | the world |\n")
        append("| `minecartographer-sources.xml` | which worlds the chunks came from and the textures they were drawn with. Minecraft does not need it. |\n")
        append("| `$README` | this file |\n\n")

        append("## Installing the world\n\n")
        append("Copy the folder `$worldFolder` into the `saves` folder of your game:\n\n")
        append("- Windows: `%APPDATA%\\.minecraft\\saves`\n")
        append("- macOS: `~/Library/Application Support/minecraft/saves`\n")
        append("- Linux: `~/.minecraft/saves`\n")
        append("- A launcher with instances (Prism, MultiMC, CurseForge…): the `saves` folder of the instance.\n\n")
        append("Then start Minecraft $version and choose **$levelName** under Singleplayer.\n\n")
        val mods = (base as? AnvilWorld)?.info?.forge?.playedMods.orEmpty()
        if (mods.isNotEmpty()) {
            append("The world was played with ${mods.size} mods (${(base as AnvilWorld).info.forge!!.generation.label}). ")
            append("Open it with the same mods installed, or their blocks are lost:\n\n")
            for (m in mods.sortedBy { it.id }) append("- `${m.id}` ${m.version}\n")
            append("\n")
        }

        for (section in extra) append("## ${section.title}\n\n${section.markdown.trimEnd()}\n\n")

        append("---\n\nMineCartographer is an alpha: keep a backup of anything you care about, and check the world in Minecraft before relying on it.\n")
    }

    fun writeReadme(bundle: Path, text: String) {
        Files.writeString(bundle.resolve(README), text)
    }
}
