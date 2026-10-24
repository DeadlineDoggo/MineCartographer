package minecartographer.core.convert

import minecartographer.core.BlockState
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The tables Minecraft itself upgrades old worlds with, read out of a 1.13+ client or server jar: its data fixers
 * keep them as constants in code (`register(16, "{Name:'minecraft:stone'}", ...)`, `map.put("Chest", "minecraft:chest")`).
 * Class names are obfuscated and change with every version, so the classes are found by what they contain.
 */
class MinecraftTables(
    /** Pre-1.13 `id shl 4 or data` -> block state as of 1.13. */
    val blocks: Map<Int, BlockState>,
    /** Numeric item ids (before 1.8) -> names, e.g. 1 -> `minecraft:stone`. */
    val itemIds: Map<Int, String>,
    /** `name.damage` -> 1.13 item, e.g. `minecraft:stone.1` -> `minecraft:granite`. */
    val itemFlattening: Map<String, String>,
    /** Block entity ids before 1.11 -> names, e.g. `Chest` -> `minecraft:chest`. */
    val blockEntityIds: Map<String, String>,
    /** Entity ids before 1.11 -> names, e.g. `Creeper` -> `minecraft:creeper`. */
    val entityIds: Map<String, String>,
    /** Numeric biome ids (before 1.18) -> names of the time, e.g. 3 -> `minecraft:mountains`. */
    val biomeIds: Map<Int, String>,
    /** Biome renames of 1.13 (`minecraft:extreme_hills` -> `minecraft:mountains`). */
    val biomeRenames113: Map<String, String>,
    /** Biome renames of 1.18 (`minecraft:mountains` -> `minecraft:windswept_hills`). */
    val biomeRenames118: Map<String, String>,
) {
    /** For each block name, the first old key giving it: the way back for worlds that still store numbers. */
    val legacyKeys: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        for ((key, state) in blocks.entries.sortedBy { it.key }) out.putIfAbsent(state.name, key)
        out
    }

    companion object {
        private val NEEDLES = listOf(
            "{Name:'minecraft:granite'}", "minecraft:iron_shovel", "minecraft:stone.1", "Airportal",
            "MinecartRideable", "minecraft:deep_frozen_ocean", "minecraft:extreme_hills", "minecraft:windswept_hills",
        ).map { it.toByteArray() }

        /** Reads the tables from [jar]; null when it holds no data fixers (before 1.13, or not a Minecraft jar). */
        fun read(jar: Path): MinecraftTables? = runCatching {
            ZipFile(jar.toFile()).use { zip ->
                val found = ArrayList<Constants>()
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class") || entry.size > 4 shl 20) continue
                    val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                    if (NEEDLES.none { contains(bytes, it) }) continue
                    runCatching { Constants.read(bytes) }.getOrNull()?.let { found += it }
                }
                fun ints(test: (Map<Int, String>) -> Boolean) = found.map { it.ints }.firstOrNull(test).orEmpty()
                fun pairs(test: (Map<String, String>) -> Boolean) = found.map { it.pairs }.firstOrNull(test).orEmpty()
                val blocks = found.map { it.ints }.maxByOrNull { m -> m.values.count { it.startsWith("{Name:") } }
                    ?.filterValues { it.startsWith("{Name:") }?.mapValues { parseState(it.value) }.orEmpty()
                if (blocks.size < 1000) return null
                MinecraftTables(
                    blocks = blocks,
                    itemIds = ints { it[256] == "minecraft:iron_shovel" },
                    itemFlattening = pairs { "minecraft:stone.1" in it },
                    blockEntityIds = pairs { it["Airportal"] != null },
                    entityIds = pairs { it["Creeper"] == "minecraft:creeper" },
                    biomeIds = ints { it[0] == "minecraft:ocean" && it[1] == "minecraft:plains" },
                    biomeRenames113 = pairs { "minecraft:extreme_hills" in it },
                    biomeRenames118 = pairs { it["minecraft:mountains"] == "minecraft:windswept_hills" },
                )
            }
        }.getOrNull()

        /** `{Name:'minecraft:oak_log',Properties:{axis:'y'}}` -> the state. */
        fun parseState(snbt: String): BlockState {
            val name = Regex("""Name:'([^']*)'""").find(snbt)?.groupValues?.get(1) ?: "minecraft:air"
            val props = Regex("""Properties:\{([^}]*)}""").find(snbt)?.groupValues?.get(1)
            val map = props?.split(',')?.mapNotNull { p ->
                val i = p.indexOf(':')
                if (i < 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim().trim('\'')
            }?.toMap().orEmpty()
            return BlockState.of(name, map)
        }

        private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
            val first = needle[0]
            outer@ for (i in 0..haystack.size - needle.size) {
                if (haystack[i] != first) continue
                for (j in 1 until needle.size) if (haystack[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }

    /**
     * The constants a class's code pairs up: an int pushed right before a string (`register(16, "{Name:...}")`,
     * `map.put(1, "minecraft:stone")`; not array indices, which follow a `dup`), and two strings in a row passed to
     * a call (`map.put("Chest", "minecraft:chest")`).
     */
    private class Constants(val ints: Map<Int, String>, val pairs: Map<String, String>) {
        companion object {
            fun read(bytes: ByteArray): Constants {
                val b = ByteBuffer.wrap(bytes)
                require(b.int == 0xCAFEBABE.toInt())
                b.position(8)
                val count = b.short.toInt() and 0xFFFF
                val utf8 = arrayOfNulls<String>(count)
                val stringRef = IntArray(count) { -1 }
                val intValue = arrayOfNulls<Int>(count)
                var i = 1
                while (i < count) {
                    when (b.get().toInt()) {
                        1 -> {
                            val len = b.short.toInt() and 0xFFFF
                            utf8[i] = String(bytes, b.position(), len, Charsets.UTF_8)
                            b.position(b.position() + len)
                        }
                        3 -> intValue[i] = b.int
                        4 -> b.position(b.position() + 4)
                        5, 6 -> { b.position(b.position() + 8); i++ }
                        7, 16, 19, 20 -> b.position(b.position() + 2)
                        8 -> stringRef[i] = b.short.toInt() and 0xFFFF
                        9, 10, 11, 12, 17, 18 -> b.position(b.position() + 4)
                        15 -> b.position(b.position() + 3)
                        else -> error("bad constant")
                    }
                    i++
                }
                fun string(index: Int) = if (index in stringRef.indices && stringRef[index] >= 0) utf8[stringRef[index]] else null

                val ints = LinkedHashMap<Int, String>()
                val pairs = LinkedHashMap<String, String>()
                b.position(b.position() + 6)
                val interfaces = b.short.toInt() and 0xFFFF
                b.position(b.position() + 2 * interfaces)
                repeat(2) { member ->
                    val members = b.short.toInt() and 0xFFFF
                    repeat(members) {
                        b.position(b.position() + 6)
                        val attributes = b.short.toInt() and 0xFFFF
                        repeat(attributes) {
                            val name = utf8[b.short.toInt() and 0xFFFF]
                            val length = b.int
                            val start = b.position()
                            if (member == 1 && name == "Code") {
                                val codeLength = ByteBuffer.wrap(bytes, start + 4, 4).int
                                scan(bytes, start + 8, codeLength, ::string, intValue, ints, pairs)
                            }
                            b.position(start + length)
                        }
                    }
                }
                return Constants(ints, pairs)
            }

            private fun scan(
                code: ByteArray, from: Int, length: Int, string: (Int) -> String?, intValue: Array<Int?>,
                ints: MutableMap<Int, String>, pairs: MutableMap<String, String>,
            ) {
                fun u1(p: Int) = code[p].toInt() and 0xFF
                fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
                // What the last instructions pushed: an int, a string, or something else (null).
                var prevOp = -1
                var lastInt: Int? = null
                var lastIntAfterDup = false
                var lastString: String? = null
                var prevString: String? = null
                var p = from
                val end = from + length
                while (p < end) {
                    val op = u1(p)
                    var pushedInt: Int? = null
                    var pushedString: String? = null
                    when (op) {
                        in 0x02..0x08 -> pushedInt = op - 3
                        0x10 -> pushedInt = code[p + 1].toInt()
                        0x11 -> pushedInt = ((code[p + 1].toInt() shl 8) or u1(p + 2)).toShort().toInt()
                        0x12, 0x13 -> {
                            val index = if (op == 0x12) u1(p + 1) else u2(p + 1)
                            pushedString = string(index)
                            if (pushedString == null) pushedInt = intValue.getOrNull(index)
                        }
                    }
                    if (pushedString != null) {
                        if (lastInt != null && !lastIntAfterDup) ints.putIfAbsent(lastInt, pushedString)
                        lastInt = null
                    }
                    if (op in INVOKES && lastString != null && prevString != null) pairs.putIfAbsent(prevString, lastString)
                    prevString = if (pushedString != null) lastString else null
                    lastString = pushedString
                    if (pushedInt != null) {
                        lastInt = pushedInt
                        lastIntAfterDup = prevOp == 0x59
                    } else if (pushedString == null) {
                        lastInt = null
                    }
                    prevOp = op
                    p += instructionLength(code, p, from)
                }
            }

            private val INVOKES = setOf(0xB6, 0xB7, 0xB8, 0xB9)

            /** Length of the instruction at [p]; [codeStart] aligns the switch tables. */
            private fun instructionLength(code: ByteArray, p: Int, codeStart: Int): Int {
                fun s4(at: Int) = ((code[at].toInt() and 0xFF) shl 24) or ((code[at + 1].toInt() and 0xFF) shl 16) or
                    ((code[at + 2].toInt() and 0xFF) shl 8) or (code[at + 3].toInt() and 0xFF)
                return when (val op = code[p].toInt() and 0xFF) {
                    0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2
                    0x11, 0x13, 0x14, 0x84, in 0x99..0xA8, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3
                    0xC5 -> 4
                    0xB9, 0xBA, 0xC8, 0xC9 -> 5
                    0xC4 -> if ((code[p + 1].toInt() and 0xFF) == 0x84) 6 else 4 // wide
                    0xAA -> { // tableswitch
                        val pad = (4 - ((p + 1 - codeStart) % 4)) % 4
                        val base = p + 1 + pad
                        val low = s4(base + 4)
                        val high = s4(base + 8)
                        1 + pad + 12 + 4 * (high - low + 1)
                    }
                    0xAB -> { // lookupswitch
                        val pad = (4 - ((p + 1 - codeStart) % 4)) % 4
                        val base = p + 1 + pad
                        1 + pad + 8 + 8 * s4(base + 4)
                    }
                    else -> 1
                }
            }
        }
    }
}
