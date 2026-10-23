package minecartographer.core.anvil

import minecartographer.core.ChunkPos
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.Nbt
import minecartographer.core.nbt.NbtException
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** A chunk as stored in a region file: compression type and the still-compressed bytes. */
class RawChunk(val compression: Int, val data: ByteArray, val timestamp: Int = 0) {
    fun nbt(): CompoundTag {
        val input = when (compression) {
            GZIP -> GZIPInputStream(ByteArrayInputStream(data))
            ZLIB -> InflaterInputStream(ByteArrayInputStream(data))
            NONE -> ByteArrayInputStream(data)
            LZ4 -> throw NbtException("LZ4-compressed chunks are not supported (server.properties region-file-compression)")
            else -> throw NbtException("Unknown chunk compression $compression")
        }
        return Nbt.read(DataInputStream(input.buffered())).second
    }

    companion object {
        const val GZIP = 1
        const val ZLIB = 2
        const val NONE = 3
        const val LZ4 = 4
        /** Flag on the compression byte: the payload lives in an external c.X.Z.mcc file. */
        const val EXTERNAL = 128

        fun of(nbt: CompoundTag) = RawChunk(ZLIB, Nbt.toZlib(nbt), (System.currentTimeMillis() / 1000).toInt())
    }
}

/**
 * Reads an Anvil region file (r.X.Z.mca, 32x32 chunks): an 8 KiB header of sector offsets and
 * timestamps followed by 4 KiB sectors. Reads are positional, so one instance is safe to share
 * between threads.
 */
class RegionFile(val path: Path) : Closeable {
    val regionX: Int
    val regionZ: Int
    private val channel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
    private val offsets = IntArray(1024)
    private val timestamps = IntArray(1024)

    init {
        val (x, z) = parseName(path.fileName.toString()) ?: throw IllegalArgumentException("Not a region file name: $path")
        regionX = x
        regionZ = z
        if (channel.size() >= HEADER_BYTES) {
            val header = ByteBuffer.allocate(HEADER_BYTES)
            readFully(header, 0)
            header.flip()
            for (i in 0 until 1024) offsets[i] = header.getInt()
            for (i in 0 until 1024) timestamps[i] = header.getInt()
        }
    }

    /** Chunk positions (world chunk coordinates) that have data in this file. */
    fun chunks(): List<ChunkPos> = (0 until 1024).filter { offsets[it] != 0 }.map { ChunkPos(regionX * 32 + (it and 31), regionZ * 32 + (it shr 5)) }

    fun has(pos: ChunkPos) = offsets[index(pos)] != 0

    fun read(pos: ChunkPos): RawChunk? {
        val entry = offsets[index(pos)]
        if (entry == 0) return null
        val start = (entry ushr 8).toLong() * SECTOR
        val head = ByteBuffer.allocate(5)
        readFully(head, start)
        head.flip()
        val length = head.getInt()
        val type = head.get().toInt() and 0xFF
        if (type and RawChunk.EXTERNAL != 0) {
            val external = path.resolveSibling("c.${pos.x}.${pos.z}.mcc")
            return RawChunk(type and RawChunk.EXTERNAL.inv(), Files.readAllBytes(external), timestamps[index(pos)])
        }
        if (length <= 1 || length > (entry and 0xFF) * SECTOR) return null // corrupt entry
        val data = ByteBuffer.allocate(length - 1)
        readFully(data, start + 5)
        return RawChunk(type, data.array(), timestamps[index(pos)])
    }

    private fun readFully(buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n < 0) throw java.io.EOFException("Unexpected end of $path")
            pos += n
        }
    }

    override fun close() = channel.close()

    companion object {
        const val SECTOR = 4096
        const val HEADER_BYTES = 8192
        private val NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

        fun parseName(name: String): Pair<Int, Int>? = NAME.matchEntire(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        fun fileName(regionX: Int, regionZ: Int) = "r.$regionX.$regionZ.mca"
        fun index(pos: ChunkPos) = (pos.x and 31) + (pos.z and 31) * 32
    }
}

/** Writes a complete region file from scratch. */
object RegionWriter {
    private const val MAX_SECTORS = 255

    fun write(path: Path, chunks: Map<ChunkPos, RawChunk>) {
        Files.createDirectories(path.parent)
        val header = ByteBuffer.allocate(RegionFile.HEADER_BYTES)
        val body = java.io.ByteArrayOutputStream()
        var sector = 2
        val sorted = chunks.entries.sortedBy { RegionFile.index(it.key) }
        val offsets = IntArray(1024)
        val stamps = IntArray(1024)
        for ((pos, chunk) in sorted) {
            val i = RegionFile.index(pos)
            var payload = chunk.data
            var type = chunk.compression
            val external = path.resolveSibling("c.${pos.x}.${pos.z}.mcc")
            if ((payload.size + 5 + RegionFile.SECTOR - 1) / RegionFile.SECTOR > MAX_SECTORS) {
                Files.write(external, payload)
                payload = ByteArray(0)
                type = type or RawChunk.EXTERNAL
            } else {
                Files.deleteIfExists(external)
            }
            val length = payload.size + 1
            val sectors = (length + 4 + RegionFile.SECTOR - 1) / RegionFile.SECTOR
            val out = ByteBuffer.allocate(sectors * RegionFile.SECTOR)
            out.putInt(length).put(type.toByte()).put(payload)
            body.write(out.array())
            offsets[i] = (sector shl 8) or sectors
            stamps[i] = chunk.timestamp
            sector += sectors
        }
        offsets.forEach(header::putInt)
        stamps.forEach(header::putInt)
        Files.newOutputStream(path).use {
            it.write(header.array())
            body.writeTo(it)
        }
    }
}
