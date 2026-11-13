package minecartographer.core.anvil

import minecartographer.core.DemoWorld
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportBundleTest {
    @Test
    fun `the bundle is named after the world and numbered when taken`() {
        val dir = Files.createTempDirectory("bundle")
        assertEquals(dir.resolve("My World_Export"), ExportBundle.folderFor(dir, "My World"))
        dir.resolve("My World_Export").createDirectories()
        assertEquals(dir.resolve("My World_Export (1)"), ExportBundle.folderFor(dir, "My World"))
        dir.resolve("My World_Export (1)").createDirectories()
        assertEquals(dir.resolve("My World_Export (2)"), ExportBundle.folderFor(dir, "My World"))
        assertEquals(dir.resolve("a_b_Export"), ExportBundle.folderFor(dir, "a:b"))
    }

    @Test
    fun `the readme says where to copy the world and for which version`() {
        val text = ExportBundle.readme("Merged", "Merged", DemoWorld("Valley", seed = 1), "9.9",
            listOf(ExportBundle.Section("Waypoints", "Copy `xaero` too.")))
        assertTrue("# Merged" in text)
        assertTrue("MineCartographer 9.9" in text)
        assertTrue("Copy the folder `Merged` into the `saves` folder" in text)
        assertTrue("## Waypoints\n\nCopy `xaero` too." in text)
    }

    @Test
    fun `waypoints go where the mods read them, and the readme lists them`() {
        val bundle = Files.createTempDirectory("bundle")
        val key = minecartographer.core.markers.WaypointKey("JourneyMap", "a.json", "Home", 1, 64, 2)
        val home = minecartographer.core.markers.Marker(minecartographer.core.markers.MarkerKind.Waypoint, "Home", 1, 64, 2, 0x55FF55, "JourneyMap", key)
        val section = ExportBundle.writeWaypoints(bundle, "My_World", listOf(home to minecartographer.core.markers.WaypointOrigin.Mod),
            ExportBundle.WaypointFormats(xaero = true, journeyMap = true), "9.9")
        assertTrue(Files.exists(bundle.resolve("waypoints.json")))
        assertTrue(Files.exists(bundle.resolve("xaero/minimap/My%us%World/dim%0/waypoints.txt")), "Xaero writes _ as %us%")
        assertTrue(Files.exists(bundle.resolve("journeymap/data/sp/My_World/waypoints/Home_1-64-2.json")))
        // The bundle's folders are laid out like a game folder: the readers find the waypoints there.
        val world = bundle.resolve("saves/My_World").also { it.createDirectories() }
        assertEquals(2, minecartographer.core.markers.MarkerReader.read(world).size)
        val readme = ExportBundle.readme("My World", "My_World", DemoWorld("Valley", seed = 1), "9.9", listOf(section))
        assertTrue("| `xaero/` |" in readme && "| `journeymap/` |" in readme && "## Waypoints" in readme)
    }

    @Test
    fun `only the chosen mods get files`() {
        val bundle = Files.createTempDirectory("bundle")
        ExportBundle.writeWaypoints(bundle, "W", emptyList(), ExportBundle.WaypointFormats(xaero = false, journeyMap = true), "1")
        assertTrue(Files.exists(bundle.resolve("waypoints.json")))
        assertTrue(!Files.exists(bundle.resolve("xaero")))
    }

    @Test
    fun `a waypoints-only export has its own name and readme`() {
        val dir = Files.createTempDirectory("bundle")
        assertEquals(dir.resolve("World_Waypoints"), ExportBundle.folderFor(dir, "World", "_Waypoints"))
        val section = ExportBundle.writeWaypoints(dir, "World", emptyList(), ExportBundle.WaypointFormats(xaero = true, journeyMap = false), "9.9")
        val readme = ExportBundle.waypointsReadme("World", DemoWorld("Valley", seed = 1), "9.9", section)
        assertTrue("# Waypoints of Valley" in readme && "| `xaero/` |" in readme && "Only the waypoints" in readme)
    }
}
