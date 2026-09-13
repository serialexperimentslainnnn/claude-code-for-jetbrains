package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LogOpsTools(
    private val project: Project,
    private val actions: IdeActions,
    private val git: GitCommands = GitCommands(project),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    fun domain(): ToolDomain = ToolDomain(
        "log_ops",
        "What the Git Log's commit menu and the Branches popup offer: an action on a commit, an operation on a branch, " +
            "the working trees and the remotes",
        listOf(Tool(COMMIT_ACTION, ::commitAction), Tool(BRANCH_OP, ::branchOp), Tool(WORKTREES, ::worktrees), Tool(REMOTES, ::remotes)),
    )

    private suspend fun commitAction(args: ToolArgs): ToolResult {
        val name = args.string("action")
        val id = COMMIT_ACTIONS[name] ?: throw ToolException("action must be one of ${COMMIT_ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args)
        val hash = target.hash ?: throw ToolException("commit_action needs hash")
        actions.dispatch(id, target)
        return ToolResult.toon(
            buildJsonObject {
                put("action", name)
                put("id", id)
                put("hash", hash)
                put("dispatched", true)
            },
        )
    }

    private suspend fun branchOp(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val ref = args.string("ref")
        val target = args.optionalString("target")
        withContext(Dispatchers.EDT) { FocusKeeper.keeping(project) { git.branchOp(action, ref, target) } }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("ref", ref)
                put("target", target ?: "")
                put("started", true)
            },
        )
    }

    private suspend fun worktrees(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        val path = args.optionalString("path")?.let { if (action == "add") VcsPaths.inside(project, it).toString() else it }
        val branch = args.optionalString("branch")
        val output = progress("Claude: git worktree $action") { git.worktrees(action, path, branch) }
        val listed = if (action == "list") output else git.worktrees("list", null, null)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("worktrees", buildJsonArray { worktreeRows(listed).forEach { add(it) } })
            },
        )
    }

    private fun worktreeRows(porcelain: List<String>) = porcelain
        .fold(mutableListOf<MutableMap<String, String>>()) { rows, line ->
            if (line.startsWith("worktree ")) rows += mutableMapOf("path" to line.removePrefix("worktree "))
            rows.lastOrNull()?.let { row ->
                if (line.startsWith("HEAD ")) row["head"] = line.removePrefix("HEAD ")
                if (line.startsWith("branch ")) row["branch"] = line.removePrefix("branch refs/heads/")
                if (line == "detached") row["branch"] = ""
            }
            rows
        }
        .map { row ->
            buildJsonObject {
                put("path", row["path"] ?: "")
                put("head", row["head"] ?: "")
                put("branch", row["branch"] ?: "")
            }
        }

    private suspend fun remotes(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        val name = args.optionalString("name")
        val url = args.optionalString("url")
        progress("Claude: git remote $action") { git.remotes(action, name, url) }
        val listed = git.remotes("list", null, null).filter { it.endsWith("(fetch)") }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("remotes", buildJsonArray { listed.forEach { add(JsonPrimitive(it.removeSuffix("(fetch)").trim())) } })
            },
        )
    }

    private suspend fun <T> progress(title: String, block: () -> T): T =
        withBackgroundProgress(project, title, cancellable = true) { withContext(io) { block() } }

    companion object {

        val COMMIT_ACTIONS: Map<String, String> = linkedMapOf(
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
        )

        val COMMIT_ACTION = ToolSpec(
            "commit_action",
            "Performs one entry of the Git Log's commit context menu on a commit (" + COMMIT_ACTIONS.keys.joinToString() + "): " +
                "the commit is selected in the Log, shown to the user, and the action runs with the log's own context, " +
                "exactly as the menu would. Entries that rewrite history or push open the IDE's dialog for the user to " +
                "finish.",
            listOf(Param("action", "One of the names above"), Param("hash", "The commit, 4 to 64 hex characters")),
            mutates = true,
        )

        val BRANCH_OP = ToolSpec(
            "branch_op",
            "One operation of the Branches popup on a branch, through the IDE's own branch machinery with its progress, " +
                "smart checkout and conflict resolution: " + GitCommands.BRANCH_ACTIONS + ". target is the other name " +
                "where one is needed: the upstream for rebase_onto, the new name for rename, checkout_as_new and new_tag.",
            listOf(
                Param("action", GitCommands.BRANCH_ACTIONS),
                Param("ref", "The branch, or for new_tag the reference the tag points at"),
                Param("target", "The other name the action needs (see above)", required = false),
            ),
            mutates = true,
        )

        val WORKTREES = ToolSpec(
            "worktrees",
            "The repository's working trees, as Git ▸ Working Trees shows them, or adds one at a path (with a new branch when " +
                "branch is given) or removes one. Returns the list after the action.",
            listOf(
                Param("action", "list (default), add or remove", required = false),
                Param("path", "Directory of the working tree (add, remove)", required = false),
                Param("branch", "New branch to create for the working tree (add)", required = false),
            ),
            mutates = true,
        )

        val REMOTES = ToolSpec(
            "remotes",
            "The repository's remotes with their fetch urls, as Git ▸ Manage Remotes shows them, or adds, removes or " +
                "renames one (rename takes the new name in url). Returns the list after the action.",
            listOf(
                Param("action", "list (default), add, remove or rename", required = false),
                Param("name", "The remote's name (add, remove, rename)", required = false),
                Param("url", "The remote's url (add), or the new name (rename)", required = false),
            ),
            mutates = true,
        )
    }
}
