package dev.lain.claudejb.view.feed

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.diff.EditSnapshot
import dev.lain.claudejb.model.permission.broker.PendingPermission
import dev.lain.claudejb.model.settings.ClaudeSettings
import java.io.File

internal class ChatEditReview(
    private val project: Project,
    private val session: ClaudeSession,
    private val notify: (String) -> Unit,
) {

    fun diffsFor(perms: List<PendingPermission>): Map<String, String> =
        perms.mapNotNull { p -> inlineDiffFor(p)?.let { p.requestId to it } }.toMap()

    private fun inlineDiffFor(p: PendingPermission): String? {
        if (!p.reviewable || p.toolName !in DiffPresenter.REVIEWABLE_TOOLS) return null
        val path = DiffPresenter.filePathOf(p.input) ?: return null
        val file = File(path)
        if (file.isFile && file.length() > MAX_HUNK_FILE_BYTES) return null
        val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
        val proposed = DiffPresenter.proposedContent(p.toolName, p.input, current) ?: return null
        return DiffPresenter.unifiedDiff(current, proposed).takeIf { it.isNotBlank() }
    }

    fun rewindOrRevert(toolUseId: String) {
        val snap = session.cards.editSnapshot(toolUseId)
        val turn = session.prompts.userMessageIdFor(toolUseId)
        val checkpointing = ClaudeSettings.getInstance(project).enableFileCheckpointing
        if (turn == null || !checkpointing) {
            offerIdeFallback(snap, if (!checkpointing) "checkpointing disabled" else "no turn anchor for this edit")
            return
        }
        session.queries.requestRewindFiles(turn, dryRun = true) { probe ->
            if (probe == null || !probe.canRewind) {
                offerIdeFallback(snap, probe?.error ?: "no checkpoint for this turn")
                return@requestRewindFiles
            }
            session.queries.requestRewindFiles(turn, dryRun = false) { done ->
                if (done != null && done.canRewind) {
                    session.diffs.refreshAfterRewind(done.filesChanged)
                    val n = done.filesChanged.size
                    notify("Restored to this turn via Claude Code" + if (n > 0) " ($n file(s))." else ".")
                } else {
                    offerIdeFallback(snap, done?.error ?: "rewind failed")
                }
            }
        }
    }

    private fun offerIdeFallback(snap: EditSnapshot?, reason: String) {
        if (snap == null) {
            notify("Nothing to restore for this edit.")
            return
        }
        val settings = ClaudeSettings.getInstance(project)
        when (settings.rewindFallback) {
            "ide" -> {
                revert(snap)
                return
            }

            "never" -> {
                notify("Native rewind unavailable ($reason).")
                return
            }
        }
        val doNotAsk = object : DialogWrapper.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected) settings.rewindFallback = if (exitCode == Messages.YES) "ide" else "never"
            }
        }
        val restore = MessageDialogBuilder
            .yesNo(
                "Rewind Unavailable",
                "Claude Code's native rewind isn't available for this edit ($reason).\nRestore this file via the IDE instead?",
            )
            .yesText("Restore via IDE")
            .noText("Cancel")
            .icon(Messages.getQuestionIcon())
            .doNotAsk(doNotAsk)
            .ask(project)
        if (restore) revert(snap)
    }

    private fun revert(snap: EditSnapshot) {
        val name = File(snap.filePath).name
        if (session.rollback.revertEdit(snap)) {
            session.notifier.info("Reverted $name to its state before this edit.")
        } else {
            session.notifier.error("Couldn't revert $name (the file may be outside the project, missing, or locked).")
        }
    }

    private companion object {
        const val MAX_HUNK_FILE_BYTES = 1_000_000L
    }
}
