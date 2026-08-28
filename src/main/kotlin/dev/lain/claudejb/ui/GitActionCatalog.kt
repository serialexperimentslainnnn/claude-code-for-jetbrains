package dev.lain.claudejb.ui

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

    data class GitAction(
        val id: String,
        val label: String,
        val hint: String,
        val kind: Kind,
        val requires: Requires,
        val ideActionId: String? = null,
        val group: String,
        val startsBlock: Boolean = false,
    ) {

        val takesCommit: Boolean get() = requires == Requires.COMMIT
    }

    val ACTIONS: List<GitAction> = listOf(
        GitAction(
            id = "init",
            label = "Initialize repository",
            hint = "Run git init -b main in the project root",
            kind = Kind.DIRECT,
            requires = Requires.NO_REPO,
            group = "Repository",
        ),
        GitAction(
            id = "commit",
            label = "Commit with Claude",
            hint = "Claude stages the changes and writes the commit message",
            kind = Kind.PROMPT,
            requires = Requires.CHANGES,
            group = "Ask Claude",
        ),
        GitAction(
            id = "revertFile",
            label = "Revert this file with Claude",
            hint = "Restore the file open in the editor to its committed state",
            kind = Kind.PROMPT,
            requires = Requires.CHANGED_FILE,
            group = "Ask Claude",
        ),
        commitAction("commitDiff", "View diff", "Show this commit and its changes in the IDE", Kind.HOST),
        commitAction("commitCopyHash", "Copy hash", "Put the full commit hash on the clipboard", Kind.HOST),
        commitAction(
            "commitRevertToBranch",
            "Revert to this commit on a new branch",
            "Ask Claude to create a branch at this commit — the branch you are on does not move",
            Kind.PROMPT,
        ),
        commitAction(
            "commitRevert",
            "Revert just this commit",
            "Ask Claude to record a new commit undoing this one, keeping the history",
            Kind.PROMPT,
        ),
        commitAction(
            "commitBranch",
            "Create branch from this commit",
            "Ask Claude to start a branch at this commit — the branch you are on does not move",
            Kind.PROMPT,
        ),
        commitAction(
            "commitTag",
            "Create tag from this commit",
            "Ask Claude to put a tag on this commit",
            Kind.PROMPT,
        ),
        GitAction(
            id = "forgeView",
            label = "Requests",
            hint = "Open the IDE's own pull or merge request view",
            kind = Kind.HOST,
            requires = Requires.REPO,
            group = "Repository",
        ),
        GitAction(
            id = "gitLog",
            label = "Git log",
            hint = "Open the IDE's Git log",
            kind = Kind.HOST,
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

    private fun commitAction(id: String, label: String, hint: String, kind: Kind) = GitAction(
        id = id,
        label = label,
        hint = hint,
        kind = kind,
        requires = Requires.COMMIT,
        group = "Commit",
    )

    private fun ideAction(
        id: String,
        label: String,
        hint: String,
        actionId: String,
        startsBlock: Boolean = false,
        requires: Requires = Requires.REPO,
        group: String = "IDE actions",
    ) = GitAction(
        id = id,
        label = label,
        hint = hint,
        kind = Kind.IDE,
        requires = requires,
        ideActionId = actionId,
        group = group,
        startsBlock = startsBlock,
    )

    private const val MIN_HASH_LENGTH = 4

    private const val MAX_HASH_LENGTH = 64
}
