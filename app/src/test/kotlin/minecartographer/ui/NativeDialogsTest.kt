package minecartographer.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeDialogsTest {
    private val textures = DialogSpec(
        "Textures", DialogSpec.Mode.OpenFiles, multiple = true, start = Path.of("/home/me/.minecraft"),
        filterName = "Packs", extensions = listOf("jar", "zip"),
    )
    private val folder = DialogSpec("World", DialogSpec.Mode.OpenFolder, start = Path.of("/home/me/.minecraft/saves"))
    private val save = DialogSpec("Save", DialogSpec.Mode.Save, start = Path.of("/home/me"), fileName = "a.csv", filterName = "CSV", extensions = listOf("csv"))

    private fun installed(vararg names: String): List<Path> {
        val dir = Files.createTempDirectory("bin")
        for (name in names) dir.resolve(name).also { Files.createFile(it) }.toFile().setExecutable(true)
        return listOf(dir)
    }

    @Test
    fun `kdialog opens several files in a folder that starts with a dot`() {
        assertEquals(
            listOf("kdialog", "--title", "Textures", "--multiple", "--separate-output", "--getopenfilename", "/home/me/.minecraft/", "*.jar *.zip|Packs"),
            NativeDialogs.kdialogCommand(textures),
        )
    }

    @Test
    fun `kdialog picks a folder and saves a named file`() {
        assertEquals(listOf("kdialog", "--title", "World", "--getexistingdirectory", "/home/me/.minecraft/saves/"), NativeDialogs.kdialogCommand(folder))
        assertEquals(listOf("kdialog", "--title", "Save", "--getsavefilename", "/home/me/a.csv", "*.csv|CSV"), NativeDialogs.kdialogCommand(save))
    }

    @Test
    fun `zenity asks for the same things`() {
        val files = NativeDialogs.gtkCommand(textures, "zenity")
        assertEquals(listOf("zenity", "--file-selection", "--title=Textures", "--multiple", "--separator=\n", "--filename=/home/me/.minecraft/"), files.take(6))
        assertTrue("--file-filter=Packs | *.jar *.JAR *.zip *.ZIP" in files)
        assertTrue("--directory" in NativeDialogs.gtkCommand(folder, "zenity"))
        val saving = NativeDialogs.gtkCommand(save, "zenity")
        assertTrue("--save" in saving && "--filename=/home/me/a.csv" in saving)
    }

    @Test
    fun `on KDE the KDE dialog comes first and a missing program is skipped`() {
        val all = NativeDialogs.linuxCommands(folder, "KDE", installed("kdialog", "zenity"))
        assertEquals(listOf("kdialog", "zenity"), all.map { it.first() })
        assertEquals(listOf("zenity"), NativeDialogs.linuxCommands(folder, "KDE", installed("zenity")).map { it.first() })
        assertEquals(listOf("zenity", "kdialog"), NativeDialogs.linuxCommands(folder, "GNOME", installed("kdialog", "zenity")).map { it.first() })
        assertTrue(NativeDialogs.linuxCommands(folder, "KDE", installed()).isEmpty())
    }

    @Test
    fun `chosen paths are read one per line`() {
        assertEquals(listOf(Path.of("/a/b"), Path.of("/c/.d")), NativeDialogs.parseLines("/a/b\n/c/.d\n\n"))
    }
}
