package dev.lain.claudejb.session

import com.intellij.openapi.project.Project
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.FileRollback
import java.io.File

/** One file-writing edit in this session that still has a captured pre-write snapshot, plus its display path. */
data class ReviewableEdit(val toolUseId: String, val snapshot: EditSnapshot, val displayPath: String)

/**
 * IDE-side undo of applied Edit/Write/MultiEdit calls, backing the Diff History panel (B-epic). Extracted from
 * [ClaudeSession] so the session stays a thin delegating orchestrator: it owns no rollback logic, it just forwards
 * to this collaborator (mirroring [DiffLifecycleManager], [TokenAccountant], etc.).
 *
 * It enumerates reviewable edits from the [transcript] (cross-referencing the pre-write snapshots held by
 * [diffs]), and reverts a file to a captured `beforeText` via the pure [FileRollback] writer — then refreshes the
 * VFS and reseeds the binary's read-state cache (via the injected [reseedReadState], which the session wires to a
 * `seed_read_state` control request) so the binary's next Edit re-validates against the rolled-back contents.
 *
 * Threading: revert writes run in a `WriteCommandAction` inside [FileRollback.revert], so the public revert methods
 * must be invoked on the EDT — same contract the diff-history panel already honours.
 */
class RollbackManager(
    private val project: Project,
    private val transcript: TranscriptModel,
    private val diffs: DiffLifecycleManager,
    /** Reseeds the binary's read-state for a rolled-back file (path + new mtime); a no-op when the process is down. */
    private val reseedReadState: (path: String, mtime: Long) -> Unit,
) {

    /**
     * Every reviewable file-writing edit in this session that has a captured snapshot, in transcript order
     * (oldest first). Walks the transcript's TOOL rows, keeps the reviewable Edit/Write/MultiEdit calls whose
     * pre-write snapshot was captured, and resolves each path to a project-root-relative [ReviewableEdit.displayPath]
     * (falling back to the absolute path when it is outside the root).
     */
    fun reviewableEdits(): List<ReviewableEdit> =
        transcript.entries.asSequence()
            .filter { it.speaker == Speaker.TOOL && it.toolUseId != null && it.meta in DiffPresenter.REVIEWABLE_TOOLS }
            .mapNotNull { entry ->
                val toolUseId = entry.toolUseId ?: return@mapNotNull null
                val snapshot = diffs.snapshot(toolUseId) ?: return@mapNotNull null
                ReviewableEdit(toolUseId, snapshot, displayPathOf(snapshot.filePath))
            }
            .toList()

    /**
     * IDE-side revert of one edit: restores the captured `beforeText` to the file (path-confined, in a
     * WriteCommandAction — see [FileRollback.revert]), refreshes the VFS for that path, and reseeds the binary's
     * read-state cache with the file's new mtime so its next Edit re-validates against the rolled-back contents.
     * Returns true if the file was restored. Call from the EDT (the write runs in a WriteCommandAction).
     */
    fun revertEdit(snapshot: EditSnapshot): Boolean {
        val ok = FileRollback.revert(project, snapshot)
        if (ok) {
            diffs.markForRefresh(snapshot.filePath)
            diffs.refreshTouched()
            reseedReadState(snapshot.filePath, File(snapshot.filePath).lastModified())
        }
        return ok
    }

    /**
     * Rolls every edited file back to its OLDEST captured `beforeText` (the state before Claude first touched it),
     * walking newest-file-edit first ([FileRollback.rollbackAllOrder]). Returns the number of files reverted.
     */
    fun revertAllEdits(): Int {
        val edits = reviewableEdits()
        val refs = edits.mapIndexed { index, e -> FileRollback.EditRef(index, e.snapshot.filePath) }
        return FileRollback.rollbackAllOrder(refs)
            .count { ref -> revertEdit(edits[ref.index].snapshot) }
    }

    /** Project-root-relative form of [path] for display, or the path itself when it is outside the root.
     *  Delegates to the shared [dev.lain.claudejb.context.FilePickerHelper.relativeWithinRoot]. */
    private fun displayPathOf(path: String): String =
        dev.lain.claudejb.context.FilePickerHelper.relativeWithinRoot(project.basePath, path) ?: path
}
