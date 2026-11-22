package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import minecartographer.core.BlockState
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.BlockColors
import minecartographer.core.anvil.BlockKind
import minecartographer.textures.BlockModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import minecartographer.editor.EditorState
import minecartographer.editor.PickedBlock
import minecartographer.model.ModelCamera
import minecartographer.model.ModelQuad
import minecartographer.model.ModelRenderer
import minecartographer.textures.BlockModelSource
import minecartographer.textures.FlatModels
import minecartographer.viewport.imageOf
import minecartographer.editor.Tool

/** The Block dock: the block picked on the map with the Inspect block tool. */
@Composable
fun BlockPanel(state: EditorState) {
    val picked = state.pickedBlock?.takeIf { it.world == state.activeWorld }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (picked == null) {
            Txt("Click a block on the map with the Inspect block tool (K) to see what it is and where.",
                color = EditorTheme.textDim, size = 12.sp, maxLines = 4)
            if (state.tool != Tool.InspectBlock) {
                Spacer(Modifier.height(10.dp))
                TextButton("Inspect block", Modifier.fillMaxWidth(), icon = Icons.InspectBlock, primary = true) { state.selectTool(Tool.InspectBlock) }
            }
            return@Column
        }
        BlockPreview(state, picked)
        Spacer(Modifier.height(10.dp))
        BlockTitle(picked.state, (picked.origin.world as? AnvilWorld)?.legacyIdOf(picked.state))
        val properties = picked.state.properties.takeIf { picked.state.name != BlockState.COLOR_BLOCK }.orEmpty()
        if (properties.isNotEmpty()) {
            SectionHeader("PROPERTIES")
            for ((key, value) in properties.toSortedMap()) KeyValue(key, value)
        }
        if (picked.state.name != BlockState.COLOR_BLOCK) Look(state, picked)

        SectionHeader("POSITION")
        KeyValue("X Y Z", picked.coords)
        KeyValue("Chunk", "${picked.chunk.x}, ${picked.chunk.z}")
        KeyValue("In the chunk", "${picked.x - picked.chunk.x * 16}, ${picked.z - picked.chunk.z * 16}")
        KeyValue("Section", "${Math.floorDiv(picked.y, 16)}")
        KeyValue("Region file", regionFile(picked.chunk))

        SectionHeader("COMES FROM")
        KeyValue("Layer", picked.layerName)
        Origin(picked)

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton("Copy coords", Modifier.weight(1f), icon = Icons.Copy) { state.copyText(picked.coords) }
            TextButton("Clear", Modifier.weight(1f)) { state.pickedBlock = null }
        }
    }
}

/** Where the picked block was read from, and what was done to it on the way: moved, turned, raised, or below a cut. */
@Composable
private fun Origin(picked: PickedBlock) {
    val origin = picked.origin
    KeyValue("World", origin.world.name)
    if (picked.belowCut || picked.inPlace) {
        Txt(if (picked.belowCut) "Below the layer's cut at ${picked.ref.floor}: the original world's own block." else "Its own place in this world.",
            color = EditorTheme.textFaint, size = 11.sp, maxLines = 3)
        return
    }
    KeyValue("X Y Z there", "${origin.x} ${origin.y} ${origin.z}")
    KeyValue("Chunk there", "${origin.chunk.x}, ${origin.chunk.z}")
    KeyValue("Region file there", regionFile(origin.chunk))
    val ref = picked.ref
    val changes = buildList {
        if (ref.pos != picked.chunk || ref.world != picked.world) add("moved from chunk ${ref.pos.x}, ${ref.pos.z}")
        if (ref.turns != 0) add("turned ${ref.turns * 90}° clockwise")
        if (ref.dy != 0) add(if (ref.dy > 0) "raised ${ref.dy}" else "lowered ${-ref.dy}")
    }
    if (changes.isNotEmpty()) Txt(changes.joinToString(", ").replaceFirstChar { it.uppercase() } + ".",
        Modifier.padding(top = 4.dp), color = EditorTheme.textDim, size = 11.sp, maxLines = 4)
}

private fun regionFile(chunk: minecartographer.core.ChunkPos) = "r.${chunk.x shr 5}.${chunk.z shr 5}.mca"

/**
 * The block's name, large, with its namespace (the mod) under it and, in worlds before 1.13, the [legacyId] (id and
 * data value) it is stored as; demo terrain shows its color instead.
 */
@Composable
private fun BlockTitle(state: BlockState, legacyId: Pair<Int, Int>?) {
    if (state.name == BlockState.COLOR_BLOCK) {
        val rgb = state.properties["rgb"]?.toIntOrNull(16) ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF000000.toInt() or rgb))
                .border(1.dp, EditorTheme.border, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Column {
                Txt("Terrain color", weight = FontWeight.SemiBold, size = 14.sp)
                Txt("#%06X · demo world, no real blocks".format(rgb), color = EditorTheme.textDim, size = 11.sp)
            }
        }
        return
    }
    Txt(state.path, weight = FontWeight.SemiBold, size = 14.sp, maxLines = 2)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(state.name.substringBefore(':'), color = EditorTheme.textDim, size = 11.sp, mono = true)
        if (legacyId != null) WithTooltip("Stored in the chunk as block id ${legacyId.first}, data value ${legacyId.second}") {
            Txt("${legacyId.first}:${legacyId.second}", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(3.dp))
                .background(EditorTheme.window).padding(horizontal = 6.dp, vertical = 1.dp), color = EditorTheme.textDim, size = 11.sp, mono = true)
        }
    }
}

/** The pack the block's textures come from: the one of the world it was read from (as the map draws it), else this world's. */
private fun modelsFor(state: EditorState, picked: PickedBlock): BlockModels? =
    state.modelsOf(picked.ref.world) ?: state.modelsOf(picked.world)

/**
 * How the block looks: its color on the map, the mod it comes from, where its look is taken from and the textures
 * it is drawn with (click one to copy its id), with the archive each comes from.
 */
@Composable
private fun Look(state: EditorState, picked: PickedBlock) {
    val block = picked.state
    val models = modelsFor(state, picked)
    val ns = block.name.substringBefore(':')
    SectionHeader("LOOK")
    val look = BlockColors.look(block)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("Map color", Modifier.weight(1f), color = EditorTheme.textDim, size = 12.sp)
        if (look.kind == BlockKind.SKIP) Txt("not drawn, the block below shows", size = 12.sp)
        else {
            Spacer(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF000000.toInt() or look.color))
                .border(1.dp, EditorTheme.border, RoundedCornerShape(2.dp)))
            Txt((if (look.kind == BlockKind.WATER) "water, by depth" else "#%06X".format(look.color and 0xFFFFFF)),
                Modifier.padding(start = 6.dp), size = 12.sp, mono = true)
        }
    }
    if (ns != "minecraft") KeyValue("Mod", modOf(picked, ns, models))
    if (models == null) {
        Txt("Load this world's textures (the Textured mode, 3) to see which ones the block is drawn with.",
            Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 3)
        return
    }
    val details by produceState<Pair<BlockModels.BlockInfo?, List<BlockModels.TextureUse>>?>(null, models, block) {
        value = withContext(Dispatchers.IO) {
            runCatching { models.info(block) }.getOrNull() to runCatching { models.textureUses(block) }.getOrDefault(emptyList())
        }
    }
    val (info, uses) = details ?: return
    if (info != null) KeyValue("Look from", info.source.label)
    if (uses.isEmpty()) {
        Txt(if (info?.source == BlockModels.Source.UNKNOWN) "No texture found: the map color is guessed from the name." else "No textures: the game draws this block in code.",
            Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 3)
        return
    }
    SectionHeader("TEXTURES")
    val thumbs = rememberThumbs()(models)
    for (use in uses) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { state.copyText(use.id) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbs != null) Thumb(thumbs, use.id, null, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Txt(use.id.removePrefix("minecraft:"), size = 11.sp, mono = true, maxLines = 2)
                Txt(use.names.joinToString(", ") + (models.archiveOf(use.id)?.let { " · $it" } ?: ""),
                    color = EditorTheme.textFaint, size = 10.sp, maxLines = 2)
            }
        }
    }
}

/** The mod of namespace [ns]: as the world's level.dat lists it (with its version), else the namespace itself. */
private fun modOf(picked: PickedBlock, ns: String, models: BlockModels?): String {
    if (ns == "minecartographer") return "MineCartographer (demo)"
    val forge = (picked.origin.world as? AnvilWorld)?.info?.forge
    val id = models?.legacyModId(picked.state.name) ?: ns
    val mod = forge?.playedMods?.firstOrNull { it.id.equals(id, ignoreCase = true) || it.id.equals(ns, ignoreCase = true) }
    return when {
        mod == null -> ns
        mod.version.isBlank() -> mod.id
        else -> "${mod.id} ${mod.version}"
    }
}

/**
 * The picked block up close: its model with the world's textures (map colors until they are loaded), drawn by the
 * model editor's renderer from above at an angle. Dragging sideways turns it.
 */
@Composable
private fun BlockPreview(state: EditorState, picked: PickedBlock) {
    // Read while composing: the preview is drawn again once the world's textures arrive.
    val textured = modelsFor(state, picked)
    val models: BlockModelSource = textured ?: FlatModels
    val quads by produceState<List<ModelQuad>?>(null, models, picked.state) {
        value = withContext(Dispatchers.IO) {
            runCatching { models.model(picked.state, 0).quads.map { ModelQuad(0, 0, it) } }.getOrNull()
        }
    }
    var yaw by remember { mutableFloatStateOf(ModelCamera.ISO.yaw) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(quads, yaw, size) {
        val list = quads
        if (list.isNullOrEmpty() || size.width < 2 || size.height < 2) null else {
            // A block is 16 units; turned and seen from above it spans about 25 of them.
            val camera = ModelCamera.ISO.copy(yaw = yaw, zoom = minOf(size.width, size.height) / 28f)
            val frame = ModelRenderer.render(list, camera, size.width, size.height)
            imageOf(frame.pixels, frame.width, frame.height).toComposeImageBitmap()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(shape).background(EditorTheme.viewportBackground)
            .border(1.dp, EditorTheme.border, shape)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectDragGestures { _, drag -> yaw += drag.x * 0.6f } },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) Image(image, picked.state.name, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        else if (quads != null) Txt("Nothing to draw for this block", color = EditorTheme.textFaint, size = 11.sp)
    }
    Txt(if (textured != null) "Drag to turn it." else "Map colors: the textures show in the Textured mode (3) once loaded. Drag to turn it.",
        Modifier.padding(top = 4.dp), color = EditorTheme.textFaint, size = 11.sp, maxLines = 3)
}
