package minecartographer.core

/** Chunk coordinates (16x16 block columns). Block x = chunk x * 16 + local x. */
data class ChunkPos(val x: Int, val z: Int) {
    val regionX: Int get() = x shr 5
    val regionZ: Int get() = z shr 5

    override fun toString() = "[$x, $z]"
}

/** Inclusive rectangle of chunk coordinates. */
data class ChunkBounds(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
    val centerBlockX: Double get() = (minX + maxX + 1) * 8.0
    val centerBlockZ: Double get() = (minZ + maxZ + 1) * 8.0

    operator fun contains(pos: ChunkPos) = pos.x in minX..maxX && pos.z in minZ..maxZ
}
