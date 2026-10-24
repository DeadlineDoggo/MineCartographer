package minecartographer.textures

import minecartographer.core.anvil.ForgeData
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Guesses the textures of 1.6 mod blocks, which have no blockstates or models: the mod names its icons only in
 * code (`registerIcon("ns:name")`) and picks one per side and data value in code too. Clues, scored, best wins:
 *
 * | Clue | Score |
 * |---|---|
 * | a png named like the block (`ForcedName`, or its key in the mod's config) | 100 (95 for a family `name.*`) |
 * | icon names in the block class constructed right after its config key is read | 70 |
 * | icon names in the item class level.dat names, or a class it refers to | 60 |
 * | words of the name and config key found in png names | 20 + matched letters |
 *
 * Words every png of the mod has (its own name, `tile`, `block`) don't count, and classes naming more than
 * [MAX_ICONS] icons are registries of the whole mod, not one block. Icons of one block are grouped into variants
 * by stem (`leavesfirfancy` and `leavesfirfast` are one variant); the data value picks the variant in order.
 * All of it is a guess; [Choice.source] says which clue won, so the user can tell and override it.
 */
class LegacyTextures(private val assets: AssetSource) {
    /** One variant: the texture shown on top and the one on the sides (often the same). */
    data class Variant(val top: String, val side: String)

    /** What a block resolved to: its variants in data-value order and the clue that won. */
    data class Choice(val variants: List<Variant>, val source: String, val score: Int) {
        fun variant(meta: Int): Variant = variants[Math.floorMod(meta, variants.size)]
    }

    private class Pack(val file: Int, val modIds: Set<String>, val pngs: List<String>, val namespaces: Set<String>)

    private val packs: List<Pack> by lazy {
        val byFile = HashMap<Int, MutableList<String>>()
        for ((path, file) in assets.index) {
            if (!path.endsWith(".png")) continue
            val m = BLOCK_TEXTURE.matchEntire(path) ?: continue
            byFile.getOrPut(file) { ArrayList() } += "${m.groupValues[1]}:${m.groupValues[2]}"
        }
        assets.files.indices.mapNotNull { i ->
            val pngs = byFile[i]?.sorted() ?: return@mapNotNull null
            val ids = ModLocator.archive(assets.files[i])?.modIds.orEmpty().mapTo(HashSet()) { it.lowercase() }
            Pack(i, ids, pngs, pngs.mapTo(HashSet()) { it.substringBefore(':') })
        }
    }

    private val scans = ConcurrentHashMap<Int, ClassScan.Archive>()
    private fun scan(file: Int) = scans.computeIfAbsent(file) { ClassScan.scan(assets.zipAt(it)) }

    /** The archive whose classes include [cls] (a JVM name like `extrabiomes/blocks/BlockGreenLeaves`). */
    private fun packOfClass(cls: String): Pack? = packs.firstOrNull { cls in scan(it.file).classes }

    private class Found(val choice: Choice?)
    private val choices = ConcurrentHashMap<String, Found>()

    /** Textures for [block] (with its keys in the mod's config), or null when no clue points anywhere. */
    fun choose(block: ForgeData.LegacyBlock, configKeys: List<String>): Choice? =
        choices.getOrPut(block.name) { Found(resolve(block, configKeys)) }.choice

    private fun resolve(block: ForgeData.LegacyBlock, configKeys: List<String>): Choice? {
        val itemClass = block.itemClass.replace('.', '/')
        val own = packs.filter { block.modId.lowercase() in it.modIds }.ifEmpty { listOfNotNull(packOfClass(itemClass)) }
        if (own.isEmpty()) return null
        val candidates = ArrayList<Choice>()

        // 1. A png named like the block.
        val names = (listOf(block.forcedName.removePrefix("tile.")) + configKeys.map(::stripConfigKey)).filter { it.isNotBlank() }
        for (pack in own) for (name in names) {
            val wanted = nameVariants(name, pack)
            val exact = pack.pngs.filter { norm(baseName(it)) in wanted }
            if (exact.isNotEmpty()) candidates += Choice(variants(exact), "name", 100)
            val family = pack.pngs.filter { png -> wanted.any { w -> w.length >= 5 && norm(baseName(png)).startsWith(w) } }
            if (family.size > 1) candidates += Choice(variants(family), "name family", 95)
        }

        // 2. Classes: the one built right after the config key is read, the item class and what it refers to.
        fun classChoice(cls: String, source: String, score: Int) {
            val pack = packOfClass(cls) ?: return
            val icons = icons(pack, scan(pack.file).classes[cls] ?: return)
            if (icons.isNotEmpty() && icons.size <= MAX_ICONS) candidates += Choice(variants(icons), source, score)
        }
        for (pack in own) for (key in configKeys) {
            scan(pack.file).constructedAfter[key]?.forEach { classChoice(it, "class after config key", 70) }
        }
        classChoice(itemClass, "item class", 60)
        // Classes the item refers to, when their name shares a word with the item or the block
        // (ItemCustomGreenLeaves -> BlockGreenLeaves); any other class it touches says nothing.
        val itemWords = (words(itemClass.substringAfterLast('/')) + names.flatMap(::words)).filter { it.length >= 4 && it !in CLASS_WORDS }.toSet()
        packOfClass(itemClass)?.let { pack ->
            scan(pack.file).classes[itemClass]?.classRefs?.filter { it in scan(pack.file).classes }
                ?.filter { ref -> words(ref.substringAfterLast('/')).any { it in itemWords } }
                ?.forEach { classChoice(it, "class of the item", 45) }
        }

        // 3. Words of the name, matched against whole words (or their start) of png names.
        for (pack in own) {
            val wanted = (names + block.forcedName).flatMap(::words).map(::singular).filter { it.length >= 3 && it !in commonWords(pack) }.distinct()
            if (wanted.isEmpty()) continue
            val scored = pack.pngs.map { png ->
                val pngWords = words(baseName(png)).map(::singular)
                png to wanted.filter { w -> pngWords.any { it == w || (w.length >= 4 && (it.startsWith(w) || it.endsWith(w))) } }.sumOf { it.length }
            }.filter { it.second >= 3 }
            val best = scored.maxOfOrNull { it.second } ?: continue
            candidates += Choice(variants(scored.filter { it.second == best }.map { it.first }), "name words", 20 + best)
        }
        return candidates.maxByOrNull { it.score }?.let(::withStripFrames)
    }

    /**
     * A single texture that is a wide strip of square frames (Railcraft: `brick.infernal.png`, 96 x 16) holds the
     * block's variants side by side, in data-value order: each frame becomes a variant (`ns:name#k`).
     */
    private fun withStripFrames(choice: Choice): Choice {
        val only = choice.variants.singleOrNull()?.takeIf { it.top == it.side } ?: return choice
        val (ns, name) = only.top.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val header = assets.read("assets/$ns/textures/blocks/$name.png")?.takeIf { it.size >= 24 } ?: return choice
        val width = java.nio.ByteBuffer.wrap(header, 16, 8).int
        val height = java.nio.ByteBuffer.wrap(header, 20, 4).int
        if (height <= 0 || width <= height || width % height != 0) return choice
        return choice.copy(variants = List(width / height) { Variant("${only.top}#$it", "${only.top}#$it") })
    }

    /** Icon names a class's constants mention that exist as block textures of its own archive, in constant order. */
    private fun icons(pack: Pack, info: ClassScan.Info): List<String> {
        val byName = HashMap<String, String>()
        for (png in pack.pngs) {
            byName.putIfAbsent(png.lowercase(), png)
            byName.putIfAbsent(png.substringAfter(':').lowercase(), png)
        }
        return info.strings.mapNotNull { s -> byName[s.lowercase()] ?: byName[s.substringAfter(':').lowercase()] }.distinct()
    }

    /** Words in more than a quarter of a mod's png names (its own name, "block"...): no clue to which block. */
    private val common = ConcurrentHashMap<Int, Set<String>>()
    private fun commonWords(pack: Pack): Set<String> = common.computeIfAbsent(pack.file) {
        val counts = HashMap<String, Int>()
        for (png in pack.pngs) words(baseName(png)).toSet().forEach { counts.merge(it, 1, Int::plus) }
        counts.filterValues { it * 4 > pack.pngs.size }.keys + STOP_WORDS + pack.namespaces
    }

    /** `railcraft.brick.infernal` -> with and without the mod's own words in front. */
    private fun nameVariants(name: String, pack: Pack): Set<String> {
        val parts = name.split('.', '_', ' ').filter { it.isNotEmpty() }
        val out = HashSet<String>()
        for (i in parts.indices) out += norm(parts.drop(i).joinToString(""))
        out += norm(name)
        return out.filter { it.length >= 3 && it !in pack.namespaces }.toSet()
    }

    companion object {
        private const val MAX_ICONS = 40
        private val BLOCK_TEXTURE = Regex("""assets/([^/]+)/textures/blocks/(.+)\.png""")
        private val STOP_WORDS = setOf("tile", "block", "blocks", "item", "id", "ids")
        private val SUFFIXES = listOf("fancy", "fast", "opaque", "top", "bottom", "side", "sides", "front", "back", "end",
            "on", "off", "active", "inactive", "lit", "unlit", "still", "flow", "flowing")

        /** Words of a class or file name that are about Java, not the block. */
        private val CLASS_WORDS = setOf("item", "block", "blocks", "base", "custom", "multi", "generic", "common")

        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** `berries` -> `berry`, `flowers` -> `flower`: names and pngs don't agree on plurals. */
        fun singular(w: String) = when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        /** `ItemCustomGreenLeaves` / `ore.dark.diamond` / `tracks/rail_normal` -> lowercase words. */
        fun words(s: String): List<String> =
            s.split(Regex("""[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])""")).filter { it.isNotEmpty() }.map { it.lowercase() }
        private fun baseName(png: String) = png.substringAfter(':')

        /** `block.brick.infernal` / `ID.RubberLeaves` / `greenleaves.id` -> the name part. */
        fun stripConfigKey(key: String): String = key.split('.', ' ').filter { norm(it) !in STOP_WORDS }.joinToString(".")

        /**
         * Icons grouped into variants by stem, in first-seen order: `leavesfirfancy` + `leavesfirfast` -> one variant
         * shown with the fancy texture; `logtop` + `logside` -> top and side of one variant.
         */
        fun variants(icons: List<String>): List<Variant> {
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (icon in icons) groups.getOrPut(stem(icon)) { ArrayList() } += icon
            return groups.values.map { members ->
                fun suffix(m: String) = SUFFIXES.firstOrNull { norm(baseName(m)).endsWith(it) }
                val visible = members.filter { suffix(it) != "fast" && suffix(it) != "flow" && suffix(it) != "flowing" }.ifEmpty { members }
                val top = visible.firstOrNull { suffix(it) == "top" || suffix(it) == "end" } ?: visible.first()
                val side = visible.firstOrNull { suffix(it) == "side" || suffix(it) == "sides" } ?: visible.firstOrNull { it != top } ?: top
                Variant(top, if (suffix(top) == "top" || suffix(top) == "end") side else top)
            }
        }

        private fun stem(icon: String): String {
            var s = norm(baseName(icon))
            var changed = true
            while (changed) {
                changed = false
                for (suffix in SUFFIXES) if (s.length > suffix.length + 2 && s.endsWith(suffix)) {
                    s = s.dropLast(suffix.length); changed = true
                }
            }
            return s
        }

        /**
         * Block id -> keys in 1.6 Forge config files (`I:greenleaves.id=2204`) under [folders]: mods read their block
         * ids from there, so the key is the mod's own name for the block. Files with a `block { ... }` category only
         * count keys inside it, since item ids share the numbers.
         */
        fun configKeys(folders: List<Path>): Map<Int, List<String>> {
            val out = HashMap<Int, MutableList<String>>()
            val entry = Regex("""^\s*I:"?([^"=]+?)"?\s*=\s*(\d+)\s*$""")
            val open = Regex("""^\s*"?([^"{]+?)"?\s*\{\s*$""")
            for (folder in folders.filter { it.isDirectory() }) runCatching {
                Files.walk(folder, 4).use { s ->
                    s.filter { it.isRegularFile() && Files.size(it) < 2_000_000 && it.toString().endsWith(".cfg") }.forEach { f ->
                        val lines = runCatching { Files.readAllLines(f, Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
                        val hasBlockCategory = lines.any { l -> open.find(l)?.groupValues?.get(1)?.contains("block", true) == true }
                        val categories = ArrayDeque<String>()
                        for (l in lines) {
                            val category = open.find(l)
                            if (category != null) { categories.addLast(category.groupValues[1]); continue }
                            if (l.trim() == "}") { categories.removeLastOrNull(); continue }
                            val m = entry.find(l) ?: continue
                            if (hasBlockCategory && categories.none { it.contains("block", true) }) continue
                            val id = m.groupValues[2].toIntOrNull() ?: continue
                            if (id in 256 until ForgeData.MAX_ID) out.getOrPut(id) { ArrayList() } += m.groupValues[1].trim()
                        }
                    }
                }
            }
            return out
        }
    }
}
