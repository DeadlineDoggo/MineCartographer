package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.edit.LayerStack
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.DoubleTag
import minecartographer.core.nbt.IntArrayTag
import minecartographer.core.nbt.LongTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.StringTag
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * Writes the visible result of a [LayerStack] as a new world folder. Source worlds are only read.
 *
 * Mixed versions: every chunk keeps the DataVersion it was saved with, and Minecraft upgrades
 * each chunk on its own when the merged world is opened with the base world's version. That only
 * works upward, so chunks from worlds newer than the base world are rejected.
 */
object WorldExporter {
    /** 1.17 (20w45a): entities moved out of chunks into entities/ region files. */
    const val DV_ENTITY_FILES = 2681

    class Problem(message: String) : IOException(message)

    data class Report(val chunks: Int, val copiedUnchanged: Int, val relocated: Int, val regions: Int, val warnings: List<String>)

    /** Checks that [stack] can be exported; returns human-readable problems (empty when fine). */
    fun validate(stack: LayerStack): List<String> {
        val base = stack.world as? AnvilWorld ?: return listOf("The original world is a demo world; open a real world to export.")
        val problems = ArrayList<String>()
        val sources = sourceWorlds(stack)
        sources.filter { it !is AnvilWorld }.forEach { problems += "Chunks from \"${it.name}\" are demo chunks and can't be exported." }
        val target = base.dataVersion ?: 0
        sources.filterIsInstance<AnvilWorld>().filter { (it.dataVersion ?: 0) > target }.forEach {
            problems += "\"${it.name}\" (${it.versionLabel}) is newer than \"${base.name}\" (${base.versionLabel}); Minecraft can only upgrade chunks, not downgrade them."
        }
        // Before 1.13 chunks store bare numbers, and Forge numbers modded blocks per world: copied as they are,
        // they would turn into whatever the base world calls those numbers.
        val baseIds = base.info.forge?.takeIf { it.numericIds }?.blockNames.orEmpty()
        sources.filterIsInstance<AnvilWorld>().filter { it != base }.forEach { source ->
            val ids = source.info.forge?.takeIf { it.numericIds }?.blockNames.orEmpty()
            if (ids != baseIds && (source.dataVersion ?: 0) < SurfaceExtractor.DV_FLATTENING) {
                problems += "\"${source.name}\" numbers its modded blocks differently from \"${base.name}\"; " +
                    "its chunks would change into other blocks. Only worlds of the same modpack can be merged before 1.13."
            }
        }
        return problems
    }

    private fun sourceWorlds(stack: LayerStack) = buildSet {
        add(stack.world)
        stack.layers.forEach { layer ->
            when (layer) {
                is minecartographer.core.edit.ChunkLayer -> if (layer.visible) layer.chunks.values.forEach { add(it.world) }
                is minecartographer.core.edit.BaseLayer -> layer.overrides.values.forEach { if (it is minecartographer.core.edit.ChunkEdit.Place) add(it.source.world) }
            }
        }
    }

    /**
     * Exports to [output], which must not exist yet (or be an empty folder).
     * [progress] gets a step description plus done/total counts.
     */
    fun export(stack: LayerStack, output: Path, levelName: String, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Report {
        validate(stack).firstOrNull()?.let { throw Problem(it) }
        val base = stack.world as AnvilWorld
        if (output.exists() && (!output.isDirectory() || output.listDirectoryEntries().isNotEmpty())) {
            throw Problem("$output already exists and is not empty")
        }
        val outNorm = output.toAbsolutePath().normalize()
        sourceWorlds(stack).filterIsInstance<AnvilWorld>().forEach {
            if (outNorm.startsWith(it.folder.toAbsolutePath().normalize())) throw Problem("Can't export into a source world's folder")
        }
        val warnings = ArrayList<String>()
        val targetDv = base.dataVersion ?: 0

        // 1. Everything except the overworld chunk data comes from the base world as-is
        //    (other dimensions, player data, maps, advancements, datapacks...).
        progress("Copying world files", 0, 1)
        copyTree(base.folder, output, skip = setOf("region", "entities", "session.lock"))

        // 2. The overworld: every position that shows a chunk, grouped by region file.
        val positions = HashSet<ChunkPos>()
        base.chunks().forEach { positions += it }
        stack.layers.forEach { if (it is minecartographer.core.edit.ChunkLayer && it.visible) positions += it.chunks.keys }
        stack.base.overrides.keys.forEach { positions += it }
        val byRegion = positions.groupBy { it.regionX to it.regionZ }

        var done = 0
        var unchanged = 0
        var relocated = 0
        var written = 0
        for ((region, chunks) in byRegion) {
            val blockData = HashMap<ChunkPos, RawChunk>()
            val entityData = HashMap<ChunkPos, RawChunk>()
            for (pos in chunks) {
                val ref = stack.resolve(pos) ?: continue
                val source = ref.world as AnvilWorld
                val raw = source.readChunk(ref.pos)
                if (raw == null) {
                    warnings += "Chunk ${ref.pos} of ${source.name} could not be read"
                    continue
                }
                if (source == base && ref.pos == pos) {
                    blockData[pos] = raw // untouched chunk: copy the stored bytes
                    base.readEntities(pos)?.let { entityData[pos] = it }
                    unchanged++
                } else {
                    val nbt = raw.nbt()
                    relocateChunk(nbt, ref.pos, pos)
                    blockData[pos] = RawChunk.of(nbt)
                    if ((source.dataVersion ?: 0) >= DV_ENTITY_FILES) {
                        source.readEntities(ref.pos)?.let { entities ->
                            val e = entities.nbt()
                            relocateEntityChunk(e, ref.pos, pos)
                            entityData[pos] = RawChunk.of(e)
                        }
                    }
                    relocated++
                }
            }
            val name = RegionFile.fileName(region.first, region.second)
            if (blockData.isNotEmpty()) {
                RegionWriter.write(output.resolve("region").resolve(name), blockData)
                written++
            }
            if (entityData.isNotEmpty() && targetDv >= DV_ENTITY_FILES) {
                RegionWriter.write(output.resolve("entities").resolve(name), entityData)
            }
            done++
            progress("Writing region files", done, byRegion.size)
        }
        Files.createDirectories(output.resolve("region"))

        // 3. level.dat with the new name.
        val levelDat = output.resolve("level.dat")
        val root = Files.newInputStream(levelDat).use { Nbt.readGzip(it) }
        root.compound("Data")?.set("LevelName", StringTag(levelName))
        levelDat.outputStream().use { Nbt.writeGzip(it, root) }

        return Report(unchanged + relocated, unchanged, relocated, written, warnings)
    }

    /** Moves chunk NBT (any version) from [from] to [to]: coordinates, block entities, ticks, entities. */
    fun relocateChunk(root: CompoundTag, from: ChunkPos, to: ChunkPos) {
        val c = root.compound("Level") ?: root
        val dx = (to.x - from.x) * 16
        val dz = (to.z - from.z) * 16
        c.putInt("xPos", to.x)
        c.putInt("zPos", to.z)
        for (name in listOf("TileEntities", "block_entities", "TileTicks", "LiquidTicks", "block_ticks", "fluid_ticks")) {
            c.list(name)?.compounds()?.forEach { shiftXZ(it, dx, dz) }
        }
        c.list("Entities")?.compounds()?.forEach { relocateEntity(it, dx, dz) } // before 1.17
        // Structure pieces point at their original location; drop them rather than leave broken references.
        for (name in listOf("Structures", "structures")) {
            c.compound(name)?.let {
                it["References"] = CompoundTag()
                it["starts"]?.let { _ -> it["starts"] = CompoundTag() }
                it["Starts"]?.let { _ -> it["Starts"] = CompoundTag() }
            }
        }
        // Light from the old neighbors is wrong here; ask the game to recompute it.
        if ("isLightOn" in c) c.putByte("isLightOn", 0)
        if ("LightPopulated" in c) c.putByte("LightPopulated", 0)
    }

    /** 1.17+ entity chunk: `Position` plus the entity list. */
    fun relocateEntityChunk(root: CompoundTag, from: ChunkPos, to: ChunkPos) {
        root["Position"] = IntArrayTag(intArrayOf(to.x, to.z))
        root.list("Entities")?.compounds()?.forEach { relocateEntity(it, (to.x - from.x) * 16, (to.z - from.z) * 16) }
    }

    private fun shiftXZ(tag: CompoundTag, dx: Int, dz: Int) {
        tag.int("x")?.let { tag.putInt("x", it + dx) }
        tag.int("z")?.let { tag.putInt("z", it + dz) }
    }

    private fun relocateEntity(e: CompoundTag, dx: Int, dz: Int) {
        e.list("Pos")?.let { pos ->
            if (pos.size == 3) {
                val (x, y, z) = pos.doubles()
                pos.clear()
                pos.addAll(listOf(DoubleTag(x + dx), DoubleTag(y), DoubleTag(z + dz)))
                pos.elementType = 6
            }
        }
        // Hanging entities (paintings, item frames) store the block they hang on.
        e.int("TileX")?.let { e.putInt("TileX", it + dx) }
        e.int("TileZ")?.let { e.putInt("TileZ", it + dz) }
        e.compound("Leash")?.let { leash -> leash.int("X")?.let { leash.putInt("X", it + dx) }; leash.int("Z")?.let { leash.putInt("Z", it + dz) } }
        // Copies of the same chunk must not share entity UUIDs.
        val id = UUID.randomUUID()
        if ("UUID" in e) {
            val m = id.mostSignificantBits
            val l = id.leastSignificantBits
            e["UUID"] = IntArrayTag(intArrayOf((m shr 32).toInt(), m.toInt(), (l shr 32).toInt(), l.toInt()))
        }
        if ("UUIDMost" in e) {
            e["UUIDMost"] = LongTag(id.mostSignificantBits)
            e["UUIDLeast"] = LongTag(id.leastSignificantBits)
        }
        e.list("Passengers")?.compounds()?.forEach { relocateEntity(it, dx, dz) }
    }

    private fun copyTree(from: Path, to: Path, skip: Set<String>) {
        Files.createDirectories(to)
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != from && dir.parent == from && dir.name in skip) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.parent == from && file.name in skip) return FileVisitResult.CONTINUE
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
