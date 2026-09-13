package dev.lain.claudejb.controller.mcp.tools.vcs

import dev.lain.claudejb.model.mcp.Batch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogOpsToolSpecsTest {

    private val logOps = listOf(LogOpsTools.COMMIT_ACTION, LogOpsTools.BRANCH_OP, LogOpsTools.WORKTREES, LogOpsTools.REMOTES)
    private val changes = listOf(ChangesTools.STASH, ChangesTools.SHELVE, ChangesTools.PATCH, ChangesTools.ROLLBACK)
    private val history = listOf(HistoryTools.BLAME, HistoryTools.FILE_HISTORY, HistoryTools.LOCAL_HISTORY, HistoryTools.FILE_AT)

    @Test
    fun `the tool names are pinned per domain, and the readers of a file's past are read-only`() {
        assertEquals(listOf("commit_action", "branch_op", "worktrees", "remotes"), logOps.map { it.name })
        assertEquals(listOf("stash", "shelve", "patch", "rollback"), changes.map { it.name })
        assertEquals(listOf("blame", "file_history", "local_history", "file_at"), history.map { it.name })
        (logOps + changes).forEach { assertTrue(it.mutates, it.name) }
        listOf(HistoryTools.BLAME, HistoryTools.FILE_HISTORY, HistoryTools.FILE_AT).forEach { assertFalse(it.mutates, it.name) }
        assertTrue(HistoryTools.LOCAL_HISTORY.mutates)
    }

    @Test
    fun `every commit action is a verified log context menu id, listed in the description, and needs a hash`() {
        assertEquals(
            linkedMapOf(
                "cherry_pick" to "Vcs.CherryPick",
                "checkout" to "Git.CheckoutRevision",
                "browse_at_revision" to "Git.BrowseRepoAtRevision",
                "compare_with_local" to "Vcs.ShowDiffWithLocal",
                "reset_to" to "Git.Reset.In.Log",
                "revert" to "Git.Revert.In.Log",
                "undo" to "Git.Uncommit",
                "reword" to "Git.Reword.Commit",
                "fixup" to "Git.Fixup.To.Commit",
                "squash_into" to "Git.Squash.Into.Commit",
                "squash" to "Git.Squash.Commits",
                "drop" to "Git.Drop.Commits",
                "interactive_rebase" to "Git.Interactive.Rebase",
                "push_up_to" to "Git.PushUpToCommit",
                "add_to_remote_branch" to "Git.AddCommitToRemoteBranch",
                "new_branch" to "Git.CreateNewBranch.FromCommit",
                "new_tag" to "Git.CreateNewTag",
                "copy_revision" to "Vcs.CopyRevisionNumberAction",
                "open_in_browser" to "Github.Open.In.Browser",
            ),
            LogOpsTools.COMMIT_ACTIONS,
        )
        LogOpsTools.COMMIT_ACTIONS.keys.forEach { assertTrue(it in LogOpsTools.COMMIT_ACTION.description, "commit_action does not list $it") }
        assertEquals(listOf("action", "hash"), LogOpsTools.COMMIT_ACTION.params.filter { it.required }.map { it.name })
    }

    @Test
    fun `branch_op names every branch action of the write gateway`() {
        listOf("merge", "rebase", "rebase_onto", "compare", "diff_with_local", "rename", "delete", "checkout", "checkout_as_new", "new_tag")
            .forEach { assertTrue(it in GitCommands.BRANCH_ACTIONS && it in LogOpsTools.BRANCH_OP.description, it) }
    }

    @Test
    fun `the tools that take a list of changed files take it in one call`() {
        listOf("shelve", "patch", "rollback").forEach { assertTrue(it in Batch.ONE_CALL_LISTS, it) }
        assertTrue(ChangesTools.ROLLBACK.params.single().let { it.name == "paths" && it.type == "array" })
    }
}
