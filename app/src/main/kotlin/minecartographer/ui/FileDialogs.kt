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
 * File and folder pickers. They use the dialogs of the operating system (see [NativeDialogs]); only when none can be
 * shown does this fall back to Swing's JFileChooser.
 */
object FileDialogs {
    private val platformLook by lazy {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    /** The folder the Minecraft launcher keeps its saves, resource packs and versions in, when it exists. */
    private val minecraftFolder: Path? by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            "win" in os -> listOfNotNull(System.getenv("APPDATA")?.let { Path.of(it, ".minecraft") })
            "mac" in os -> listOf(Path.of(home, "Library", "Application Support", "minecraft"))
            else -> listOf(Path.of(home, ".minecraft"))
        }
        candidates.firstOrNull { it.isDirectory() }
    }

    /**
     * Shows a dialog for [spec]: the native one when there is one, else Swing's. Returns null when cancelled.
     * [approve] is the label of the confirm button where the dialog lets us set it.
     */
    private fun choose(spec: DialogSpec, approve: String): Pick? = when (val outcome = NativeDialogs.show(spec)) {
        is DialogOutcome.Picked -> Pick(outcome.paths, outcome.confirmedOverwrite)
        DialogOutcome.Cancelled -> null
        DialogOutcome.Unavailable -> swing(spec, approve)?.let { Pick(it, confirmedOverwrite = false) }
    }

    private class Pick(val paths: List<Path>, val confirmedOverwrite: Boolean) {
        val first get() = paths.first()
    }

    private fun swing(spec: DialogSpec, approve: String): List<Path>? {
        platformLook
        val chooser = JFileChooser(spec.start?.takeIf { it.isDirectory() }?.toFile()).apply {
            dialogTitle = spec.title
            isFileHidingEnabled = false // show .minecraft and other dot folders
            isMultiSelectionEnabled = spec.multiple
            fileSelectionMode = if (spec.mode == DialogSpec.Mode.OpenFolder) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            if (spec.extensions.isNotEmpty() && spec.mode != DialogSpec.Mode.OpenFolder) {
                isAcceptAllFileFilterUsed = false
                fileFilter = object : FileFilter() {
                    override fun accept(f: File) = f.isDirectory || spec.extensions.any { f.name.endsWith(".$it", ignoreCase = true) }
                    override fun getDescription() = (spec.filterName ?: "Files") + " (" + spec.extensions.joinToString(", ") { ".$it" } + ")"
                }
            }
            approveButtonText = approve
            spec.fileName?.let { selectedFile = File(currentDirectory, it) }
        }
        val result = if (spec.mode == DialogSpec.Mode.Save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return null
        return if (spec.multiple) chooser.selectedFiles.map { it.toPath() }.ifEmpty { null } else listOf(chooser.selectedFile.toPath())
    }

    /** Lets the user pick a world folder. Returns null when cancelled. */
    fun chooseWorldFolder(): Path? {
        val start = WorldLibrary.recent().firstOrNull()?.parent ?: minecraftFolder?.resolve("saves")?.takeIf { it.isDirectory() } ?: minecraftFolder
        val spec = DialogSpec("Open Minecraft world (choose the world folder)", DialogSpec.Mode.OpenFolder, start = start)
        val folder = choose(spec, "Open world")?.first ?: return null
        // Accept being one level too deep (e.g. picked the region folder) or the level.dat itself.
        val dir = if (folder.isDirectory()) folder else folder.parent
        return when {
            AnvilWorld.isWorld(dir) -> dir
            dir.parent != null && AnvilWorld.isWorld(dir.parent) -> dir.parent
            else -> dir
        }
    }

    /** Lets the user pick a Minecraft client jar and/or resource packs (zips) for textures. */
    fun chooseTextureFiles(): List<Path>? {
        val start = listOfNotNull(minecraftFolder?.resolve("resourcepacks"), minecraftFolder?.resolve("versions"), minecraftFolder).firstOrNull { it.isDirectory() }
        val spec = DialogSpec(
            "Choose textures: a Minecraft client jar and/or resource packs (top pack first)", DialogSpec.Mode.OpenFiles,
            multiple = true, start = start, filterName = "Client jars and resource packs", extensions = listOf("jar", "zip"),
        )
        return choose(spec, "Use textures")?.paths
    }

    private fun projectFolder(current: Path?) = current?.parent ?: WorldLibrary.recentProjects().firstOrNull()?.parent

    /** Lets the user pick a project file to open. */
    fun chooseProjectToOpen(current: Path?): Path? {
        val spec = DialogSpec(
            "Open project", DialogSpec.Mode.OpenFiles, start = projectFolder(current),
            filterName = "MineCartographer projects", extensions = listOf(ProjectFile.EXTENSION),
        )
        return choose(spec, "Open project")?.first
    }

    /** Asks where to save a project, adding the extension and confirming before replacing a file. */
    fun chooseProjectToSave(current: Path?, suggestedName: String): Path? {
        val name = current?.fileName?.toString() ?: "$suggestedName.${ProjectFile.EXTENSION}"
        return chooseToSave("Save project", ProjectFile.EXTENSION, "MineCartographer projects", projectFolder(current), name, keep = current)
    }

    /** Asks where to save a CSV file, suggesting [suggestedName]; adds the extension and confirms before replacing. */
    fun chooseCsvToSave(suggestedName: String): Path? = chooseFileToSave("Export CSV", "csv", "CSV spreadsheets", suggestedName)

    /** Where the last file was exported to, so the next dialog opens there (kept for this session only). */
    private var lastExportFolder: Path? = null

    /**
     * Asks where to save a file of the type [extension] (without the dot), suggesting [suggestedName] (with or without
     * the extension); adds the extension and confirms before replacing.
     */
    fun chooseFileToSave(title: String, extension: String, description: String, suggestedName: String): Path? {
        val name = if (suggestedName.endsWith(".$extension")) suggestedName else "$suggestedName.$extension"
        val file = chooseToSave(title, extension, description, lastExportFolder, name, keep = null) ?: return null
        lastExportFolder = file.parent
        return file
    }

    /** [keep] is a file that may be replaced without asking (the project being saved over). */
    private fun chooseToSave(title: String, extension: String, description: String, start: Path?, name: String, keep: Path?): Path? {
        val suffix = ".$extension"
        val spec = DialogSpec(title, DialogSpec.Mode.Save, start = start, fileName = name, filterName = description, extensions = listOf(extension))
        val pick = choose(spec, "Save") ?: return null
        val picked = pick.first
        val file = if (picked.fileName.toString().endsWith(suffix)) picked else picked.resolveSibling("${picked.fileName}$suffix")
        // A native dialog has asked already about the file it was given; only a changed name needs asking again.
        val asked = pick.confirmedOverwrite && file == picked
        if (file.exists() && file != keep && !asked) {
            val answer = JOptionPane.showConfirmDialog(null, "${file.fileName} already exists. Replace it?", title, JOptionPane.YES_NO_OPTION)
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
        val spec = DialogSpec(
            "Archive of the mod \"$modId\" (.jar or .zip)", DialogSpec.Mode.OpenFiles, start = start,
            filterName = "Mod archives", extensions = listOf("jar", "zip"),
        )
        return choose(spec, "Use for $modId")?.first
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
    fun chooseFolder(title: String, start: Path?): Path? =
        choose(DialogSpec(title, DialogSpec.Mode.OpenFolder, start = start), "Choose")?.first
}
