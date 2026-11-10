package minecartographer.core.markers

import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.IntTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkerReaderTest {
    private fun game(): Path = Files.createTempDirectory("game")

    @Test
    fun `banners on maps are read, the named ones with their text and color`() {
        val world = game().resolve("saves/World").also { it.resolve("data").createDirectories() }
        fun banner(color: String, name: String?, x: Int, y: Int, z: Int) = CompoundTag().apply {
            this["Color"] = StringTag(color)
            if (name != null) this["Name"] = StringTag(name)
            this["Pos"] = CompoundTag().apply { this["X"] = IntTag(x); this["Y"] = IntTag(y); this["Z"] = IntTag(z) }
        }
        val map = CompoundTag().apply {
            this["data"] = CompoundTag().apply {
                this["dimension"] = StringTag("minecraft:overworld")
                this["banners"] = ListTag(10, mutableListOf(banner("red", "{\"text\":\"Home\"}", 10, 70, -20), banner("white", null, 5, 64, 5)))
            }
        }
        val nether = CompoundTag().apply {
            this["data"] = CompoundTag().apply {
                this["dimension"] = StringTag("minecraft:the_nether")
                this["banners"] = ListTag(10, mutableListOf(banner("blue", "{\"text\":\"Portal\"}", 1, 1, 1)))
            }
        }
        world.resolve("data/map_0.dat").outputStream().use { Nbt.writeGzip(it, map) }
        world.resolve("data/map_1.dat").outputStream().use { Nbt.writeGzip(it, nether) }

        val found = MarkerReader.read(world)
        assertEquals(listOf("Home", "Banner"), found.map { it.name })
        assertEquals(Marker(MarkerKind.Banner, "Home", 10, 70, -20, 0xB02E26, "Map banner"), found[0])
    }

    @Test
    fun `Xaero waypoints of the overworld are read, switched off ones are not`() {
        val game = game()
        val world = game.resolve("saves/World").also { it.createDirectories() }
        val dir = game.resolve("XaeroWaypoints/World/dim%0").also { it.createDirectories() }
        dir.resolve("mw\$default_1.txt").writeText(
            """
            #
            sets:gui.xaero_default
            waypoint:Base:B:100:64:-200:12:false:0:gui.xaero_default:false:0:0:false
            waypoint:Mine:M:5:~:7:2:false:0:gui.xaero_default:false:0:0:false
            waypoint:Old:O:1:2:3:2:true:0:gui.xaero_default:false:0:0:false
            """.trimIndent(),
        )
        game.resolve("XaeroWaypoints/World/dim%-1").createDirectories().resolve("mw\$default_1.txt").writeText("waypoint:Hell:H:1:2:3:2:false:0:x:false:0:0:false")

        val found = MarkerReader.read(world)
        assertEquals(listOf("Base", "Mine"), found.map { it.name })
        val key = WaypointKey("Xaero's Minimap", "mw\$default_1.txt", "Base", 100, 64, -200)
        assertEquals(Marker(MarkerKind.Waypoint, "Base", 100, 64, -200, 0xFF5555, "Xaero's Minimap", key), found[0])
        assertEquals(null, found[1].y)
        assertEquals(null, found[1].key?.y)
    }

    @Test
    fun `JourneyMap waypoints are read from the folder of the world`() {
        val game = game()
        val world = game.resolve("saves/My World").also { it.createDirectories() }
        val dir = game.resolve("journeymap/data/sp/My World/waypoints").also { it.createDirectories() }
        dir.resolve("a.json").writeText("""{"name":"Farm","x":-40,"y":70,"z":12,"r":0,"g":255,"b":0,"enable":true,"dimensions":["minecraft:overworld"]}""")
        dir.resolve("b.json").writeText("""{"name":"Nether","x":1,"y":1,"z":1,"enable":true,"dimensions":["minecraft:the_nether"]}""")
        val key = WaypointKey("JourneyMap", "a.json", "Farm", -40, 70, 12)
        assertEquals(listOf(Marker(MarkerKind.Waypoint, "Farm", -40, 70, 12, 0x00FF00, "JourneyMap", key)), MarkerReader.read(world))
    }

    @Test
    fun `the key of a waypoint is the same wherever the mod folder is and differs per file`() {
        val a = MarkerReader.xaeroLine("waypoint:Base:B:1:2:3:2:false:0:s", "one.txt")!!
        val b = MarkerReader.xaeroLine("waypoint:Base:B:1:2:3:2:false:0:s", "two.txt")!!
        assertEquals(a.key, MarkerReader.xaeroLine("waypoint:Base:B:1:2:3:2:false:0:s", "one.txt")!!.key)
        assertEquals(false, a.key == b.key)
        assertEquals(null, MarkerReader.banners(Files.createTempDirectory("none")).firstOrNull()?.key)
    }

    @Test
    fun `a world outside a saves folder has no mod waypoints and no maps is no problem`() {
        assertEquals(emptyList(), MarkerReader.read(Files.createTempDirectory("lonely")))
    }
}
