package minecartographer.textures

import minecartographer.core.BlockState
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackCacheTest {
    private val dir = Files.createTempDirectory("pack-cache")
    private val root = dir.resolve("cache")

    @AfterTest
    fun forget() = PackCache.forgetOpen()

    private fun png(rgb: Int): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, rgb or (0xFF shl 24))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun zip(name: String, vararg entries: Pair<String, Any>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(path.outputStream()).use { out ->
            for ((entry, content) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(if (content is ByteArray) content else content.toString().toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `what was computed comes back from disk in a new session`() {
        val mod = zip("mod.jar", "assets/m/textures/block/a.png" to png(0x336699))
        val cache = PackCache.of(listOf(mod), root)
        cache.putIndex(mapOf("assets/m/textures/block/a.png" to 0))
        cache.putLook("m:ore[meta=2]", BlockColors.solidLook(0x404070))
        cache.putLook("m:nothing", null)
        cache.putVariant("m:ore[meta=2]", "type=lead")
        cache.putTexture("m:block/a", Texture.decode(png(0x336699))!!)
        cache.save()
        assertTrue(cache.dir.resolve("pack.xml").exists() && cache.dir.resolve("catalog.bin").exists())

        PackCache.forgetOpen()
        val again = PackCache.of(listOf(mod), root)
        assertNotSame(cache, again)
        assertEquals(mapOf("assets/m/textures/block/a.png" to 0), again.index)
        assertEquals(0x404070, again.looks["m:ore[meta=2]"]!!.color and 0xFFFFFF)
        assertEquals(BlockKind.SOLID, again.looks["m:ore[meta=2]"]!!.kind)
        assertSame(PackCache.NO_LOOK, again.looks["m:nothing"])
        assertEquals("type=lead", again.variants["m:ore[meta=2]"])
        assertEquals(0x336699, again.texture("m:block/a")?.averageRgb)
        assertTrue("absolute-path" in Files.readString(again.dir.resolve("pack.xml")), "references to the source files")
    }

    @Test
    fun `a changed file means a different cache`() {
        val mod = zip("mod.jar", "assets/m/a.json" to "{}")
        val before = PackCache.key(listOf(mod))
        Files.setLastModifiedTime(mod, FileTime.fromMillis(Files.getLastModifiedTime(mod).toMillis() + 60_000))
        assertNotEquals(before, PackCache.key(listOf(mod)))
        assertNotEquals(before, PackCache.key(listOf(mod, zip("other.zip", "assets/o/a.json" to "{}"))))
    }

    @Test
    fun `with a cached index no archive is opened until it is read`() {
        val gone = dir.resolve("gone.jar")
        val assets = AssetSource(listOf(gone), mapOf("assets/m/textures/block/a.png" to 0))
        assertTrue(assets.has("assets/m/textures/block/a.png"))
        assertEquals(setOf("m"), assets.namespaces)
        assertFailsWith<Exception> { assets.read("assets/m/textures/block/a.png") }
        assets.close()
    }

    @Test
    fun `block models use cached textures and variants instead of the archives`() {
        val mod = zip("mod.jar", "assets/m/textures/block/a.png" to png(0x336699))
        val cache = PackCache.of(listOf(mod), root)
        AssetSource(listOf(mod)).use { BlockModels(it, cache).texture("m:block/a") }
        assertNotNull(cache.texture("m:block/a"), "decoded once, then kept")

        mod.deleteExisting() // a new session with the archive gone still has the texture
        AssetSource(listOf(mod), mapOf("assets/m/textures/block/a.png" to 0)).use {
            assertEquals(0x336699, BlockModels(it, cache).texture("m:block/a")?.averageRgb)
        }
        assertNull(cache.texture("m:block/missing"))
    }

    @Test
    fun `unused caches are pruned`() {
        val mod = zip("mod.jar", "assets/m/a.json" to "{}")
        val cache = PackCache.of(listOf(mod), root)
        cache.putLook("m:a", BlockColors.solidLook(0x112233))
        cache.save()
        Files.setLastModifiedTime(cache.dir, FileTime.fromMillis(System.currentTimeMillis() - 90L * 24 * 3600 * 1000))
        PackCache.prune(root)
        assertTrue(!cache.dir.exists())
    }

    @Suppress("unused")
    private fun state(name: String) = BlockState.of(name)

    private fun assertNotSame(a: Any, b: Any) = assertTrue(a !== b)
}
