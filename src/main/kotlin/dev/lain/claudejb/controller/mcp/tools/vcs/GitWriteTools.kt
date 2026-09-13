package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.model.git.GitCommitInfo
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

internal class GitWriteTools(
    private val project: Project,
    private val git: GitCommands = GitCommands(project),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val history: GitHistoryService get() = project.service()

    fun domain(): ToolDomain = ToolDomain(
        "git_write",
        "Stage, commit, branch and sync through the IDE's Git; every one refreshes the IDE's VCS views",
        listOf(Tool(GIT_STAGE, ::stage), Tool(GIT_COMMIT, ::commit), Tool(GIT_BRANCH, ::branch), Tool(GIT_REMOTE, ::remote)),
    )

    private suspend fun stage(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val paths = args.strings("paths")
        if (paths.isEmpty()) throw ToolException("paths must name at least one file or directory")
        val run: (List<FilePath>) -> Unit = when (action) {
            "add" -> git::stage
            "reset" -> git::unstage
            else -> throw ToolException("action must be add or reset")
        }
        val filePaths = paths.map { VcsPaths.filePath(project, it) }
        progress("Claude: git $action") { run(filePaths) }
        val root = root()
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("count", filePaths.size)
                put("paths", buildJsonArray { filePaths.forEach { add(JsonPrimitive(VcsPaths.relative(root, it))) } })
            },
        )
    }

    private suspend fun commit(args: ToolArgs): ToolResult {
        val message = args.string("message")
        if (message.isBlank()) throw ToolException("message must not be blank")
        val paths = args.strings("paths").map { VcsPaths.filePath(project, it) }
        val amend = args.boolean("amend", false)
        progress("Claude: git commit") { git.commit(message, paths, amend) }
        return ToolResult.toon(
            buildJsonObject {
                put("hash", history.headRevision() ?: "")
                put("subject", GitCommitInfo.subjectOf(message))
                put("files", paths.size)
            },
        )
    }

    private suspend fun branch(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val name = args.string("name")
        val start = args.optionalString("start_point")
        val run: () -> Unit = when (action) {
            "create" -> ({ git.createBranch(name, start ?: "HEAD") })
            "checkout" -> ({ git.checkout(start ?: name, start?.let { name }) })
            else -> throw ToolException("action must be create or checkout; a branch is deleted in the IDE's Branches popup")
        }
        progress("Claude: git $action $name", run)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("name", name)
                put("branch", history.currentBranch() ?: "")
                put("head", history.headRevision() ?: "")
            },
        )
    }

    private suspend fun remote(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val remote = args.optionalString("remote")
        val branch = args.optionalString("branch") ?: history.currentBranch()
            ?: throw ToolException("HEAD is detached; pass branch")
        val run: () -> String = when (action) {
            "fetch" -> ({ git.fetch(remote) })
            "pull" -> ({ git.pull(remote, branch) })
            "push" -> ({ git.push(remote, branch) })
            else -> throw ToolException("action must be fetch, pull or push")
        }
        val used = progress("Claude: git $action", run)
        val topology = withContext(io) { history.branchTopology() }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("remote", used)
                put("branch", branch)
                put("upstream", topology.upstream ?: "")
                put("ahead", topology.ahead ?: 0)
                put("behind", topology.behind ?: 0)
            },
        )
    }

    private fun root(): String = history.primaryRepositoryRoot() ?: throw ToolException("this project is not a Git working copy")

    private suspend fun <T> progress(title: String, block: () -> T): T =
        withBackgroundProgress(project, title, cancellable = true) { withContext(io) { block() } }

    companion object {

        private const val REMOTE_TIMEOUT_MILLIS = 300_000L

        val GIT_STAGE = ToolSpec(
            "git_stage",
            "Stages (add) or unstages (reset) files or directories through the IDE's Git, refreshing the Commit view. " +
                "Paths outside the project are refused.",
            listOf(
                Param("action", "add to stage, reset to unstage"),
                Param("paths", "Files or directories, absolute or relative to the project root", type = "array"),
            ),
            mutates = true,
        )

        val GIT_COMMIT = ToolSpec(
            "git_commit",
            "Commits through the IDE's Git: what is staged, or only the given paths (staged first, like the Commit " +
                "view does). Signing and hooks run as the user's Git configures them; the IDE answers any prompt.",
            listOf(
                Param("message", "The commit message; the first line is the subject"),
                Param("paths", "Commit only these files or directories (default: what is staged)", type = "array", required = false),
                Param("amend", "true to amend the previous commit (default false)", type = "boolean", required = false),
            ),
            mutates = true,
        )

        val GIT_BRANCH = ToolSpec(
            "git_branch",
            "Creates a branch without switching, or checks one out; with start_point, checkout creates the branch there " +
                "first. Deleting a branch is the user's, in the IDE's Branches popup.",
            listOf(
                Param("action", "create or checkout"),
                Param("name", "The branch name"),
                Param("start_point", "Commit or branch to start from (create: default HEAD; checkout: creates name)", required = false),
            ),
            mutates = true,
        )

        val GIT_REMOTE = ToolSpec(
            "git_remote",
            "Fetches, pulls or pushes through the IDE's Git, with the IDE's credentials; a first push sets the upstream. " +
                "Returns the branch's upstream with ahead/behind afterwards. Conflicts are resolved in the IDE.",
            listOf(
                Param("action", "fetch, pull or push"),
                Param("remote", "Remote name (default origin, or the only remote)", required = false),
                Param("branch", "Branch to pull or push (default: the current branch)", required = false),
            ),
            mutates = true,
            timeoutMillis = REMOTE_TIMEOUT_MILLIS,
        )
    }
}
