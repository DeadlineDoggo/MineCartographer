package minecartographer.core.nbt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream

/** Minecraft's Named Binary Tag format (big-endian, Java modified UTF-8 strings). */
sealed interface Tag {
    val id: Int
}

data class ByteTag(val value: Byte) : Tag { override val id get() = 1 }
data class ShortTag(val value: Short) : Tag { override val id get() = 2 }
data class IntTag(val value: Int) : Tag { override val id get() = 3 }
data class LongTag(val value: Long) : Tag { override val id get() = 4 }
data class FloatTag(val value: Float) : Tag { override val id get() = 5 }
data class DoubleTag(val value: Double) : Tag { override val id get() = 6 }
class ByteArrayTag(val value: ByteArray) : Tag { override val id get() = 7 }
data class StringTag(val value: String) : Tag { override val id get() = 8 }
class IntArrayTag(val value: IntArray) : Tag { override val id get() = 11 }
class LongArrayTag(val value: LongArray) : Tag { override val id get() = 12 }

/** A list of tags that all have [elementType] (0 when empty and untyped). */
class ListTag(var elementType: Int = 0, val items: MutableList<Tag> = ArrayList()) : Tag, MutableList<Tag> by items {
    override val id get() = 9

    fun compound(i: Int) = items[i] as CompoundTag
    fun compounds(): List<CompoundTag> = items.filterIsInstance<CompoundTag>()
    fun doubles(): List<Double> = items.map { (it as? DoubleTag)?.value ?: (it as? FloatTag)?.value?.toDouble() ?: 0.0 }
}

/** Mutable compound; insertion order is kept so written files stay diff-friendly. */
class CompoundTag(val entries: MutableMap<String, Tag> = LinkedHashMap()) : Tag {
    override val id get() = 10

    operator fun get(name: String): Tag? = entries[name]
    operator fun set(name: String, tag: Tag) { entries[name] = tag }
    operator fun contains(name: String) = name in entries
    fun remove(name: String) = entries.remove(name)

    fun compound(name: String) = entries[name] as? CompoundTag
    fun list(name: String) = entries[name] as? ListTag
    fun string(name: String) = (entries[name] as? StringTag)?.value
    fun byteArray(name: String) = (entries[name] as? ByteArrayTag)?.value
    fun intArray(name: String) = (entries[name] as? IntArrayTag)?.value
    fun longArray(name: String) = (entries[name] as? LongArrayTag)?.value

    /** Any numeric tag as Int (NBT writers are not consistent about Byte/Short/Int). */
    fun int(name: String): Int? = when (val t = entries[name]) {
        is ByteTag -> t.value.toInt()
        is ShortTag -> t.value.toInt()
        is IntTag -> t.value
        is LongTag -> t.value.toInt()
        else -> null
    }

    fun putInt(name: String, v: Int) = set(name, IntTag(v))
    fun putByte(name: String, v: Int) = set(name, ByteTag(v.toByte()))
}

object Nbt {
    fun read(input: DataInput): Pair<String, CompoundTag> {
        val type = input.readByte().toInt()
        require(type == 10) { "Root tag must be a compound, got type $type" }
        val name = input.readUTF()
        return name to readPayload(input, 10, 0) as CompoundTag
    }

    fun write(output: DataOutput, root: CompoundTag, name: String = "") {
        output.writeByte(10)
        output.writeUTF(name)
        writePayload(output, root)
    }

    fun readGzip(input: InputStream): CompoundTag = read(DataInputStream(BufferedInputStream(GZIPInputStream(input)))).second

    fun writeGzip(out: OutputStream, root: CompoundTag) {
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(out))).use { write(it, root) }
    }

    fun toZlib(root: CompoundTag): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(bytes))).use { write(it, root) }
        return bytes.toByteArray()
    }

    private const val MAX_DEPTH = 512

    private fun readPayload(input: DataInput, type: Int, depth: Int): Tag {
        if (depth > MAX_DEPTH) throw NbtException("NBT nested too deeply")
        return when (type) {
            1 -> ByteTag(input.readByte())
            2 -> ShortTag(input.readShort())
            3 -> IntTag(input.readInt())
            4 -> LongTag(input.readLong())
            5 -> FloatTag(input.readFloat())
            6 -> DoubleTag(input.readDouble())
            7 -> ByteArrayTag(ByteArray(checkedLength(input.readInt())).also { input.readFully(it) })
            8 -> StringTag(input.readUTF())
            9 -> {
                val elementType = input.readByte().toInt()
                val n = checkedLength(input.readInt())
                val list = ListTag(elementType, ArrayList(minOf(n, 1024)))
                repeat(n) { list.items += readPayload(input, elementType, depth + 1) }
                list
            }
            10 -> {
                val compound = CompoundTag()
                while (true) {
                    val t = input.readByte().toInt()
                    if (t == 0) break
                    val name = input.readUTF()
                    compound[name] = readPayload(input, t, depth + 1)
                }
                compound
            }
            11 -> IntArrayTag(IntArray(checkedLength(input.readInt())) { input.readInt() })
            12 -> LongArrayTag(LongArray(checkedLength(input.readInt())) { input.readLong() })
            else -> throw NbtException("Unknown tag type $type")
        }
    }

    private fun checkedLength(n: Int): Int {
        if (n < 0 || n > 64 * 1024 * 1024) throw NbtException("Bad NBT length $n")
        return n
    }

    private fun writePayload(out: DataOutput, tag: Tag) {
        when (tag) {
            is ByteTag -> out.writeByte(tag.value.toInt())
            is ShortTag -> out.writeShort(tag.value.toInt())
            is IntTag -> out.writeInt(tag.value)
            is LongTag -> out.writeLong(tag.value)
            is FloatTag -> out.writeFloat(tag.value)
            is DoubleTag -> out.writeDouble(tag.value)
            is ByteArrayTag -> { out.writeInt(tag.value.size); out.write(tag.value) }
            is StringTag -> out.writeUTF(tag.value)
            is ListTag -> {
                val type = if (tag.items.isEmpty()) tag.elementType.takeIf { it != 0 } ?: 0 else tag.items[0].id
                out.writeByte(type)
                out.writeInt(tag.items.size)
                tag.items.forEach { writePayload(out, it) }
            }
            is CompoundTag -> {
                for ((name, child) in tag.entries) {
                    out.writeByte(child.id)
                    out.writeUTF(name)
                    writePayload(out, child)
                }
                out.writeByte(0)
            }
            is IntArrayTag -> { out.writeInt(tag.value.size); tag.value.forEach(out::writeInt) }
            is LongArrayTag -> { out.writeInt(tag.value.size); tag.value.forEach(out::writeLong) }
        }
    }
}

class NbtException(message: String) : RuntimeException(message)
