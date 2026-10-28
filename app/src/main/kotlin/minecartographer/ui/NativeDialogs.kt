package minecartographer.ui

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.isDirectory

/** What a native dialog should ask for. [extensions] have no dot; empty means any file. */
class DialogSpec(
    val title: String,
    val mode: Mode,
    val multiple: Boolean = false,
    val start: Path? = null,
    val fileName: String? = null,
    val filterName: String? = null,
    val extensions: List<String> = emptyList(),
) {
    enum class Mode { OpenFiles, OpenFolder, Save }
}

sealed interface DialogOutcome {
    /** [confirmedOverwrite]: the dialog already asked before replacing a file. */
    class Picked(val paths: List<Path>, val confirmedOverwrite: Boolean = true) : DialogOutcome
    data object Cancelled : DialogOutcome

    /** No native dialog could be shown here; the caller should use its own. */
    data object Unavailable : DialogOutcome
}

/**
 * The file dialogs of the operating system, shown without any extra library:
 * - Windows: the Common Item Dialog (what Explorer uses) through PowerShell, with hidden files forced visible.
 * - macOS: AWT's FileDialog, which is the Cocoa panel.
 * - Linux: kdialog (KDE), or zenity / yad sent through the desktop portal, which is the KDE or GNOME dialog itself.
 */
object NativeDialogs {
    private val os = System.getProperty("os.name").lowercase()
    private val busy = AtomicBoolean(false)

    fun show(spec: DialogSpec): DialogOutcome {
        // The window behind a dialog stays alive, so a second click must not stack another one.
        if (!busy.compareAndSet(false, true)) return DialogOutcome.Cancelled
        try {
            return when {
                "mac" in os -> awtDialog(spec)
                "win" in os -> whileWaiting { windowsDialog(spec) }
                else -> whileWaiting { linuxDialog(spec) }
            }
        } finally {
            busy.set(false)
        }
    }

    // ---- Linux ----

    private fun linuxDialog(spec: DialogSpec): DialogOutcome {
        for (command in linuxCommands(spec, desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(), path = pathDirs())) {
            val result = run(command, linuxEnvironment()) ?: continue
            when (result.code) {
                0 -> {
                    val paths = parseLines(result.output)
                    return if (paths.isEmpty()) DialogOutcome.Cancelled else DialogOutcome.Picked(paths)
                }
                1 -> return DialogOutcome.Cancelled
            }
        }
        return DialogOutcome.Unavailable
    }

    /** The commands to try, best first, for the dialog programs that are installed. */
    internal fun linuxCommands(spec: DialogSpec, desktop: String, path: List<Path>): List<List<String>> {
        val installed = { name: String -> path.any { (it.resolve(name).toFile().canExecute()) } }
        val onKde = desktop.split(':').any { it.equals("KDE", true) }
        val order = if (onKde) listOf("kdialog", "zenity", "yad") else listOf("zenity", "yad", "kdialog")
        return order.filter(installed).map {
            when (it) {
                "kdialog" -> kdialogCommand(spec)
                "zenity" -> gtkCommand(spec, "zenity")
                else -> gtkCommand(spec, "yad") + listOf("--width=900", "--height=600")
            }
        }
    }

    internal fun kdialogCommand(spec: DialogSpec): List<String> {
        val filter = patterns(spec).takeIf { it.isNotEmpty() }?.let { it.joinToString(" ") + "|" + filterLabel(spec) }
        val start = spec.start?.toString()?.let { if (spec.fileName != null) File(it, spec.fileName).path else "$it/" } ?: ""
        val command = mutableListOf("kdialog", "--title", spec.title)
        when (spec.mode) {
            DialogSpec.Mode.OpenFolder -> command += listOf("--getexistingdirectory", start)
            DialogSpec.Mode.Save -> command += listOf("--getsavefilename", start.ifEmpty { spec.fileName.orEmpty() }) + listOfNotNull(filter)
            DialogSpec.Mode.OpenFiles -> {
                if (spec.multiple) command += listOf("--multiple", "--separate-output")
                command += listOf("--getopenfilename", start) + listOfNotNull(filter)
            }
        }
        return command
    }

    /** zenity and yad take the same options for a file dialog. */
    internal fun gtkCommand(spec: DialogSpec, program: String): List<String> {
        val command = mutableListOf(program, if (program == "yad") "--file" else "--file-selection", "--title=${spec.title}")
        when (spec.mode) {
            DialogSpec.Mode.OpenFolder -> command += "--directory"
            DialogSpec.Mode.Save -> command += listOf("--save", "--confirm-overwrite")
            DialogSpec.Mode.OpenFiles -> Unit
        }
        if (spec.multiple && spec.mode == DialogSpec.Mode.OpenFiles) command += listOf("--multiple", "--separator=\n")
        spec.start?.let { dir -> command += "--filename=" + (spec.fileName?.let { File(dir.toString(), it).path } ?: "$dir/") }
        if (spec.mode != DialogSpec.Mode.OpenFolder) {
            patterns(spec).takeIf { it.isNotEmpty() }?.let { command += "--file-filter=${filterLabel(spec)} | ${it.flatMap { p -> listOf(p, p.uppercase()) }.distinct().joinToString(" ")}" }
        }
        return command
    }

    /** GTK dialogs go through xdg-desktop-portal, which on KDE Plasma is the KDE file dialog. */
    private fun linuxEnvironment() = mapOf("GTK_USE_PORTAL" to "1", "LD_LIBRARY_PATH" to cleanLibraryPath())

    /** The packaged app points LD_LIBRARY_PATH at its bundled runtime, which the system programs must not load. */
    private fun cleanLibraryPath(): String? {
        val home = System.getProperty("java.home").orEmpty()
        val kept = System.getenv("LD_LIBRARY_PATH").orEmpty().split(':').filter { it.isNotBlank() && !it.startsWith(home) && "MineCartographer" !in it }
        return kept.joinToString(":").ifEmpty { null }
    }

    private fun pathDirs(): List<Path> = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path.of(it) }

    // ---- Windows ----

    private val windowsScript by lazy {
        NativeDialogs::class.java.getResourceAsStream("/FilePicker.ps1")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun windowsDialog(spec: DialogSpec): DialogOutcome {
        val encoded = Base64.getEncoder().encodeToString(windowsScript.toByteArray(Charsets.UTF_16LE))
        val powershell = System.getenv("SystemRoot")?.let { File(it, "System32/WindowsPowerShell/v1.0/powershell.exe") }?.takeIf { it.exists() }?.path ?: "powershell.exe"
        val environment = mapOf(
            "MC_MODE" to when (spec.mode) {
                DialogSpec.Mode.OpenFiles -> "open"
                DialogSpec.Mode.OpenFolder -> "folder"
                DialogSpec.Mode.Save -> "save"
            },
            "MC_MULTIPLE" to if (spec.multiple) "1" else "0",
            "MC_TITLE" to spec.title,
            "MC_START" to spec.start?.toString(),
            "MC_NAME" to spec.fileName,
            "MC_FILTER_NAME" to filterLabel(spec),
            "MC_FILTER_SPEC" to patterns(spec).joinToString(";"),
            "MC_EXTENSION" to spec.extensions.firstOrNull(),
            "MC_PID" to ProcessHandle.current().pid().toString(),
        )
        val result = run(listOf(powershell, "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded), environment)
            ?: return DialogOutcome.Unavailable
        return when (result.code) {
            0 -> {
                val paths = result.output.lines().filter { it.isNotBlank() }.map { Path.of(String(Base64.getDecoder().decode(it.trim()), Charsets.UTF_8)) }
                if (paths.isEmpty()) DialogOutcome.Cancelled else DialogOutcome.Picked(paths)
            }
            1 -> DialogOutcome.Cancelled
            else -> DialogOutcome.Unavailable
        }
    }

    // ---- macOS ----

    private fun awtDialog(spec: DialogSpec): DialogOutcome {
        val folders = spec.mode == DialogSpec.Mode.OpenFolder
        val dialog = FileDialog(null as Frame?, spec.title, if (spec.mode == DialogSpec.Mode.Save) FileDialog.SAVE else FileDialog.LOAD)
        dialog.isMultipleMode = spec.multiple && spec.mode == DialogSpec.Mode.OpenFiles
        spec.start?.takeIf { it.isDirectory() }?.let { dialog.directory = it.toString() }
        spec.fileName?.let { dialog.file = it }
        if (spec.extensions.isNotEmpty() && !folders) {
            dialog.setFilenameFilter { _, name -> spec.extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        }
        System.setProperty("apple.awt.fileDialogForDirectories", folders.toString())
        try {
            dialog.isVisible = true
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
        val picked = dialog.files.map { it.toPath() }
        return if (picked.isEmpty()) DialogOutcome.Cancelled else DialogOutcome.Picked(picked)
    }

    // ---- shared ----

    private fun patterns(spec: DialogSpec) = spec.extensions.map { "*.$it" }

    private fun filterLabel(spec: DialogSpec) = spec.filterName ?: spec.extensions.joinToString(", ") { ".$it" }

    internal fun parseLines(output: String): List<Path> = output.lines().filter { it.isNotBlank() }.map { Path.of(it.trim()) }

    private class Ran(val code: Int, val output: String)

    /** Runs [command] to the end; null when it can't be started. A null value in [environment] removes the variable. */
    private fun run(command: List<String>, environment: Map<String, String?>): Ran? = try {
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        for ((key, value) in environment) if (value == null) builder.environment().remove(key) else builder.environment()[key] = value
        val process = builder.start()
        process.outputStream.close()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        Ran(process.waitFor(), output)
    } catch (_: Exception) {
        null
    }

    /**
     * Runs [task] on another thread while the calling UI thread keeps handling events, so the window stays
     * drawn and responsive behind a dialog that belongs to another process.
     */
    private fun <T> whileWaiting(task: () -> T): T {
        if (!EventQueue.isDispatchThread()) return task()
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val outcome = AtomicReference<Result<T>>()
        val left = AtomicBoolean(false)
        thread(isDaemon = true, name = "native-dialog") {
            outcome.set(runCatching(task))
            // exit() does nothing before enter() was reached, so repeat it until the loop is really gone.
            while (!left.get()) {
                loop.exit()
                Thread.sleep(5)
            }
        }
        loop.enter()
        left.set(true)
        return outcome.get().getOrThrow()
    }
}
