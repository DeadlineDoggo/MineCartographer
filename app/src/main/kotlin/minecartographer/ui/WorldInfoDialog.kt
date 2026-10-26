package minecartographer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import minecartographer.core.WorldSource
import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.anvil.WorldDetails
import minecartographer.editor.EditorState
import minecartographer.textures.ModLocator
import minecartographer.textures.PackCache
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.io.path.name

/**
 * The Info window of a world, from its context menu: where it lives, which version and mods it was played with, where
 * the mods and textures come from, the dates, the game settings and what it takes on disk. Paths come with buttons to
 * open or copy them.
 */
@Composable
fun WorldInfoDialog(state: EditorState) {
    val world = state.worldInfo ?: return
    DialogWindow(
        onCloseRequest = { state.worldInfo = null },
        title = "Info · ${world.name}",
        icon = AppIconPainter,
        state = rememberDialogState(size = DpSize(780.dp, 820.dp)),
    ) {
        Column(Modifier.fillMaxSize().background(EditorTheme.window).padding(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(world.name, Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Txt(world.versionLabel, color = EditorTheme.textDim, size = 12.sp, mono = true)
                }
                Content(state, world)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("Close", primary = true) { state.worldInfo = null }
            }
        }
    }
}

@Composable
private fun Content(state: EditorState, world: WorldSource) {
    val anvil = world as? AnvilWorld
    // Walking a big world folder takes a while: the rest shows first.
    val details by produceState<WorldDetails?>(null, anvil) { value = anvil?.let { withContext(Dispatchers.IO) { WorldDetails.read(it.folder) } } }
    val d = details

    SectionHeader("WORLD")
    Info("Name", world.name)
    Info("Game version", listOfNotNull(world.versionLabel, world.dataVersion?.let { "data version $it" }, d?.versionId?.takeIf { it != world.dataVersion }?.let { "id $it" },
        if (d?.snapshot == true) "snapshot" else null).joinToString(" · "))
    anvil?.let { Info("Chunks", "%,d with data".format(it.chunkCount)) }
    world.bounds?.let { b ->
        Info("Area", "chunks ${b.minX}, ${b.minZ} to ${b.maxX}, ${b.maxZ} · blocks ${b.minX * 16}, ${b.minZ * 16} to ${b.maxX * 16 + 15}, ${b.maxZ * 16 + 15}")
    }
    Info("Build height", "${world.minY} to ${world.maxY}")
    val layers = (state.stackOf(world)?.layers?.size ?: 1) - 1
    if (layers > 0) Info("In this project", "$layers layers on top of it")
    if (anvil == null) {
        Txt("A sample world made by the editor; there is nothing of it on disk.", Modifier.padding(top = 8.dp), color = EditorTheme.textDim, size = 12.sp, maxLines = 2)
        return
    }

    SectionHeader("WHERE")
    Info("Folder", anvil.folder.toString(), path = anvil.folder)
    Info("level.dat", anvil.folder.resolve("level.dat").toString(), path = anvil.folder.resolve("level.dat"))
    val saves = anvil.folder.toAbsolutePath().normalize().parent
    val instance = saves?.takeIf { it.name.equals("saves", ignoreCase = true) }?.parent
    instance?.let { Info("Game folder", it.toString(), path = it) }
    if (instance == null) Info("Saves folder", saves?.toString(), path = saves)

    SectionHeader("DATES")
    Info("Last played", d?.lastPlayed?.let(::date))
    Info("level.dat saved", d?.levelDatModified?.let(::date))
    Info("Chunks last written", d?.newestRegion?.let(::date))
    Info("Folder created", d?.folderCreated?.let(::date))

    SectionHeader("GAME")
    if (d == null) Txt("Reading…", color = EditorTheme.textFaint, size = 12.sp) else {
        Info("Game mode", d.gameMode)
        Info("Difficulty", d.difficulty)
        Info("Hardcore", d.hardcore?.let { if (it) "yes" else "no" })
        Info("Cheats", d.cheats?.let { if (it) "allowed" else "off" })
        Info("Seed", d.seed?.toString(), copy = d.seed?.toString())
        Info("Generator", d.generator)
        Info("Spawn", d.spawn?.let { (x, y, z) -> "$x, $y, $z" }, copy = d.spawn?.let { (x, y, z) -> "$x $y $z" })
        Info("Time played", d.gameTicks?.let(::playTime))
        Info("Day", d.dayTime?.let { "${it / 24000 + 1}, ${clock(it)}" })
        d.player?.let { p ->
            val at = "%.1f %.1f %.1f".format(p.x, p.y, p.z)
            Info("Player", "$at · ${p.dimension?.removePrefix("minecraft:") ?: "overworld"}", copy = at)
        }

        SectionHeader("ON DISK")
        Info("Size", "${size(d.totalBytes)} in ${"%,d".format(d.files)} files")
        for (dim in d.dimensions) Info(dim.name, "${dim.regionFiles} region files, ${size(dim.bytes)} · ${dim.folder}")
        Info("Players", d.playerFiles.takeIf { it > 0 }?.let { "$it player files" })
        Info("Data packs", d.datapacks.takeIf { it.isNotEmpty() }?.joinToString())
    }

    val forge = anvil.info.forge
    if (forge != null) {
        SectionHeader("FORGE")
        Info("Played with", forge.generation.label)
        val loader = d?.loaderEntries ?: forge.mods.filter { it.id.lowercase() in setOf("minecraft", "mcp", "fml", "forge") }
        for (m in loader) Info(m.id, m.version)
        Info("Mods", "${forge.playedMods.size}")
        if (forge.numericIds) Info("Block ids", "%,d modded ids in level.dat".format(forge.blockNames.size))
        Column(Modifier.padding(top = 4.dp)) {
            for (m in forge.playedMods) Row(Modifier.padding(vertical = 1.dp)) {
                Txt(m.id, Modifier.width(260.dp), size = 11.sp, mono = true, color = EditorTheme.textDim)
                Txt(m.version, size = 11.sp, mono = true, color = EditorTheme.textFaint)
            }
        }
        ModFiles(state, anvil)
    }

    SectionHeader("THE EDITOR")
    Info("Texture cache", PackCache.defaultRoot.toString(), path = PackCache.defaultRoot)
    state.projectFile?.let { Info("Project file", it.toString(), path = it) }
}

/** Where the mods and textures this world is drawn with come from. */
@Composable
private fun ModFiles(state: EditorState, world: AnvilWorld) {
    val forge = world.info.forge ?: return
    val loaded = state.loadedTexturesOf(world)
    SectionHeader("MODS AND TEXTURES COME FROM")
    if (loaded == null) {
        Txt("Not looked for yet: open Mods & textures or switch to Textured mode.", color = EditorTheme.textDim, size = 12.sp, maxLines = 2)
        return
    }
    Info("Mods found", "${forge.playedMods.size - loaded.missingMods.size} of ${forge.playedMods.size}")
    val byFolder = loaded.providers.values.distinct().groupBy { it.parent }
    for ((folder, archives) in byFolder.entries.sortedByDescending { it.value.size }) {
        Info("Mods from", "${archives.size} in $folder", path = folder)
    }
    if (loaded.missingMods.isNotEmpty()) Info("Not found", loaded.missingMods.joinToString { it.id })
    val saves = world.folder.toAbsolutePath().normalize().parent
    val searched = (listOfNotNull(saves?.takeIf { it.name.equals("saves", true) }?.parent?.resolve("mods"), saves?.resolve("mods")) + ModLocator.folders)
        .distinct().filter { java.nio.file.Files.isDirectory(it) }
    for (folder in searched) Info("Looks in", folder.toString(), path = folder)
    loaded.coreJar?.let { Info("Minecraft jar", it.toString(), path = it) }
    for (file in loaded.files.filter { it != loaded.coreJar && it !in loaded.mods }) Info("Resource pack", file.toString(), path = file)
    for ((id, archive) in state.texturesOf(world).modArchives) Info("Picked for $id", archive.toString(), path = archive)
}

/** One line: the label, the value, and buttons to show a [path] or copy it, or a [copy] text. Nothing when [value] is null. */
@Composable
private fun Info(label: String, value: String?, path: Path? = null, copy: String? = null) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(170.dp), color = EditorTheme.textDim, size = 12.sp)
        Txt(value, Modifier.weight(1f), size = 12.sp, mono = path != null, maxLines = 3)
        if (path != null) {
            IconButton(Icons.Folder, "Show in the file manager", size = 24.dp, iconSize = 13.dp) { FileDialogs.showInFolder(path) }
            IconButton(Icons.Copy, "Copy the path", size = 24.dp, iconSize = 13.dp) { copyText(path.toString()) }
        } else if (copy != null) {
            IconButton(Icons.Copy, "Copy", size = 24.dp, iconSize = 13.dp) { copyText(copy) }
        }
    }
}

private fun copyText(text: String) {
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}

private val DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/** `Oct 24, 2026, 10:21:05 PM (2 days ago)`. */
private fun date(at: Instant): String {
    val ago = Duration.between(at, Instant.now())
    val rel = when {
        ago.isNegative -> null
        ago.toMinutes() < 1 -> "just now"
        ago.toHours() < 1 -> "${ago.toMinutes()} min ago"
        ago.toDays() < 1 -> "${ago.toHours()} h ago"
        ago.toDays() < 60 -> "${ago.toDays()} days ago"
        ago.toDays() < 730 -> "${ago.toDays() / 30} months ago"
        else -> "${ago.toDays() / 365} years ago"
    }
    return DATE.format(at) + (rel?.let { " ($it)" } ?: "")
}

/** `12 h 5 min` for the ticks the world has run (20 a second). */
private fun playTime(ticks: Long): String {
    val minutes = ticks / 20 / 60
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/** The time of day for a day time in ticks, 0 being 6:00. */
private fun clock(dayTime: Long): String {
    val t = ((dayTime % 24000) + 24000) % 24000
    val minutes = ((t + 6000) % 24000) * 60 / 1000
    return "%02d:%02d".format(minutes / 60, minutes % 60)
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
