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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val read = MarkerReader.read(world)
        assertEquals(listOf(Marker(MarkerKind.Waypoint, "Farm", -40, 70, 12, 0x00FF00, "JourneyMap", key)), read.map { it.copy(modifiedMillis = null) })
        assertEquals(true, read.single().modifiedMillis != null, "JourneyMap has a file per waypoint, so it has a date")
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

    @Test
    fun `JourneyMap names are decoded as JSON`() {
        // Gson, which JourneyMap writes with, escapes ' < > & = as \u0027 and so on
        val m = MarkerReader.journeyMapFile("""{"name":"Tom\u0027s \u003cbase\u003e \\n \"1\"","x":1.0,"y":64,"z":-2,"enable":true,"dimensions":["minecraft:overworld"]}""")!!
        assertEquals("Tom's <base> \\n \"1\"", m.name)
        assertEquals(listOf(1, 64, -2), listOf(m.x, m.y, m.z))
    }

    @Test
    fun `JourneyMap files before 1_16 name the overworld 0, other dimensions and switched off ones are left out`() {
        assertEquals("Old", MarkerReader.journeyMapFile("""{"name":"Old","x":1,"y":2,"z":3,"enable":true,"dimensions":[0, -1]}""")?.name)
        assertEquals(null, MarkerReader.journeyMapFile("""{"name":"Hell","x":1,"y":2,"z":3,"enable":true,"dimensions":[-1]}"""))
        assertEquals(null, MarkerReader.journeyMapFile("""{"name":"Off","x":1,"y":2,"z":3,"enable":false}"""))
        assertEquals(null, MarkerReader.journeyMapFile("""not json"""))
    }

    @Test
    fun `chat component text is decoded`() {
        assertEquals("Home", MarkerReader.plainText("""{"text":"Home"}"""))
        assertEquals("Tom's base", MarkerReader.plainText("""{"text":"Tom\u0027s base"}"""))
        assertEquals("a\\nb", MarkerReader.plainText("""{"text":"a\\nb"}"""), "a backslash and n is not a line break")
        assertEquals("a b", MarkerReader.plainText("""{"text":"a\nb"}"""))
        assertEquals("Big base", MarkerReader.plainText("""{"text":"","extra":[{"text":"Big "},"base"]}"""))
        assertEquals("Home", MarkerReader.plainText("\"Home\""))
        assertEquals("Home", MarkerReader.plainText("'Home'"))
        assertEquals("Home", MarkerReader.plainText("Home"))
    }

    @Test
    fun `a Xaero file that isn't UTF-8 is read with its odd letters replaced, and the other files still count`() {
        val game = game()
        val world = game.resolve("saves/World").also { it.createDirectories() }
        val dir = game.resolve("XaeroWaypoints/World/dim%0").also { it.createDirectories() }
        dir.resolve("a.txt").writeText("waypoint:Base:B:1:64:2:12:false:0:gui.xaero_default:false:0:0:false")
        // "Chata" with an a-acute written in cp1250 (0xE1), which isn't UTF-8.
        Files.write(dir.resolve("b.txt"), "waypoint:Chat".toByteArray() + byteArrayOf(0xE1.toByte()) + ":C:3:64:4:12:false:0:x:false:0:0:false".toByteArray())

        val found = MarkerReader.readAll(world)
        assertEquals(listOf("Base", "Chat\uFFFD"), found.markers.map { it.name })
        assertTrue(found.complete)
    }

    @Test
    fun `a JourneyMap file that can't be read or is cut short is listed, not taken for a deleted waypoint`() {
        val game = game()
        val world = game.resolve("saves/World").also { it.createDirectories() }
        val dir = game.resolve("journeymap/data/sp/World/waypoints").also { it.createDirectories() }
        dir.resolve("a.json").writeText("""{"name":"Farm","x":1,"y":2,"z":3,"enable":true}""")
        dir.resolve("b.json").writeText("""{"name":"Mine","x":1,""")
        dir.resolve("c.json").createDirectories() // can't be read as a file, like one the game holds locked

        val found = MarkerReader.readAll(world)
        assertEquals(listOf("Farm"), found.markers.map { it.name })
        assertEquals(setOf("b.json", "c.json"), found.unreadable.map { it.fileName.toString() }.toSet())
        assertFalse(found.complete)
    }
}
