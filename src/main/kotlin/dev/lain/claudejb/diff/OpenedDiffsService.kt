package dev.lain.claudejb.diff

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Project-level registry of diff tabs the plugin has opened (auto-approved edits, manual review cards).
 * Membership is plugin-scoped: we only know about diffs that went through [DiffPresenter.openDiff], so the
 * "Close all diffs" action never closes tabs the user opened from VCS, "Compare with…" or another source.
 *
 * Many edits in a row can pile up diff tabs — the per-request cleanup in `ChatPanel` only fires when the
 * permission card resolves, and auto-approved writes (acceptEdits/bypassPermissions) don't have a card. Sessions
 * close their own auto-opened diffs once the tool result lands (via [ClaudeSession]); this service is the
 * safety net + the explicit "Close all" button surface.
 */
@Service(Service.Level.PROJECT)
class OpenedDiffsService(private val project: Project) {

    private val files = CopyOnWriteArraySet<VirtualFile>()

    /** Records that we opened [file] as a diff tab. Idempotent; safe to call from any thread. */
    fun register(file: VirtualFile) { files.add(file) }

    /** Forgets [file] (called from [DiffPresenter.closeDiff] so unregister stays in lockstep with closing). */
    fun unregister(file: VirtualFile) { files.remove(file) }

    /** How many of our diff tabs are currently tracked as open. Drives action presentation (greyed when 0). */
    fun openCount(): Int = files.size

    /** Closes every diff tab the plugin opened (and forgets them). EDT only — touches the editor. */
    fun closeAll() {
        val manager = FileEditorManager.getInstance(project)
        val snapshot = files.toList()
        files.clear()
        snapshot.forEach { if (manager.isFileOpen(it)) manager.closeFile(it) }
    }

    companion object {
        fun getInstance(project: Project): OpenedDiffsService = project.service()
    }
}
