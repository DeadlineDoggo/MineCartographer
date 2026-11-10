package minecartographer.core.markers

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaypointWritersTest {
    private fun wp(name: String, x: Int, y: Int?, z: Int, rgb: Int) = Marker(MarkerKind.Waypoint, name, x, y, z, rgb, "test")
    private val sample = listOf(
        wp("Home", 100, 64, -200, 0xFF5555),
        wp("Mine: deep", 5, null, 7, 0x55FF55), // a colon and no height
        wp("Say \"hi\" é", -1, 70, 2, 0x5555FF),
    )
    private val banner = Marker(MarkerKind.Banner, "Banner", 1, 1, 1, 0xFFFFFF, "Map banner")

    @Test
    fun `Xaero text is read back by the reader, banners are left out`() {
        val game = Files.createTempDirectory("game")
        val world = game.resolve("saves/World").also { it.createDirectories() }
        WaypointWriters.writeXaero(game.resolve(WaypointWriters.XAERO_ROOT), "World", sample + banner)
        val text = game.resolve("xaero/minimap/World/dim%0/waypoints.txt").let(Files::readString)
        assertTrue(text.startsWith("#\n#waypoint:name:initials:x:y:z:color:disabled:type:set"))
        val read = MarkerReader.read(world)
        assertEquals(sample.map { it.name }, read.map { it.name })
        assertEquals(sample.map { Triple(it.x, it.y, it.z) }, read.map { Triple(it.x, it.y, it.z) })
        assertEquals(sample.map { it.rgb }, read.map { it.rgb })
    }

    @Test
    fun `an off-palette color becomes the nearest Xaero color`() {
        val line = WaypointWriters.xaeroText(listOf(wp("Red", 0, 0, 0, 0xF02020))).lines().first { it.startsWith("waypoint:") }
        assertEquals(0xFF5555, MarkerReader.xaeroLine(line)!!.rgb)
    }

    @Test
    fun `JourneyMap files are read back by the reader`() {
        val game = Files.createTempDirectory("game")
        val world = game.resolve("saves/World").also { it.createDirectories() }
        val files = WaypointWriters.writeJourneyMap(game.resolve(WaypointWriters.JOURNEYMAP_ROOT), "World", sample + banner)
        assertEquals(3, files.size)
        assertEquals("Home_100-64--200", files[0].fileName.toString().removeSuffix(".json"))
        val read = MarkerReader.read(world).sortedBy { it.x }
        val expected = sample.sortedBy { it.x }
        assertEquals(expected.map { it.name }, read.map { it.name })
        assertEquals(expected.map { Triple(it.x, it.z, it.rgb) }, read.map { Triple(it.x, it.z, it.rgb) })
        assertEquals(64, read.first { it.name.startsWith("Mine") }.y) // no height -> 64
    }

    @Test
    fun `our own file lists every waypoint with its origin`() {
        val text = WaypointWriters.ownJson(listOf(sample[0] to WaypointOrigin.Mod, sample[1] to WaypointOrigin.Own, banner to WaypointOrigin.Mod), "9.9")
        assertTrue("\"app-version\": \"9.9\"" in text)
        assertTrue("\"name\": \"Home\", \"x\": 100, \"y\": 64, \"z\": -200, \"color\": \"#FF5555\", \"source\": \"test\", \"origin\": \"mod\"" in text)
        assertTrue("\"y\": null" in text && "\"origin\": \"own\"" in text)
        assertEquals(2, Regex("\"origin\"").findAll(text).count()) // the banner is not a waypoint
        assertTrue("\"waypoints\": []" in WaypointWriters.ownJson(emptyList(), "1"))
    }
}
