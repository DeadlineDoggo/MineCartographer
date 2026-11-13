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
}
