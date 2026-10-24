package minecartographer.core.anvil

import minecartographer.core.BlockState
import minecartographer.core.nbt.CompoundTag

/** A mod a world was played with, as level.dat lists it. */
data class ModInfo(val id: String, val version: String)

/**
 * What Forge adds to level.dat: the mods a world was played with and, for worlds before 1.13,
 * what its modded block ids mean (chunks store only numbers there, and Forge hands them out per world).
 *
 * - [Generation.NUMERIC_ITEM_DATA] (1.6.x): `FML.ModItemData`, entries `{ItemId, ModId, ForcedName, ItemType}`.
 *   Blocks and items share the id space; `ForcedName` (the unlocalized name) is often empty.
 * - [Generation.NUMERIC_ITEM_DATA] also covers 1.7.x `FML.ItemData` (`{K: "\u0001mod:name", V: id}`), not
 *   verified on a real world.
 * - [Generation.NUMERIC_REGISTRY] (1.8 - 1.12.2): `FML.Registries["minecraft:blocks"].ids`, `{K: "mod:name", V: id}`.
 * - [Generation.NAMED] (1.13+): `fml.LoadingModList`; chunks already store block names.
 */
class ForgeData(
    val generation: Generation,
    val mods: List<ModInfo>,
    /** Block id -> name for modded ids (vanilla ids keep the built-in table); empty for [Generation.NAMED]. */
    val blockNames: Map<Int, String>,
) {
    enum class Generation(val label: String) {
        NUMERIC_ITEM_DATA("Forge 1.6 – 1.7"),
        NUMERIC_REGISTRY("Forge 1.8 – 1.12"),
        NAMED("Forge 1.13+"),
    }

    /** Mods besides Minecraft, Forge itself and its loader parts. */
    val playedMods: List<ModInfo> get() = mods.filter { it.id.lowercase() !in LOADER_IDS }

    /** True when chunks store numbers whose meaning comes from [blockNames]. */
    val numericIds: Boolean get() = generation != Generation.NAMED

    /** Maps this world's numeric block ids to states; vanilla ids go through [LegacyBlocks]. */
    fun legacyMapping(): LegacyMapping = if (blockNames.isEmpty()) LegacyMapping.VANILLA else LegacyMapping(blockNames)

    companion object {
        private val LOADER_IDS = setOf("minecraft", "mcp", "fml", "forge")

        /** Reads Forge's data from a level.dat root (the compound around `Data`); null for worlds without Forge. */
        fun read(root: CompoundTag): ForgeData? {
            val fml = root.compound("FML")
            if (fml != null) {
                val mods = mods(fml.list("ModList")?.compounds())
                fml.list("ModItemData")?.let { entries ->
                    val names = HashMap<Int, String>()
                    for (e in entries.compounds()) {
                        val id = e.int("ItemId") ?: continue
                        val mod = e.string("ModId") ?: continue
                        // Vanilla ids have fixed meanings that LegacyBlocks knows in more detail.
                        if (mod == "Minecraft" || id !in 1 until MAX_ID) continue
                        names[id] = name(mod, e.string("ForcedName").orEmpty(), id)
                    }
                    return ForgeData(Generation.NUMERIC_ITEM_DATA, mods, names)
                }
                fml.list("ItemData")?.let { entries ->
                    val names = HashMap<Int, String>()
                    for (e in entries.compounds()) {
                        val key = e.string("K") ?: continue
                        val id = e.int("V") ?: continue
                        // \u0001 marks blocks, \u0002 items.
                        if (!key.startsWith('\u0001') || id !in 1 until MAX_ID) continue
                        val name = key.substring(1)
                        if (!name.startsWith("minecraft:")) names[id] = name
                    }
                    return ForgeData(Generation.NUMERIC_ITEM_DATA, mods, names)
                }
                fml.compound("Registries")?.let { registries ->
                    val names = HashMap<Int, String>()
                    for (e in registries.compound("minecraft:blocks")?.list("ids")?.compounds().orEmpty()) {
                        val name = e.string("K")?.trimStart('\u0001') ?: continue
                        val id = e.int("V") ?: continue
                        if (!name.startsWith("minecraft:") && id in 1 until MAX_ID) names[id] = name
                    }
                    return ForgeData(Generation.NUMERIC_REGISTRY, mods, names)
                }
            }
            val modern = root.compound("fml") ?: return null
            return ForgeData(Generation.NAMED, mods(modern.list("LoadingModList")?.compounds()), emptyMap())
        }

        /** Highest numeric block id plus one: 8 bits from `Blocks` and 4 from `Add`. */
        const val MAX_ID = 4096

        private fun mods(list: List<CompoundTag>?) = list.orEmpty().mapNotNull { m ->
            m.string("ModId")?.let { ModInfo(it, m.string("ModVersion").orEmpty()) }
        }

        /**
         * 1.6 has no registry names: "IC2" + "tile.blockOreCopper" becomes `ic2:blockorecopper`. Mod ids like
         * `ProjRed|Exploration` become `projred_exploration`; an empty name falls back to the id.
         */
        private fun name(mod: String, forced: String, id: Int): String {
            fun clean(s: String) = s.lowercase().replace(Regex("[^a-z0-9_./-]"), "_").trim('_')
            val path = clean(forced.removePrefix("tile.")).ifEmpty { "id_$id" }
            return "${clean(mod).ifEmpty { "mod" }}:$path"
        }
    }
}

/**
 * Turns pre-1.13 `(id, data)` pairs into block states for one world: vanilla ids through [LegacyBlocks],
 * modded ids through the world's Forge table, with the data value kept as a `meta` property, also when it is 0
 * (it picks the variant, e.g. which ore, and only the mod's code knows how).
 */
class LegacyMapping(private val modded: Map<Int, String>) {
    private val cache = arrayOfNulls<BlockState>(ForgeData.MAX_ID shl 4)

    fun state(id: Int, data: Int): BlockState {
        if (id !in modded) return LegacyBlocks.state(id, data)
        val key = (id shl 4) or (data and 15)
        cache[key]?.let { return it }
        val name = modded.getValue(id)
        return BlockState.of(name, mapOf("meta" to (data and 15).toString())).also { cache[key] = it }
    }

    companion object {
        /** Worlds without modded ids. */
        val VANILLA = LegacyMapping(emptyMap())
    }
}
