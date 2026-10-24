package minecartographer.textures

import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * What a mod's compiled classes say without running them, for 1.6 mods whose block textures are named only in
 * code: each class's string constants (where `registerIcon("ns:name")` names end up), the classes it refers to,
 * and which class is constructed right after a string is loaded. The last one links config keys to blocks:
 * `config.getBlock("greenleaves.id", ...)` followed by `new BlockGreenLeaves(...)`.
 */
object ClassScan {
    class Info(val name: String, val strings: List<String>, val classRefs: List<String>)

    class Archive(
        val classes: Map<String, Info>,
        /** String constant -> classes constructed next in the same method, in code order. */
        val constructedAfter: Map<String, List<String>>,
    )

    private const val MAX_CLASS_BYTES = 512 * 1024

    fun scan(zip: ZipFile): Archive {
        val classes = HashMap<String, Info>()
        val after = HashMap<String, MutableList<String>>()
        for (entry in zip.entries()) {
            if (!entry.name.endsWith(".class") || entry.size > MAX_CLASS_BYTES) continue
            runCatching { read(entry.name.removeSuffix(".class"), zip.getInputStream(entry).use { it.readAllBytes() }, after) }
                .getOrNull()?.let { classes[it.name] = it }
        }
        return Archive(classes, after)
    }

    internal fun read(name: String, bytes: ByteArray, after: MutableMap<String, MutableList<String>>): Info? {
        val b = ByteBuffer.wrap(bytes)
        if (b.int != 0xCAFEBABE.toInt()) return null
        b.position(8)
        val count = b.short.toInt() and 0xFFFF
        val utf8 = arrayOfNulls<String>(count)
        val stringRef = IntArray(count) { -1 }
        val classRef = IntArray(count) { -1 }
        var i = 1
        while (i < count) {
            when (b.get().toInt()) {
                1 -> {
                    val len = b.short.toInt() and 0xFFFF
                    utf8[i] = String(bytes, b.position(), len, Charsets.UTF_8)
                    b.position(b.position() + len)
                }
                3, 4 -> b.position(b.position() + 4)
                5, 6 -> { b.position(b.position() + 8); i++ }
                7 -> classRef[i] = b.short.toInt() and 0xFFFF
                8 -> stringRef[i] = b.short.toInt() and 0xFFFF
                16, 19, 20 -> b.position(b.position() + 2)
                9, 10, 11, 12, 17, 18 -> b.position(b.position() + 4)
                15 -> b.position(b.position() + 3)
                else -> return null
            }
            i++
        }
        val strings = (0 until count).mapNotNull { if (stringRef[it] >= 0) utf8[stringRef[it]] else null }
        val refs = (0 until count).mapNotNull { if (classRef[it] >= 0) utf8[classRef[it]] else null }.filter { it != name }

        b.position(b.position() + 6) // access, this, super
        val interfaces = b.short.toInt() and 0xFFFF
        b.position(b.position() + 2 * interfaces)
        repeat(2) { member -> // fields, then methods
            val members = b.short.toInt() and 0xFFFF
            repeat(members) {
                b.position(b.position() + 6) // access, name, descriptor
                val attributes = b.short.toInt() and 0xFFFF
                repeat(attributes) {
                    val attrName = utf8[b.short.toInt() and 0xFFFF]
                    val length = b.int
                    val start = b.position()
                    if (member == 1 && attrName == "Code") {
                        val codeLength = ByteBuffer.wrap(bytes, start + 4, 4).int
                        linkStrings(bytes, start + 8, codeLength, utf8, stringRef, classRef, after)
                    }
                    b.position(start + length)
                }
            }
        }
        return Info(name, strings, refs)
    }

    /** Records, for each string constant loaded, the class of the next `new` in the same method. */
    private fun linkStrings(
        code: ByteArray, from: Int, length: Int, utf8: Array<String?>, stringRef: IntArray, classRef: IntArray,
        after: MutableMap<String, MutableList<String>>,
    ) {
        fun u1(p: Int) = code[p].toInt() and 0xFF
        fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
        var pending: String? = null
        var p = from
        val end = from + length
        while (p < end) {
            when (u1(p)) {
                0x12, 0x13 -> {
                    val index = if (u1(p) == 0x12) u1(p + 1) else u2(p + 1)
                    if (index in stringRef.indices && stringRef[index] >= 0) pending = utf8[stringRef[index]]
                }
                0xBB -> {
                    val index = u2(p + 1)
                    val cls = if (index in classRef.indices && classRef[index] >= 0) utf8[classRef[index]] else null
                    if (pending != null && cls != null) after.getOrPut(pending) { ArrayList(1) } += cls
                    pending = null
                }
            }
            p += EnumConstants.instructionLength(code, p, from)
        }
    }
}
