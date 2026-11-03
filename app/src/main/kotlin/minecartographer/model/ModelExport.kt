package minecartographer.model

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

/** How the files are laid out on disk. */
enum class ExportLayout(val label: String) {
    /** `pack.mcmeta` and `assets/<namespace>/...`: a resource pack the game can load as it is. */
    ResourcePack("Resource pack"),

    /** The model next to a `textures` folder, for taking the parts elsewhere. */
    LooseFiles("Model and textures"),
}

data class ExportOptions(
    val namespace: String,
    /** Model name, may hold folders (`furniture/chair`). */
    val name: String,
    val layout: ExportLayout = ExportLayout.ResourcePack,
    /** Write a zip instead of a folder (resource pack only). */
    val zip: Boolean = false,
    /** Copy the textures the model uses out of the packs and mods. */
    val textures: Boolean = true,
    /** Move the copied textures into [namespace], so they can't clash with those of their mods. */
    val ownNamespace: Boolean = false,
    /** Leave out `minecraft:` textures: the game has them, a copy would only override them with the same pixels. */
    val skipVanilla: Boolean = true,
    val packFormat: Int = 15,
    val description: String = "",
)

class PlannedFile(val path: String, val bytes: ByteArray)

/** What an export would write: [files] by path inside the target, and the textures that could not be read. */
class ExportPlan(val files: List<PlannedFile>, val missing: List<String>, val textureCount: Int) {
    val size get() = files.sumOf { it.bytes.size.toLong() }
}

object ModelExport {
    private val NAMESPACE = Regex("[a-z0-9_.-]+")
    private val NAME = Regex("[a-z0-9_.-]+(/[a-z0-9_.-]+)*")

    /** Why [o] can't be exported, in words for the user; empty when it can. */
    fun problems(o: ExportOptions, doc: ModelDoc): List<String> = buildList {
        if (doc.elements.isEmpty()) add("The model has no elements.")
        if (!NAMESPACE.matches(o.namespace)) add("The namespace may only have lowercase letters, digits, _ . and -.")
        if (!NAME.matches(o.name) || o.name.split('/').any { it == "." || it == ".." }) add("The model name may only have lowercase letters, digits, _ . - and / between folders.")
        if (o.layout == ExportLayout.ResourcePack && o.packFormat < 1) add("The pack format must be a positive number.")
        if (o.ownNamespace && !o.textures) add("Moving textures into the namespace needs them copied.")
    }

    /** Game versions and the `pack_format` each reads, newest first, for the export window to offer. */
    val PACK_VERSIONS = listOf(
        "1.21 – 1.21.1" to 34,
        "1.20.5 – 1.20.6" to 32,
        "1.20.3 – 1.20.4" to 22,
        "1.20.2" to 18,
        "1.20 – 1.20.1" to 15,
        "1.19.4" to 13,
        "1.19.3" to 12,
        "1.19 – 1.19.2" to 9,
        "1.18 – 1.18.2" to 8,
        "1.17 – 1.17.1" to 7,
        "1.16.2 – 1.16.5" to 6,
        "1.15 – 1.16.1" to 5,
        "1.13 – 1.14.4" to 4,
    )

    /** The resource pack format the game of [dataVersion] reads; 15 (1.20.1) when the world doesn't say. */
    fun packFormatFor(dataVersion: Int?): Int = when {
        dataVersion == null -> 15
        dataVersion >= 3953 -> 34 // 1.21
        dataVersion >= 3837 -> 32 // 1.20.5
        dataVersion >= 3700 -> 22 // 1.20.3
        dataVersion >= 3578 -> 18 // 1.20.2
        dataVersion >= 3463 -> 15 // 1.20
        dataVersion >= 3337 -> 13 // 1.19.4
        dataVersion >= 3218 -> 12 // 1.19.3
        dataVersion >= 2975 -> 9 // 1.19
        dataVersion >= 2860 -> 8 // 1.18
        dataVersion >= 2724 -> 7 // 1.17
        dataVersion >= 2578 -> 6 // 1.16.2
        dataVersion >= 2225 -> 5 // 1.15
        else -> 4 // 1.13
    }

    /** `ns:path` of a texture reference as written in the model (`block/stone` means `minecraft:block/stone`). */
    private fun normalize(ref: String): String {
        val id = ref.substringBefore('#')
        return if (':' in id) id else "minecraft:$id"
    }

    /** Every texture the model uses, by id (`ns:path`): on its faces, and its variables that are not references. */
    fun textureIds(doc: ModelDoc): List<String> {
        val used = LinkedHashSet<String>()
        for (e in doc.elements) for (f in e.faces.values) doc.resolve(f.texture)?.let { used += normalize(it) }
        for (value in doc.textures.values) if (!value.startsWith("#")) used += normalize(value)
        return used.toList()
    }

    /**
     * What writing [doc] with [o] would put on disk. [read] gives the bytes of an asset by its path in the packs
     * (`assets/<ns>/textures/<path>.png`), or null when there is none.
     */
    fun plan(doc: ModelDoc, o: ExportOptions, read: (String) -> ByteArray?): ExportPlan {
        val files = ArrayList<PlannedFile>()
        val missing = ArrayList<String>()
        val copied = LinkedHashMap<String, String>() // source id -> id in the export
        if (o.textures) {
            val taken = HashSet<String>()
            for (id in textureIds(doc)) {
                if (o.skipVanilla && id.startsWith("minecraft:")) continue
                copied[id] = if (o.ownNamespace) own(o.namespace, id, taken) else id.also { taken += it }
            }
        }
        // The model refers to the textures by their new ids.
        fun rename(ref: String?): String? {
            if (ref == null || ref.startsWith("#")) return ref
            return copied[normalize(ref)]?.takeIf { it != normalize(ref) } ?: ref
        }
        val out = doc.copy(
            textures = doc.textures.mapValues { (_, v) -> rename(v) ?: v },
            elements = doc.elements.map { e -> e.copy(faces = e.faces.mapValues { (_, f) -> f.copy(texture = rename(f.texture)) }) },
        )
        val json = out.toJson().toByteArray()
        val prefix = if (o.layout == ExportLayout.ResourcePack) "assets/${o.namespace}/models/block/" else ""
        files += PlannedFile("$prefix${o.name}.json", json)
        if (o.layout == ExportLayout.ResourcePack) {
            val meta = """{ "pack": { "pack_format": ${o.packFormat}, "description": ${quote(o.description.ifBlank { "${o.name} model" })} } }""" + "\n"
            files.add(0, PlannedFile("pack.mcmeta", meta.toByteArray()))
        }
        var textures = 0
        for ((source, target) in copied) {
            val (sns, spath) = source.split(':', limit = 2)
            val (tns, tpath) = target.split(':', limit = 2)
            val asset = "assets/$sns/textures/$spath.png"
            val png = read(asset)
            if (png == null) {
                missing += source
                continue
            }
            val dir = if (o.layout == ExportLayout.ResourcePack) "assets/$tns/textures/" else "textures/$tns/"
            files += PlannedFile("$dir$tpath.png", png)
            // An animation's timing lives next to the strip.
            read("$asset.mcmeta")?.let { files += PlannedFile("$dir$tpath.png.mcmeta", it) }
            textures++
        }
        return ExportPlan(files, missing, textures)
    }

    /** The id a texture gets in [namespace]: its last path segment in `block/` (or `blocks/` as before 1.13), made unique. */
    private fun own(namespace: String, id: String, taken: MutableSet<String>): String {
        val path = id.substringAfter(':')
        val folder = if (path.startsWith("blocks/")) "blocks" else "block"
        val base = path.substringAfterLast('/')
        var candidate = "$namespace:$folder/$base"
        var n = 2
        while (!taken.add(candidate)) candidate = "$namespace:$folder/${base}_${n++}"
        return candidate
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The files of [plan] that already exist in the folder [target]. */
    fun existing(plan: ExportPlan, target: Path): List<String> = plan.files.map { it.path }.filter { target.resolve(it).exists() }

    /** Writes [plan] into the folder [target]; returns how many files were written. */
    fun writeFolder(plan: ExportPlan, target: Path): Int {
        for (file in plan.files) {
            val path = target.resolve(file.path).normalize()
            check(path.startsWith(target.normalize())) { "refusing to write outside ${target}: ${file.path}" }
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
        }
        return plan.files.size
    }

    /** Writes [plan] as the zip [target]; returns how many files were written. */
    fun writeZip(plan: ExportPlan, target: Path): Int {
        target.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(target).use { writeZip(plan, it) }
        return plan.files.size
    }

    fun writeZip(plan: ExportPlan, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (file in plan.files) {
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
    }
}
