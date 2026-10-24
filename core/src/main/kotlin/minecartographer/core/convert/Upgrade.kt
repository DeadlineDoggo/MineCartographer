package minecartographer.core.convert

import minecartographer.core.BlockState
import minecartographer.core.anvil.ChunkTransform
import minecartographer.core.anvil.LegacyBlocks
import minecartographer.core.nbt.ByteTag
import minecartographer.core.nbt.CompoundTag
import minecartographer.core.nbt.IntArrayTag
import minecartographer.core.nbt.IntTag
import minecartographer.core.nbt.ListTag
import minecartographer.core.nbt.ShortTag
import minecartographer.core.nbt.StringTag

/**
 * What Minecraft's own upgrades do to blocks, biomes, items, block entities and entities, done here so chunks of an
 * older version can be merged into a newer chunk. Uses [MinecraftTables] read from an installed jar ([tables]); without
 * one, old blocks fall back to [LegacyBlocks] (their looks only) and old items and entities are dropped.
 */
object Upgrade {
    @Volatile
    var tables: MinecraftTables? = null

    const val DV_FLATTENING = 1451
    private const val DV_1_14 = 1952
    private const val DV_UUID_ARRAYS = 2513
    private const val DV_1_16 = 2566
    private const val DV_1_17 = 2724
    const val DV_1_18 = 2860
    private const val DV_SPAWN_DATA_ENTITY = 2831
    private const val DV_SIGN_SIDES = 3463
    private const val DV_SHORT_GRASS = 3698

    private val DYE = arrayOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )

    // ---- Blocks ----

    /** A pre-1.13 vanilla block (`id shl 4 or data`) as of [toDv]; [be] is its block entity, which some blocks need. */
    fun legacyBlock(key: Int, be: CompoundTag?, toDv: Int): BlockState {
        val t = tables
        val flat = t?.blocks?.get(key) ?: t?.blocks?.get(key and 15.inv()) ?: LegacyBlocks.state(key ushr 4, key and 15)
        return renamed(withEntity(key, flat, be), DV_FLATTENING, toDv)
    }

    /** Blocks whose 1.13 state comes from their block entity: flower pots, heads, beds, banners, note blocks. */
    private fun withEntity(key: Int, flat: BlockState, be: CompoundTag?): BlockState {
        val id = key ushr 4
        val data = key and 15
        if (be == null) return flat
        return when (id) {
            140 -> potted(be)?.let { BlockState.of("minecraft:potted_$it") } ?: flat
            144 -> {
                val type = arrayOf("skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head")
                    .getOrElse(be.int("SkullType") ?: 0) { "skeleton_skull" }
                if (data and 7 == 1) BlockState.of("minecraft:$type", mapOf("rotation" to (((be.int("Rot") ?: 0) and 15).toString())))
                else BlockState.of("minecraft:" + type.replace("_skull", "_wall_skull").replace("_head", "_wall_head"),
                    mapOf("facing" to (flat.properties["facing"] ?: "north")))
            }
            26 -> be.int("color")?.let { c -> BlockState.of("minecraft:${DYE.getOrElse(c) { "red" }}_bed", flat.properties) } ?: flat
            176, 177 -> be.int("Base")?.let { base ->
                val color = DYE.getOrElse(15 - base) { "white" }
                BlockState.of("minecraft:$color" + if (id == 176) "_banner" else "_wall_banner", flat.properties)
            } ?: flat
            25 -> be.int("note")?.let { BlockState.of(flat.name, flat.properties + ("note" to it.toString())) } ?: flat
            else -> flat
        }
    }

    private fun potted(be: CompoundTag): String? {
        val item = when (val tag = be["Item"]) {
            is StringTag -> tag.value.substringAfter(':')
            is IntTag, is ShortTag -> tables?.itemIds?.get(be.int("Item"))?.substringAfter(':')
            else -> null
        } ?: return null
        val d = be.int("Data") ?: 0
        val wood = arrayOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")
        return when (item) {
            "red_flower" -> arrayOf("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy").getOrNull(d)
            "yellow_flower" -> "dandelion"
            "sapling" -> wood.getOrNull(d)?.let { "${it}_sapling" }
            "red_mushroom", "brown_mushroom", "cactus" -> item
            "deadbush" -> "dead_bush"
            "tallgrass" -> if (d == 2) "fern" else null
            "air", "" -> null
            else -> null
        }
    }

    /** Renames Minecraft made to blocks after 1.13, applied from [fromDv] up to [toDv]. */
    fun renamed(state: BlockState, fromDv: Int, toDv: Int): BlockState {
        var s = state
        fun crossed(dv: Int) = fromDv < dv && toDv >= dv
        if (crossed(DV_1_14)) s = when (s.name) {
            "minecraft:sign" -> BlockState.of("minecraft:oak_sign", s.properties)
            "minecraft:wall_sign" -> BlockState.of("minecraft:oak_wall_sign", s.properties)
            "minecraft:stone_slab" -> BlockState.of("minecraft:smooth_stone_slab", s.properties)
            else -> s
        }
        if (crossed(DV_1_16) && s.name.endsWith("_wall") && s.properties.values.any { it == "true" || it == "false" }) {
            s = BlockState.of(s.name, s.properties.mapValues { (k, v) ->
                if (k in setOf("north", "east", "south", "west")) (if (v == "true") "low" else if (v == "false") "none" else v) else v
            })
        }
        if (crossed(DV_1_17)) s = when {
            s.name == "minecraft:grass_path" -> BlockState.of("minecraft:dirt_path", s.properties)
            s.name == "minecraft:cauldron" && (s.properties["level"] ?: "0") != "0" -> BlockState.of("minecraft:water_cauldron", mapOf("level" to s.properties.getValue("level")))
            s.name == "minecraft:cauldron" -> BlockState.of("minecraft:cauldron")
            else -> s
        }
        if (crossed(DV_SHORT_GRASS) && s.name == "minecraft:grass") s = BlockState.of("minecraft:short_grass", s.properties)
        return s
    }

    // ---- Biomes ----

    /** A biome as stored at [fromDv] (numeric id or name) as [toDv] stores it. */
    fun biome(value: Any, fromDv: Int, toDv: Int): Any {
        if (toDv < ChunkTransform.MODERN_DATA_VERSION) return value // numbers, kept as they are
        val t = tables
        var name = when (value) {
            is Int -> t?.biomeIds?.get(value) ?: "minecraft:plains"
            else -> value.toString()
        }
        if (fromDv < DV_FLATTENING) t?.biomeRenames113?.get(name)?.let { name = it }
        if (fromDv < DV_1_18 && toDv >= ChunkTransform.MODERN_DATA_VERSION) t?.biomeRenames118?.get(name)?.let { name = it }
        return name
    }

    // ---- Items, block entities, entities ----

    /** An item stack of [fromDv] as [toDv] stores it; null when it can't be read. */
    fun item(stack: CompoundTag, fromDv: Int, toDv: Int): CompoundTag? {
        val out = ChunkTransform.copy(stack) as CompoundTag
        if (fromDv >= DV_FLATTENING) return out
        val t = tables
        var name = when (val id = stack["id"]) {
            is StringTag -> id.value.let { if (':' in it) it else "minecraft:${it.lowercase()}" }
            null -> return null
            else -> stack.int("id")?.let { t?.itemIds?.get(it) } ?: return null
        }
        val damage = stack.int("Damage") ?: 0
        val flat = t?.itemFlattening?.get("$name.$damage")
        if (flat != null) name = flat
        out["id"] = StringTag(name)
        out.remove("Damage")
        if (flat == null && damage > 0) (out.compound("tag") ?: CompoundTag().also { out["tag"] = it }).putInt("Damage", damage)
        out["Count"] = ByteTag((stack.int("Count") ?: 1).toByte())
        return out
    }

    private fun items(list: ListTag?, fromDv: Int, toDv: Int): ListTag? = list?.let { l ->
        ListTag(10, l.compounds().mapNotNullTo(ArrayList()) { item(it, fromDv, toDv) })
    }

    /** A block entity of [fromDv] as [toDv] stores it; null when it has no place there (its data moved into the block). */
    fun blockEntity(be: CompoundTag, fromDv: Int, toDv: Int): CompoundTag? {
        val out = ChunkTransform.copy(be) as CompoundTag
        if (fromDv < DV_FLATTENING) {
            val id = be.string("id") ?: return null
            val name = tables?.blockEntityIds?.get(id) ?: if (':' in id) id else "minecraft:${id.lowercase()}"
            // Their data went into the block state in 1.13.
            if (name in setOf("minecraft:flower_pot", "minecraft:noteblock")) return null
            if (name == "minecraft:skull" && (be.int("SkullType") ?: 0) != 3) return null
            out["id"] = StringTag(name)
            for (key in listOf("Items")) items(be.list(key), fromDv, toDv)?.let { out[key] = it }
            be.compound("RecordItem")?.let { r -> item(r, fromDv, toDv)?.let { out["RecordItem"] = it } ?: out.remove("RecordItem") }
            be.string("EntityId")?.let { e ->
                val entity = tables?.entityIds?.get(e) ?: "minecraft:pig"
                out.remove("EntityId")
                out["SpawnData"] = CompoundTag().apply { this["id"] = StringTag(entity) }
            }
            // Before 1.8 signs hold plain text; since then JSON.
            for (i in 1..4) out.string("Text$i")?.let { if (!it.startsWith("{") && !it.startsWith("\"")) out["Text$i"] = StringTag(json(it)) }
        }
        if (fromDv < DV_SPAWN_DATA_ENTITY && toDv >= DV_SPAWN_DATA_ENTITY) out.compound("SpawnData")?.let { data ->
            if ("entity" !in data) out["SpawnData"] = CompoundTag().apply { this["entity"] = data }
        }
        if (fromDv < DV_SIGN_SIDES && toDv >= DV_SIGN_SIDES && "Text1" in out) {
            fun side(texts: List<String>) = CompoundTag().apply {
                this["messages"] = ListTag(8, texts.mapTo(ArrayList()) { StringTag(it) })
                this["color"] = StringTag(out.string("Color") ?: "black")
                putByte("has_glowing_text", out.int("GlowingText") ?: 0)
            }
            out["front_text"] = side((1..4).map { out.string("Text$it") ?: "\"\"" })
            out["back_text"] = side(List(4) { "\"\"" })
            putFlag(out, "is_waxed", 0)
            for (k in listOf("Text1", "Text2", "Text3", "Text4", "Color", "GlowingText")) out.remove(k)
        }
        return out
    }

    /** An entity of [fromDv] as [toDv] stores it; null when it can't be carried over. */
    fun entity(e: CompoundTag, fromDv: Int, toDv: Int): CompoundTag? {
        val out = ChunkTransform.copy(e) as CompoundTag
        if (fromDv < DV_FLATTENING) {
            val id = e.string("id") ?: return null
            out["id"] = StringTag(tables?.entityIds?.get(id) ?: if (':' in id) id else return null)
            e.compound("Item")?.let { i -> item(i, fromDv, toDv)?.let { out["Item"] = it } ?: out.remove("Item") }
            e.list("Equipment")?.compounds()?.let { eq ->
                fun at(i: Int) = eq.getOrNull(i)?.let { item(it, fromDv, toDv) } ?: CompoundTag()
                out["HandItems"] = ListTag(10, mutableListOf(at(0), CompoundTag()))
                out["ArmorItems"] = ListTag(10, mutableListOf(at(1), at(2), at(3), at(4)))
                out.remove("Equipment")
            }
            for (key in listOf("HandItems", "ArmorItems")) if (e.list(key) != null && "Equipment" !in e) items(e.list(key), fromDv, toDv)?.let { out[key] = it }
            out.list("Passengers")?.let { p -> out["Passengers"] = ListTag(10, p.compounds().mapNotNullTo(ArrayList()) { entity(it, fromDv, toDv) }) }
        }
        if (fromDv < DV_UUID_ARRAYS && toDv >= DV_UUID_ARRAYS) {
            val most = out.long("UUIDMost")
            val least = out.long("UUIDLeast")
            if (most != null && least != null) {
                out["UUID"] = IntArrayTag(intArrayOf((most shr 32).toInt(), most.toInt(), (least shr 32).toInt(), least.toInt()))
                out.remove("UUIDMost")
                out.remove("UUIDLeast")
            }
        }
        return out
    }

    private fun CompoundTag.long(name: String): Long? = (this[name] as? minecartographer.core.nbt.LongTag)?.value

    private fun putFlag(tag: CompoundTag, name: String, v: Int) = tag.putByte(name, v)

    private fun json(text: String) = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
}
