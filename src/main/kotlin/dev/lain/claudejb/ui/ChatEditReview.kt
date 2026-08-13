package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * Reviewing and undoing an edit: the read-only diff a permission card shows, and the restore behind a
 * completed one.
 *
 * Extracted from `JcefChatPanel`, which is an assembler. Both halves are the same subject — what the file
 * looked like before, what it would look like after — and both are EDT-confined, which is what caps the file
 * read below.
 */
internal class ChatEditReview(
    private val project: Project,
    private val session: ClaudeSession,
    /** Transcript-side notice (the panel's attachment tray owns the one place these are worded). */
    private val notify: (String) -> Unit,
) {

    /**
     * For each reviewable Edit/Write/MultiEdit permission, compute a read-only unified diff (current vs proposed)
     * so the card can show what's changing in red/green. Edits are accepted/rejected as a whole — there is no
     * per-line selection (it produced incoherent, broken code).
     */
    fun diffsFor(perms: List<dev.lain.claudejb.permission.PendingPermission>): Map<String, String> =
        perms.mapNotNull { p -> inlineDiffFor(p)?.let { p.requestId to it } }.toMap()

    /**
     * The inline unified diff for one pending permission, or null when there is nothing worth rendering.
     *
     * Runs on the EDT, so the file read and the diff are both capped: a multi-MB file would freeze the UI, and
     * an inline diff is meaningless at that size. An oversized file simply skips the inline preview ("View
     * diff" still works, and accept/reject is unaffected — the binary does its own read and write).
     */
    private fun inlineDiffFor(p: dev.lain.claudejb.permission.PendingPermission): String? {
        if (!p.reviewable || p.toolName !in DiffPresenter.REVIEWABLE_TOOLS) return null
        val path = DiffPresenter.filePathOf(p.input) ?: return null
        val file = java.io.File(path)
        if (file.isFile && file.length() > MAX_HUNK_FILE_BYTES) return null
        val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
        val proposed = DiffPresenter.proposedContent(p.toolName, p.input, current) ?: return null
        return DiffPresenter.unifiedDiff(current, proposed).takeIf { it.isNotBlank() }
    }

    /**
     * Restore an edit: prefer the NATIVE rewind (ask Claude Code to restore the whole turn via
     * rewind_files), and only if that's unavailable offer the IDE-side per-file revert — behind a
     * confirmation with a "don't ask again" choice.
     */
    fun rewindOrRevert(toolUseId: String) {
        val snap = session.editSnapshot(toolUseId)
        val turn = session.userMessageIdFor(toolUseId)
        if (turn != null && session.checkpointingEnabled) {
            session.queries.requestRewindFiles(turn, dryRun = true) { probe ->
                if (probe != null && probe.canRewind) {
                    session.queries.requestRewindFiles(turn, dryRun = false) { done ->
                        if (done != null && done.canRewind) {
                            session.refreshAfterRewind(done.filesChanged)
                            val n = done.filesChanged.size
                            notify("Restored to this turn via Claude Code" + if (n > 0) " ($n file(s))." else ".")
                        } else {
                            offerIdeFallback(snap, done?.error ?: "rewind failed")
                        }
                    }
                } else {
                    offerIdeFallback(snap, probe?.error ?: "no checkpoint for this turn")
                }
            }
        } else {
            offerIdeFallback(snap, if (!session.checkpointingEnabled) "checkpointing disabled" else "no turn anchor for this edit")
        }
    }

    /** Confirmation (with a remembered choice) to fall back to the IDE-side per-file revert. */
    private fun offerIdeFallback(snap: dev.lain.claudejb.diff.EditSnapshot?, reason: String) {
        if (snap == null) {
            notify("Nothing to restore for this edit.")
            return
        }
        val settings = ClaudeSettings.getInstance(project)
        when (settings.rewindFallback) {
            "ide" -> {
                session.revertEdit(snap)
                return
            }

            "never" -> {
                notify("Native rewind unavailable ($reason).")
                return
            }
        }
        val doNotAsk = object : com.intellij.openapi.ui.DialogWrapper.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected) settings.rewindFallback = if (exitCode == com.intellij.openapi.ui.Messages.YES) "ide" else "never"
            }
        }
        val restore = com.intellij.openapi.ui.MessageDialogBuilder
            .yesNo(
                "Rewind Unavailable",
                "Claude Code's native rewind isn't available for this edit ($reason).\nRestore this file via the IDE instead?",
            )
            .yesText("Restore via IDE")
            .noText("Cancel")
            .icon(com.intellij.openapi.ui.Messages.getQuestionIcon())
            .doNotAsk(doNotAsk)
            .ask(project)
        if (restore) session.revertEdit(snap)
    }

    private companion object {
        // Files larger than this skip the EDT-side hunk read/diff for hunk-by-hunk review (full accept still works).
        const val MAX_HUNK_FILE_BYTES = 1_000_000L
    }
}
