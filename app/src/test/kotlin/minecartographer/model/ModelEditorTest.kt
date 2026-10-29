package minecartographer.model

import minecartographer.textures.AssetSource
import minecartographer.textures.BlockModels
import minecartographer.textures.Dir
import minecartographer.textures.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelEditorTest {
    private val dir: Path = Files.createTempDirectory("model-editor")

    private fun zip(name: String, vararg entries: Pair<String, String>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(path.outputStream()).use { out ->
            for ((entry, content) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    private val table = """
        { "parent": "block/block", "textures": { "top": "block/planks", "particle": "#top" },
          "elements": [
            { "name": "top", "from": [0, 14, 0], "to": [16, 16, 16], "faces": { "up": { "texture": "#top", "uv": [0, 0, 16, 16], "cullface": "up" }, "south": { "texture": "#top", "tintindex": 0 } } },
            { "from": [6, 0, 6], "to": [10, 14, 10], "rotation": { "origin": [8, 7, 8], "axis": "y", "angle": 22.5, "rescale": true }, "shade": false, "faces": { "north": { "texture": "#top", "rotation": 90 } } }
          ] }
    """.trimIndent()

    private fun tableDoc(): ModelDoc {
        val mod = zip(
            "mod.jar",
            "assets/m/models/block/table.json" to table,
            "assets/m/models/block/block.json" to """{ "textures": { "particle": "block/stone" } }""",
            "assets/m/models/item/table.json" to "{}",
        )
        AssetSource(listOf(mod)).use { assets ->
            val models = BlockModels(assets)
            assertEquals(listOf("m:block/block", "m:block/table"), assets.modelIds)
            return ModelDoc.from(assertNotNull(models.sourceModel("m:block/table")))
        }
    }

    @Test
    fun `a model from the assets becomes a document with its parts intact`() {
        val doc = tableDoc()
        assertEquals(2, doc.elements.size)
        assertEquals("top", doc.elements[0].name)
        assertEquals("element 2", doc.elements[1].name)
        assertEquals(mapOf("top" to "block/planks", "particle" to "#top"), doc.textures)
        val leg = doc.elements[1]
        assertEquals(Vec3(6f, 0f, 6f), leg.from)
        assertEquals(ElementRotation(1, 22.5f, Vec3(8f, 7f, 8f), true), leg.rotation)
        assertFalse(leg.shade)
        assertEquals(90, leg.faces.getValue(Dir.NORTH).rotation)
        val top = doc.elements[0].faces
        assertEquals(Uv(0f, 0f, 16f, 16f), top.getValue(Dir.UP).uv)
        assertEquals(Dir.UP, top.getValue(Dir.UP).cull)
        assertTrue(top.getValue(Dir.SOUTH).tint)
        assertEquals("block/planks", doc.resolve("#particle"), "variables follow references")
        assertNull(doc.resolve("#missing"))
    }

    @Test
    fun `the JSON written is the JSON read`() {
        val doc = tableDoc()
        val written = Json.parse(doc.toJson()) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val back = ModelDoc.from(minecartographer.textures.SourceModel("x", written["textures"] as Map<String, String>, written["elements"] as List<Map<String, Any?>>))
        assertEquals(doc.elements.map { it.copy(id = 0) }, back.elements.map { it.copy(id = 0) })
        assertEquals(doc.textures, back.textures)
    }

    @Test
    fun `mirroring swaps the sides and keeps the model inside the block`() {
        val doc = ModelDoc(textures = mapOf("t" to "x")).withCube().first
        val e = doc.elements.single().copy(from = Vec3(1f, 0f, 0f), to = Vec3(5f, 8f, 4f), faces = mapOf(Dir.EAST to ModelFace("#t", cull = Dir.EAST)))
        val mirrored = ModelDoc(listOf(e), doc.textures, 2).mirror(setOf(e.id), 0).elements.single()
        assertEquals(Vec3(11f, 0f, 0f), mirrored.from)
        assertEquals(Vec3(15f, 8f, 4f), mirrored.to)
        assertEquals(setOf(Dir.WEST), mirrored.faces.keys)
        assertEquals(Dir.WEST, mirrored.faces.getValue(Dir.WEST).cull)
    }

    @Test
    fun `a drag is one undo step and keeps the limits`() {
        val model = ModelEditorState(1, "m")
        model.addCube()
        val id = model.selection.single()
        model.beginGesture()
        model.dragMove(0, 2.4f)
        model.dragMove(0, 3.2f)
        model.endGesture("Move")
        assertEquals(7f, model.doc.element(id)!!.from.x, "snapped to whole units: 4 + 3")
        assertEquals("Move", model.undoLabel)
        model.undo()
        assertEquals(4f, model.doc.element(id)!!.from.x)
        model.redo()
        assertEquals(7f, model.doc.element(id)!!.from.x)

        // Far beyond the limits: the element stops at 32 without being squashed.
        model.beginGesture()
        model.dragMove(0, 500f)
        model.endGesture("Move")
        val e = model.doc.element(id)!!
        assertEquals(32f, e.to.x)
        assertEquals(8f, e.size.x)
    }

    @Test
    fun `resizing pushes one side and rotation steps by 22 and a half degrees`() {
        val model = ModelEditorState(1, "m")
        model.addCube()
        val id = model.selection.single()
        model.beginGesture()
        model.dragResize(Dir.UP, 3f)
        model.endGesture("Resize")
        assertEquals(11f, model.doc.element(id)!!.to.y)
        assertEquals(0f, model.doc.element(id)!!.from.y)
        model.beginGesture()
        model.dragResize(Dir.DOWN, 50f)
        model.endGesture("Resize")
        assertEquals(11f, model.doc.element(id)!!.from.y, "a side can't pass the other one")

        model.beginGesture()
        model.dragRotate(2, 40f)
        model.endGesture("Rotate")
        val r = model.doc.element(id)!!.rotation!!
        assertEquals(2, r.axis)
        assertEquals(45f, r.angle)
        assertEquals(model.doc.element(id)!!.center, r.origin)
    }

    @Test
    fun `an unchanged drag leaves no undo step`() {
        val model = ModelEditorState(1, "m")
        model.addCube()
        model.beginGesture()
        model.dragMove(0, 0.2f)
        model.endGesture("Move")
        assertEquals("Add cube", model.undoLabel)
    }

    @Test
    fun `a click in the view picks the element and face under it`() {
        val cube = ModelDoc().withCube(null).first.elements.single().copy(from = Vec3(0f, 0f, 0f), to = Vec3(16f, 16f, 16f))
        val doc = ModelDoc(listOf(cube), nextId = 2)
        val quads = ModelRenderer.quads(doc, emptySet()) { null }
        assertEquals(6, quads.size)
        // Small enough that the cube leaves the corners of the view empty.
        val camera = ModelCamera.FRONT.copy(zoom = 6f)
        val frame = ModelRenderer.render(quads, camera, 200, 200)
        // Straight on from the south, the middle of the cube is its south face.
        assertEquals(doc.elements.single().id to Dir.SOUTH, frame.pick(100, 100))
        assertNull(frame.pick(2, 2))
        val above = ModelRenderer.render(quads, ModelCamera.TOP.copy(zoom = 6f), 200, 200)
        assertEquals(doc.elements.single().id to Dir.UP, above.pick(100, 100))
        // Hidden elements are neither drawn nor picked.
        val none = ModelRenderer.render(ModelRenderer.quads(doc, setOf(doc.elements.single().id)) { null }, camera, 200, 200)
        assertNull(none.pick(100, 100))
    }

    @Test
    fun `the nearer element wins where two overlap`() {
        var doc = ModelDoc()
        doc = doc.withCube(null).first
        val back = doc.elements.single().copy(from = Vec3(0f, 0f, 0f), to = Vec3(16f, 16f, 2f))
        val front = back.copy(id = 2, from = Vec3(0f, 0f, 14f), to = Vec3(16f, 16f, 16f))
        val frame = ModelRenderer.render(ModelRenderer.quads(ModelDoc(listOf(back, front), nextId = 3), emptySet()) { null }, ModelCamera.FRONT, 200, 200)
        assertEquals(2 to Dir.SOUTH, frame.pick(100, 100))
        val behind = ModelRenderer.render(ModelRenderer.quads(ModelDoc(listOf(back, front), nextId = 3), emptySet()) { null }, ModelCamera(yaw = 180f, pitch = 0f), 200, 200)
        assertEquals(back.id to Dir.NORTH, behind.pick(100, 100))
    }

    @Test
    fun `renaming a texture variable follows its faces`() {
        val model = ModelEditorState(1, "m")
        model.addTextureVariable()
        model.addCube()
        val variable = model.doc.textures.keys.single()
        // The cube was added after the variable existed, so its faces use it.
        assertTrue(model.doc.elements.single().faces.values.all { it.texture == "#$variable" })
        model.renameTexture(variable, "#top")
        assertEquals(setOf("top"), model.doc.textures.keys)
        assertTrue(model.doc.elements.single().faces.values.all { it.texture == "#top" })
        model.removeTextureVariable("top")
        assertTrue(model.doc.elements.single().faces.values.all { it.texture == null })
        model.undo()
        assertTrue(model.doc.elements.single().faces.values.all { it.texture == "#top" })
    }
}

/**
 * Opens every block model of real asset sets as the editor does and draws it. Prints how many models have
 * elements and how many faces end up without a texture. Skipped without a client jar; the modded sets need
 * MINECARTOGRAPHER_WORLDS.
 */
class RealModelsTest {
    private fun measure(label: String, files: List<Path>) {
        AssetSource(files).use { assets ->
            val models = BlockModels(assets)
            var withElements = 0
            var failed = 0
            var faces = 0
            var untextured = 0
            val broken = ArrayList<String>()
            val start = System.nanoTime()
            for (id in assets.modelIds) {
                try {
                    val source = models.sourceModel(id) ?: continue
                    val doc = ModelDoc.from(source)
                    if (doc.elements.isEmpty()) continue
                    withElements++
                    val quads = ModelRenderer.quads(doc, emptySet()) { models.texture(it) }
                    ModelRenderer.render(quads, ModelCamera.ISO, 96, 96)
                    faces += quads.size
                    untextured += quads.count { it.quad.texture == null }
                    // Writing it out must be readable again.
                    check(Json.parse(doc.toJson()) is Map<*, *>)
                } catch (e: Exception) {
                    failed++
                    if (broken.size < 5) broken += "$id: $e"
                }
            }
            println("$label: ${assets.modelIds.size} models, $withElements with elements, $failed failed, $faces faces, $untextured without texture, " +
                "${(System.nanoTime() - start) / 1_000_000} ms; ${broken.joinToString()}")
            kotlin.test.assertEquals(0, failed, "models that failed in $label: $broken")
        }
    }

    @Test
    fun `every model of the newest client jar opens and draws`() {
        val jar = minecartographer.textures.TextureFiles.findClientJar() ?: return
        measure(jar.fileName.toString(), listOf(jar))
    }
}
