package minecartographer.ui

import minecartographer.core.anvil.AnvilWorld
import minecartographer.core.project.ProjectFile
import minecartographer.editor.WorldLibrary
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileFilter
import kotlin.io.path.exists
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

    private val projectFilter = object : FileFilter() {
        override fun accept(f: File) = f.isDirectory || f.name.endsWith(".${ProjectFile.EXTENSION}")
        override fun getDescription() = "MineCartographer projects (.${ProjectFile.EXTENSION})"
    }

    private fun projectFolder(current: Path?) = current?.parent ?: WorldLibrary.recentProjects().firstOrNull()?.parent

    /** Lets the user pick a project file to open. */
    fun chooseProjectToOpen(current: Path?): Path? {
        platformLook
        val chooser = JFileChooser(projectFolder(current)?.toFile()).apply {
            dialogTitle = "Open project"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = projectFilter
            approveButtonText = "Open project"
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    /** Asks where to save a project, adding the extension and confirming before replacing a file. */
    fun chooseProjectToSave(current: Path?, suggestedName: String): Path? {
        platformLook
        val chooser = JFileChooser(projectFolder(current)?.toFile()).apply {
            dialogTitle = "Save project"
            isAcceptAllFileFilterUsed = false
            fileFilter = projectFilter
            selectedFile = File(currentDirectory, current?.fileName?.toString() ?: "$suggestedName.${ProjectFile.EXTENSION}")
            approveButtonText = "Save"
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val picked = chooser.selectedFile.toPath()
        val file = if (picked.fileName.toString().endsWith(".${ProjectFile.EXTENSION}")) picked else picked.resolveSibling("${picked.fileName}.${ProjectFile.EXTENSION}")
        if (file.exists() && file != current) {
            val answer = JOptionPane.showConfirmDialog(null, "${file.fileName} already exists. Replace it?", "Save project", JOptionPane.YES_NO_OPTION)
            if (answer != JOptionPane.YES_OPTION) return null
        }
        return file
    }

    enum class UnsavedChoice { Save, Discard, Cancel }

    /** Asks what to do with unsaved changes to the project called [name]. */
    fun askSaveChanges(name: String): UnsavedChoice {
        platformLook
        val options = arrayOf("Save", "Don't save", "Cancel")
        val answer = JOptionPane.showOptionDialog(
            null, "Save changes to \"$name\" before closing it?", "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0],
        )
        return when (answer) {
            0 -> UnsavedChoice.Save
            1 -> UnsavedChoice.Discard
            else -> UnsavedChoice.Cancel
        }
    }

    /** Lets the user pick the archive (jar or zip) of one mod. */
    fun chooseModArchive(modId: String, start: Path?): Path? {
        platformLook
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = "Archive of the mod \"$modId\" (.jar or .zip)"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = object : FileFilter() {
                override fun accept(f: File) = f.isDirectory || f.name.endsWith(".jar", true) || f.name.endsWith(".zip", true)
                override fun getDescription() = "Mod archives (.jar, .zip)"
            }
            approveButtonText = "Use for $modId"
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    /** Opens the system file manager with [path] selected (Windows), or its folder. */
    fun showInFolder(path: Path) {
        runCatching {
            if (System.getProperty("os.name").startsWith("Windows")) {
                ProcessBuilder("explorer.exe", "/select,${path.toAbsolutePath()}").start()
            } else {
                java.awt.Desktop.getDesktop().open((if (path.isDirectory()) path else path.parent).toFile())
            }
        }
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
