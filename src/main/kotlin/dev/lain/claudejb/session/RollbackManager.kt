package dev.lain.claudejb.session

import com.intellij.openapi.project.Project
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.FileRollback
import java.io.File

/**
 * IDE-side undo of ONE applied Edit/Write/MultiEdit call — what a transcript card's **Restore** falls back to
 * when the binary cannot rewind. Extracted from [ClaudeSession] so the session stays a thin delegating
 * orchestrator: it owns no rollback logic, it just forwards to this collaborator (mirroring
 * [DiffLifecycleManager], [TokenAccountant], etc.).
 *
 * It reverts a file to a captured `beforeText` via the pure [FileRollback] writer — then refreshes the VFS and
 * reseeds the binary's read-state cache (via the injected [reseedReadState], which the session wires to a
 * `seed_read_state` control request) so the binary's next Edit re-validates against the rolled-back contents.
 *
 * **One edit is the whole unit, deliberately.** This used to also enumerate every reviewable edit and roll the
 * lot back at once, for the Diff History panel; both went with it in 5.5.0. A whole-session rollback cannot
 * tell Claude's edits from the ones the user made in between, and where there is Git the IDE's own Local
 * Changes does it better and with a way back.
 *
 * Threading: revert writes run in a `WriteCommandAction` inside [FileRollback.revert], so [revertEdit] must be
 * invoked on the EDT.
 */
class RollbackManager(
    private val project: Project,
    private val diffs: DiffLifecycleManager,
    /** Reseeds the binary's read-state for a rolled-back file (path + new mtime); a no-op when the process is down. */
    private val reseedReadState: (path: String, mtime: Long) -> Unit,
) {

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
}
