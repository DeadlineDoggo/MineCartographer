package minecartographer.core.project

import minecartographer.core.ChunkPos
import minecartographer.core.DemoWorld
import minecartographer.core.WorldSource
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.edit.BaseLayer
import minecartographer.core.edit.ChunkEdit
import minecartographer.core.edit.ChunkLayer
import minecartographer.core.edit.ChunkRef
import minecartographer.core.edit.Layer
import minecartographer.core.edit.LayerStack
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.exists
import kotlin.io.path.name

/** Where a project's world comes from, so it can be opened again. */
sealed interface WorldLocation {
    /** Opens the world; [progress] gets (region files scanned, total). May take a while. */
    fun open(progress: (Int, Int) -> Unit = { _, _ -> }): WorldSource

    data class Folder(val path: Path) : WorldLocation {
        override fun open(progress: (Int, Int) -> Unit) = AnvilWorld.open(path, progress)
    }

    data class Demo(val name: String, val seed: Long, val radiusChunks: Int) : WorldLocation {
        override fun open(progress: (Int, Int) -> Unit) = DemoWorld(name, seed, radiusChunks)
    }

    companion object {
        /** Null for worlds that can't be opened again (none so far). */
        fun of(world: WorldSource): WorldLocation? = when (world) {
            is AnvilWorld -> Folder(world.folder.toAbsolutePath().normalize())
            is DemoWorld -> Demo(world.name, world.seed, world.radiusChunks)
            else -> null
        }
    }
}

/**
 * What a world's chunks are drawn with in the Textured mode: [files] the user picked for it (resource packs,
 * top first, optionally a client jar), empty for the editor's default; [coreJar] the client jar with the
 * vanilla ("core") assets that was last used under them. Kept so the merged result knows where every
 * chunk's textures come from, also for chunks pasted from other worlds.
 */
data class WorldTextures(val files: List<Path> = emptyList(), val coreJar: Path? = null) {
    val isDefault: Boolean get() = files.isEmpty() && coreJar == null
}

/** Everything a project file holds, ready to write. */
class ProjectSnapshot(
    /** Open worlds in tab order, each with its layer stack. */
    val stacks: List<LayerStack>,
    val activeWorld: WorldSource?,
    /** Selected chunks in the active world. */
    val selection: Set<ChunkPos>,
    /** Editor settings (camera, render mode, docks...) as XML-safe names and plain values. */
    val editor: Map<String, String>,
    /** Texture sources per world; worlds left out use the default. */
    val textures: Map<WorldSource, WorldTextures> = emptyMap(),
)

/** Layer stacks rebuilt from a project, plus what had to be left out. */
class RestoredStacks(val stacks: List<LayerStack>, val missingChunks: Int)

/**
 * A project file read from disk. Worlds are opened by the caller (it can take long and show
 * progress); [stacks] then rebuilds the layers on top of them.
 */
class ProjectDocument internal constructor(
    val worlds: List<WorldLocation>,
    /** Lines up with [worlds]. */
    val textures: List<WorldTextures>,
    /** Index into [worlds], or null. */
    val activeWorld: Int?,
    val selection: Set<ChunkPos>,
    val editor: Map<String, String>,
    private val ids: List<String>,
    private val stackElements: List<Element>,
) {
    /** Rebuilds the layer stacks; [opened] lines up with [worlds], null where a world could not be opened. */
    fun stacks(opened: List<WorldSource?>): RestoredStacks {
        require(opened.size == worlds.size)
        val byId = ids.zip(opened).toMap()
        var missing = 0
        val stacks = ArrayList<LayerStack>()
        for (s in stackElements) {
            val world = byId[s.getAttribute("world")] ?: continue
            fun ref(e: Element): ChunkRef? {
                val from = e.getAttribute("world").ifEmpty { null }?.let { byId[it] } ?: if (e.hasAttribute("world")) null else world
                if (from == null) {
                    missing++
                    return null
                }
                val pos = e.pos()
                return ChunkRef(from, ChunkPos(e.int("from-x") ?: pos.x, e.int("from-z") ?: pos.z))
            }
            val layers = ArrayList<Layer>()
            val base = s.children("base").firstOrNull()
            val overrides = HashMap<ChunkPos, ChunkEdit>()
            base?.children()?.forEach { e ->
                val pos = e.pos()
                when (e.tagName) {
                    "erase" -> overrides[pos] = ChunkEdit.Delete
                    "place" -> ref(e)?.let { overrides[pos] = ChunkEdit.Place(it) }
                }
            }
            layers += BaseLayer(world, overrides, visible = base?.bool("visible") ?: true, locked = base?.bool("locked") ?: false)
            for (l in s.children("layer")) {
                val chunks = HashMap<ChunkPos, ChunkRef>()
                for (c in l.children("chunk")) ref(c)?.let { chunks[c.pos()] = it }
                val id = l.long("id")?.takeIf { it != BaseLayer.BASE_ID && layers.none { other -> other.id == it } } ?: continue
                layers += ChunkLayer(
                    id, l.getAttribute("name").ifBlank { "Layer $id" }, chunks,
                    visible = l.bool("visible") ?: true, locked = l.bool("locked") ?: false,
                    color = l.getAttribute("color").removePrefix("#").toLongOrNull(16)?.toInt() ?: LayerStack.LAYER_COLORS[0],
                )
            }
            val active = s.long("active")?.takeIf { id -> layers.any { it.id == id } } ?: BaseLayer.BASE_ID
            val nextId = maxOf(s.long("next-id") ?: 1, layers.maxOf { it.id } + 1)
            stacks += LayerStack(world, layers, active, nextId)
        }
        return RestoredStacks(stacks, missing)
    }
}

/**
 * MineCartographer project files: plain XML listing the open worlds, every layer and its chunks,
 * the selection and editor settings. Worlds are referenced by folder, never copied, so a project
 * is small and the saves stay untouched.
 */
object ProjectFile {
    const val EXTENSION = "mcproj"
    private const val ROOT = "minecartographer-project"
    private const val FORMAT = 1

    fun write(file: Path, project: ProjectSnapshot, appVersion: String) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.appendChild(doc.createElement(ROOT)) as Element
        root.setAttribute("format", FORMAT.toString())
        root.setAttribute("app-version", appVersion)

        val dir = file.toAbsolutePath().parent
        val worlds = project.stacks.map { it.world }
        val ids = worlds.withIndex().associate { (i, w) -> w to "w$i" }
        val worldsEl = root.add(doc, "worlds")
        project.activeWorld?.let { ids[it] }?.let { worldsEl.setAttribute("active", it) }
        for (world in worlds) {
            val e = worldsEl.add(doc, "world")
            e.setAttribute("id", ids.getValue(world))
            e.setAttribute("name", world.name)
            when (val loc = WorldLocation.of(world)) {
                is WorldLocation.Folder -> {
                    e.setAttribute("kind", "folder")
                    e.setPath(dir, loc.path)
                }
                is WorldLocation.Demo -> {
                    e.setAttribute("kind", "demo")
                    e.setAttribute("seed", loc.seed.toString())
                    e.setAttribute("radius", loc.radiusChunks.toString())
                }
                null -> e.setAttribute("kind", "unknown")
            }
            project.textures[world]?.takeIf { !it.isDefault }?.let { e.writeTextures(doc, dir, it) }
        }

        for (stack in project.stacks) {
            val s = root.add(doc, "stack")
            s.setAttribute("world", ids.getValue(stack.world))
            s.setAttribute("active", stack.activeId.toString())
            s.setAttribute("next-id", stack.nextId.toString())
            fun Element.ref(pos: ChunkPos, ref: ChunkRef) {
                setPos(pos)
                if (ref.world != stack.world) ids[ref.world]?.let { setAttribute("world", it) }
                if (ref.pos != pos) {
                    setAttribute("from-x", ref.pos.x.toString())
                    setAttribute("from-z", ref.pos.z.toString())
                }
            }
            for (layer in stack.layers) {
                when (layer) {
                    is BaseLayer -> {
                        val b = s.add(doc, "base")
                        b.setAttribute("visible", layer.visible.toString())
                        b.setAttribute("locked", layer.locked.toString())
                        for ((pos, edit) in layer.overrides.entries.sortedWith(compareBy({ it.key.z }, { it.key.x }))) when (edit) {
                            ChunkEdit.Delete -> b.add(doc, "erase").setPos(pos)
                            is ChunkEdit.Place -> b.add(doc, "place").ref(pos, edit.source)
                        }
                    }
                    is ChunkLayer -> {
                        val l = s.add(doc, "layer")
                        l.setAttribute("id", layer.id.toString())
                        l.setAttribute("name", layer.name)
                        l.setAttribute("visible", layer.visible.toString())
                        l.setAttribute("locked", layer.locked.toString())
                        l.setAttribute("color", "#%08X".format(layer.color))
                        for ((pos, ref) in layer.chunks.entries.sortedWith(compareBy({ it.key.z }, { it.key.x }))) l.add(doc, "chunk").ref(pos, ref)
                    }
                }
            }
        }

        if (project.selection.isNotEmpty()) {
            val sel = root.add(doc, "selection")
            sel.textContent = project.selection.sortedWith(compareBy({ it.z }, { it.x }))
                .chunked(16) { row -> row.joinToString(" ") { "${it.x},${it.z}" } }
                .joinToString("\n", prefix = "\n", postfix = "\n")
        }

        val editor = root.add(doc, "editor")
        for ((key, value) in project.editor) editor.setAttribute(key, value)

        writeXml(doc, file)
    }

    fun read(file: Path): ProjectDocument {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Project files may come from anyone: no DTDs, no external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val root = Files.newInputStream(file).use { factory.newDocumentBuilder().parse(it) }.documentElement
        require(root.tagName == ROOT) { "${file.name} is not a MineCartographer project" }
        val format = root.int("format") ?: 0
        require(format <= FORMAT) { "${file.name} was saved by a newer version of MineCartographer" }

        val dir = file.toAbsolutePath().parent
        val worldsEl = root.children("worlds").firstOrNull()
        val ids = ArrayList<String>()
        val worlds = ArrayList<WorldLocation>()
        val textures = ArrayList<WorldTextures>()
        for (e in worldsEl?.children("world").orEmpty()) {
            val loc = when (e.getAttribute("kind")) {
                "folder" -> WorldLocation.Folder(e.readPath(dir) ?: continue)
                "demo" -> WorldLocation.Demo(e.getAttribute("name"), e.long("seed") ?: 1, e.int("radius") ?: 24)
                else -> continue
            }
            ids += e.getAttribute("id")
            worlds += loc
            textures += e.children("textures").firstOrNull()?.readTextures(dir) ?: WorldTextures()
        }
        val active = worldsEl?.getAttribute("active")?.let(ids::indexOf)?.takeIf { it >= 0 }
        val selection = root.children("selection").firstOrNull()?.textContent.orEmpty()
            .split(' ', '\n', '\r', '\t').filter { it.isNotBlank() }
            .mapNotNullTo(HashSet()) { s ->
                val (x, z) = s.split(',').map { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNullTo null
                if (x == null || z == null) null else ChunkPos(x, z)
            }
        val editor = root.children("editor").firstOrNull()?.let { e ->
            (0 until e.attributes.length).associate { e.attributes.item(it).nodeName to e.attributes.item(it).nodeValue }
        }.orEmpty()
        return ProjectDocument(worlds, textures, active, selection, editor, ids, root.children("stack"))
    }

    /** Name of the file [writeSources] puts into an exported world. */
    const val SOURCES_FILE = "minecartographer-sources.xml"

    /**
     * Writes [SOURCES_FILE] into an exported world: every world that chunks came from, how many, and the
     * textures they were drawn with, so whatever finishes the world later (resource packs, mods) knows
     * where its blocks and textures come from. Minecraft ignores the file.
     */
    fun writeSources(worldFolder: Path, stack: LayerStack, textures: Map<WorldSource, WorldTextures>, appVersion: String) {
        val counts = LinkedHashMap<WorldSource, Int>()
        counts[stack.world] = 0
        val b = stack.bounds
        if (b != null) for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
            val ref = stack.resolve(ChunkPos(x, z)) ?: continue
            counts.merge(ref.world, 1, Int::plus)
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.appendChild(doc.createElement("minecartographer-sources")) as Element
        root.setAttribute("format", FORMAT.toString())
        root.setAttribute("app-version", appVersion)
        for ((world, chunks) in counts) {
            val e = root.add(doc, "world")
            e.setAttribute("name", world.name)
            e.setAttribute("version", world.versionLabel)
            world.dataVersion?.let { e.setAttribute("data-version", it.toString()) }
            e.setAttribute("chunks", chunks.toString())
            if (world == stack.world) e.setAttribute("base", "true")
            (WorldLocation.of(world) as? WorldLocation.Folder)?.let { e.setAttribute("absolute-path", it.path.toString()) }
            textures[world]?.takeIf { !it.isDefault }?.let { e.writeTextures(doc, null, it) }
        }
        writeXml(doc, worldFolder.resolve(SOURCES_FILE))
    }

    /** `<textures>` with a `<file>` per picked file and the `<core-jar>`; paths also relative to [dir] when given. */
    private fun Element.writeTextures(doc: Document, dir: Path?, t: WorldTextures) {
        val e = add(doc, "textures")
        for (file in t.files) e.add(doc, "file").setPath(dir, file)
        t.coreJar?.let { e.add(doc, "core-jar").setPath(dir, it) }
    }

    private fun Element.readTextures(dir: Path) = WorldTextures(
        files = children("file").mapNotNull { it.readPath(dir) },
        coreJar = children("core-jar").firstOrNull()?.readPath(dir),
    )

    private fun Element.setPath(dir: Path?, path: Path) {
        val abs = path.toAbsolutePath().normalize()
        // Relative first, so a project moved together with its files still finds them.
        if (dir != null) runCatching { dir.relativize(abs).toString() }.getOrNull()?.let { setAttribute("path", it.replace('\\', '/')) }
        setAttribute("absolute-path", abs.toString())
    }

    /** The relative path if it exists, else the absolute one; null when neither is given. */
    private fun Element.readPath(dir: Path): Path? {
        val relative = getAttribute("path").ifEmpty { null }?.let { dir.resolve(it).normalize() }
        val absolute = getAttribute("absolute-path").ifEmpty { null }?.let { Path.of(it) }
        val path = listOfNotNull(relative, absolute).firstOrNull { it.exists() } ?: absolute ?: relative ?: return null
        return path.toAbsolutePath().normalize()
    }

    /** Writes next to the target, then swaps, so a failed save never leaves half a file. */
    private fun writeXml(doc: Document, file: Path) {
        val tmp = Files.createTempFile(file.toAbsolutePath().parent, file.name, ".tmp")
        try {
            Files.newOutputStream(tmp).use { out ->
                TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                }.transform(DOMSource(doc), StreamResult(out))
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun Element.add(doc: Document, name: String): Element = appendChild(doc.createElement(name)) as Element
    private fun Element.setPos(pos: ChunkPos) {
        setAttribute("x", pos.x.toString())
        setAttribute("z", pos.z.toString())
    }
}

private fun Element.children(name: String? = null): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { name == null || it.tagName == name }
}

private fun Element.int(name: String) = getAttribute(name).toIntOrNull()
private fun Element.long(name: String) = getAttribute(name).toLongOrNull()
private fun Element.bool(name: String) = getAttribute(name).ifEmpty { null }?.toBooleanStrictOrNull()
private fun Element.pos() = ChunkPos(int("x") ?: 0, int("z") ?: 0)
