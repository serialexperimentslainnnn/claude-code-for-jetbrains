package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession

internal object GitPromptedActions {

    fun gearEntries(project: Project, gitChat: () -> ClaudeSession): List<AnAction> = listOf(
        CommitChangesAction(project, gitChat),
        RevertFileAction(project, gitChat),
    )

    fun commitPrompt(changed: List<String>): String {
        val files = changed.take(MAX_LISTED_FILES).joinToString("\n") { "- ${oneLine(it)}" }
        val more = (changed.size - MAX_LISTED_FILES).takeIf { it > 0 }?.let { "\n- …and $it more\n" }.orEmpty()
        return "Commit the current changes in this repository.\n\n" +
            "The working tree has these changes:\n$files\n$more\n" +
            "Stage them and make ONE commit, writing the message yourself: a short imperative subject line, " +
            "and a body only if the change needs a reason. Do not push, do not amend an existing commit, do " +
            "not rebase, and do not create, switch or delete any branch. Tell me the subject line you used."
    }

    fun revertFilePrompt(path: String): String {
        val safe = oneLine(path)
        return "Restore `$safe` to its committed state, using `git restore -- $safe` (or `git checkout -- $safe` " +
            "on an older Git).\n\n" +
            "That one file, and no other. Do not pass any other pathspec, do not use `.` or `-A`, do not run " +
            "`git reset`, `git clean` or `git stash`, and do not touch the index for anything else. This " +
            "discards uncommitted work in that file, so run exactly the command above and nothing more."
    }

    fun revertToCommitOnNewBranchPrompt(hash: String): String? {
        if (!GitActionCatalog.isCommitHash(hash)) return null
        val branch = revertBranchName(hash)
        return "Take this repository back to commit `$hash`, on a NEW branch called `$branch`.\n\n" +
            "Create that branch at that commit and switch to it: `git switch --create $branch $hash` (or " +
            "`git checkout -b $branch $hash` on an older Git).\n\n" +
            "The branch I am on now must not move. Do not run `git reset`, do not rebase, amend, merge or " +
            "cherry-pick, do not force anything, do not push, and do not create, rename or delete any branch " +
            "other than `$branch`. Do not stash, commit or discard my uncommitted changes — if the switch is " +
            "refused because of them, stop and tell me rather than clearing the way. If `$branch` already " +
            "exists, stop and tell me; do not reuse it and do not overwrite it. Tell me which branch I am on " +
            "when you are done."
    }

    fun revertCommitPrompt(hash: String): String? {
        if (!GitActionCatalog.isCommitHash(hash)) return null
        return "Revert commit `$hash` in this repository, keeping the history: run `git revert --no-edit $hash`, " +
            "which records a NEW commit undoing that one.\n\n" +
            "That one commit, and no other. Do not run `git reset`, do not rebase, amend or force anything, do " +
            "not push, and do not create, switch or delete any branch. Do not stash or discard my uncommitted " +
            "changes — if `git revert` refuses because the working tree is dirty, stop and tell me. If it stops " +
            "on a conflict, leave the repository exactly as it is and tell me, naming `git revert --abort` as " +
            "the way back; do not resolve the conflict yourself and do not take one side wholesale."
    }

    fun createBranchFromCommitPrompt(hash: String): String? {
        if (!GitActionCatalog.isCommitHash(hash)) return null
        val branch = branchFromCommitName(hash)
        return "Create a branch called `$branch` at commit `$hash`, without switching to it: " +
            "`git branch $branch $hash`.\n\n" +
            "Stay on the branch I am on now. Do not check out or switch to anything, do not run `git reset`, " +
            "do not rebase, amend, merge or cherry-pick, do not force anything, do not push, and do not " +
            "create, rename or delete any branch other than `$branch`. Do not touch my uncommitted changes. " +
            "If `$branch` already exists, stop and tell me; do not reuse it and do not overwrite it. Tell me " +
            "the branch name when it exists."
    }

    fun createTagFromCommitPrompt(hash: String): String? {
        if (!GitActionCatalog.isCommitHash(hash)) return null
        return "I want a tag on commit `$hash`.\n\n" +
            "First ask me what to call it and wait for my answer — do not invent a name, and do not derive " +
            "one from the hash or the commit message. Once I give you the name, create the tag on that " +
            "commit and nothing else.\n\n" +
            "This repository signs its tags, so let the configured signing key do its work: do not pass " +
            "`--no-gpg-sign` and do not override the signing configuration to get around a prompt. If " +
            "signing fails, stop and tell me rather than retrying — repeated failures lock the key. Do not " +
            "push the tag, do not move or delete an existing tag, and do not create, switch or delete any " +
            "branch. If a tag of that name already exists, stop and tell me."
    }

    private fun revertBranchName(hash: String): String = "revert-to-${GitCommitInfo.shortHash(hash)}"

    private fun branchFromCommitName(hash: String): String = "from-${GitCommitInfo.shortHash(hash)}"

    private fun oneLine(path: String): String = path.map { if (isRenderable(it)) it else ' ' }.joinToString("")

    private fun isRenderable(ch: Char): Boolean =
        ch != '`' && !Character.isISOControl(ch) && Character.getType(ch) !in SEPARATOR_CATEGORIES

    private class CommitChangesAction(project: Project, gitChat: () -> ClaudeSession) :
        PromptEntry(
            project,
            gitChat,
            "Commit Changes with Claude",
            "Ask Claude to stage the current changes and commit them, message included",
        ) {

        override fun isApplicable(history: GitHistoryService): Boolean =
            history.isAvailable() && history.workingTreeChanges().isNotEmpty()

        override fun prompt(history: GitHistoryService): String? =
            history.workingTreeChanges().takeIf { it.isNotEmpty() }?.let { commitPrompt(it) }
    }

    private class RevertFileAction(project: Project, gitChat: () -> ClaudeSession) :
        PromptEntry(
            project,
            gitChat,
            "Revert This File with Claude",
            "Ask Claude to restore the file in the editor to its committed state",
        ) {

        override fun isApplicable(history: GitHistoryService): Boolean = changedFile(history) != null

        override fun prompt(history: GitHistoryService): String? = changedFile(history)?.let { revertFilePrompt(it) }

        private fun changedFile(history: GitHistoryService): String? {
            if (!history.isAvailable()) return null
            val absolute = EditorContextProvider.currentFilePath(project) ?: return null
            val root = history.primaryRepositoryRoot() ?: return null
            val relative = GitCommitInfo.relativize(root, absolute)
            return relative.takeIf { it in history.workingTreeChanges() }
        }
    }

    private abstract class PromptEntry(
        protected val project: Project,
        private val gitChat: () -> ClaudeSession,
        text: String,
        description: String,
    ) : AnAction(text, description, null) {

        abstract fun isApplicable(history: GitHistoryService): Boolean

        abstract fun prompt(history: GitHistoryService): String?

        protected fun history(): GitHistoryService? =
            if (project.isDisposed) null else project.service<GitHistoryService>()

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = history()?.let { isApplicable(it) } == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = history()?.let { prompt(it) } ?: return
            gitChat().send(text)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private const val MAX_LISTED_FILES = 40

    private val SEPARATOR_CATEGORIES =
        setOf(Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())

    const val INITIAL_BRANCH = "main"
}
