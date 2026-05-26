package dev.lain.claudejb.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Turns an Edit/Write/MultiEdit tool input into a native side-by-side diff (current on disk vs. the content
 * `claude` is about to write) and opens it as an **in-editor diff tab** — the same surface the IDE uses for
 * VCS diffs — so the user reviews the change in place, then Accepts/Rejects from the chat's permission card.
 *
 * The binary — not the IDE — performs the actual write once we answer `allow`, so the right-hand side is a
 * preview reconstructed from the tool input applied to the current file contents.
 */
object DiffPresenter {

    /** Tools whose effect is a file write and therefore get a diff review. */
    val REVIEWABLE_TOOLS = setOf("Edit", "Write", "MultiEdit")

    fun filePathOf(input: JsonObject): String? = input.str("file_path")

    /** Reconstructs the proposed file content, or null if it cannot be derived from [input]. */
    fun proposedContent(toolName: String, input: JsonObject, currentText: String): String? = when (toolName) {
        "Write" -> input.str("content") ?: ""
        "Edit" -> applyEdit(currentText, input)
        "MultiEdit" -> {
            val edits = input["edits"] as? JsonArray ?: return null
            edits.fold(currentText) { acc, element -> applyEdit(acc, element.jsonObject) ?: acc }
        }
        else -> null
    }

    private fun applyEdit(text: String, edit: JsonObject): String? {
        val old = edit.str("old_string") ?: return null
        val new = edit.str("new_string") ?: ""
        val replaceAll = (edit["replace_all"] as? JsonPrimitive)?.booleanOrNull ?: false
        return if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
    }

    /**
     * Opens the proposed change as a diff in an **editor tab** (never a separate window) and returns the tab's
     * virtual file so it can later be revealed or closed. Must be called on the EDT. Returns null if the change
     * cannot be reconstructed (e.g. a missing `file_path`).
     */
    fun openDiff(project: Project, toolName: String, input: JsonObject, currentSnapshot: String? = null): VirtualFile? {
        val path = filePathOf(input) ?: return null
        val file = File(path)
        // [currentSnapshot] lets the caller pass the contents captured before the binary wrote the file
        // (auto-approve modes); otherwise we read disk now (manual review opens the diff before approval).
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposed = proposedContent(toolName, input, current) ?: return null

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        val title = "Claude · ${file.name}"
        val request = SimpleDiffRequest(
            title,
            factory.create(project, current, fileType),
            factory.create(project, proposed, fileType),
            "Current: ${file.name}",
            "Proposed by Claude",
        )
        val vFile = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        // Use FileEditorManager.openFile (not DiffEditorTabFilesManager.showDiffFile): showDiffFile honours the
        // global "show diff in window vs. editor" setting and would pop a separate window. openFile always lands
        // the diff in the editor area — the no-popup behaviour we want, like the JetBrains AI Assistant.
        FileEditorManager.getInstance(project).openFile(vFile, true)
        return vFile
    }

    /** Brings an already-opened diff tab to the front (reopening it if the user closed it). EDT only. */
    fun revealDiff(project: Project, file: VirtualFile) {
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Closes a diff tab once its request has been resolved. EDT only. */
    fun closeDiff(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        if (manager.isFileOpen(file)) manager.closeFile(file)
    }
}
