package minecartographer.editor

import minecartographer.ui.FileDialogs
import minecartographer.ui.FileDialogs.UnsavedChoice
import java.nio.file.Path

// Project commands as the menus and shortcuts run them: with file dialogs and unsaved-changes prompts.

/** Saves to the current project file, or asks for one first ([saveAs] always asks). Returns false if not saved. */
fun EditorState.saveProjectInteractive(saveAs: Boolean = false): Boolean {
    val current = projectFile
    val file = if (current != null && !saveAs) current else FileDialogs.chooseProjectToSave(current, activeWorld?.name ?: projectName) ?: return false
    return saveProject(file)
}

/** Returns true when it's fine to drop the current project: no changes, saved, or the user doesn't want them. */
fun EditorState.confirmCloseProject(): Boolean {
    if (!projectDirty) return true
    return when (FileDialogs.askSaveChanges(projectName)) {
        UnsavedChoice.Save -> saveProjectInteractive()
        UnsavedChoice.Discard -> true
        UnsavedChoice.Cancel -> false
    }
}

fun EditorState.newProjectInteractive() {
    if (busy) return notify("Wait for the current task to finish", warning = true)
    if (confirmCloseProject()) newProject()
}

/** Opens [file], or asks which project to open. */
fun EditorState.openProjectInteractive(file: Path? = null) {
    if (busy) return notify("Wait for the current task to finish", warning = true)
    val target = file ?: FileDialogs.chooseProjectToOpen(projectFile) ?: return
    if (confirmCloseProject()) openProject(target)
}
