package minecartographer.core.markers

import java.nio.file.Files
import java.nio.file.Path

/** Where a waypoint of the exported list comes from. */
enum class WaypointOrigin(val label: String) {
    /** Exactly as the minimap mod has it. */
    Mod("mod"),
    /** From a mod, changed in the app. */
    Edited("edited"),
    /** Made in the app. */
    Own("own"),
}

/**
 * Writes waypoints in the files the minimap mods read, using the layout of the mods' game folder
 * (`xaero/minimap/<world>/dim%0/waypoints.txt`, `journeymap/data/sp/<world>/waypoints/<id>.json`) and the
 * overworld only. The formats were checked against files written by Xaero's Minimap and JourneyMap on 1.20.1.
 */
object WaypointWriters {
    /** Relative to the game folder; [MarkerReader.xaero] and [MarkerReader.journeyMap] read these. */
    const val XAERO_ROOT = "xaero/minimap"
    const val JOURNEYMAP_ROOT = "journeymap/data/sp"

    private fun waypoints(markers: List<Marker>) = markers.filter { it.kind == MarkerKind.Waypoint }

    // ---- Xaero's Minimap ----

    /** The text of Xaero's `waypoints.txt`: the three header lines, then a `waypoint:` line per waypoint. */
    fun xaeroText(markers: List<Marker>): String = buildString {
        append("#\n#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination\n#\n")
        for (m in waypoints(markers)) {
            val name = m.name.replace(":", "§§")
            val color = nearestChatColor(m.rgb)
            append("waypoint:$name:${initials(m.name)}:${m.x}:${m.y ?: "~"}:${m.z}:$color:false:0:gui.xaero_default:false:0:0:false\n")
        }
    }

    /** Writes `<root>/<worldFolder>/dim%0/waypoints.txt`; [root] is the `xaero/minimap` folder. Returns the file. */
    fun writeXaero(root: Path, worldFolder: String, markers: List<Marker>): Path {
        val file = root.resolve(worldFolder).resolve("dim%0").resolve("waypoints.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, xaeroText(markers))
        return file
    }

    private fun nearestChatColor(rgb: Int): Int =
        MarkerReader.CHAT.indices.minBy { distance(MarkerReader.CHAT[it], rgb) }

    private fun distance(a: Int, b: Int): Int {
        val r = (a shr 16 and 255) - (b shr 16 and 255)
        val g = (a shr 8 and 255) - (b shr 8 and 255)
        val bl = (a and 255) - (b and 255)
        return r * r + g * g + bl * bl
    }

    /** Xaero shows the first letter on the pin. */
    private fun initials(name: String): String {
        val c = name.firstOrNull { it.isLetterOrDigit() } ?: return "W"
        return c.uppercaseChar().toString()
    }

    // ---- JourneyMap ----

    /** The file name (without `.json`) and the id JourneyMap gives a waypoint: `name_x-y-z`. */
    fun journeyMapId(m: Marker): String {
        val safe = m.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "waypoint" }
        return "${safe}_${m.x}-${m.y ?: 64}-${m.z}"
    }

    /** One JourneyMap waypoint file. A missing height becomes 64, the mod needs one. */
    fun journeyMapJson(m: Marker): String = """
        {
          "id": ${json(journeyMapId(m))},
          "name": ${json(m.name)},
          "icon": "journeymap:ui/img/waypoint-icon.png",
          "x": ${m.x},
          "y": ${m.y ?: 64},
          "z": ${m.z},
          "r": ${m.rgb shr 16 and 255},
          "g": ${m.rgb shr 8 and 255},
          "b": ${m.rgb and 255},
          "enable": true,
          "type": "Normal",
          "origin": "journeymap",
          "dimensions": [
            "minecraft:overworld"
          ],
          "persistent": true
        }
    """.trimIndent() + "\n"

    /** Writes `<root>/<worldFolder>/waypoints/<id>.json` per waypoint; [root] is the `journeymap/data/sp` folder. */
    fun writeJourneyMap(root: Path, worldFolder: String, markers: List<Marker>): List<Path> {
        val dir = root.resolve(worldFolder).resolve("waypoints")
        Files.createDirectories(dir)
        return waypoints(markers).map { m -> dir.resolve(journeyMapId(m) + ".json").also { Files.writeString(it, journeyMapJson(m)) } }
    }

    // ---- Our own format ----

    /** `waypoints.json` of an export: every waypoint, with where it came from. Format 1. */
    fun ownJson(entries: List<Pair<Marker, WaypointOrigin>>, appVersion: String): String = buildString {
        append("{\n  \"format\": 1,\n  \"app-version\": ${json(appVersion)},\n  \"waypoints\": [")
        val items = entries.filter { it.first.kind == MarkerKind.Waypoint }
        items.forEachIndexed { i, (m, origin) ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"name\": ${json(m.name)}, \"x\": ${m.x}, \"y\": ${m.y ?: "null"}, \"z\": ${m.z}, ")
            append("\"color\": ${json("#%06X".format(m.rgb and 0xFFFFFF))}, \"source\": ${json(m.source)}, \"origin\": ${json(origin.label)}}")
        }
        append(if (items.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
