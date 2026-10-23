package minecartographer.core.anvil

import java.util.concurrent.ConcurrentHashMap

/** How a block shows up from above. */
enum class BlockKind {
    /** Not drawn; look further down (air, flowers, torches, rails...). */
    SKIP,
    /** Water surface; the color depends on depth. */
    WATER,
    SOLID,
}

class BlockLook(val kind: BlockKind, val color: Int) {
    companion object {
        val SKIP = BlockLook(BlockKind.SKIP, 0)
        val WATER = BlockLook(BlockKind.WATER, 0)
    }
}

/**
 * Top-down colors for block names (1.13+) and numeric ids (1.2-1.12). Colors are close to
 * Minecraft's map colors; unknown and modded blocks get a guess from their name, then a neutral gray.
 */
object BlockColors {
    private val cache = ConcurrentHashMap<String, BlockLook>()

    fun look(name: String): BlockLook = cache.getOrPut(name) { classify(name.substringAfter(':')) }

    fun legacy(id: Int, data: Int): BlockLook {
        val name = LEGACY_IDS[id] ?: return if (id == 0) BlockLook.SKIP else solid(0x8A8A8A)
        return if (id == 35 || id == 159 || id == 171 || id == 95 || id == 160) {
            look(name.replace("white", DYE[data and 15]))
        } else look(name)
    }

    fun waterColor(depth: Int): Int {
        val t = (depth.coerceIn(1, 16) - 1) / 15f
        return lerp(0xFF4A80E0.toInt(), 0xFF1D3F8F.toInt(), t)
    }

    private fun solid(rgb: Int) = BlockLook(BlockKind.SOLID, rgb or (0xFF shl 24))

    private fun classify(n: String): BlockLook {
        if (n in SKIP_EXACT || SKIP_SUFFIX.any { n.endsWith(it) } || SKIP_CONTAINS.any { it in n }) return BlockLook.SKIP
        if (n in WATERY) return BlockLook.WATER
        EXACT[n]?.let { return solid(it) }
        DYE.forEachIndexed { i, dye ->
            if (n.startsWith("${dye}_") && dye != "light" ) return solid(dyeColor(i, n))
        }
        for ((key, rgb) in CONTAINS) if (key in n) return solid(rgb)
        return solid(0x8A8A8A)
    }

    /** Wool is bright, concrete a bit darker, terracotta and glass muted. */
    private fun dyeColor(i: Int, n: String): Int {
        val base = DYE_RGB[i]
        return when {
            "terracotta" in n -> lerp(base or (0xFF shl 24), 0xFF9A6A55.toInt(), 0.55f) and 0xFFFFFF
            "glass" in n -> lerp(base or (0xFF shl 24), 0xFFD0D8DC.toInt(), 0.4f) and 0xFFFFFF
            "concrete" in n -> lerp(base or (0xFF shl 24), 0xFF000000.toInt(), 0.12f) and 0xFFFFFF
            else -> base
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val SKIP_EXACT = setOf(
        "air", "cave_air", "void_air", "barrier", "light", "structure_void",
        "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "tallgrass", "deadbush",
        "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
        "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower",
        "pink_petals", "yellow_flower", "red_flower", "double_plant", "pitcher_plant", "spore_blossom",
        "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch",
        "redstone_wire", "tripwire", "tripwire_hook", "lever", "ladder", "vine", "cobweb", "web",
        "string", "fire", "soul_fire", "nether_portal", "portal", "end_gateway", "scaffolding",
        "glow_lichen", "sculk_vein", "hanging_roots", "cave_vines", "cave_vines_plant", "weeping_vines",
        "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "brown_mushroom", "red_mushroom",
        "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts", "sapling",
        "small_dripleaf", "big_dripleaf_stem", "frogspawn", "repeater", "comparator", "unpowered_repeater",
        "powered_repeater", "unpowered_comparator", "powered_comparator", "end_rod", "lightning_rod", "chain",
    )
    private val SKIP_SUFFIX = listOf(
        "_sapling", "_button", "_pressure_plate", "_rail", "_wall_sign", "_wall_banner", "_banner", "_sign",
        "_hanging_sign", "_wall_hanging_sign", "_torch", "_head", "_skull", "_tulip", "_coral_fan", "_coral_wall_fan",
        "_flower", "_candle", "candle", "_pane", "_bars",
    )
    private val SKIP_CONTAINS = listOf("potted_", "flower_pot")
    private val WATERY = setOf("water", "flowing_water", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant")

    private val EXACT = mapOf(
        "grass_block" to 0x6DA544, "dirt" to 0x8C6744, "coarse_dirt" to 0x7A5A3C, "rooted_dirt" to 0x8E6A4A,
        "podzol" to 0x6A4A2A, "mycelium" to 0x6F6266, "farmland" to 0x6B4A2E, "dirt_path" to 0x9A8452, "grass_path" to 0x9A8452,
        "mud" to 0x3C3A3D, "clay" to 0xA4A8B8, "gravel" to 0x857F7D, "sand" to 0xDCCF9E, "red_sand" to 0xB86A2E,
        "sandstone" to 0xD8CB94, "red_sandstone" to 0xB45E26, "stone" to 0x7D7D7D, "cobblestone" to 0x767676,
        "mossy_cobblestone" to 0x6E7A5E, "smooth_stone" to 0x9E9E9E, "andesite" to 0x888889, "diorite" to 0xBDBDBD,
        "granite" to 0x956755, "deepslate" to 0x505053, "tuff" to 0x6C6D66, "calcite" to 0xDFDFDB, "bedrock" to 0x555555,
        "snow" to 0xF4FAFA, "snow_block" to 0xF4FAFA, "snow_layer" to 0xF4FAFA, "powder_snow" to 0xF2F7F7,
        "ice" to 0x9DBEF5, "packed_ice" to 0x8DB4F0, "blue_ice" to 0x74A8F2, "frosted_ice" to 0x9DBEF5,
        "lava" to 0xD45A12, "flowing_lava" to 0xD45A12, "obsidian" to 0x1A1428, "crying_obsidian" to 0x2A0A4E,
        "netherrack" to 0x6E3434, "soul_sand" to 0x51402F, "soul_soil" to 0x4B3A2C, "glowstone" to 0xC59B5A,
        "nether_bricks" to 0x2C161A, "nether_brick" to 0x2C161A, "basalt" to 0x4A4A50, "blackstone" to 0x2A2428,
        "crimson_nylium" to 0x852222, "warped_nylium" to 0x2B7265, "magma_block" to 0x8E3F16, "nether_wart_block" to 0x731010,
        "warped_wart_block" to 0x167A72, "end_stone" to 0xDCDE9E, "end_stone_bricks" to 0xDAE0A2, "purpur_block" to 0xA77BA7,
        "bricks" to 0x965A4B, "brick_block" to 0x965A4B, "stonebrick" to 0x7A7A7A, "stone_bricks" to 0x7A7A7A,
        "bookshelf" to 0x6E5634, "crafting_table" to 0x7A5A36, "furnace" to 0x6E6E6E, "chest" to 0x9C6E2E,
        "hay_block" to 0xB59A1E, "pumpkin" to 0xC4771A, "melon" to 0x6F9A24, "melon_block" to 0x6F9A24,
        "cactus" to 0x4F7F2A, "sugar_cane" to 0x88B85A, "reeds" to 0x88B85A, "lily_pad" to 0x2A6E2E, "waterlily" to 0x2A6E2E,
        "wheat" to 0xB59A3A, "carrots" to 0x5E9A2A, "potatoes" to 0x5E9A2A, "beetroots" to 0x5E9A2A, "bamboo" to 0x5E8F22,
        "moss_block" to 0x5A7A2A, "moss_carpet" to 0x5A7A2A, "azalea" to 0x5E7A2E, "flowering_azalea" to 0x6E7A4E,
        "quartz_block" to 0xEDE8E0, "iron_block" to 0xDCDCDC, "gold_block" to 0xF5D33A, "diamond_block" to 0x6EDCD6,
        "emerald_block" to 0x2ACB57, "lapis_block" to 0x1E4A9E, "redstone_block" to 0xAA1A10, "coal_block" to 0x141414,
        "sea_lantern" to 0xACC8BE, "prismarine" to 0x5EA49A, "dark_prismarine" to 0x345A4C, "sponge" to 0xC3C04A,
        "glass" to 0xC8DCE0, "tnt" to 0xC03A2A, "note_block" to 0x5E3E2A, "jukebox" to 0x5E3E2A, "beacon" to 0x7AE0DA,
        "white_wool" to 0xE9ECEC, "wool" to 0xE9ECEC, "carpet" to 0xE9ECEC, "stained_hardened_clay" to 0x9A6A55,
        "hardened_clay" to 0x985E43, "terracotta" to 0x985E43, "stained_glass" to 0xC8DCE0,
    )

    /** Checked in order, so more specific keys come first. */
    private val CONTAINS = listOf(
        "leaves" to 0x3F6E28, "azalea_leaves" to 0x4E7A2E, "mangrove_roots" to 0x4A3A28,
        "spruce_log" to 0x3A2A18, "birch_log" to 0xD8D6CC, "dark_oak_log" to 0x3A2A16, "log" to 0x6A5232, "wood" to 0x6A5232,
        "stem" to 0x5A2A3A, "hyphae" to 0x5A2A3A,
        "birch_planks" to 0xC4B07A, "spruce_planks" to 0x725432, "dark_oak_planks" to 0x452D16, "jungle_planks" to 0xA0734A,
        "acacia_planks" to 0xA85A32, "crimson_planks" to 0x6A3048, "warped_planks" to 0x2B6862, "cherry_planks" to 0xE2B2A8,
        "mangrove_planks" to 0x7A3630, "bamboo_planks" to 0xC2AE52, "planks" to 0x9C7F4E,
        "_slab" to 0x9E9E9E, "_stairs" to 0x8E8E8E, "_wall" to 0x7A7A7A, "_fence" to 0x8C6E44, "_door" to 0x8C6E44,
        "trapdoor" to 0x8C6E44, "_bed" to 0xA02A2A, "bed" to 0xA02A2A,
        "copper" to 0xC06C50, "oxidized" to 0x52A28A, "weathered" to 0x6C9A74, "exposed" to 0xA0786A,
        "amethyst" to 0x8A62C2, "sculk" to 0x0E2A32, "dripstone" to 0x8A6A5A, "deepslate" to 0x505053,
        "ore" to 0x7A7A7A, "nether" to 0x4A1E1E, "end_" to 0xDCDE9E, "purpur" to 0xA77BA7, "prismarine" to 0x5EA49A,
        "sandstone" to 0xD8CB94, "sand" to 0xDCCF9E, "stone" to 0x7D7D7D, "brick" to 0x8A5A4B, "quartz" to 0xEDE8E0,
        "coral" to 0xC85A8A, "mushroom" to 0x9A6A50, "snow" to 0xF4FAFA, "ice" to 0x9DBEF5, "grass" to 0x6DA544,
        "dirt" to 0x8C6744, "mud" to 0x3C3A3D, "terracotta" to 0x985E43, "concrete" to 0x8A8A8A, "wool" to 0xD8D8D8,
        "glass" to 0xC8DCE0, "iron" to 0xC8C8C8, "gold" to 0xE6C53A,
    )

    private val DYE = arrayOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )
    private val DYE_RGB = intArrayOf(
        0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C527, 0x70B919, 0xED8DAC, 0x3E4447,
        0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519,
    )

    /** Pre-1.13 numeric ids mapped to modern names (vanilla only; modded ids fall back to gray). */
    private val LEGACY_IDS: Map<Int, String> = mapOf(
        0 to "air", 1 to "stone", 2 to "grass_block", 3 to "dirt", 4 to "cobblestone", 5 to "oak_planks",
        6 to "oak_sapling", 7 to "bedrock", 8 to "flowing_water", 9 to "water", 10 to "flowing_lava", 11 to "lava",
        12 to "sand", 13 to "gravel", 14 to "gold_ore", 15 to "iron_ore", 16 to "coal_ore", 17 to "oak_log",
        18 to "oak_leaves", 19 to "sponge", 20 to "glass", 21 to "lapis_ore", 22 to "lapis_block", 24 to "sandstone",
        25 to "note_block", 26 to "red_bed", 27 to "powered_rail", 28 to "detector_rail", 30 to "cobweb",
        31 to "short_grass", 32 to "dead_bush", 35 to "white_wool", 37 to "dandelion", 38 to "poppy",
        39 to "brown_mushroom", 40 to "red_mushroom", 41 to "gold_block", 42 to "iron_block", 43 to "stone_slab",
        44 to "stone_slab", 45 to "bricks", 46 to "tnt", 47 to "bookshelf", 48 to "mossy_cobblestone", 49 to "obsidian",
        50 to "torch", 51 to "fire", 52 to "spawner", 53 to "oak_stairs", 54 to "chest", 55 to "redstone_wire",
        56 to "diamond_ore", 57 to "diamond_block", 58 to "crafting_table", 59 to "wheat", 60 to "farmland",
        61 to "furnace", 62 to "furnace", 63 to "oak_sign", 64 to "oak_door", 65 to "ladder", 66 to "rail",
        67 to "cobblestone_stairs", 68 to "oak_wall_sign", 69 to "lever", 70 to "stone_pressure_plate", 71 to "iron_door",
        72 to "oak_pressure_plate", 73 to "redstone_ore", 74 to "redstone_ore", 75 to "redstone_torch", 76 to "redstone_torch",
        77 to "stone_button", 78 to "snow_layer", 79 to "ice", 80 to "snow_block", 81 to "cactus", 82 to "clay",
        83 to "sugar_cane", 84 to "jukebox", 85 to "oak_fence", 86 to "pumpkin", 87 to "netherrack", 88 to "soul_sand",
        89 to "glowstone", 90 to "nether_portal", 91 to "pumpkin", 92 to "cake", 93 to "repeater", 94 to "repeater",
        95 to "white_stained_glass", 96 to "oak_trapdoor", 97 to "stone", 98 to "stone_bricks", 99 to "brown_mushroom_block",
        100 to "red_mushroom_block", 101 to "iron_bars", 102 to "glass_pane", 103 to "melon", 104 to "pumpkin_stem",
        105 to "melon_stem", 106 to "vine", 107 to "oak_fence_gate", 108 to "brick_stairs", 109 to "stone_brick_stairs",
        110 to "mycelium", 111 to "lily_pad", 112 to "nether_bricks", 113 to "nether_brick_fence", 114 to "nether_brick_stairs",
        115 to "nether_wart", 116 to "enchanting_table", 117 to "brewing_stand", 118 to "cauldron", 119 to "end_portal",
        120 to "end_portal_frame", 121 to "end_stone", 122 to "dragon_egg", 123 to "redstone_lamp", 124 to "redstone_lamp",
        125 to "oak_slab", 126 to "oak_slab", 127 to "cocoa", 128 to "sandstone_stairs", 129 to "emerald_ore",
        130 to "ender_chest", 131 to "tripwire_hook", 132 to "tripwire", 133 to "emerald_block", 134 to "spruce_stairs",
        135 to "birch_stairs", 136 to "jungle_stairs", 137 to "command_block", 138 to "beacon", 139 to "cobblestone_wall",
        140 to "flower_pot", 141 to "carrots", 142 to "potatoes", 143 to "oak_button", 144 to "skeleton_skull", 145 to "anvil",
        146 to "trapped_chest", 147 to "light_weighted_pressure_plate", 148 to "heavy_weighted_pressure_plate",
        149 to "comparator", 150 to "comparator", 151 to "daylight_detector", 152 to "redstone_block", 153 to "nether_quartz_ore",
        154 to "hopper", 155 to "quartz_block", 156 to "quartz_stairs", 157 to "activator_rail", 158 to "dropper",
        159 to "white_terracotta", 160 to "white_stained_glass_pane", 161 to "acacia_leaves", 162 to "acacia_log",
        163 to "acacia_stairs", 164 to "dark_oak_stairs", 165 to "slime_block", 166 to "barrier", 167 to "iron_trapdoor",
        168 to "prismarine", 169 to "sea_lantern", 170 to "hay_block", 171 to "white_carpet", 172 to "terracotta",
        173 to "coal_block", 174 to "packed_ice", 175 to "sunflower", 176 to "white_banner", 177 to "white_wall_banner",
        178 to "daylight_detector", 179 to "red_sandstone", 180 to "red_sandstone_stairs", 181 to "red_sandstone_slab",
        182 to "red_sandstone_slab", 183 to "spruce_fence_gate", 188 to "spruce_fence", 193 to "spruce_door",
        198 to "end_rod", 199 to "chorus_plant", 200 to "chorus_flower", 201 to "purpur_block", 206 to "end_stone_bricks",
        207 to "beetroots", 208 to "grass_path", 212 to "frosted_ice", 213 to "magma_block", 214 to "nether_wart_block",
        215 to "red_nether_bricks", 216 to "bone_block", 251 to "white_concrete", 252 to "white_concrete_powder",
    )
}
