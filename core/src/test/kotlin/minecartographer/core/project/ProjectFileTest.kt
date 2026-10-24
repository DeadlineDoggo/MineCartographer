package minecartographer.core.project

import minecartographer.core.ChunkPos
import minecartographer.core.DemoWorld
import minecartographer.core.WorldSource
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.LayerStack
import org.w3c.dom.Element
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectFileTest {
    private val valley = DemoWorld("Valley", seed = 1, radiusChunks = 8)
    private val hills = DemoWorld("Hills", seed = 2, radiusChunks = 8)
    private val dir = Files.createTempDirectory("mc-project")
    private val file = dir.resolve("test.${ProjectFile.EXTENSION}")

    private fun edited(): LayerStack {
        val pasted = mapOf(ChunkPos(3, 3) to ChunkRef(hills, ChunkPos(0, 0)), ChunkPos(4, 3) to ChunkRef(hills, ChunkPos(1, 0)))
        return LayerStack.of(valley)
            .addLayer("Pasted: Hills <&\">", pasted)
            .let { it.setLocked(it.activeId, true) }
            .addLayer("Hidden")
            .let { it.setVisible(it.activeId, false) }
            .clear(0, listOf(ChunkPos(-1, -1)))
            .withActive(1)
            .let { it.update(0) { base -> (base as BaseLayer).withOverrides(mapOf(ChunkPos(0, 0) to ChunkEdit.Place(ChunkRef(hills, ChunkPos(5, 5))))) } }
    }

    @Test
    fun `layers, selection and settings survive a round trip`() {
        val stack = edited()
        val snapshot = ProjectSnapshot(
            stacks = listOf(stack, LayerStack.of(hills)),
            activeWorld = valley,
            selection = setOf(ChunkPos(3, 3), ChunkPos(-2, 7)),
            editor = mapOf("render-mode" to "Blocks", "zoom" to "1.5"),
        )
        ProjectFile.write(file, snapshot, "test")

        val doc = ProjectFile.read(file)
        assertEquals(listOf(WorldLocation.Demo("Valley", 1, 8), WorldLocation.Demo("Hills", 2, 8)), doc.worlds)
        assertEquals(0, doc.activeWorld)
        assertEquals(snapshot.selection, doc.selection)
        assertEquals(snapshot.editor, doc.editor)

        val restored = doc.stacks(listOf(valley, hills))
        assertEquals(0, restored.missingChunks)
        assertEquals(listOf(stack, LayerStack.of(hills)), restored.stacks)
    }

    @Test
    fun `chunks from a world that could not be opened are dropped`() {
        ProjectFile.write(file, ProjectSnapshot(listOf(edited(), LayerStack.of(hills)), valley, emptySet(), emptyMap()), "test")
        val restored = ProjectFile.read(file).stacks(listOf(valley, null))
        val stack = restored.stacks.single()
        assertEquals(3, restored.missingChunks)
        assertTrue((stack.layer(1) as ChunkLayer).chunks.isEmpty())
        assertEquals(1, stack.base.overrides.size, "the erase stays, the place from Hills is gone")
    }

    @Test
    fun `folder worlds are stored relative to the project`() {
        Files.writeString(file, """
            <minecartographer-project format="1">
              <worlds active="a">
                <world id="a" kind="folder" path="saves/My World" absolute-path="Z:/nowhere/My World"/>
              </worlds>
            </minecartographer-project>
        """.trimIndent())
        Files.createDirectories(dir.resolve("saves/My World"))
        assertEquals(listOf(WorldLocation.Folder(dir.resolve("saves/My World").normalize())), ProjectFile.read(file).worlds)
    }

    @Test
    fun `other files and newer formats are refused`() {
        Files.writeString(file, "<html/>")
        assertFailsWith<IllegalArgumentException> { ProjectFile.read(file) }
        Files.writeString(file, "<minecartographer-project format=\"99\"/>")
        assertFailsWith<IllegalArgumentException> { ProjectFile.read(file) }
        Files.writeString(file, "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><minecartographer-project>&e;</minecartographer-project>")
        assertFailsWith<Exception> { ProjectFile.read(file) }
    }

    @Test
    fun `textures are kept per world, with the core jar`() {
        Files.createDirectories(dir.resolve("packs"))
        val pack = Files.createFile(dir.resolve("packs/Faithful.zip")).toAbsolutePath()
        val jar = dir.resolve("1.20.1.jar").toAbsolutePath() // need not exist: it is only a reference
        val blocks = mapOf("extrabiomesxl:id_2204[meta=3]" to "extrabiomesxl:leavesfirfancy")
        val textures = mapOf<WorldSource, WorldTextures>(hills to WorldTextures(listOf(pack), coreJar = jar, blocks = blocks), valley to WorldTextures())
        ProjectFile.write(file, ProjectSnapshot(listOf(LayerStack.of(valley), LayerStack.of(hills)), valley, emptySet(), emptyMap(), textures), "test")

        assertEquals(listOf(WorldTextures(), WorldTextures(listOf(pack), jar, blocks = blocks)), ProjectFile.read(file).textures)
        assertTrue("path=\"packs/Faithful.zip\"" in file.readText(), "stored relative to the project too")
    }

    @Test
    fun `an export lists where its chunks and textures come from`() {
        val jar = dir.resolve("1.20.1.jar").toAbsolutePath()
        ProjectFile.writeSources(dir, edited(), mapOf(hills to WorldTextures(coreJar = jar)), "test")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dir.resolve(ProjectFile.SOURCES_FILE).toFile()).documentElement
        val worlds = root.getElementsByTagName("world").let { list -> (0 until list.length).map { list.item(it) as Element } }.associateBy { it.getAttribute("name") }
        assertEquals("true", worlds.getValue("Valley").getAttribute("base"))
        // Valley is 16 x 16 chunks, minus the erased one and the one replaced from Hills (the pasted two lie on top of Valley too).
        assertEquals((16 * 16 - 1 - 1 - 2).toString(), worlds.getValue("Valley").getAttribute("chunks"))
        assertEquals("3", worlds.getValue("Hills").getAttribute("chunks"))
        assertEquals(jar.toString(), (worlds.getValue("Hills").getElementsByTagName("core-jar").item(0) as Element).getAttribute("absolute-path"))
    }

    @Test
    fun `the file is readable XML`() {
        ProjectFile.write(file, ProjectSnapshot(listOf(edited()), valley, emptySet(), emptyMap()), "test")
        val text = file.readText()
        assertTrue("<layer" in text && "Pasted: Hills &lt;&amp;" in text, text)
    }
}
