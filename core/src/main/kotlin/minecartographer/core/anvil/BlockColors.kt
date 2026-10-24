package minecartographer.core.anvil

import minecartographer.core.BlockState
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
 * Top-down colors for block names (older numeric ids are mapped to names by [LegacyBlocks]). Colors are close to
 * Minecraft's map colors; unknown and modded blocks get a guess from their name, then a neutral gray.
 *
 * Modded blocks can do better once their textures are known: every modded name looked up is remembered
 * ([takeWanted]), and the app [learn]s its color from the mod's textures. Learned looks win over guesses.
 */
object BlockColors {
    private val cache = ConcurrentHashMap<String, BlockLook>()
    private val learned = ConcurrentHashMap<String, BlockLook>()
    private val wanted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun look(name: String): BlockLook = learned[name] ?: cache.getOrPut(name) {
        if (isModded(name)) wanted += name
        classify(name.substringAfter(':'))
    }

    /** Modded names looked up since the last call, still without a learned look. */
    fun takeWanted(): Set<String> {
        val names = wanted.toSet()
        wanted.removeAll(names)
        return names - learned.keys
    }

    /** The look learned for a name or `name[meta=n]` key, if any. */
    fun learnedLook(key: String): BlockLook? = learned[key]

    /** Uses [look] for [name] from now on (chunks read before keep their colors until read again). */
    fun learn(name: String, look: BlockLook) {
        learned[name] = look
    }

    /** [rgb] as a solid look, for colors learned from textures. */
    fun solidLook(rgb: Int) = solid(rgb and 0xFFFFFF)

    private fun isModded(name: String) = ':' in name && !name.startsWith("minecraft:") && !name.startsWith("minecartographer:")

    fun look(state: BlockState): BlockLook {
        if (state.name == BlockState.COLOR_BLOCK) return solid(state.properties["rgb"]?.toIntOrNull(16) ?: 0x8A8A8A)
        // Modded blocks before 1.13 keep their data value, which often picks the variant (which ore, which stone).
        val meta = state.properties["meta"]
        if (meta != null && isModded(state.name)) {
            val key = keyOf(state.name, meta)
            learned[key]?.let { return it }
            wanted += key
        }
        return look(state.name)
    }

    /** What [takeWanted] and [learn] use for a block with a data value: `mod:name[meta=3]`. */
    fun keyOf(name: String, meta: String) = "$name[meta=$meta]"

    /** The block a [takeWanted] key stands for. */
    fun stateOf(key: String): BlockState {
        val bracket = key.indexOf("[meta=")
        if (bracket < 0) return BlockState.of(key)
        return BlockState.of(key.substring(0, bracket), mapOf("meta" to key.substring(bracket + 6).trimEnd(']')))
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
}
