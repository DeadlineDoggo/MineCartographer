package minecartographer.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import minecartographer.AppInfo
import minecartographer.ui.drawAppIcon
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the app icon (see `AppIcon.kt`) to the files packaging needs:
 * `icons/app.png`, `icons/app.ico` and the JVM splash `resources/common/splash.png`.
 *
 * Run with `./gradlew :app:generateIcons` after changing the icon.
 */
fun main(args: Array<String>) {
    val appDir = File(args.firstOrNull() ?: "app")
    val icons = File(appDir, "icons").apply { mkdirs() }
    File(icons, "app.png").writeBytes(renderIcon(512))
    File(icons, "app.ico").writeBytes(ico(listOf(16, 24, 32, 48, 64, 128, 256).map { it to renderIcon(it) }))
    File(appDir, "resources/common").apply { mkdirs() }.resolve("splash.png").writeBytes(renderSplash())
    println("Icons written to ${icons.absolutePath}")
}

private fun bitmap(width: Int, height: Int, draw: DrawScope.(Canvas) -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) { draw(canvas) }
    return bitmap
}

private fun ImageBitmap.png(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes

private fun renderIcon(px: Int) = bitmap(px, px) { drawAppIcon() }.png()

/** Shown by the JVM (`-splash:`) the moment the app starts, before the UI toolkit is loaded. */
private fun renderSplash(): ByteArray {
    val icon = bitmap(160, 160) { drawAppIcon() }
    return bitmap(560, 300) { canvas -> drawSplash(canvas, icon) }.png()
}

private fun DrawScope.drawSplash(canvas: Canvas, icon: ImageBitmap) {
    drawRect(Color(0xFF1E1F22))
    drawRect(Color(0xFF393B40), style = Stroke(2f))
    drawImage(icon, topLeft = Offset(48f, 70f))
    val sk = canvas.nativeCanvas
    val typeface = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD) ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
    val regular = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.NORMAL) ?: FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL)
    sk.drawString("MineCartographer", 236f, 140f, Font(typeface, 34f), Paint().apply { color = 0xFFDFE1E5.toInt() })
    sk.drawString("Merge worlds chunk by chunk", 238f, 174f, Font(regular, 16f), Paint().apply { color = 0xFF9A9DA5.toInt() })
    sk.drawString("${AppInfo.versionLabel} · Starting…", 238f, 236f, Font(regular, 14f), Paint().apply { color = 0xFF6C707A.toInt() })
}

/** Windows .ico containing PNG-compressed images (supported since Windows Vista). */
private fun ico(images: List<Pair<Int, ByteArray>>): ByteArray {
    val header = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0).putShort(1).putShort(images.size.toShort())
    var offset = header.capacity()
    for ((px, png) in images) {
        val dim = if (px >= 256) 0 else px
        header.put(dim.toByte()).put(dim.toByte()).put(0).put(0)
        header.putShort(1).putShort(32).putInt(png.size).putInt(offset)
        offset += png.size
    }
    return ByteArrayOutputStream().apply {
        write(header.array())
        images.forEach { write(it.second) }
    }.toByteArray()
}
