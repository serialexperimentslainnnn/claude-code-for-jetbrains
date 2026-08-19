package dev.lain.claudejb.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.git.GitLogNavigator
import javax.swing.JList

internal object GitContextActions {

    fun gearEntries(project: Project): List<AnAction> = listOf(
        RecentCommitsAction(project),
        CurrentFileHistoryAction(project),
        OpenGitLogAction(project),
    )

    fun menuText(branch: String?): String {
        val name = branch?.trim().orEmpty()
        if (name.isEmpty()) return "Recent Commits…"
        return "Recent Commits on ${TabSessionCommands.truncate(name, BRANCH_LABEL_MAX)}…"
    }

    fun popupTitle(branch: String?, head: String?): String {
        val where = branch?.trim()?.ifBlank { null } ?: DETACHED_HEAD
        val revision = head?.trim()?.ifBlank { null }?.let { " · ${GitCommitInfo.shortHash(it)}" }.orEmpty()
        return "Recent commits · $where$revision"
    }

    fun commitRow(commit: GitCommitInfo, now: Long = System.currentTimeMillis()): String {
        val subject = commit.subject.trim().ifBlank { NO_SUBJECT }
        val author = commit.authorName.trim().ifBlank { commit.authorEmail.trim() }.ifBlank { UNKNOWN_AUTHOR }
        val age = TabSessionCommands.relativeTime(commit.authoredAtMillis, now)
        return "${commit.shortHash}  ${TabSessionCommands.truncate(subject, SUBJECT_MAX)}" +
            "  ·  $author  ·  $age  ·  ${fileCount(commit.changedPaths.size)}"
    }

    private fun fileCount(files: Int): String = if (files == 1) "1 file" else "$files files"

    private class RecentCommitsAction(project: Project) :
        GitEntry(project, menuText(null), "Show this project's branch and its most recent commits") {

        override fun update(e: AnActionEvent) {
            super.update(e)
            if (!e.presentation.isVisible) return
            e.presentation.text = menuText(history()?.currentBranch())
        }

        override fun actionPerformed(e: AnActionEvent) {
            val history = history() ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val branch = history.currentBranch()
                val head = history.headRevision()
                val commits = history.recentCommits()
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) present(branch, head, commits)
                }, ModalityState.any())
            }
        }

        private fun present(branch: String?, head: String?, commits: List<GitCommitInfo>) {
            if (commits.isEmpty()) {
                Messages.showInfoMessage(project, NO_COMMITS, DIALOG_TITLE)
                return
            }
            val now = System.currentTimeMillis()
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(commits)
                .setTitle(popupTitle(branch, head))
                .setAdText(AD_OPENS_LOG)
                .setRenderer(
                    object : SimpleListCellRenderer<GitCommitInfo>() {
                        override fun customize(
                            list: JList<out GitCommitInfo>,
                            value: GitCommitInfo?,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean,
                        ) {
                            text = value?.let { commitRow(it, now) }.orEmpty()
                        }
                    },
                )
                .setItemChosenCallback { openLog(project) }
                .setRequestFocus(true)
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    private class CurrentFileHistoryAction(project: Project) :
        GitEntry(project, "Git History for the Current File", "Open the IDE's Git history for the file in the active editor") {

        override fun actionPerformed(e: AnActionEvent) {
            val path = EditorContextProvider.currentFilePath(project)
            if (path == null) {
                Messages.showInfoMessage(project, NO_FILE, DIALOG_TITLE)
                return
            }
            if (!GitLogNavigator.showFileHistory(project, path)) {
                Messages.showInfoMessage(project, NO_FILE_HISTORY, DIALOG_TITLE)
            }
        }
    }

    private class OpenGitLogAction(project: Project) :
        GitEntry(project, "Open Git Log", "Bring up the IDE's Version Control tool window") {

        override fun actionPerformed(e: AnActionEvent) = openLog(project)
    }

    private abstract class GitEntry(protected val project: Project, text: String, description: String) :
        AnAction(text, description, null) {

        protected fun history(): GitHistoryService? =
            if (project.isDisposed) null else project.service<GitHistoryService>()

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = history()?.isAvailable() == true
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private fun openLog(project: Project) {
        if (!GitLogNavigator.showLog(project)) {
            Messages.showInfoMessage(project, NO_LOG, DIALOG_TITLE)
        }
    }

    private const val BRANCH_LABEL_MAX = 24

    private const val SUBJECT_MAX = 72

    private const val DETACHED_HEAD = "detached HEAD"
    private const val NO_SUBJECT = "(no commit message)"
    private const val UNKNOWN_AUTHOR = "unknown author"

    private const val DIALOG_TITLE = "Claude Code"
    private const val AD_OPENS_LOG = "Choosing a commit opens the IDE's Git Log"
    private const val NO_COMMITS = "This repository has no commits yet."
    private const val NO_FILE = "Open a file in the editor first."
    private const val NO_FILE_HISTORY =
        "No Git history is available for this file. It has to live inside the project and be tracked by Git."
    private const val NO_LOG = "This IDE has no Version Control tool window for this project."
}
