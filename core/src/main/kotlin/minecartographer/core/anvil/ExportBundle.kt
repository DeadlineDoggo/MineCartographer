package minecartographer.core.anvil

import minecartographer.core.WorldSource
import minecartographer.core.markers.Marker
import minecartographer.core.markers.MarkerReader
import minecartographer.core.markers.WaypointOrigin
import minecartographer.core.markers.WaypointWriters
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
 *   waypoints.json                when waypoints are exported: all of them, in a plain form
 *   xaero/minimap/<World>/...     Xaero's Minimap's waypoints   -> merged into the game folder
 *   journeymap/data/sp/<World>/... JourneyMap's waypoints       -> merged into the game folder
 * ```
 * The mods' folders are laid out as in the game folder (the one that holds `saves`), so copying them there is all it takes.
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

    /** A part of the README beyond the world: a heading and its markdown, and the bundle's files it is about (name to what it is). */
    class Section(val title: String, val markdown: String, val files: List<Pair<String, String>> = emptyList())

    /** Which minimap mods get the waypoints. */
    data class WaypointFormats(val xaero: Boolean, val journeyMap: Boolean)

    /**
     * Writes [waypoints] (with where each came from) into [bundle] for the world in [worldFolder]: `waypoints.json` always,
     * and the files of the chosen mods. Returns the README section that says where they go.
     */
    fun writeWaypoints(bundle: Path, worldFolder: String, waypoints: List<Pair<Marker, WaypointOrigin>>, formats: WaypointFormats, appVersion: String): Section {
        val markers = waypoints.map { it.first }
        Files.writeString(bundle.resolve("waypoints.json"), WaypointWriters.ownJson(waypoints, appVersion))
        val files = mutableListOf("waypoints.json" to "every waypoint: name, position, color, and whether it is the mod's, changed or made here")
        val text = StringBuilder()
        text.append("${markers.size} waypoints of this world, for the minimap mods. Copy these folders into your **game folder** ")
        text.append("(the one that holds `saves`, or the instance folder of a launcher), merging them with what is there:\n\n")
        if (formats.xaero) {
            WaypointWriters.writeXaero(bundle.resolve(WaypointWriters.XAERO_ROOT), worldFolder, markers)
            files += "xaero/" to "Xaero's Minimap's waypoints"
            text.append("- `xaero/` for **Xaero's Minimap**: it holds `minimap/${MarkerReader.xaeroFolder(worldFolder)}/dim%0/waypoints.txt`. ")
            text.append("Xaero knows the 16 colors of Minecraft's chat; other colors became the nearest of them. ")
            text.append("A `waypoints.txt` already there for a world of this name is replaced.\n")
        }
        if (formats.journeyMap) {
            WaypointWriters.writeJourneyMap(bundle.resolve(WaypointWriters.JOURNEYMAP_ROOT), worldFolder, markers)
            files += "journeymap/" to "JourneyMap's waypoints"
            text.append("- `journeymap/` for **JourneyMap**: it holds `data/sp/$worldFolder/waypoints/`, one file per waypoint. ")
            text.append("Waypoints already there stay; files of the same name are replaced.\n")
        }
        text.append("\nThe mods find them by the world's folder name, `$worldFolder`. If you rename the world's folder, rename theirs to match.\n")
        return Section("Waypoints", text.toString(), files)
    }

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
        append("| `$README` | this file |\n")
        for (section in extra) for ((file, what) in section.files) append("| `$file` | $what |\n")
        append("\n")

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
