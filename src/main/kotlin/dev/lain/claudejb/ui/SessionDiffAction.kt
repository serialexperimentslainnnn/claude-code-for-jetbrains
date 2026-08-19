package dev.lain.claudejb.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkspaceDiff
import dev.lain.claudejb.session.WorkspaceDiffReview
import java.io.File

internal class SessionDiffAction(private val project: Project, private val tabs: ChatTabsPanel) :
    AnAction("Review This Session's Changes…", "Diff everything this session has changed, against its base", null) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = session()?.isRunning() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = session() ?: return
        session.queries.requestWorkspaceDiff { diff ->
            when (diff) {
                null -> info(NOTHING)
                else -> present(diff)
            }
        }
    }

    private fun present(diff: WorkspaceDiff) {
        val root = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            val sides = WorkspaceDiffReview.sides(diff) { path ->
                val absolute = if (File(path).isAbsolute) path else root?.let { "$it/$path" }
                absolute
                    ?.takeIf { DiffPresenter.isWithinRoot(it, root) }
                    ?.let { runCatching { File(it).readText() }.getOrNull() }
            }
            ApplicationManager.getApplication().invokeLater(
                { if (!project.isDisposed) open(diff, sides) },
                ModalityState.any(),
            )
        }
    }

    private fun open(diff: WorkspaceDiff, sides: List<WorkspaceDiffReview.Side>) {
        if (sides.isEmpty()) {
            info(NOTHING)
            return
        }
        if (sides.size > MAX_TABS) {
            val proceed = Messages.showYesNoDialog(
                project,
                "This session changed ${sides.size} files. Open a diff tab for each?",
                TITLE,
                "Open ${sides.size} tabs",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (proceed != Messages.YES) return
        }
        sides.forEach { side ->
            DiffPresenter.openTextDiff(
                project = project,
                path = side.path,
                base = DiffPresenter.TextSide(WorkspaceDiffReview.baseLabel(side, diff.baseLabel), side.base.orEmpty()),
                current = DiffPresenter.TextSide("Now: ${File(side.path).name}", side.current),
            )
        }
    }

    private fun session(): ClaudeSession? = tabs.selectedChat?.session

    private fun info(message: String) = Messages.showInfoMessage(project, message, TITLE)

    private companion object {
        const val TITLE = "Session Changes"
        const val NOTHING = "This session has not changed any files that can be diffed."

        const val MAX_TABS = 8
    }
}
