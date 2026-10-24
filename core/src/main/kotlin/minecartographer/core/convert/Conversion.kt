package minecartographer.core.convert

import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.ChunkTransform
import minecartographer.core.anvil.LegacyBlocks
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.StringTag

/**
 * How chunks of [source] become chunks of [target]: modded blocks the target doesn't have are mapped to others
 * ([overrides] by the user, otherwise [ModdedRemap]), and, for chunks merged into one of the target's (raised or cut),
 * blocks, biomes, block entities and entities are upgraded to the target's version ([Upgrade]).
 */
class Conversion(val source: AnvilWorld, val target: AnvilWorld, val overrides: Map<String, String> = emptyMap()) {
    val fromDv = source.dataVersion ?: 0
    val toDv = target.dataVersion ?: 0
    private val sourceLegacy = ChunkTransform.formatOf(source.dataVersion) == ChunkTransform.Format.LEGACY
    private val targetLegacy = ChunkTransform.formatOf(target.dataVersion) == ChunkTransform.Format.LEGACY

    /** Modded block names the target numbers (before 1.13) -> its id. */
    private val targetIds: Map<String, Int> =
        target.info.forge?.takeIf { it.numericIds }?.blockNames?.entries?.associate { (id, name) -> name to id }.orEmpty()
    private val targetMods: Set<String> = target.info.forge?.mods?.mapTo(HashSet()) { it.id.lowercase() }.orEmpty()

    /** True when some of the source's blocks don't exist in the target as they are stored, so chunks must be rewritten. */
    val remaps: Boolean = run {
        val forge = source.info.forge ?: return@run false
        if (forge.numericIds) forge.blockNames.isNotEmpty() && forge.blockNames != target.info.forge?.blockNames
        else forge.playedMods.any { it.id.lowercase() !in targetMods }
    }

    /** Whether the target has no such block: a modded one of a mod (or, before 1.13, a numbering) the target lacks. */
    fun foreign(state: BlockState): Boolean {
        val ns = state.name.substringBefore(':')
        if (ns == "minecraft" || ns == "minecartographer") return false
        return if (sourceLegacy && targetLegacy) state.name !in targetIds else ns.lowercase() !in targetMods
    }

    /** What a foreign block becomes: the user's choice, or a guess. */
    fun mapped(state: BlockState): ModdedRemap.Mapped =
        overrides[keyOf(state)]?.let { ModdedRemap.Mapped(parse(it), "chosen by you", true) } ?: ModdedRemap.auto(state)

    /**
     * Whether an old `id shl 4 or data` is foreign: a modded block the target lacks, or a number with no name at all
     * (a mod's block missing from level.dat) that isn't a vanilla block either.
     */
    private fun foreignKey(entry: Int, state: BlockState): Boolean =
        if (state.name == UNKNOWN) remaps && !vanilla(entry) else foreign(state)

    private fun vanilla(key: Int): Boolean = Upgrade.tables?.blocks?.let { key in it || (key and 15.inv()) in it } ?: ((key ushr 4) < 256)

    /** A block entry of the source, in the source's own format, with foreign blocks replaced. */
    fun remapEntry(entry: Any): Any = when (entry) {
        is Int -> {
            val state = source.legacy.state(entry ushr 4, entry and 15)
            if (!foreignKey(entry, state)) entry
            else targetIds[state.name]?.let { (it shl 4) or (entry and 15) } ?: legacyKey(mapped(state).target)
        }
        is CompoundTag -> stateOf(entry).let { if (foreign(it)) entryOf(mapped(it).target) else entry }
        else -> entry
    }

    /** Whether [entry] holds a foreign block (its block entity then has no place in the target either). */
    fun isForeign(entry: Any): Boolean = when (entry) {
        is Int -> foreignKey(entry, source.legacy.state(entry ushr 4, entry and 15))
        is CompoundTag -> foreign(stateOf(entry))
        else -> false
    }

    /** A block entry of the source as the target's version stores it (a palette entry); [be] is its block entity. */
    fun upgradeEntry(entry: Any, be: CompoundTag?): CompoundTag = entryOf(when (entry) {
        is Int -> {
            val state = source.legacy.state(entry ushr 4, entry and 15)
            when {
                state.name.startsWith("minecraft:") || (state.name == UNKNOWN && vanilla(entry)) -> Upgrade.legacyBlock(entry, be, toDv)
                else -> mapped(state).target // modded numbers mean nothing in a newer version
            }
        }
        is CompoundTag -> stateOf(entry).let { if (foreign(it)) mapped(it).target else Upgrade.renamed(it, fromDv, toDv) }
        else -> BlockState.AIR
    })

    fun upgradeBiome(value: Any): Any = Upgrade.biome(value, fromDv, toDv)
    fun upgradeBlockEntity(be: CompoundTag): CompoundTag? = Upgrade.blockEntity(be, fromDv, toDv)
    fun upgradeEntity(e: CompoundTag): CompoundTag? = Upgrade.entity(e, fromDv, toDv)

    /** The old `id shl 4 or data` of a vanilla block, for targets that still store numbers. */
    private fun legacyKey(state: BlockState): Int {
        val key = Upgrade.tables?.legacyKeys?.get(state.name) ?: LegacyFallback.keys[state.name] ?: return 0
        // Leaves placed by hand don't decay.
        return if (state.name.endsWith("_leaves")) key or 4 else key
    }

    companion object {
        private const val UNKNOWN = "minecartographer:unknown"

        /** How mapping choices are keyed: the block name, plus the data value for blocks before 1.13. */
        fun keyOf(state: BlockState): String = state.properties["meta"]?.let { BlockColors.keyOf(state.name, it) } ?: state.name

        fun parse(text: String): BlockState {
            val bracket = text.indexOf('[')
            if (bracket < 0) return BlockState.of(text)
            val props = text.substring(bracket + 1, text.lastIndexOf(']')).split(',').mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
            }.toMap()
            return BlockState.of(text.substring(0, bracket), props)
        }

        /** `minecraft:oak_log[axis=y]`, the way [parse] reads it. */
        fun format(state: BlockState): String =
            if (state.properties.isEmpty()) state.name else state.name + state.properties.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" }

        fun stateOf(entry: CompoundTag): BlockState = BlockState.of(entry.string("Name") ?: "minecraft:air",
            entry.compound("Properties")?.entries?.mapNotNull { (k, v) -> (v as? StringTag)?.let { k to it.value } }?.toMap().orEmpty())

        fun entryOf(state: BlockState): CompoundTag = CompoundTag().apply {
            this["Name"] = StringTag(state.name)
            if (state.properties.isNotEmpty()) this["Properties"] = CompoundTag().apply { for ((k, v) in state.properties) this[k] = StringTag(v) }
        }
    }

    /** Old numbers of vanilla blocks from [LegacyBlocks], when no Minecraft jar gives the real table. */
    private object LegacyFallback {
        val keys: Map<String, Int> by lazy {
            val out = HashMap<String, Int>()
            for (id in 1 until 256) for (data in 0..15) out.putIfAbsent(LegacyBlocks.state(id, data).name, (id shl 4) or data)
            out
        }
    }
}
