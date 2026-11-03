package minecartographer.model

import minecartographer.textures.AssetSource
import minecartographer.textures.BlockModels
import minecartographer.textures.TextureFiles
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelExportTest {
    private val down = 0
    private val up = 1
    private val north = 2

    private fun doc(): ModelDoc {
        val face = { t: String -> ModelFace(t) }
        val e = ModelElement(1, "box", Vec3(0f, 0f, 0f), Vec3(16f, 4f, 16f), faces = mapOf(up to face("#top"), down to face("#side"), north to face("tfc:block/direct")))
        return ModelDoc(
            listOf(e),
            mapOf("top" to "tfc:block/track/oak/top", "side" to "block/stone", "particle" to "#top"),
            2,
        )
    }

    private val assets = mapOf(
        "assets/tfc/textures/block/track/oak/top.png" to byteArrayOf(1, 2, 3),
        "assets/tfc/textures/block/track/oak/top.png.mcmeta" to "{}".toByteArray(),
        "assets/minecraft/textures/block/stone.png" to byteArrayOf(4),
        // tfc:block/direct is not in the packs
    )
    private val read = { path: String -> assets[path] }

    @Test
    fun `a resource pack holds the model, the pack file and the textures where their mods expect them`() {
        val plan = ModelExport.plan(doc(), ExportOptions("mymod", "furniture/table"), read)
        assertEquals(
            listOf("pack.mcmeta", "assets/mymod/models/block/furniture/table.json", "assets/tfc/textures/block/track/oak/top.png", "assets/tfc/textures/block/track/oak/top.png.mcmeta"),
            plan.files.map { it.path },
            "vanilla stone is skipped, the animation file comes along",
        )
        assertEquals(listOf("tfc:block/direct"), plan.missing)
        assertEquals(1, plan.textureCount)
        assertTrue(String(plan.files[0].bytes).contains("\"pack_format\": 15"))
        // The model still names the textures the way it did.
        assertTrue(String(plan.files[1].bytes).contains("tfc:block/track/oak/top"))
    }

    @Test
    fun `moving the textures into the namespace rewrites the ids and keeps clashes apart`() {
        val d = doc().copy(textures = doc().textures + ("other" to "ex:block/top"))
        val files = mapOf("assets/tfc/textures/block/track/oak/top.png" to byteArrayOf(1), "assets/ex/textures/block/top.png" to byteArrayOf(2))
        val plan = ModelExport.plan(d, ExportOptions("mymod", "t", ownNamespace = true, skipVanilla = false)) { files[it] ?: if (it.endsWith("stone.png")) byteArrayOf(3) else null }
        val paths = plan.files.map { it.path }
        assertTrue("assets/mymod/textures/block/top.png" in paths)
        assertTrue("assets/mymod/textures/block/top_2.png" in paths, "the second 'top' does not overwrite the first: $paths")
        assertTrue("assets/mymod/textures/block/stone.png" in paths, "vanilla too when not skipped")
        val model = String(plan.files.first { it.path.endsWith("t.json") }.bytes)
        assertTrue("mymod:block/top\"" in model, model)
        assertFalse("tfc:block/track" in model, "the old ids are gone: $model")
        assertEquals(1, plan.files.count { it.path == "assets/mymod/textures/block/top.png" })
    }

    @Test
    fun `loose files keep the textures beside the model and without copying nothing else is written`() {
        val loose = ModelExport.plan(doc(), ExportOptions("mymod", "t", layout = ExportLayout.LooseFiles), read)
        assertEquals(listOf("t.json", "textures/tfc/block/track/oak/top.png", "textures/tfc/block/track/oak/top.png.mcmeta"), loose.files.map { it.path })
        val none = ModelExport.plan(doc(), ExportOptions("mymod", "t", textures = false), read)
        assertEquals(listOf("pack.mcmeta", "assets/mymod/models/block/t.json"), none.files.map { it.path })
    }

    @Test
    fun `names that the game would not accept are refused`() {
        val d = doc()
        assertTrue(ModelExport.problems(ExportOptions("mymod", "ok/name_1"), d).isEmpty())
        assertFalse(ModelExport.problems(ExportOptions("My Mod", "t"), d).isEmpty())
        assertFalse(ModelExport.problems(ExportOptions("mymod", "../t"), d).isEmpty())
        assertFalse(ModelExport.problems(ExportOptions("mymod", "/t"), d).isEmpty())
        assertFalse(ModelExport.problems(ExportOptions("mymod", "t", packFormat = 0), d).isEmpty())
        assertFalse(ModelExport.problems(ExportOptions("mymod", "t"), ModelDoc()).isEmpty(), "no elements")
        assertFalse(ModelExport.problems(ExportOptions("mymod", "t", textures = false, ownNamespace = true), d).isEmpty())
        assertEquals(15, ModelExport.packFormatFor(3465))
        assertEquals(34, ModelExport.packFormatFor(3955))
        assertEquals(8, ModelExport.packFormatFor(2865))
    }

    @Test
    fun `a folder and a zip get the same files, and nothing is written outside the target`() {
        val dir = Files.createTempDirectory("export")
        val plan = ModelExport.plan(doc(), ExportOptions("mymod", "t"), read)
        assertEquals(emptyList(), ModelExport.existing(plan, dir))
        assertEquals(plan.files.size, ModelExport.writeFolder(plan, dir))
        assertEquals(plan.files.size, ModelExport.existing(plan, dir).size, "what was written is seen as existing")
        val zip = dir.resolve("pack.zip")
        ModelExport.writeZip(plan, zip)
        ZipFile(zip.toFile()).use { z -> assertEquals(plan.files.map { it.path }.sorted(), z.entries().toList().map { it.name }.sorted()) }
        val evil = ExportPlan(listOf(PlannedFile("../outside.txt", byteArrayOf(1))), emptyList(), 0)
        val result = runCatching { ModelExport.writeFolder(evil, dir.resolve("sub")) }
        assertTrue(result.isFailure)
        assertFalse(Files.exists(dir.resolve("outside.txt")))
    }
}

/** Exports a model of the newest client jar with its textures and opens the pack again: it must be the same model. */
class RealModelExportTest {
    @Test
    fun `an exported pack opens as the same model with the same textures`() {
        val jar = TextureFiles.findClientJar() ?: return
        val dir = Files.createTempDirectory("export-real")
        AssetSource(listOf(jar)).use { assets ->
            val models = BlockModels(assets)
            val original = ModelDoc.from(assertNotNull(models.sourceModel("minecraft:block/furnace")))
            val options = ExportOptions("mymod", "oven", ownNamespace = true, skipVanilla = false)
            val plan = ModelExport.plan(original, options) { assets.read(it) }
            assertTrue(plan.missing.isEmpty(), "missing ${plan.missing}")
            ModelExport.writeZip(plan, dir.resolve("oven.zip"))
            println("exported furnace: ${plan.files.size} files, ${plan.textureCount} textures, ${plan.size} bytes")

            AssetSource(listOf(dir.resolve("oven.zip"))).use { packed ->
                val reopened = ModelDoc.from(assertNotNull(BlockModels(packed).sourceModel("mymod:block/oven")))
                assertEquals(original.elements.map { it.copy(id = 0) }, reopened.elements.map { it.copy(id = 0) }, "same elements")
                // Every texture of the copy is there and has the pixels of the original.
                assertEquals(original.textures.keys, reopened.textures.keys)
                for ((variable, id) in reopened.textures.filterValues { !it.startsWith("#") }) {
                    val copy = packed.read("assets/${id.substringBefore(':')}/textures/${id.substringAfter(':')}.png")
                    assertNotNull(copy, "$variable -> $id")
                    val from = original.textures.getValue(variable)
                    val source = assets.read("assets/${from.substringBefore(':', "minecraft")}/textures/${from.substringAfter(':')}.png")
                    assertTrue(source != null && source.contentEquals(copy), "$variable: same bytes")
                }
            }
        }
    }
}
