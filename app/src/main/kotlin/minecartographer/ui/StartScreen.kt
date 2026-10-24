package minecartographer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import minecartographer.AppInfo
import minecartographer.editor.EditorState
import minecartographer.editor.WorldLibrary
import minecartographer.editor.newProjectInteractive
import minecartographer.editor.openProjectInteractive
import minecartographer.core.project.ProjectFile
import java.net.URI
import kotlin.io.path.name

/**
 * What the app opens with: the editor faded out behind a borderless card. Left: the app's name and the ways to
 * start (new or open a project, open a world, the demo) with recent files; right: a place to support the project.
 * Folds away when anything is opened, with Esc, or by clicking outside.
 */
@Composable
fun StartScreen(state: EditorState) {
    // Whatever gets opened (a world loads in the background, a project, the demo) clears the way.
    LaunchedEffect(state.busy, state.worlds.size) {
        if (state.busy || state.worlds.isNotEmpty()) state.startScreenVisible = false
    }
    if (!state.startScreenVisible) return
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxSize().background(Color(0xD9101113))
            // The editor underneath must not react while this is up; a click on the backdrop folds it.
            .pointerInput(Unit) { detectTapGestures { state.startScreenVisible = false } },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.width(780.dp).height(496.dp).clip(shape).background(EditorTheme.panel)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.weight(1.35f).fillMaxHeight().padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(AppIconPainter, null, Modifier.size(64.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Txt(AppInfo.NAME, size = 24.sp, weight = FontWeight.SemiBold)
                        Txt(AppInfo.versionLabel, color = EditorTheme.textFaint, size = 12.sp)
                    }
                }
                Spacer(Modifier.height(22.dp))
                StartAction(Icons.NewLayer, "New project", "Ctrl+N") { state.newProjectInteractive(); state.startScreenVisible = false }
                StartAction(Icons.Folder, "Open project…", "Ctrl+Shift+O") { state.openProjectInteractive() }
                StartAction(Icons.Worlds, "Open world folder…", "Ctrl+O") {
                    FileDialogs.chooseWorldFolder()?.let(state::openWorldFolder)
                }
                StartAction(Icons.Eye, "Open the demo worlds", null) { state.openDemo() }
                Recent(state)
                Spacer(Modifier.weight(1f))
                LicenseLine()
            }
            SupportSide(state, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun StartAction(icon: EditorIcon, label: String, shortcut: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hovered) EditorTheme.hover else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, Modifier.size(16.dp), EditorTheme.accent)
        Spacer(Modifier.width(12.dp))
        Txt(label, Modifier.weight(1f), size = 13.sp)
        if (shortcut != null) Txt(shortcut, color = EditorTheme.textFaint, size = 11.sp, mono = true)
    }
}

/** The last projects and worlds opened, newest first. */
@Composable
private fun Recent(state: EditorState) {
    val projects = remember { WorldLibrary.recentProjects().take(4) }
    val worlds = remember { WorldLibrary.recent().take(3) }
    if (projects.isEmpty() && worlds.isEmpty()) return
    SectionHeader("RECENT")
    for (file in projects) RecentRow(file.name.removeSuffix(".${ProjectFile.EXTENSION}"), "project · ${file.parent}") {
        state.openProjectInteractive(file)
    }
    for (folder in worlds) RecentRow(folder.name, "world · ${folder.parent}") { state.openWorldFolder(folder) }
}

@Composable
private fun RecentRow(name: String, detail: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hovered) EditorTheme.hover else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(name, Modifier.width(150.dp), size = 12.sp)
        Txt(detail, Modifier.weight(1f), color = EditorTheme.textFaint, size = 11.sp)
    }
}

/** Who made it and under which license (the NOTICE file asks every copy to show this); opens the license. */
@Composable
private fun LicenseLine() {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Txt(
        "© 2026 ${AppInfo.AUTHOR} · Free software under the GNU GPL v3",
        Modifier.padding(horizontal = 8.dp).hoverable(interaction).clickable(interaction, indication = null) { browse(AppInfo.LICENSE_URL) },
        color = if (hovered) EditorTheme.text else EditorTheme.textFaint, size = 11.sp,
    )
}

/** Footer of the right card: the GitHub mark, its name and the project's. */
@Composable
private fun GitHubFooter(modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (hovered) Color(0x22FFFFFF) else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null) { browse(AppInfo.REPOSITORY_URL) }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitHubMark(Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Txt("GitHub", size = 12.sp, weight = FontWeight.SemiBold)
            Txt(AppInfo.REPOSITORY_URL.removePrefix("https://github.com/"), color = Color(0xFFB9C4D8), size = 11.sp)
        }
    }
}

/** A simplified GitHub mark: a round head with two ears and a tail, drawn in code like the app icon. */
@Composable
private fun GitHubMark(modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    drawCircle(Color.White, radius = w * 0.5f)
    val dark = Color(0xFF1D2A40)
    val head = Offset(w * 0.5f, w * 0.52f)
    drawCircle(dark, radius = w * 0.3f, center = head)
    for (side in listOf(-1f, 1f)) {
        val ear = Path().apply {
            moveTo(head.x + side * w * 0.28f, head.y - w * 0.1f)
            lineTo(head.x + side * w * 0.26f, head.y - w * 0.34f)
            lineTo(head.x + side * w * 0.06f, head.y - w * 0.27f)
            close()
        }
        drawPath(ear, dark)
    }
    drawCircle(Color.White, radius = w * 0.05f, center = Offset(head.x - w * 0.1f, head.y - w * 0.04f))
    drawCircle(Color.White, radius = w * 0.05f, center = Offset(head.x + w * 0.1f, head.y - w * 0.04f))
}

/** Right side, the whole height: a short thank-you and the support button. */
@Composable
private fun SupportSide(state: EditorState, modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF2A3A55), Color(0xFF1D2A40)))).padding(28.dp)) {
    GitHubFooter(Modifier.align(Alignment.BottomStart))
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt("☕", size = 48.sp)
        Spacer(Modifier.height(14.dp))
        Txt("Enjoying it?", size = 18.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Txt(
            "MineCartographer is free and made in spare time. A coffee keeps it going.",
            Modifier.fillMaxWidth(), color = Color(0xFFB9C4D8), size = 12.sp, maxLines = 5,
        )
        Spacer(Modifier.height(18.dp))
        TextButton("Buy me a coffee", Modifier.fillMaxWidth(), primary = true) {
            if (AppInfo.SUPPORT_URL.isBlank()) state.notify("The support link is not set up yet") else browse(AppInfo.SUPPORT_URL)
        }
    }
    }
}

private fun browse(url: String) {
    Thread {
        runCatching { java.awt.Desktop.getDesktop().browse(URI(url)) }
    }.apply { isDaemon = true }.start()
}
