package minecartographer.core.nbt

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
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
    /**
     * Parses uncompressed NBT. Compounds entries named in [skip] (at any depth) are stepped over
     * without being decoded, which saves most of the work when only a few tags are needed.
     */
    fun read(bytes: ByteArray, skip: Set<String> = emptySet()): Pair<String, CompoundTag> = try {
        Reader(ByteBuffer.wrap(bytes), skip).root()
    } catch (e: BufferUnderflowException) {
        throw NbtException("NBT data ends unexpectedly")
    }

    fun write(output: DataOutput, root: CompoundTag, name: String = "") {
        output.writeByte(10)
        output.writeUTF(name)
        writePayload(output, root)
    }

    fun readGzip(input: InputStream): CompoundTag = read(GZIPInputStream(input, 1 shl 16).readAllBytes()).second

    fun writeGzip(out: OutputStream, root: CompoundTag) {
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(out))).use { write(it, root) }
    }

    fun toZlib(root: CompoundTag): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(bytes))).use { write(it, root) }
        return bytes.toByteArray()
    }

    private const val MAX_DEPTH = 512

    private class Reader(private val buf: ByteBuffer, private val skip: Set<String>) {
        fun root(): Pair<String, CompoundTag> {
            val type = buf.get().toInt()
            require(type == 10) { "Root tag must be a compound, got type $type" }
            val name = utf()
            return name to payload(10, 0) as CompoundTag
        }

        fun payload(type: Int, depth: Int): Tag {
            if (depth > MAX_DEPTH) throw NbtException("NBT nested too deeply")
            return when (type) {
                1 -> ByteTag(buf.get())
                2 -> ShortTag(buf.getShort())
                3 -> IntTag(buf.getInt())
                4 -> LongTag(buf.getLong())
                5 -> FloatTag(buf.getFloat())
                6 -> DoubleTag(buf.getDouble())
                7 -> ByteArrayTag(ByteArray(length(1)).also { buf.get(it) })
                8 -> StringTag(utf())
                9 -> {
                    val elementType = buf.get().toInt()
                    val n = length(0)
                    val list = ListTag(elementType, ArrayList(minOf(n, 1024)))
                    repeat(n) { list.items += payload(elementType, depth + 1) }
                    list
                }
                10 -> {
                    val compound = CompoundTag()
                    while (true) {
                        val t = buf.get().toInt()
                        if (t == 0) break
                        val name = utf()
                        if (name in skip) skipPayload(t, depth + 1) else compound[name] = payload(t, depth + 1)
                    }
                    compound
                }
                11 -> {
                    val a = IntArray(length(4))
                    buf.asIntBuffer().get(a)
                    buf.position(buf.position() + a.size * 4)
                    IntArrayTag(a)
                }
                12 -> {
                    val a = LongArray(length(8))
                    buf.asLongBuffer().get(a)
                    buf.position(buf.position() + a.size * 8)
                    LongArrayTag(a)
                }
                else -> throw NbtException("Unknown tag type $type")
            }
        }

        private fun skipPayload(type: Int, depth: Int) {
            if (depth > MAX_DEPTH) throw NbtException("NBT nested too deeply")
            when (type) {
                1 -> skipBytes(1)
                2 -> skipBytes(2)
                3, 5 -> skipBytes(4)
                4, 6 -> skipBytes(8)
                7 -> skipBytes(length(1))
                8 -> skipBytes(buf.getShort().toInt() and 0xFFFF)
                9 -> {
                    val elementType = buf.get().toInt()
                    val n = length(0)
                    when (elementType) {
                        0 -> {}
                        1 -> skipBytes(n)
                        2 -> skipBytes(n * 2)
                        3, 5 -> skipBytes(n * 4)
                        4, 6 -> skipBytes(n * 8)
                        else -> repeat(n) { skipPayload(elementType, depth + 1) }
                    }
                }
                10 -> while (true) {
                    val t = buf.get().toInt()
                    if (t == 0) break
                    skipBytes(buf.getShort().toInt() and 0xFFFF)
                    skipPayload(t, depth + 1)
                }
                11 -> skipBytes(length(4) * 4)
                12 -> skipBytes(length(8) * 8)
                else -> throw NbtException("Unknown tag type $type")
            }
        }

        private fun skipBytes(n: Int) {
            if (n > buf.remaining()) throw BufferUnderflowException()
            buf.position(buf.position() + n)
        }

        /** Reads an element count and checks that [elementSize]-byte elements would fit in what is left. */
        private fun length(elementSize: Int): Int {
            val n = buf.getInt()
            if (n < 0 || n > 64 * 1024 * 1024 || n.toLong() * elementSize > buf.remaining()) throw NbtException("Bad NBT length $n")
            return n
        }

        /** Java's modified UTF-8; plain ASCII (nearly every tag name) is decoded directly. */
        private fun utf(): String {
            val n = buf.getShort().toInt() and 0xFFFF
            if (n > buf.remaining()) throw BufferUnderflowException()
            val start = buf.position()
            val array = buf.array()
            val offset = buf.arrayOffset() + start
            var ascii = true
            for (i in offset until offset + n) if (array[i] <= 0) { ascii = false; break }
            buf.position(start + n)
            return if (ascii) String(array, offset, n, Charsets.ISO_8859_1)
            else DataInputStream(ByteArrayInputStream(array, offset - 2, n + 2)).readUTF()
        }
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
