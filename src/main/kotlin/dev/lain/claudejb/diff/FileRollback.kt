package dev.lain.claudejb.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

/**
 * IDE-side restore of a file to a captured [EditSnapshot.beforeText] — the inverse of an applied Edit/Write.
 *
 * Unlike the normal flow (the binary writes once we answer `allow`), a rollback is the IDE undoing a write that
 * already happened, so the IDE performs the write itself via [WriteCommandAction] + [VfsUtil.saveText] (undoable,
 * VFS-consistent). It is **path-confined**: a snapshot whose `file_path` resolves outside the project root is
 * refused outright (defence in depth — the same containment that gates auto-approved writes in [DiffPresenter]),
 * so a binary-supplied path pointing at `~/.ssh/config` or `/etc/...` can never be clobbered by a "roll back".
 *
 * The ordering helpers are pure and IDE-free so the rollback UI's "newer edits" warning and "roll back all"
 * traversal order are unit-testable in isolation.
 */
object FileRollback {

    /**
     * Restores [snapshot]'s pre-write contents to its file, **only if** the file lies within [project]'s root
     * ([DiffPresenter.isWithinRoot]); a path outside the tree returns false without writing. The write runs in a
     * [WriteCommandAction] on the EDT (must be called from the EDT) so it is undoable and VFS-consistent.
     *
     * Returns true when the contents were restored. Special case: if the file does not exist and `beforeText`
     * is empty, the change was a fresh-file creation, so deletion-by-the-user (or never-created) is treated as
     * already-reverted (true) — there is nothing to restore. Any I/O failure is swallowed and yields false.
     */
    fun revert(project: Project, snapshot: EditSnapshot): Boolean {
        if (!DiffPresenter.isWithinRoot(snapshot.filePath, project.basePath)) return false
        var wrote = false
        // A Write that *created* the file is undone by DELETING it (back to "didn't exist"), not by leaving a
        // 0-byte husk. `existedBefore` distinguishes a creation from an overwrite of an already-empty file — both
        // have an empty beforeText, so the flag is the only way to tell them apart.
        val wasCreation = !snapshot.existedBefore
        runCatching {
            WriteCommandAction.runWriteCommandAction(project) {
                val lfs = LocalFileSystem.getInstance()
                val vf = lfs.refreshAndFindFileByPath(snapshot.filePath) ?: lfs.findFileByPath(snapshot.filePath)
                when {
                    wasCreation -> {
                        // Undo a creation: delete the file if still present; already-gone also counts as reverted.
                        if (vf != null && vf.exists()) vf.delete(this)
                        wrote = true
                    }
                    vf != null -> {
                        VfsUtil.saveText(vf, snapshot.beforeText)
                        wrote = true
                    }
                    // No file on disk + empty before-text → nothing to restore.
                    snapshot.beforeText.isEmpty() -> wrote = true
                }
            }
        }
        return wrote
    }

    // -----------------------------------------------------------------------
    // Pure ordering helpers (no IDE) — drive the rollback UI; unit-testable.
    // -----------------------------------------------------------------------

    /** A caller's edit mapped to its transcript position ([index], oldest = lowest) and target [filePath]. */
    data class EditRef(val index: Int, val filePath: String)

    /**
     * True iff some edit *after* [target] (a higher [EditRef.index]) touches the same file — i.e. reverting
     * [target] would clobber a later change to that path, so the UI should warn before rolling it back.
     */
    fun hasNewerEditToSameFile(edits: List<EditRef>, target: EditRef): Boolean =
        edits.any { it.index > target.index && it.filePath == target.filePath }

    /**
     * The lowest-index (oldest) edit per file path — the snapshot that holds the *original* pre-write contents,
     * so reverting it returns the file to its state before Claude touched it at all. File order follows the first
     * appearance of each path; ties never occur (indices are unique).
     */
    fun oldestPerFile(edits: List<EditRef>): List<EditRef> {
        val oldest = LinkedHashMap<String, EditRef>()
        for (e in edits) {
            val cur = oldest[e.filePath]
            if (cur == null || e.index < cur.index) oldest[e.filePath] = e
        }
        return oldest.values.toList()
    }

    /**
     * "Roll back all" traversal: the [oldestPerFile] refs, ordered newest-first (highest index first), so the
     * revert walks the most-recently-introduced file backwards toward the oldest — deterministic and stable.
     */
    fun rollbackAllOrder(edits: List<EditRef>): List<EditRef> =
        oldestPerFile(edits).sortedByDescending { it.index }
}
