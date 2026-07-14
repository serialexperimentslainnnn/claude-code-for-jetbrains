package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.EditSnapshotStore
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the full diff lifecycle of one [ClaudeSession]: capturing the pre-write snapshot of a reviewable
 * Edit/Write/MultiEdit (the binary — not the IDE — writes the file once we answer `allow`, so the current
 * contents are only recoverable *before* that response goes out), opening/closing the transient diff tab for
 * auto-approved edits, surfacing the persisted snapshot for the transcript's "View diff" affordance, and the
 * write-safe VFS refresh of touched files after a turn.
 *
 * Extracted from [ClaudeSession] so the session no longer juggles the [EditSnapshotStore], the auto-opened diff
 * registry and the pending-refresh set directly. Semantics are identical to the previous inline logic.
 *
 * Threading: [captureForReview]/[markForRefresh] are called off-EDT (process reader thread / broker callbacks);
 * the snapshot store and the refresh set are concurrency-safe. The EDT-bound work ([autoOpenDiff], [onToolResult],
 * [refreshTouched]) is dispatched here via [edt] — for [refreshTouched] specifically with
 * [ModalityState.nonModal] (write-safe), because a VFS [com.intellij.openapi.vfs.newvfs.RefreshSession] asserts a
 * write-safe context on 262+ ([ModalityState.any] would throw "Write-unsafe context!").
 */
class DiffLifecycleManager(private val project: Project) {

    private val editSnapshots = EditSnapshotStore()

    /**
     * Diff tabs opened automatically for acceptEdits/bypassPermissions edits, keyed by `tool_use_id` so the
     * matching ToolResult can close the right one — keeps a long auto-approve run from leaving dozens of diff
     * tabs pinned in the editor area.
     */
    private val autoOpenedDiffs = ConcurrentHashMap<String, VirtualFile>()

    /** Paths touched this turn, refreshed in the VFS once the turn ends (or eagerly on approval). */
    private val pendingRefresh = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Open editable review diffs, keyed by permission requestId, so accept/reject can capture-then-close them. */
    private val reviewDiffs = ConcurrentHashMap<String, DiffPresenter.ReviewDiff>()

    /**
     * Opens an editable review diff for a reviewable edit permission card (default/plan mode — auto-approve modes
     * use [autoOpenDiff] instead), keyed by [requestId]. Called from the EDT (permission present path).
     */
    fun openReviewDiff(requestId: String, toolName: String, input: JsonObject) {
        if (reviewDiffs.containsKey(requestId)) return
        DiffPresenter.openReviewDiff(project, toolName, input)?.let { reviewDiffs[requestId] = it }
    }

    /**
     * Resolves the review diff for [requestId] on accept: closes the tab and, if the user edited the proposed text,
     * returns the (currentText, editedText) so the caller can narrow the write to exactly what the user left.
     * Returns null when there was no review diff or the proposed text is unchanged. EDT-only (reads the document).
     */
    fun takeReviewEdit(requestId: String): Pair<String, String>? {
        val rd = reviewDiffs.remove(requestId) ?: return null
        val edited = runCatching { rd.proposed.text }.getOrNull()
        DiffPresenter.closeDiff(project, rd.file)
        return if (edited != null && edited != rd.originalProposed) rd.currentText to edited else null
    }

    /** Closes the review diff for [requestId] without capturing (reject). EDT-only. */
    fun closeReviewDiff(requestId: String) {
        reviewDiffs.remove(requestId)?.let { DiffPresenter.closeDiff(project, it.file) }
    }

    /** Closes every open review diff (stop / interrupt / dispose). EDT-only. */
    fun clearReviewDiffs() {
        reviewDiffs.keys.toList().forEach { closeReviewDiff(it) }
    }

    /**
     * Captures the file's pre-write contents for a reviewable tool call and persists them keyed by [toolUseId]
     * so the change stays re-diffable from the transcript long after the transient approval diff has closed.
     * Must be called *before* the `allow` response is written (the binary writes right after). Returns the
     * captured snapshot, or null when the input carries no `file_path`.
     */
    fun captureForReview(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? =
        editSnapshots.capture(toolName, input, toolUseId)

    /**
     * Auto-approve path (acceptEdits/bypassPermissions): there is no permission card, but the user still wants
     * to see the change. Captures the pre-write contents *now* and opens the diff tab on the EDT with that
     * snapshot, tracking it by [toolUseId] so [onToolResult] can close it. No-op if the input carries no
     * `file_path` or the change cannot be reconstructed.
     */
    fun autoOpenDiff(toolName: String, input: JsonObject, toolUseId: String) {
        val snapshot = editSnapshots.capture(toolName, input, toolUseId) ?: return
        edt {
            val file = DiffPresenter.openDiff(project, toolName, input, snapshot.beforeText) ?: return@edt
            autoOpenedDiffs[toolUseId] = file
        }
    }

    /**
     * Called when the binary reports a tool finished: closes the auto-opened diff tab (if any) on the EDT —
     * the inline unified diff in the tool card preserves the change visually, so leaving the tab pinned only
     * clutters the workspace — and returns the persisted pre-write snapshot so [ClaudeSession] can build that
     * inline diff. Returns null if no snapshot was captured for [toolUseId].
     */
    fun onToolResult(toolUseId: String): EditSnapshot? {
        autoOpenedDiffs.remove(toolUseId)?.let { file -> edt { DiffPresenter.closeDiff(project, file) } }
        return editSnapshots.get(toolUseId)
    }

    /** The persisted pre-write snapshot for a reviewable tool call, or null if none was captured (e.g. rejected). */
    fun snapshot(toolUseId: String): EditSnapshot? = editSnapshots.get(toolUseId)

    /**
     * Updates a captured snapshot's input to what was actually written (e.g. the user's edited review diff), so the
     * transcript's inline diff and "View diff" show the real change rather than Claude's original proposal.
     */
    fun updateSnapshotInput(toolUseId: String, input: JsonObject) = editSnapshots.updateInput(toolUseId, input)

    /** Records a path touched this turn for a later VFS refresh. Off-EDT safe. */
    fun markForRefresh(path: String) {
        pendingRefresh.add(path)
    }

    /**
     * Refreshes (and clears) the touched paths in the VFS. A refresh enqueues a RefreshSession, which the IDE
     * (262+) asserts must run from a WRITE-SAFE context — so this re-dispatches on the EDT with
     * [ModalityState.nonModal] (which IS write-safe), unlike the [ModalityState.any] used elsewhere.
     * async=true keeps it off the hot path. No-op when nothing is pending.
     */
    fun refreshTouched() {
        val paths = synchronized(pendingRefresh) { pendingRefresh.toList().also { pendingRefresh.clear() } }
        if (paths.isEmpty()) return
        val files = paths.map { File(it) }
        // The PARENTS matter: refreshIoFiles only refreshes files the VFS already knows about, and a file the tool
        // has just CREATED is not one of them — there is nothing there to refresh, so it stayed invisible to the
        // IDE (stale editor, dead jump-to-code link) until something else re-scanned its directory. Re-scanning the
        // parent is what makes the new child appear.
        val targets = (files.mapNotNull { it.parentFile }.distinct() + files)
        ApplicationManager.getApplication().invokeLater(
            { LocalFileSystem.getInstance().refreshIoFiles(targets, true, false, null) },
            ModalityState.nonModal(),
        )
    }

    /**
     * Refreshes the whole project tree in the VFS, asynchronously.
     *
     * For a tool that writes files we know (Edit/Write) [refreshTouched] refreshes exactly those paths. But a
     * `Bash` command — or an MCP tool that edits — can touch anything: a `mv`, a formatter, a codegen script. We
     * do not know *what* changed, so we mark the project root dirty and let the IDE work it out. Async and
     * write-safe ([ModalityState.nonModal], same reason as [refreshTouched]); the IDE coalesces the work, so an
     * unchanged tree costs next to nothing.
     */
    fun refreshProjectTree() {
        val root = project.basePath ?: return
        val dir = LocalFileSystem.getInstance().findFileByPath(root) ?: return
        ApplicationManager.getApplication().invokeLater(
            { VfsUtil.markDirtyAndRefresh(/* async = */ true, /* recursive = */ true, /* reloadChildren = */ true, dir) },
            ModalityState.nonModal(),
        )
    }

    private fun edt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
}
