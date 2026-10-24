package minecartographer.core.anvil

/**
 * Turns pre-1.13 blocks (numeric id + 4-bit data value) a quarter turn clockwise seen from above, like
 * [minecartographer.core.BlockRotation] does for block states. The data value means something else for
 * every block, so this is a table of the vanilla blocks whose data holds a direction or an axis
 * (as Minecraft 1.12's own `withRotation`). Other blocks, modded ones included, stay as they are.
 */
object LegacyRotation {
    private val TORCH_LIKE = setOf(50, 75, 76, 77, 143) // 1 east, 2 west, 3 south, 4 north (+ 5 floor / bit 8)
    private val STAIRS = setOf(53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180, 203)
    private val DOORS = setOf(64, 71, 193, 194, 195, 196, 197)
    private val STANDING_16 = setOf(63, 176) // sign, banner: rotation 0..15
    /** Facing in the low 3 bits as 2 north, 3 south, 4 west, 5 east (0 down, 1 up). */
    private val FACING_2345 = setOf(23, 29, 33, 34, 54, 61, 62, 65, 68, 130, 137, 144, 146, 154, 158, 177, 198, 210, 211, 218) + (219..234)
    /** Horizontal index in the low 2 bits: 0 south, 1 west, 2 north, 3 east. */
    private val HORIZONTAL_SWNE = setOf(26, 86, 91, 93, 94, 107, 120, 127, 131, 145, 149, 150, 183, 184, 185, 186, 187) + (235..250)
    private val TRAPDOORS = setOf(96, 167) // low 2 bits: 0 north, 1 south, 2 west, 3 east
    private val AXIS_4_8 = setOf(17, 162, 170, 202, 216) // bits 4 = x, 8 = z
    private val PLAIN_RAIL = 66
    private val POWERED_RAILS = setOf(27, 28, 157)

    /** The (id, data) key `id shl 4 or data` turned [turns] times. */
    fun rotate(key: Int, turns: Int): Int {
        var data = key and 15
        val id = key ushr 4
        repeat(turns and 3) { data = once(id, data) }
        return (id shl 4) or data
    }

    private fun once(id: Int, d: Int): Int = when (id) {
        in TORCH_LIKE -> (d and 8) or when (d and 7) { 1 -> 3; 2 -> 4; 3 -> 2; 4 -> 1; else -> d and 7 }
        69 -> (d and 8) or when (d and 7) { 1 -> 3; 2 -> 4; 3 -> 2; 4 -> 1; 5 -> 6; 6 -> 5; 7 -> 0; 0 -> 7; else -> d and 7 } // lever
        in STAIRS -> (d and 12) or when (d and 3) { 0 -> 2; 2 -> 1; 1 -> 3; else -> 0 } // east -> south -> west -> north
        in DOORS -> if (d and 8 != 0) d else (d and 12) or ((d + 1) and 3) // lower half: 0 east, 1 south, 2 west, 3 north
        in STANDING_16 -> (d + 4) and 15
        in FACING_2345 -> (d and 8) or when (d and 7) { 2 -> 5; 5 -> 3; 3 -> 4; 4 -> 2; else -> d and 7 }
        in HORIZONTAL_SWNE -> (d and 12) or ((d + 1) and 3)
        in TRAPDOORS -> (d and 12) or when (d and 3) { 0 -> 3; 3 -> 1; 1 -> 2; else -> 0 }
        in AXIS_4_8 -> (d and 3) or when (d and 12) { 4 -> 8; 8 -> 4; else -> d and 12 }
        155 -> when (d) { 3 -> 4; 4 -> 3; else -> d } // quartz pillar: 3 x, 4 z
        90 -> when (d) { 1 -> 2; 2 -> 1; else -> d } // nether portal axis
        106 -> ((d shl 1) and 15) or (d ushr 3) // vines: 1 south, 2 west, 4 north, 8 east
        99, 100 -> when (d) { 1 -> 3; 2 -> 6; 3 -> 9; 4 -> 2; 6 -> 8; 7 -> 1; 8 -> 4; 9 -> 7; else -> d } // mushroom caps
        PLAIN_RAIL -> rail(d, curves = true)
        in POWERED_RAILS -> (d and 8) or rail(d and 7, curves = false)
        else -> d
    }

    /** Rail shapes 0 north-south, 1 east-west, 2-5 ascending east/west/north/south, 6-9 curves SE, SW, NW, NE. */
    private fun rail(d: Int, curves: Boolean): Int = when (d) {
        0 -> 1; 1 -> 0
        2 -> 5; 5 -> 3; 3 -> 4; 4 -> 2 // ascending east -> south -> west -> north
        6 -> if (curves) 7 else d; 7 -> if (curves) 8 else d; 8 -> if (curves) 9 else d; 9 -> if (curves) 6 else d
        else -> d
    }
}
