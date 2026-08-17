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

    // The pure ordering helpers that used to live here (`EditRef`, `hasNewerEditToSameFile`, `oldestPerFile`,
    // `rollbackAllOrder`) went out with the Diff History panel in 5.5.0: they existed to order a
    // whole-session "roll back all", and nothing rolls back more than one edit any more. They were tested,
    // correct and unreachable — which is this plugin's own signature defect, not a reason to keep them.
}
