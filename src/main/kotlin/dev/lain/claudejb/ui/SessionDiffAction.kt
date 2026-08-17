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

/**
 * "Review this session's changes" — everything the agent has touched, as native diff tabs.
 *
 * The per-edit diffs answer "what is this one call about to do"; nothing answered "what has this whole
 * session done to my tree", which is the question you actually ask before deciding whether to keep any of
 * it. `get_workspace_diff` is one round-trip that returns exactly that, and the binary resolves ONE base ref
 * for both stats and hunks (working tree vs HEAD, or branch vs the default branch's merge base when the tree
 * is clean), so every file on screen is measured against the same thing.
 *
 * **We render no diff of our own.** The reply carries hunks; [WorkspaceDiffReview] turns each file into two
 * whole texts and the IDE's own viewer draws them, in the user's theme, with their shortcuts. A file whose
 * base could not be rebuilt faithfully is still opened — with the left pane labelled with the reason, rather
 * than silently omitted or, worse, filled in with a guess.
 */
internal class SessionDiffAction(private val project: Project, private val tabs: ChatTabsPanel) :
    AnAction("Review This Session's Changes…", "Diff everything this session has changed, against its base", null) {

    /**
     * EDT deliberately, for the same reason [ClaudeToolWindowFactory]'s other entries give: [session] reads
     * the tab strip's selection, which is Swing state mutated on the EDT with no synchronization.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        // Disabled rather than hidden, matching every other entry in this menu — a gear menu whose items
        // appear and disappear as a turn starts and ends is harder to learn than one that greys out.
        e.presentation.isEnabled = session()?.isRunning() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = session() ?: return
        // `ask` hands the reply back on the EDT, so everything below is already on it.
        session.queries.requestWorkspaceDiff { diff ->
            when (diff) {
                null -> info(NOTHING)
                else -> present(diff)
            }
        }
    }

    /**
     * Reads the working tree OFF the EDT, then presents on it.
     *
     * `SessionQueries.ask` hands its reply back on the EDT, so the obvious place to put this work is also
     * the one place it must not go: the binary caps the diff at 50 files, and reading fifty files —
     * canonicalising each path through the containment gate on the way — is a visible freeze of the whole
     * IDE, not a pause. Same shape as [GitContextActions]: gather on a pooled thread, present on the EDT.
     */
    private fun present(diff: WorkspaceDiff) {
        val root = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            val sides = WorkspaceDiffReview.sides(diff) { path ->
                val absolute = if (File(path).isAbsolute) path else root?.let { "$it/$path" }
                // The same containment gate as every other file this plugin opens: a path the diff names
                // must still be inside the project before anything is read from it.
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

    /** EDT: the dialog and the diff tabs. Everything blocking already happened in [present]. */
    private fun open(diff: WorkspaceDiff, sides: List<WorkspaceDiffReview.Side>) {
        if (sides.isEmpty()) {
            info(NOTHING)
            return
        }
        if (sides.size > MAX_TABS) {
            // Opening fifty editor tabs at once is not a review, it is a denial of service on the editor.
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

        /** Above this, ask first — see the comment at the call site. */
        const val MAX_TABS = 8
    }
}
