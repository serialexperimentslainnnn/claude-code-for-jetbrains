package dev.lain.claudejb.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Document
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

    /**
     * True iff [path] resolves to a location at or under [projectRoot]. Used to confine auto-approved writes
     * (acceptEdits/bypassPermissions) to the project tree, so a binary-supplied absolute path pointing outside
     * (e.g. ~/.ssh/config, /etc/...) cannot be written silently. Both sides are canonicalized — which also
     * resolves symlinks — to defeat `..` traversal and symlink-based escapes. Conservative on the unknown: a
     * null [path] or [projectRoot], or any canonicalization failure (IOException), returns false (never
     * auto-approve when we cannot prove containment).
     */
    fun isWithinRoot(path: String?, projectRoot: String?): Boolean {
        if (path == null || projectRoot == null) return false
        return try {
            val canonicalFile = File(path).canonicalFile
            val canonicalRoot = File(projectRoot).canonicalFile
            val rootPath = canonicalRoot.path
            val filePath = canonicalFile.path
            filePath == rootPath || filePath.startsWith(rootPath + File.separator)
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

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
     * The diff tab's title — and, because [ChainDiffVirtualFile] adopts its title AS ITS FILE NAME, a name the
     * rest of the IDE will try to interpret.
     *
     * That is not theoretical. The title used to be `"Claude · ${file.name}"`, so reviewing a change to
     * `build.gradle.kts` produced a virtual file literally named `Claude · build.gradle.kts` — ending in
     * `.kts`. Kotlin's script support picked it up as a build script, failed to resolve a file that is not a
     * file, and raised **"Circular script import — Not a kotlin file"** at the user every single time Claude
     * edited a Gradle script. The same trap is waiting for any extension the IDE attaches machinery to.
     *
     * So the file name goes FIRST and a plain suffix goes last: the tab still reads well, sorts by file, and
     * cannot be mistaken for a document of that type.
     */
    internal fun diffTitle(fileName: String) = "$fileName — Claude"

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
        val title = diffTitle(file.name)
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
        // focusEditor=false: open the diff WITHOUT stealing keyboard focus, so a diff popping up
        // (incl. auto-open during a turn) never yanks the caret out of the chat composer mid-typing.
        FileEditorManager.getInstance(project).openFile(vFile, false)
        OpenedDiffsService.getInstance(project).register(vFile)
        return vFile
    }

    /**
     * An open editable review diff: the diff tab [file], the EDITABLE "proposed" [proposed] document (the user can
     * tweak it before accepting), and the [currentText]/[originalProposed] baselines used to detect an edit.
     */
    data class ReviewDiff(
        val file: VirtualFile,
        val proposed: Document,
        val currentText: String,
        val originalProposed: String,
    )

    /**
     * Opens an EDITABLE review diff (Current | Proposed) in the editor area and returns a [ReviewDiff] handle so the
     * caller can read back the (possibly user-edited) proposed text on accept and close the tab. The proposed side
     * is a writable in-memory document; if the platform viewer renders it read-only, the handle simply reports no
     * edit (the caller falls back to the binary's own write — never a wrong write). EDT-only. Null when the change
     * can't be reconstructed (missing file_path, etc.).
     */
    fun openReviewDiff(project: Project, toolName: String, input: JsonObject, currentSnapshot: String? = null): ReviewDiff? {
        val path = filePathOf(input) ?: return null
        val file = File(path)
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposedText = proposedContent(toolName, input, current) ?: return null
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        // createEditable (NOT create) marks the proposed side writable so the diff viewer lets the user TWEAK it
        // before accepting; plain create() renders it read-only.
        val proposedDoc = factory.createEditable(project, proposedText, fileType)
        val title = diffTitle(file.name)
        val request = SimpleDiffRequest(
            title,
            factory.create(project, current, fileType),
            proposedDoc,
            "Current: ${file.name}",
            "Proposed by Claude — edit before accepting",
        )
        val vFile = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        // Opening the diff must NEVER break permission resolution — if the editor manager can't open it (e.g. a
        // headless test environment, or any platform error) degrade gracefully to "no review diff".
        val opened = runCatching { FileEditorManager.getInstance(project).openFile(vFile, false) }.isSuccess
        if (!opened) return null
        OpenedDiffsService.getInstance(project).register(vFile)
        return ReviewDiff(vFile, proposedDoc.document, current, proposedText)
    }

    /**
     * Line-level hunks between [current] and [proposed], computed via the platform diff engine — what
     * [unifiedDiff] renders from. EDT/Application-bound; callers cap the file size before asking.
     */
    fun computeHunks(current: String, proposed: String): List<Hunk> {
        val fragments = com.intellij.diff.comparison.ComparisonManager.getInstance()
            .compareLines(
                current,
                proposed,
                com.intellij.diff.comparison.ComparisonPolicy.DEFAULT,
                com.intellij.openapi.progress.DumbProgressIndicator.INSTANCE,
            )
        return fragments.map { Hunk(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
    }

    /**
     * Renders a unified diff (`@@`/` `/`-`/`+` lines) between [current] and [proposed], emitting only the
     * changed regions plus [context] lines around each — the same shape Claude Code prints in the terminal,
     * reconstructed natively so the chat can colorize it. Returns `""` when there is no change. The platform
     * diff engine ([computeHunks]) is EDT/Application-bound.
     */
    fun unifiedDiff(current: String, proposed: String, context: Int = 3): String {
        val hunks = computeHunks(current, proposed)
        if (hunks.isEmpty()) return ""
        val cur = current.split("\n")
        val pro = proposed.split("\n")
        val sb = StringBuilder()
        for (h in hunks) {
            val ctxStart = (h.start1 - context).coerceAtLeast(0)
            val ctxEnd = (h.end1 + context).coerceAtMost(cur.size)
            sb.append("@@ -${h.start1 + 1},${h.end1 - h.start1} +${h.start2 + 1},${h.end2 - h.start2} @@\n")
            for (i in ctxStart until h.start1) sb.append(' ').append(cur[i]).append('\n')
            for (i in h.start1 until h.end1) sb.append('-').append(cur.getOrElse(i) { "" }).append('\n')
            for (i in h.start2 until h.end2) sb.append('+').append(pro.getOrElse(i) { "" }).append('\n')
            for (i in h.end1 until ctxEnd) sb.append(' ').append(cur[i]).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** Closes a diff tab once its request has been resolved. EDT only. */
    fun closeDiff(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        if (manager.isFileOpen(file)) manager.closeFile(file)
        OpenedDiffsService.getInstance(project).unregister(file)
    }
}

/**
 * One contiguous changed line-range, as produced by [DiffPresenter.computeHunks].
 * `[start1,end1)` indexes the current file's lines; `[start2,end2)` indexes the proposed file's lines.
 */
data class Hunk(
    val start1: Int,
    val end1: Int,
    val start2: Int,
    val end2: Int,
)
