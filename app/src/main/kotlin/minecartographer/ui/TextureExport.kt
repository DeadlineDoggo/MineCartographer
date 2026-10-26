package minecartographer.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minecartographer.editor.EditorState
import minecartographer.textures.BlockModels
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/** Saving textures out of the loaded packs and mods as PNG files. */
object TextureExport {
    /** The size texture exports are enlarged to by "Export enlarged": 16 px textures come out as 256 px. */
    const val ENLARGED = 16

    /**
     * The texture as a PNG: the file as the pack has it at scale 1 (an animation stays a strip), otherwise its first
     * frame enlarged [scale] times with sharp pixels. Null when it can't be read.
     */
    fun png(models: BlockModels, id: String, scale: Int = 1): ByteArray? {
        if (scale == 1 && '#' !in id) models.textureBytes(id)?.takeIf { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() != null }?.let { return it }
        val t = models.texture(id) ?: return null
        val pixels = t.levelData.first()
        val size = t.size * scale
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, straight(pixels[(y / scale) * t.size + x / scale]))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Textures hold premultiplied colors; PNG wants straight ones. */
    private fun straight(c: Int): Int {
        val a = c ushr 24
        if (a == 0 || a == 255) return c
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * 255 / a).coerceAtMost(255)
        return (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** `block/barley` of `biomesoplenty:block/barley`, with `_2` for the third frame of a `#2` id. */
    private fun baseName(id: String) = id.substringBefore('#').substringAfter(':') + id.substringAfter('#', "").let { if (it.isEmpty()) "" else "_$it" }

    fun fileName(id: String, scale: Int = 1) = baseName(id).substringAfterLast('/') + (if (scale > 1) "_x$scale" else "") + ".png"

    /** Where the texture sits in a resource pack: `assets/biomesoplenty/textures/block/barley.png`. */
    fun packPath(id: String) = "assets/${if (':' in id) id.substringBefore(':') else "minecraft"}/textures/${baseName(id)}.png"

    /** Asks for a file and saves one texture to it. */
    fun exportOne(state: EditorState, models: BlockModels, id: String, scale: Int = 1) {
        val bytes = png(models, id, scale) ?: return state.notify("Could not read the texture $id", warning = true)
        val file = FileDialogs.chooseFileToSave("Export texture", "png", "PNG images", fileName(id, scale)) ?: return
        runCatching { file.writeBytes(bytes) }
            .onSuccess { state.notify("Saved ${file.fileName}") }
            .onFailure { state.notify("Could not save ${file.fileName}: ${it.message}", warning = true) }
    }

    /** Asks for a folder and saves [ids] into it laid out like a resource pack, in the background. */
    fun exportMany(state: EditorState, scope: CoroutineScope, models: BlockModels, ids: List<String>) {
        val folder = FileDialogs.chooseFolder("Export ${ids.size} textures into a folder (as a resource pack: assets/<mod>/textures/...)", null) ?: return
        scope.launch(Dispatchers.IO) {
            var saved = 0
            var failed = 0
            for (id in ids) {
                val bytes = png(models, id)
                if (bytes == null) { failed++; continue }
                runCatching {
                    val file = folder.resolve(packPath(id))
                    file.parent.createDirectories()
                    file.writeBytes(bytes)
                }.onSuccess { saved++ }.onFailure { failed++ }
            }
            withContext(Dispatchers.Main) {
                state.notify("Saved $saved textures to $folder" + if (failed > 0) ", $failed failed" else "", warning = failed > 0)
            }
        }
    }
}
