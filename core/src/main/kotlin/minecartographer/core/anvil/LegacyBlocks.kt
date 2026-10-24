package minecartographer.core.anvil

import minecartographer.core.BlockState

/**
 * Pre-1.13 blocks (numeric id plus 4-bit data value) as modern block states. Covers the data values
 * that change how a block looks (wood types, colors, rail shapes, stair facing...); the rest keep
 * their default state. Modded ids become `minecartographer:unknown`, drawn gray.
 */
object LegacyBlocks {
    private val cache = arrayOfNulls<BlockState>(1 shl 16)

    fun state(id: Int, data: Int): BlockState {
        val key = (id shl 4) or (data and 15)
        cache[key]?.let { return it }
        return map(id, data and 15).also { cache[key] = it }
    }

    private val WOOD = arrayOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")
    private val DYE = arrayOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )
    private val RAIL_SHAPES = arrayOf(
        "north_south", "east_west", "ascending_east", "ascending_west", "ascending_north", "ascending_south",
        "south_east", "south_west", "north_west", "north_east",
    )
    private val STONE_SLABS = arrayOf("smooth_stone", "sandstone", "petrified_oak", "cobblestone", "brick", "stone_brick", "nether_brick", "quartz")
    private val STAIRS = mapOf(
        53 to "oak_stairs", 67 to "cobblestone_stairs", 108 to "brick_stairs", 109 to "stone_brick_stairs",
        114 to "nether_brick_stairs", 128 to "sandstone_stairs", 134 to "spruce_stairs", 135 to "birch_stairs",
        136 to "jungle_stairs", 156 to "quartz_stairs", 163 to "acacia_stairs", 164 to "dark_oak_stairs",
        180 to "red_sandstone_stairs", 203 to "purpur_stairs",
    )
    private val FENCES = mapOf(188 to "spruce_fence", 189 to "birch_fence", 190 to "jungle_fence", 191 to "dark_oak_fence", 192 to "acacia_fence")
    private val GATES = mapOf(
        107 to "oak_fence_gate", 183 to "spruce_fence_gate", 184 to "birch_fence_gate", 185 to "jungle_fence_gate",
        186 to "dark_oak_fence_gate", 187 to "acacia_fence_gate",
    )
    /** Horizontal facings as the old data values number them in several blocks (stairs: east, west, south, north). */
    private val STAIR_FACING = arrayOf("east", "west", "south", "north")
    private val HORIZONTAL_2345 = mapOf(2 to "north", 3 to "south", 4 to "west", 5 to "east")

    private fun s(name: String, vararg props: Pair<String, String>) = BlockState.of(name, props.toMap())

    private fun map(id: Int, d: Int): BlockState = when (id) {
        0 -> BlockState.AIR
        1 -> s(arrayOf("stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite").getOrElse(d) { "stone" })
        2 -> s("grass_block")
        3 -> s(arrayOf("dirt", "coarse_dirt", "podzol").getOrElse(d) { "dirt" })
        5 -> s("${WOOD.getOrElse(d and 7) { "oak" }}_planks")
        6 -> s("${WOOD.getOrElse(d and 7) { "oak" }}_sapling")
        8, 9 -> s("water")
        10, 11 -> s("lava")
        12 -> s(if (d == 1) "red_sand" else "sand")
        17 -> log(WOOD[d and 3], d)
        162 -> log(if (d and 1 == 0) "acacia" else "dark_oak", d)
        18 -> s("${WOOD[d and 3]}_leaves")
        161 -> s(if (d and 1 == 0) "acacia_leaves" else "dark_oak_leaves")
        24 -> s(arrayOf("sandstone", "chiseled_sandstone", "cut_sandstone").getOrElse(d) { "sandstone" })
        179 -> s(arrayOf("red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone").getOrElse(d) { "red_sandstone" })
        27 -> s("powered_rail", "shape" to RAIL_SHAPES[(d and 7).coerceAtMost(5)], "powered" to "${d and 8 != 0}")
        28 -> s("detector_rail", "shape" to RAIL_SHAPES[(d and 7).coerceAtMost(5)])
        157 -> s("activator_rail", "shape" to RAIL_SHAPES[(d and 7).coerceAtMost(5)])
        66 -> s("rail", "shape" to RAIL_SHAPES.getOrElse(d) { "north_south" })
        31 -> s(when (d) { 0 -> "dead_bush"; 2 -> "fern"; else -> "grass" })
        35 -> s("${DYE[d]}_wool")
        159 -> s("${DYE[d]}_terracotta")
        171 -> s("${DYE[d]}_carpet")
        95 -> s("${DYE[d]}_stained_glass")
        160 -> s("${DYE[d]}_stained_glass_pane")
        251 -> s("${DYE[d]}_concrete")
        252 -> s("${DYE[d]}_concrete_powder")
        37 -> s("dandelion")
        38 -> s(arrayOf("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy").getOrElse(d) { "poppy" })
        175 -> if (d and 8 != 0) s("tall_grass", "half" to "upper")
            else s(arrayOf("sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony").getOrElse(d) { "tall_grass" }, "half" to "lower")
        43 -> s("${STONE_SLABS[d and 7]}_slab", "type" to "double")
        44 -> s("${STONE_SLABS[d and 7]}_slab", "type" to if (d and 8 != 0) "top" else "bottom")
        125 -> s("${WOOD.getOrElse(d and 7) { "oak" }}_slab", "type" to "double")
        126 -> s("${WOOD.getOrElse(d and 7) { "oak" }}_slab", "type" to if (d and 8 != 0) "top" else "bottom")
        181 -> s("red_sandstone_slab", "type" to "double")
        182 -> s("red_sandstone_slab", "type" to if (d and 8 != 0) "top" else "bottom")
        in STAIRS -> s(STAIRS.getValue(id), "facing" to STAIR_FACING[d and 3], "half" to if (d and 4 != 0) "top" else "bottom", "shape" to "straight")
        50 -> torch("torch", "wall_torch", d)
        75, 76 -> torch("redstone_torch", "redstone_wall_torch", d)
        78 -> s("snow", "layers" to "${(d and 7) + 1}")
        86 -> s("carved_pumpkin", "facing" to arrayOf("south", "west", "north", "east")[d and 3])
        91 -> s("jack_o_lantern", "facing" to arrayOf("south", "west", "north", "east")[d and 3])
        98 -> s(arrayOf("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks").getOrElse(d) { "stone_bricks" })
        97 -> s(arrayOf("stone", "cobblestone", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks").getOrElse(d) { "stone" })
        155 -> when (d) {
            1 -> s("chiseled_quartz_block")
            2 -> s("quartz_pillar", "axis" to "y")
            3 -> s("quartz_pillar", "axis" to "x")
            4 -> s("quartz_pillar", "axis" to "z")
            else -> s("quartz_block")
        }
        168 -> s(arrayOf("prismarine", "prismarine_bricks", "dark_prismarine").getOrElse(d) { "prismarine" })
        170 -> s("hay_block", "axis" to when (d shr 2) { 1 -> "x"; 2 -> "z"; else -> "y" })
        216 -> s("bone_block", "axis" to when (d shr 2) { 1 -> "x"; 2 -> "z"; else -> "y" })
        59 -> s("wheat", "age" to "${d and 7}")
        141 -> s("carrots", "age" to "${d and 7}")
        142 -> s("potatoes", "age" to "${d and 7}")
        207 -> s("beetroots", "age" to "${d and 3}")
        60 -> s("farmland", "moisture" to "${d and 7}")
        64, 71, 193, 194, 195, 196, 197 -> door(id, d)
        85 -> s("oak_fence")
        113 -> s("nether_brick_fence")
        in FENCES -> s(FENCES.getValue(id))
        in GATES -> s(GATES.getValue(id), "facing" to arrayOf("south", "west", "north", "east")[d and 3], "open" to "${d and 4 != 0}")
        65 -> s("ladder", "facing" to (HORIZONTAL_2345[d] ?: "north"))
        61 -> s("furnace", "facing" to (HORIZONTAL_2345[d] ?: "north"), "lit" to "false")
        62 -> s("furnace", "facing" to (HORIZONTAL_2345[d] ?: "north"), "lit" to "true")
        106 -> s("vine", "south" to "${d and 1 != 0}", "west" to "${d and 2 != 0}", "north" to "${d and 4 != 0}", "east" to "${d and 8 != 0}", "up" to "${d == 0}")
        139 -> s(if (d == 1) "mossy_cobblestone_wall" else "cobblestone_wall")
        else -> s(LEGACY_NAMES[id] ?: "minecartographer:unknown")
    }

    private fun log(wood: String, d: Int) = when (d shr 2) {
        1 -> s("${wood}_log", "axis" to "x")
        2 -> s("${wood}_log", "axis" to "z")
        3 -> s("${wood}_wood", "axis" to "y")
        else -> s("${wood}_log", "axis" to "y")
    }

    /** Data 1-4 attach to a wall (east, west, south, north), 5 stands on the floor. */
    private fun torch(floor: String, wall: String, d: Int) = when (d) {
        1 -> s(wall, "facing" to "east")
        2 -> s(wall, "facing" to "west")
        3 -> s(wall, "facing" to "south")
        4 -> s(wall, "facing" to "north")
        else -> s(floor)
    }

    private fun door(id: Int, d: Int): BlockState {
        val name = when (id) {
            71 -> "iron_door"; 193 -> "spruce_door"; 194 -> "birch_door"; 195 -> "jungle_door"
            196 -> "acacia_door"; 197 -> "dark_oak_door"; else -> "oak_door"
        }
        // The upper half only stores the hinge; its facing lives in the lower half.
        return if (d and 8 != 0) s(name, "half" to "upper", "facing" to "east", "hinge" to if (d and 1 != 0) "right" else "left", "open" to "false")
        else s(name, "half" to "lower", "facing" to arrayOf("east", "south", "west", "north")[d and 3], "hinge" to "left", "open" to "${d and 4 != 0}")
    }

    /** Ids whose data value doesn't matter for their look, as modern names. */
    private val LEGACY_NAMES: Map<Int, String> = mapOf(
        4 to "cobblestone", 7 to "bedrock", 13 to "gravel", 14 to "gold_ore", 15 to "iron_ore", 16 to "coal_ore",
        19 to "sponge", 20 to "glass", 21 to "lapis_ore", 22 to "lapis_block", 23 to "dispenser", 25 to "note_block",
        26 to "red_bed", 29 to "sticky_piston", 30 to "cobweb", 32 to "dead_bush", 33 to "piston", 39 to "brown_mushroom",
        40 to "red_mushroom", 41 to "gold_block", 42 to "iron_block", 45 to "bricks", 46 to "tnt", 47 to "bookshelf",
        48 to "mossy_cobblestone", 49 to "obsidian", 51 to "fire", 52 to "spawner", 54 to "chest", 55 to "redstone_wire",
        56 to "diamond_ore", 57 to "diamond_block", 58 to "crafting_table", 63 to "oak_sign", 68 to "oak_wall_sign",
        69 to "lever", 70 to "stone_pressure_plate", 72 to "oak_pressure_plate", 73 to "redstone_ore", 74 to "redstone_ore",
        77 to "stone_button", 79 to "ice", 80 to "snow_block", 81 to "cactus", 82 to "clay", 83 to "sugar_cane",
        84 to "jukebox", 87 to "netherrack", 88 to "soul_sand", 89 to "glowstone", 90 to "nether_portal", 92 to "cake",
        93 to "repeater", 94 to "repeater", 96 to "oak_trapdoor", 99 to "brown_mushroom_block", 100 to "red_mushroom_block",
        101 to "iron_bars", 102 to "glass_pane", 103 to "melon", 104 to "pumpkin_stem", 105 to "melon_stem", 110 to "mycelium",
        111 to "lily_pad", 112 to "nether_bricks", 115 to "nether_wart", 116 to "enchanting_table", 117 to "brewing_stand",
        118 to "cauldron", 119 to "end_portal", 120 to "end_portal_frame", 121 to "end_stone", 122 to "dragon_egg",
        123 to "redstone_lamp", 124 to "redstone_lamp", 127 to "cocoa", 129 to "emerald_ore", 130 to "ender_chest",
        131 to "tripwire_hook", 132 to "tripwire", 133 to "emerald_block", 137 to "command_block", 138 to "beacon",
        140 to "flower_pot", 143 to "oak_button", 144 to "skeleton_skull", 145 to "anvil", 146 to "trapped_chest",
        147 to "light_weighted_pressure_plate", 148 to "heavy_weighted_pressure_plate", 149 to "comparator", 150 to "comparator",
        151 to "daylight_detector", 152 to "redstone_block", 153 to "nether_quartz_ore", 154 to "hopper", 158 to "dropper",
        165 to "slime_block", 166 to "barrier", 167 to "iron_trapdoor", 169 to "sea_lantern", 172 to "terracotta",
        173 to "coal_block", 174 to "packed_ice", 176 to "white_banner", 177 to "white_wall_banner", 178 to "daylight_detector",
        198 to "end_rod", 199 to "chorus_plant", 200 to "chorus_flower", 201 to "purpur_block", 202 to "purpur_pillar",
        206 to "end_stone_bricks", 208 to "dirt_path", 212 to "frosted_ice", 213 to "magma_block", 214 to "nether_wart_block",
        215 to "red_nether_bricks", 210 to "repeating_command_block", 211 to "chain_command_block", 255 to "structure_block",
    )
}
