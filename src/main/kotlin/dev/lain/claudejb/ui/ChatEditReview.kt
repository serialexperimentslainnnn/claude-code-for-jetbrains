package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings

internal class ChatEditReview(
    private val project: Project,
    private val session: ClaudeSession,
    private val notify: (String) -> Unit,
) {

    fun diffsFor(perms: List<dev.lain.claudejb.permission.PendingPermission>): Map<String, String> =
        perms.mapNotNull { p -> inlineDiffFor(p)?.let { p.requestId to it } }.toMap()

    private fun inlineDiffFor(p: dev.lain.claudejb.permission.PendingPermission): String? {
        if (!p.reviewable || p.toolName !in DiffPresenter.REVIEWABLE_TOOLS) return null
        val path = DiffPresenter.filePathOf(p.input) ?: return null
        val file = java.io.File(path)
        if (file.isFile && file.length() > MAX_HUNK_FILE_BYTES) return null
        val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
        val proposed = DiffPresenter.proposedContent(p.toolName, p.input, current) ?: return null
        return DiffPresenter.unifiedDiff(current, proposed).takeIf { it.isNotBlank() }
    }

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
        const val MAX_HUNK_FILE_BYTES = 1_000_000L
    }
}
