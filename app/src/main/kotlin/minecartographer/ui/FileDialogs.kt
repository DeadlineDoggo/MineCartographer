package minecartographer.ui

import minecartographer.core.anvil.AnvilWorld
import minecartographer.editor.WorldLibrary
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileFilter
import kotlin.io.path.isDirectory

/**
 * Folder pickers. AWT's native FileDialog can't choose folders on Windows, so this uses
 * Swing's JFileChooser with the platform look and feel.
 */
object FileDialogs {
    private val platformLook by lazy {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    /** Lets the user pick a world folder (or its level.dat). Returns null when cancelled. */
    fun chooseWorldFolder(): Path? {
        platformLook
        val start = WorldLibrary.recent().firstOrNull()?.parent ?: WorldLibrary.savesFolders().firstOrNull()
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = "Open Minecraft world (choose the world folder or its level.dat)"
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isAcceptAllFileFilterUsed = false
            fileFilter = object : FileFilter() {
                override fun accept(f: File) = f.isDirectory || f.name == "level.dat"
                override fun getDescription() = "World folders and level.dat"
            }
            approveButtonText = "Open world"
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val picked = chooser.selectedFile.toPath()
        val folder = if (picked.isDirectory()) picked else picked.parent
        // Accept being one level too deep (e.g. picked the region folder).
        return when {
            AnvilWorld.isWorld(folder) -> folder
            folder.parent != null && AnvilWorld.isWorld(folder.parent) -> folder.parent
            else -> folder
        }
    }

    /** Lets the user pick a Minecraft client jar and/or resource packs (zips) for textures. */
    fun chooseTextureFiles(): List<Path>? {
        platformLook
        val appData = System.getenv("APPDATA")?.let { Path.of(it, ".minecraft") }
        val start = listOfNotNull(appData?.resolve("resourcepacks"), appData?.resolve("versions")).firstOrNull { it.isDirectory() }
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = "Choose textures: a Minecraft client jar and/or resource packs (top pack first)"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            isAcceptAllFileFilterUsed = false
            fileFilter = object : FileFilter() {
                override fun accept(f: File) = f.isDirectory || f.name.endsWith(".jar") || f.name.endsWith(".zip")
                override fun getDescription() = "Client jars and resource packs (.jar, .zip)"
            }
            approveButtonText = "Use textures"
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFiles.map { it.toPath() }.ifEmpty { null }
    }

    /** Lets the user pick a folder to create something in. */
    fun chooseFolder(title: String, start: Path?): Path? {
        platformLook
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            approveButtonText = "Choose"
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
}
