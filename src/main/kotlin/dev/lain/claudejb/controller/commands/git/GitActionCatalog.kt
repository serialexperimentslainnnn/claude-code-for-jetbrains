package dev.lain.claudejb.controller.commands.git

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.git.ForgeViewNavigator
import dev.lain.claudejb.controller.git.GitLogNavigator
import java.awt.datatransfer.StringSelection

internal object GitActionCatalog {

    enum class Kind { DIRECT, PROMPT, IDE, HOST }

    enum class Requires {
        NO_REPO,

        REPO,

        CHANGES,

        CHANGED_FILE,

        COMMIT,
    }

    data class RepoState(
        val hasRepo: Boolean,
        val hasChanges: Boolean = false,
        val hasChangedFile: Boolean = false,
    )

    interface PromptSubject {
        val changes: List<String>
        val changedFile: String?
    }

    sealed interface Behaviour {
        data object InitRepository : Behaviour

        class Prompt(val text: (PromptSubject, String) -> String?) : Behaviour

        data class Ide(val actionId: String) : Behaviour

        class Host(val run: (Project, String) -> Boolean) : Behaviour
    }

    data class GitAction(
        val id: String,
        val label: String,
        val hint: String,
        val behaviour: Behaviour,
        val requires: Requires,
        val group: String,
        val startsBlock: Boolean = false,
    ) {

        val kind: Kind
            get() = when (behaviour) {
                Behaviour.InitRepository -> Kind.DIRECT
                is Behaviour.Prompt -> Kind.PROMPT
                is Behaviour.Ide -> Kind.IDE
                is Behaviour.Host -> Kind.HOST
            }

        val ideActionId: String? get() = (behaviour as? Behaviour.Ide)?.actionId

        val takesCommit: Boolean get() = requires == Requires.COMMIT
    }

    val ACTIONS: List<GitAction> = listOf(
        GitAction(
            id = "init",
            label = "Initialize repository",
            hint = "Run git init -b main in the project root",
            behaviour = Behaviour.InitRepository,
            requires = Requires.NO_REPO,
            group = "Repository",
        ),
        GitAction(
            id = "commit",
            label = "Commit with Claude",
            hint = "Claude stages the changes and writes the commit message",
            behaviour = Behaviour.Prompt { subject, _ ->
                subject.changes.takeIf { it.isNotEmpty() }?.let(GitPromptedActions::commitPrompt)
            },
            requires = Requires.CHANGES,
            group = "Ask Claude",
        ),
        GitAction(
            id = "revertFile",
            label = "Revert this file with Claude",
            hint = "Restore the file open in the editor to its committed state",
            behaviour = Behaviour.Prompt { subject, _ -> subject.changedFile?.let(GitPromptedActions::revertFilePrompt) },
            requires = Requires.CHANGED_FILE,
            group = "Ask Claude",
        ),
        commitAction(
            "commitDiff",
            "View diff",
            "Show this commit and its changes in the IDE",
            Behaviour.Host { project, hash -> GitLogNavigator.showCommit(project, hash, focus = true) },
        ),
        commitAction(
            "commitCopyHash",
            "Copy hash",
            "Put the full commit hash on the clipboard",
            Behaviour.Host { _, hash ->
                CopyPasteManager.getInstance().setContents(StringSelection(hash))
                true
            },
        ),
        commitAction(
            "commitRevertToBranch",
            "Revert to this commit on a new branch",
            "Ask Claude to create a branch at this commit — the branch you are on does not move",
            Behaviour.Prompt { _, hash -> GitPromptedActions.revertToCommitOnNewBranchPrompt(hash) },
        ),
        commitAction(
            "commitRevert",
            "Revert just this commit",
            "Ask Claude to record a new commit undoing this one, keeping the history",
            Behaviour.Prompt { _, hash -> GitPromptedActions.revertCommitPrompt(hash) },
        ),
        commitAction(
            "commitBranch",
            "Create branch from this commit",
            "Ask Claude to start a branch at this commit — the branch you are on does not move",
            Behaviour.Prompt { _, hash -> GitPromptedActions.createBranchFromCommitPrompt(hash) },
        ),
        commitAction(
            "commitTag",
            "Create tag from this commit",
            "Ask Claude to put a tag on this commit",
            Behaviour.Prompt { _, hash -> GitPromptedActions.createTagFromCommitPrompt(hash) },
        ),
        GitAction(
            id = "forgeView",
            label = "Requests",
            hint = "Open the IDE's own pull or merge request view",
            behaviour = Behaviour.Host { project, _ -> ForgeViewNavigator.open(project, focus = true) },
            requires = Requires.REPO,
            group = "Repository",
        ),
        GitAction(
            id = "gitLog",
            label = "Git log",
            hint = "Open the IDE's Git log",
            behaviour = Behaviour.Host { project, _ -> GitLogNavigator.showLog(project, focus = true) },
            requires = Requires.REPO,
            group = "Repository",
        ),
        ideAction("branches", "Branches", "Switch, create or compare branches", "Git.Branches"),
        ideAction("pull", "Pull", "Pull from the remote", "Git.Pull", startsBlock = true),
        ideAction("fetch", "Fetch", "Fetch from the remote", "Git.Fetch"),
        ideAction("push", "Push", "Push to the remote", "Vcs.Push"),
        ideAction("merge", "Merge", "Merge a branch into this one", "Git.Merge", startsBlock = true),
        ideAction("rebase", "Rebase", "Rebase this branch", "Git.Rebase"),
    )

    fun byId(id: String): GitAction? = ACTIONS.firstOrNull { it.id == id }

    fun isCommitHash(hash: String): Boolean =
        hash.length in MIN_HASH_LENGTH..MAX_HASH_LENGTH &&
            hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun applicable(state: RepoState): List<GitAction> =
        ACTIONS.filter {
            when (it.requires) {
                Requires.NO_REPO -> !state.hasRepo
                Requires.REPO -> state.hasRepo
                Requires.CHANGES -> state.hasRepo && state.hasChanges
                Requires.CHANGED_FILE -> state.hasRepo && state.hasChangedFile
                Requires.COMMIT -> false
            }
        }

    fun commitActions(): List<GitAction> = ACTIONS.filter { it.requires == Requires.COMMIT }

    fun ideActions(): List<GitAction> = ACTIONS.filter { it.kind == Kind.IDE }

    private fun commitAction(id: String, label: String, hint: String, behaviour: Behaviour) = GitAction(
        id = id,
        label = label,
        hint = hint,
        behaviour = behaviour,
        requires = Requires.COMMIT,
        group = "Commit",
    )

    private fun ideAction(
        id: String,
        label: String,
        hint: String,
        actionId: String,
        startsBlock: Boolean = false,
    ) = GitAction(
        id = id,
        label = label,
        hint = hint,
        behaviour = Behaviour.Ide(actionId),
        requires = Requires.REPO,
        group = "IDE actions",
        startsBlock = startsBlock,
    )

    private const val MIN_HASH_LENGTH = 4

    private const val MAX_HASH_LENGTH = 64
}
