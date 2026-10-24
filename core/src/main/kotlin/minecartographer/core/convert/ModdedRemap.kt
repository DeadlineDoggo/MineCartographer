package minecartographer.core.convert

import minecartographer.core.BlockState
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind

/**
 * Picks a vanilla block for a modded one the target world doesn't have: by what its name says it is (planks,
 * logs, glass, wool, ore...) and then by the color of its texture, the way it looks on the map. Blocks the map
 * doesn't draw (plants, cables, pipes) become air.
 */
object ModdedRemap {
    /** [target] for a block; [sure] is false for a guess by color alone or when nothing fits. */
    data class Mapped(val target: BlockState, val reason: String, val sure: Boolean)

    private fun s(name: String, vararg props: Pair<String, String>) = BlockState.of("minecraft:$name", props.toMap())

    private val WOOD = listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")
    private val DYE = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )
    private val PLANKS = WOOD.map { s("${it}_planks") }
    private val LOGS = WOOD.map { s("${it}_log", "axis" to "y") }
    private val LEAVES = WOOD.map { s("${it}_leaves", "persistent" to "true", "distance" to "1") }
    private val WOOL = DYE.map { s("${it}_wool") }
    private val TERRACOTTA = DYE.map { s("${it}_terracotta") } + s("terracotta")
    private val STONES = listOf("stone", "cobblestone", "mossy_cobblestone", "stone_bricks", "granite", "diorite", "andesite",
        "polished_granite", "polished_diorite", "polished_andesite", "smooth_stone", "bricks", "nether_bricks", "end_stone",
        "obsidian", "netherrack", "quartz_block", "prismarine", "dark_prismarine", "purpur_block").map { s(it) }
    private val SOILS = listOf("dirt", "coarse_dirt", "podzol", "grass_block", "clay", "gravel", "sand", "red_sand", "soul_sand").map { s(it) }
    private val METALS = listOf("iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
        "coal_block", "glowstone", "sea_lantern", "snow_block", "ice", "packed_ice", "hay_block", "bookshelf", "sponge",
        "melon", "pumpkin", "sandstone", "red_sandstone", "bone_block", "magma_block").map { s(it) }
    private val ALL_SOLID = PLANKS + LOGS + STONES + SOILS + METALS + WOOL + TERRACOTTA

    /** Name parts to the blocks they suggest; checked in order, so more specific words come first. */
    private val BY_NAME: List<Pair<List<String>, List<BlockState>>> = listOf(
        listOf("planks", "plank") to PLANKS,
        listOf("leaves", "leaf", "foliage") to LEAVES,
        listOf("log", "trunk", "bark") to LOGS,
        listOf("glass", "pane", "window") to listOf(s("glass")),
        listOf("wool", "cloth", "carpet", "cushion") to WOOL,
        listOf("sandstone") to listOf(s("sandstone"), s("red_sandstone")),
        listOf("clay", "terracotta", "ceramic") to TERRACOTTA,
        listOf("ore") to listOf(s("stone"), s("deepslate"), s("netherrack"), s("end_stone")),
        listOf("brick") to listOf(s("bricks"), s("stone_bricks"), s("nether_bricks"), s("end_stone_bricks"), s("mud_bricks")),
        listOf("cobble") to listOf(s("cobblestone"), s("mossy_cobblestone")),
        listOf("stone", "rock", "marble", "basalt", "granite", "slate", "limestone", "boulder") to STONES,
        listOf("sand") to listOf(s("sand"), s("red_sand")),
        listOf("dirt", "soil", "mud", "earth") to listOf(s("dirt"), s("coarse_dirt"), s("podzol")),
        listOf("grass", "turf") to listOf(s("grass_block")),
        listOf("snow", "ice", "frost") to listOf(s("snow_block"), s("ice"), s("packed_ice")),
        listOf("wood", "timber", "lumber") to PLANKS + LOGS,
        listOf("chest", "crate", "barrel") to listOf(s("chest", "facing" to "north"), s("barrel")),
        listOf("lamp", "light", "glow") to listOf(s("glowstone"), s("sea_lantern")),
    )

    fun auto(source: BlockState): Mapped {
        val look = BlockColors.look(source)
        val name = source.path.lowercase()
        if (name.contains("water")) return Mapped(s("water"), "water", true)
        if (name.contains("lava")) return Mapped(s("lava"), "lava", true)
        if (look.kind == BlockKind.SKIP) return Mapped(BlockState.AIR, "not drawn on the map (plant, pipe, cable...)", false)
        if (look.kind == BlockKind.WATER) return Mapped(s("water"), "liquid", false)
        for ((words, blocks) in BY_NAME) {
            val word = words.firstOrNull { it in name } ?: continue
            val best = closest(look.color, blocks)
            return Mapped(best, "\"$word\" in its name" + if (blocks.size > 1) ", closest color" else "", true)
        }
        return Mapped(closest(look.color, ALL_SOLID), "closest color", false)
    }

    /** Vanilla blocks a modded one can be mapped to, for choosing by hand. */
    val choices: List<BlockState> get() = listOf(BlockState.AIR, s("water"), s("lava")) + ALL_SOLID + LEAVES + listOf(s("glass"))

    private fun closest(rgb: Int, blocks: List<BlockState>): BlockState = blocks.minBy { distance(rgb, BlockColors.look(it).color) }

    private fun distance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        // Green weighs most for the eye.
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }
}
