package dev.lain.claudejb.controller.mcp.tools.vcs

import dev.lain.claudejb.model.mcp.ToolException

internal object ForgeActions {

    private const val MIN_HASH_LENGTH = 4
    private const val MAX_HASH_LENGTH = 64
    private val HASH = Regex("[0-9a-fA-F]{$MIN_HASH_LENGTH,$MAX_HASH_LENGTH}")
    private const val RANGE_SEPARATOR = ".."
    private const val HEAD = "HEAD"
    private val REF = Regex("[A-Za-z0-9_][A-Za-z0-9._/@{}~^-]*")

    fun commitHash(hash: String): String {
        if (!HASH.matches(hash)) throw ToolException("hash must be $MIN_HASH_LENGTH to $MAX_HASH_LENGTH hexadecimal characters")
        return hash
    }

    fun refRange(range: String): Pair<String, String> {
        val refs = range.split(RANGE_SEPARATOR)
        val exclusive = refs.first()
        val inclusive = if (refs.size == 1) HEAD else refs[1]
        if (refs.size > 2 || !REF.matches(exclusive) || !REF.matches(inclusive)) {
            throw ToolException(
                "range must be exclusive..inclusive, two refs or hashes as git log takes them; the second defaults to " + HEAD,
            )
        }
        return exclusive to inclusive
    }

    val ACTIONS: Map<String, String> = linkedMapOf(
        "pull" to "Git.Pull",
        "push" to "Vcs.Push",
        "fetch" to "Git.Fetch",
        "merge" to "Git.Merge",
        "rebase" to "Git.Rebase",
        "branches" to "Git.Branches",
        "stash" to "Git.Stash",
        "unstash" to "Git.Unstash",
        "tag" to "Git.Tag",
        "reset" to "Git.Reset",
        "resolve_conflicts" to "Git.ResolveConflicts",
        "commit" to "CheckinProject",
        "update" to "Vcs.UpdateProject",
        "unshallow" to "Git.Unshallow",
        "merge_abort" to "Git.Merge.Abort",
        "merge_commit" to "Git.Merge.Commit",
        "rebase_abort" to "Git.Rebase.Abort",
        "rebase_continue" to "Git.Rebase.Continue",
        "rebase_skip" to "Git.Rebase.Skip",
        "cherry_pick_continue" to "Git.CherryPick.Continue",
        "cherry_pick_abort" to "Git.CherryPick.Abort",
        "revert_abort" to "Git.Revert.Abort",
        "new_branch" to "Git.CreateNewBranch",
        "rename_branch" to "Git.Rename.Local.Branch",
        "compare_with_branch" to "Git.CompareWithBranch",
        "worktrees" to "Git.Show.WorkingTrees",
        "new_worktree" to "Git.CreateNewWorkingTree",
        "stash_silently" to "Git.Stash.Silently",
        "show_stash" to "Git.Show.Stash",
        "shelve" to "ChangesView.Shelve",
        "show_shelf" to "Vcs.Show.Shelf",
        "rollback" to "ChangesView.Revert",
        "annotate" to "Annotate",
        "compare_same_version" to "Compare.SameVersion",
        "file_history" to "Vcs.ShowTabbedFileHistory",
        "configure_remotes" to "Git.Configure.Remotes",
        "clone" to "Git.Clone",
        "init" to "Git.Init",
        "create_pull_request" to "Github.Create.Pull.Request",
        "pull_requests" to "Github.View.Pull.Request",
        "share_on_github" to "Github.Share",
        "clone_github" to "Github.Clone",
        "sync_fork" to "Github.Sync.Fork",
        "create_gist" to "Github.Create.Gist",
        "github_accounts" to "Github.Open.Settings",
        "create_merge_request" to "GitLab.Merge.Request.Create",
        "merge_requests" to "GitLab.Merge.Request.Show.List",
        "clone_gitlab" to "GitLab.Clone",
        "create_snippet" to "GitLab.Create.Snippet",
        "gitlab_accounts" to "GitLab.Open.Settings",
    )

    val MISSING: Map<String, String> = mapOf(
        "log" to "the IDE has no Version Control tool window, this project is not a Git working copy, or the Git log is " +
            "still loading: retry in a moment",
        "history" to "the path is outside the project, or the IDE has no history for it",
        "commit" to "this IDE has no Commit tool window",
        "pull_requests" to "neither the GitHub nor the GitLab plugin is installed, so there is no requests view",
    )
}
