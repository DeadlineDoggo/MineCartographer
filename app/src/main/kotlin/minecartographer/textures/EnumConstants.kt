package minecartographer.textures

import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Enum constants as a mod's compiled classes define them: for each `new` in a class's static initializer, the
 * strings and small ints pushed before the constructor runs. Mods before 1.13 often keep a block's variants in an
 * enum like `ORE_COPPER("ore_copper_gc", 5)` (Galacticraft), where the name is the blockstate property value and
 * the int the data value the chunk stores. Reading that is the only way to know which data value is which variant.
 *
 * Only reads bytes; no class is loaded or run.
 */
object EnumConstants {
    /** One constructor call: [strings] and [ints] in push order (for an enum, the ordinal comes first). */
    class Constant(val className: String, val strings: List<String>, val ints: List<Int>)

    /** Every constant of every class in [zip], by lowercase string argument. */
    fun index(zip: ZipFile): Map<String, List<Constant>> {
        val out = HashMap<String, MutableList<Constant>>()
        for (entry in zip.entries()) {
            if (!entry.name.endsWith(".class") || entry.size > MAX_CLASS_BYTES) continue
            val constants = runCatching { read(entry.name, zip.getInputStream(entry).use { it.readAllBytes() }) }.getOrNull() ?: continue
            for (c in constants) for (s in c.strings) out.getOrPut(s.lowercase()) { ArrayList(1) } += c
        }
        return out
    }

    /**
     * Data values of a property's [values] from an enum in [index] that names (nearly) all of them with an explicit
     * int after the ordinal; null when there is no such enum, or its ints are not distinct data values (0..15).
     */
    fun dataValues(index: Map<String, List<Constant>>, values: Collection<String>): Map<String, Int>? {
        val classes = HashMap<String, Int>()
        for (v in values) index[v.lowercase()]?.mapTo(HashSet()) { it.className }?.forEach { classes.merge(it, 1, Int::plus) }
        val (cls, hits) = classes.maxByOrNull { it.value } ?: return null
        if (hits < values.size * 0.8) return null
        val metas = values.associateWith { v -> index[v.lowercase()]?.firstOrNull { it.className == cls }?.ints?.getOrNull(1) }
        val found = metas.values.filterNotNull()
        if (found.size < hits || found.any { it !in 0..15 } || found.toSet().size != found.size) return null
        @Suppress("UNCHECKED_CAST")
        return metas.filterValues { it != null } as Map<String, Int>
    }

    private const val MAX_CLASS_BYTES = 512 * 1024

    internal fun read(name: String, bytes: ByteArray): List<Constant> {
        val b = ByteBuffer.wrap(bytes)
        if (b.int != 0xCAFEBABE.toInt()) return emptyList()
        b.position(8)
        val count = b.short.toInt() and 0xFFFF
        val utf8 = arrayOfNulls<String>(count)
        val stringRef = IntArray(count) { -1 }
        val intValue = arrayOfNulls<Int>(count)
        var i = 1
        while (i < count) {
            when (val tag = b.get().toInt()) {
                1 -> {
                    val len = b.short.toInt() and 0xFFFF
                    utf8[i] = String(bytes, b.position(), len, Charsets.UTF_8) // modified UTF-8; fine for names
                    b.position(b.position() + len)
                }
                3 -> intValue[i] = b.int
                4 -> b.position(b.position() + 4)
                5, 6 -> { b.position(b.position() + 8); i++ }
                7, 16, 19, 20 -> b.position(b.position() + 2)
                8 -> stringRef[i] = b.short.toInt() and 0xFFFF
                9, 10, 11, 12, 17, 18 -> b.position(b.position() + 4)
                15 -> b.position(b.position() + 3)
                else -> error("constant tag $tag")
            }
            i++
        }
        b.position(b.position() + 6) // access, this, super
        val interfaces = b.short.toInt() and 0xFFFF
        b.position(b.position() + 2 * interfaces)
        repeat(2) { member -> // fields, then methods
            repeat(b.short.toInt() and 0xFFFF) {
                b.short // access
                val nameIndex = b.short.toInt() and 0xFFFF
                b.short // descriptor
                repeat(b.short.toInt() and 0xFFFF) {
                    val attrName = utf8[b.short.toInt() and 0xFFFF]
                    val length = b.int
                    val start = b.position()
                    if (member == 1 && attrName == "Code" && utf8[nameIndex] == "<clinit>") {
                        b.position(start + 4) // max stack, max locals
                        val codeLength = b.int
                        return constants(name.removeSuffix(".class"), bytes, b.position(), codeLength, utf8, stringRef, intValue)
                    }
                    b.position(start + length)
                }
            }
        }
        return emptyList()
    }

    private fun constants(
        cls: String, code: ByteArray, from: Int, length: Int,
        utf8: Array<String?>, stringRef: IntArray, intValue: Array<Int?>,
    ): List<Constant> {
        val out = ArrayList<Constant>()
        var strings: MutableList<String>? = null
        var ints: MutableList<Int>? = null
        fun u1(p: Int) = code[p].toInt() and 0xFF
        fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
        fun pushConstant(index: Int) {
            if (index !in stringRef.indices) return
            if (stringRef[index] >= 0) utf8[stringRef[index]]?.let { strings?.add(it) }
            intValue[index]?.let { ints?.add(it) }
        }
        var p = from
        val end = from + length
        while (p < end) {
            val op = u1(p)
            when (op) {
                0xBB -> { // new: a constructor call starts
                    strings = ArrayList(); ints = ArrayList()
                    out += Constant(cls, strings, ints)
                }
                in 0x02..0x08 -> ints?.add(op - 0x03)      // iconst_m1 .. iconst_5
                0x10 -> ints?.add(code[p + 1].toInt())     // bipush
                0x11 -> ints?.add(((code[p + 1].toInt()) shl 8) or u1(p + 2)) // sipush
                0x12 -> pushConstant(u1(p + 1))            // ldc
                0x13 -> pushConstant(u2(p + 1))            // ldc_w
            }
            p += instructionLength(code, p, from)
        }
        return out.filter { it.strings.isNotEmpty() }
    }

    /** Bytes taken by the instruction at [p] (JVM spec, chapter 6). */
    private fun instructionLength(code: ByteArray, p: Int, codeStart: Int): Int {
        fun s4(at: Int) = ((code[at].toInt() and 0xFF) shl 24) or ((code[at + 1].toInt() and 0xFF) shl 16) or
            ((code[at + 2].toInt() and 0xFF) shl 8) or (code[at + 3].toInt() and 0xFF)
        return when (val op = code[p].toInt() and 0xFF) {
            0x10, 0x12, in 0x15..0x19, in 0x36..0x3A, 0xA9, 0xBC -> 2
            0x11, 0x13, 0x14, 0x84, in 0x99..0xA8, in 0xB2..0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3
            0xC5 -> 4
            0xB9, 0xBA, 0xC8, 0xC9 -> 5
            0xC4 -> if ((code[p + 1].toInt() and 0xFF) == 0x84) 6 else 4 // wide
            0xAA -> { // tableswitch
                val base = p + 1 + (3 - (p - codeStart) % 4)
                val low = s4(base + 4); val high = s4(base + 8)
                base - p + 12 + 4 * (high - low + 1)
            }
            0xAB -> { // lookupswitch
                val base = p + 1 + (3 - (p - codeStart) % 4)
                base - p + 8 + 8 * s4(base + 4)
            }
            else -> 1
        }
    }
}
