package minecartographer.ui

import minecartographer.textures.AssetSource
import minecartographer.textures.BlockModels
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TextureExportTest {
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun models(vararg entries: Pair<String, ByteArray>): BlockModels {
        val jar = Files.createTempFile("textures", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        jar.toFile().deleteOnExit()
        return BlockModels(AssetSource(listOf(jar)))
    }

    @Test
    fun `a texture is exported as the file the pack has`() {
        val file = png(16, 16, 0xFF336699.toInt())
        val models = models("assets/mymod/textures/block/ore.png" to file)
        assertContentEquals(file, TextureExport.png(models, "mymod:block/ore"))
    }

    @Test
    fun `an enlarged export keeps the pixels sharp`() {
        val models = models("assets/mymod/textures/block/ore.png" to png(16, 16, 0xFF336699.toInt()))
        val image = ImageIO.read(ByteArrayInputStream(assertNotNull(TextureExport.png(models, "mymod:block/ore", scale = 4))))
        assertEquals(64, image.width)
        assertEquals(0xFF336699.toInt(), image.getRGB(63, 63))
    }

    @Test
    fun `one frame of a strip of variants is exported on its own`() {
        // Two 16 px variants side by side, the second one red.
        val strip = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 32) strip.setRGB(x, y, if (x < 16) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(strip, "png", it) }.toByteArray()
        val models = models("assets/railcraft/textures/blocks/brick.png" to bytes)
        val image = ImageIO.read(ByteArrayInputStream(assertNotNull(TextureExport.png(models, "railcraft:blocks/brick#1"))))
        assertEquals(16, image.width)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `names and pack paths`() {
        assertEquals("barley.png", TextureExport.fileName("biomesoplenty:block/barley"))
        assertEquals("brick_1_x16.png", TextureExport.fileName("railcraft:blocks/brick#1", 16))
        assertEquals("assets/biomesoplenty/textures/block/barley.png", TextureExport.packPath("biomesoplenty:block/barley"))
        assertEquals("assets/minecraft/textures/block/stone.png", TextureExport.packPath("block/stone"))
    }
}
