package minecartographer.core.markers

import minecartographer.core.color.MinecraftColors
import minecartographer.core.json.Json
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.Nbt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

enum class MarkerKind(val label: String) {
    /** A waypoint made in a minimap mod. */
    Waypoint("Waypoints"),
    /** A banner pinned on a filled map item. */
    Banner("Map banners"),
}

/**
 * Identity of a waypoint as a minimap mod stores it: the mod, the file inside its world folder, and the name and
 * position it has there. Edits made in the app are kept against this key, so it must describe the waypoint as
 * the mod file has it and never change when the app moves or renames it.
 */
data class WaypointKey(val source: String, val file: String, val name: String, val x: Int, val y: Int?, val z: Int)

/**
 * A named place in the overworld: [y] is null when the source does not know the height. [rgb] is 0xRRGGBB.
 * [key] is set for waypoints read from a mod file, null for map banners. [modifiedMillis] is when the waypoint's own
 * file was last changed, known only for JourneyMap (Xaero keeps all waypoints in one file, the mods store no creation date).
 */
data class Marker(
    val kind: MarkerKind, val name: String, val x: Int, val y: Int?, val z: Int, val rgb: Int, val source: String,
    val key: WaypointKey? = null,
    val modifiedMillis: Long? = null,
)

/**
 * Places that belong to a world but are not stored in its chunks: the banners on the world's filled maps
 * (`data/map_N.dat`) and the waypoints that Xaero's Minimap and JourneyMap keep next to the game's saves.
 * Only the overworld is read, which is the dimension the editor shows.
 */
object MarkerReader {
    const val XAERO = "Xaero's Minimap"
    const val JOURNEYMAP = "JourneyMap"

    fun read(world: Path, levelName: String? = null): List<Marker> = readAll(world, levelName).markers

    /**
     * What [readAll] found. [unreadable] are the mods' files or folders that couldn't be read (locked by the game, cut
     * short while it wrote them...): their waypoints are missing from [markers] but may well still exist.
     */
    class Found(val markers: List<Marker>, val unreadable: List<Path>) {
        /** Every waypoint file was read, so a waypoint missing from [markers] is really gone from the mod. */
        val complete: Boolean get() = unreadable.isEmpty()
    }

    /** The banners and waypoints of [world]; a file that can't be read is left out and listed, the others still count. */
    fun readAll(world: Path, levelName: String? = null): Found {
        val unreadable = ArrayList<Path>()
        val markers = buildList {
            runCatching { addAll(banners(world)) } // banners have no edits that depend on them
            val game = gameFolder(world)
            if (game != null) {
                runCatching { addAll(xaero(game, world.name, unreadable::add)) }.onFailure { unreadable.add(game.resolve("xaero")) }
                runCatching { addAll(journeyMap(game, listOfNotNull(world.name, levelName), unreadable::add)) }
                    .onFailure { unreadable.add(game.resolve("journeymap")) }
            }
        }
        return Found(markers, unreadable)
    }

    /**
     * A text file as UTF-8, with bytes that aren't UTF-8 replaced (older files may be in the system's encoding, and one
     * odd letter in a name must not cost the whole file). Null when the file can't be read at all.
     */
    private fun text(file: Path): String? = try {
        String(Files.readAllBytes(file), Charsets.UTF_8)
    } catch (e: java.io.IOException) {
        null
    }

    /** The game folder when the world is in its `saves` folder (that is where the mods keep their files), else null. */
    fun gameFolder(world: Path): Path? {
        val saves = world.toAbsolutePath().normalize().parent ?: return null
        return if (saves.name == "saves") saves.parent else null
    }

    // ---- Banners on maps ----

    fun banners(world: Path): List<Marker> {
        val data = world.resolve("data")
        if (!data.isDirectory()) return emptyList()
        val found = LinkedHashMap<Triple<Int, Int, Int>, Marker>()
        for (file in data.listDirectoryEntries("map_*.dat").sortedBy { it.name }) {
            val root = runCatching { file.inputStream().use { Nbt.readGzip(it) } }.getOrNull() ?: continue
            val map = root.compound("data") ?: continue
            if (!overworld(map)) continue
            for (banner in map.list("banners")?.compounds().orEmpty()) {
                val pos = banner.compound("Pos") ?: continue
                val x = pos.int("X") ?: continue
                val y = pos.int("Y") ?: 64
                val z = pos.int("Z") ?: continue
                val color = banner.string("Color") ?: "white"
                val name = banner.string("Name")?.let(::plainText)?.takeIf { it.isNotBlank() } ?: "Banner"
                found.putIfAbsent(Triple(x, y, z), Marker(MarkerKind.Banner, name, x, y, z, dyeRgb(color), "Map banner"))
            }
        }
        return found.values.toList()
    }

    /** `dimension` is a name since 1.16 and a number before; maps without one are overworld maps. */
    private fun overworld(map: CompoundTag): Boolean {
        map.string("dimension")?.let { return it.endsWith("overworld") }
        return map.int("dimension")?.let { it == 0 } ?: true
    }

    /**
     * The text of a stored chat component: `{"text":"Home"}` (with the text of its `extra` parts after it), `"Home"`,
     * `'Home'` or plain. JSON escapes (`\"`, `\\`, `\u0027`...) are decoded; line breaks become spaces.
     */
    internal fun plainText(raw: String): String {
        val trimmed = raw.trim()
        val text = when (val parsed = runCatching { Json.parse(trimmed) }.getOrNull()) {
            is String -> parsed
            is Map<*, *> -> componentText(parsed)
            else -> trimmed.removeSurrounding("'")
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim()
    }

    private fun componentText(c: Map<*, *>): String = buildString {
        (c["text"] as? String)?.let(::append)
        (c["extra"] as? List<*>)?.forEach { part ->
            when (part) {
                is String -> append(part)
                is Map<*, *> -> append(componentText(part))
            }
        }
    }

    private val DYES = mapOf(
        "white" to 0xF9FFFE, "orange" to 0xF9801D, "magenta" to 0xC74EBD, "light_blue" to 0x3AB3DA, "yellow" to 0xFED83D,
        "lime" to 0x80C71F, "pink" to 0xF38BAA, "gray" to 0x474F52, "light_gray" to 0x9D9D97, "cyan" to 0x169C9C,
        "purple" to 0x8932B8, "blue" to 0x3C44AA, "brown" to 0x835432, "green" to 0x5E7C16, "red" to 0xB02E26, "black" to 0x1D1D21,
    )

    private fun dyeRgb(name: String) = DYES[name.removePrefix("minecraft:")] ?: 0xF9FFFE

    // ---- Xaero's Minimap ----

    /** Colors 0-15 of Xaero's waypoints are the game's chat colors. */
    private val CHAT get() = MinecraftColors.CHAT

    /** Files `waypoint:name:initials:x:y:z:color:disabled:type:set...` of the overworld folder `dim%0`; `~` for a height means none. */
    fun xaero(game: Path, worldFolder: String, unreadable: (Path) -> Unit = {}): List<Marker> {
        val result = ArrayList<Marker>()
        for (root in listOf("XaeroWaypoints", "xaero/minimap")) for (folder in listOf(xaeroFolder(worldFolder), worldFolder).distinct()) {
            val dir = game.resolve(root).resolve(folder).resolve("dim%0")
            if (!dir.isDirectory()) continue
            for (file in dir.listDirectoryEntries("*.txt").sortedBy { it.name }) {
                val text = text(file) ?: run { unreadable(file); null } ?: continue
                for (line in text.lines()) result += xaeroLine(line, file.name) ?: continue
            }
        }
        return result.distinct()
    }

    /** The folder Xaero's Minimap keeps a singleplayer world's waypoints in: the save's folder name with `_` written as `%us%`. */
    fun xaeroFolder(worldFolder: String) = worldFolder.replace("_", "%us%")

    internal fun xaeroLine(line: String, file: String = ""): Marker? {
        if (!line.startsWith("waypoint:")) return null
        val f = line.split(':')
        if (f.size < 7) return null
        if (f.getOrNull(7) == "true") return null // switched off in the mod
        val x = f[3].toIntOrNull() ?: return null
        val z = f[5].toIntOrNull() ?: return null
        val name = f[1].replace("§§", ":").ifBlank { f[2] }
        val rgb = CHAT[(f[6].toIntOrNull() ?: 15).coerceIn(0, 15)]
        val y = f[4].toIntOrNull()
        return Marker(MarkerKind.Waypoint, name, x, y, z, rgb, XAERO, WaypointKey(XAERO, file, name, x, y, z))
    }

    // ---- JourneyMap ----

    /** the json files in `journeymap/data/sp/<world>/waypoints`, in the folder named like the save or the world. */
    fun journeyMap(game: Path, names: List<String>, unreadable: (Path) -> Unit = {}): List<Marker> {
        val sp = game.resolve("journeymap/data/sp")
        if (!sp.isDirectory()) return emptyList()
        val wanted = names.map { it.lowercase() }
        val result = ArrayList<Marker>()
        for (world in sp.listDirectoryEntries().filter { it.isDirectory() && it.name.lowercase() in wanted }) {
            val dir = world.resolve("waypoints")
            if (!dir.exists()) continue
            for (file in dir.listDirectoryEntries("*.json").filter { it.extension == "json" }) {
                // A file that isn't JSON is one JourneyMap is writing, or a broken one: not a deleted waypoint.
                val doc = text(file)?.let { runCatching { Json.parse(it) }.getOrNull() as? Map<*, *> }
                if (doc == null) {
                    unreadable(file)
                    continue
                }
                result += journeyMapWaypoint(doc, file.name, runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()) ?: continue
            }
        }
        return result
    }

    /**
     * One JourneyMap waypoint file, read as JSON (JourneyMap writes it with Gson, which escapes `'`, `<`, `>`, `&` and
     * `=` as `\u0027`...). Null for a file that isn't one, a switched-off waypoint or one of another dimension.
     */
    internal fun journeyMapFile(json: String, file: String = "", modifiedMillis: Long? = null): Marker? =
        (runCatching { Json.parse(json) }.getOrNull() as? Map<*, *>)?.let { journeyMapWaypoint(it, file, modifiedMillis) }

    private fun journeyMapWaypoint(doc: Map<*, *>, file: String, modifiedMillis: Long?): Marker? {
        fun number(key: String) = (doc[key] as? Double)?.takeIf { it.isFinite() }?.let { Math.floor(it).toInt() }
        if (doc["enable"] == false) return null
        // Dimension names since 1.16, numbers before (0 is the overworld).
        val dimensions = doc["dimensions"] as? List<*>
        if (dimensions != null && dimensions.none { it is String && it.endsWith("overworld") || it == 0.0 }) return null
        val x = number("x") ?: return null
        val z = number("z") ?: return null
        val rgb = ((number("r") ?: 255) shl 16) or ((number("g") ?: 255) shl 8) or (number("b") ?: 255)
        val name = (doc["name"] as? String)?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: "Waypoint"
        val y = number("y")
        return Marker(MarkerKind.Waypoint, name, x, y, z, rgb and 0xFFFFFF, JOURNEYMAP, WaypointKey(JOURNEYMAP, file, name, x, y, z), modifiedMillis)
    }
}
