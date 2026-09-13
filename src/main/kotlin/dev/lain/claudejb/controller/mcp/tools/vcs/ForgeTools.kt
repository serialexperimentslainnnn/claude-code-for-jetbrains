package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import dev.lain.claudejb.controller.git.ForgeViewNavigator
import dev.lain.claudejb.controller.github.GitHubAvailability
import dev.lain.claudejb.controller.github.GitHubGateway
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ForgeTools(
    private val project: Project,
    private val actions: IdeActions,
    private val reveal: Reveal,
    gateway: () -> GitHubGateway = { GitHubGateway(project) },
) {

    private val github: GitHubGateway by lazy { GitHubAvailability.require().let { gateway() } }

    fun domain(): ToolDomain = ToolDomain(
        "forge",
        "The IDE's own VCS surface: its log, history, commit and pull-request views, its Git, GitHub and GitLab dialogs, " +
            "and the pull requests of the GitHub repository through the IDE's account",
        listOf(Tool(VCS_OPEN, ::open), Tool(VCS_ACTION, ::action), Tool(PULL_REQUESTS, ::pullRequests), Tool(PULL_REQUEST, ::pullRequest)),
    )

    private suspend fun pullRequests(args: ToolArgs): ToolResult {
        val state = args.optionalString("state") ?: "open"
        val max = args.int("max", DEFAULT_MAX)
        val filter = STATES[state] ?: throw ToolException("state must be one of ${STATES.keys.joinToString()}")
        val repository = github.repository()
        val requests = github.pullRequests(filter, max)
        if (reveal.mirroring) reveal.requests()
        return ToolResult.toon(
            buildJsonObject {
                put("repository", "${repository.owner}/${repository.name}")
                put("state", state)
                put("count", requests.size)
                put("pull_requests", buildJsonArray { requests.forEach { add(row(it)) } })
            },
        )
    }

    private suspend fun pullRequest(args: ToolArgs): ToolResult {
        val number = args.int("number", 0).toLong()
        if (number <= 0) throw ToolException("number must be a positive pull request number")
        val request = github.pullRequest(number)
        val shown = if (args.boolean("open", false)) openInIde(number, request.head.url) else ""
        val branches = request.branches
        val bodyChars = args.int("body_chars", DEFAULT_BODY_CHARS)
        val body = branches?.body.orEmpty()
        return ToolResult.toon(
            buildJsonObject {
                row(request).forEach { (key, value) -> put(key, value) }
                put("base", branches?.base ?: "")
                put("head", branches?.head ?: "")
                put("review_decision", branches?.reviewDecision ?: "")
                put("shown", shown)
                put("body_chars", body.length)
                put("body_truncated", body.length > bodyChars)
                put("body", body.take(bodyChars))
            },
        )
    }

    private suspend fun openInIde(number: Long, url: String): String {
        if (!reveal.requests()) {
            withContext(Dispatchers.EDT) { BrowserUtil.browse(url) }
            return "browser"
        }
        if (withContext(Dispatchers.EDT) { ForgeViewNavigator.selectTimeline(project, number) }) return "ide"
        val context = withContext(Dispatchers.EDT) { ForgeViewNavigator.selectRequest(project, number) }
        if (context != null && runCatching { actions.dispatch(SHOW_PULL_REQUEST, context) }.isSuccess) return "ide"
        return "none"
    }

    private fun row(request: GitHubGateway.Request): JsonObject = buildJsonObject {
        put("number", request.head.number)
        put("title", request.head.title)
        put("state", request.head.state)
        put("draft", request.head.draft)
        put("author", request.head.author)
        put("updated", request.updatedAt)
        put("url", request.head.url)
    }

    private suspend fun open(args: ToolArgs): ToolResult {
        val view = args.string("view")
        val hash = args.optionalString("hash").orEmpty()
        val range = args.optionalString("range").orEmpty()
        val path = args.optionalString("path").orEmpty()
        val opened = when (view) {
            "log" -> showLog(hash, range)
            "history" -> reveal.fileHistory(historyPath(path))
            "commit" -> reveal.toolWindow(ToolWindowId.COMMIT)
            "pull_requests" -> reveal.requests()
            else -> throw ToolException("view must be log, history, commit or pull_requests")
        }
        if (!opened) throw ToolException(ForgeActions.MISSING.getValue(view))
        return ToolResult.toon(
            buildJsonObject {
                put("view", view)
                put("opened", true)
                put("hash", hash)
                put("range", range)
                put("path", path)
            },
        )
    }

    private suspend fun showLog(hash: String, range: String): Boolean = when {
        range.isNotEmpty() -> ForgeActions.refRange(range).let { (exclusive, inclusive) -> reveal.range(exclusive, inclusive) }
        hash.isNotEmpty() -> reveal.commit(ForgeActions.commitHash(hash))
        else -> reveal.log()
    }

    private fun historyPath(path: String): String {
        if (path.isEmpty()) throw ToolException("view=history needs path")
        return VcsPaths.base(project).resolve(path).normalize().toString()
    }

    private suspend fun action(args: ToolArgs): ToolResult {
        val name = args.string("action")
        val id = ForgeActions.ACTIONS[name] ?: throw ToolException("action must be one of ${ForgeActions.ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args)
        if (target.named) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("action", name)
                put("id", id)
                put("target", target.path ?: target.hash ?: target.node ?: "")
                put("dispatched", true)
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 30
        private const val DEFAULT_BODY_CHARS = 2_000
        private const val SHOW_PULL_REQUEST = "Github.PullRequest.Show"
        private val STATES = linkedMapOf("open" to "is:open", "closed" to "is:closed is:unmerged", "merged" to "is:merged", "all" to "")

        val PULL_REQUESTS = ToolSpec(
            "pull_requests",
            "The pull requests of the GitHub repository this project's remote points at, through the IDE's own GitHub " +
                "account: number, title, state, draft, author, last update and url, newest first; the IDE's Pull " +
                "Requests view is shown. Refused when the GitHub plugin, an account or a GitHub remote is missing.",
            listOf(
                Param("state", "open (default), closed, merged or all", required = false),
                Param("max", "Maximum pull requests (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val PULL_REQUEST = ToolSpec(
            "pull_request",
            "One pull request by number, with its base and head branches, review decision and the first body_chars of its " +
                "description (body_truncated says when there is more); with open, the request is selected and opened in the " +
                "IDE's Pull Requests view and its timeline tab is opened in the editor, or selected when it is already open " +
                "(shown=ide); shown=none when the view lists no such request yet; the browser only when the IDE has no " +
                "Pull Requests view (shown=browser).",
            listOf(
                Param("number", "The pull request number", type = "integer"),
                Param("open", "true to also open it for the user (default false)", type = "boolean", required = false),
                Param(
                    "body_chars",
                    "Characters of the description to return (default $DEFAULT_BODY_CHARS)",
                    type = "integer",
                    required = false,
                ),
            ),
        )

        val VCS_OPEN = ToolSpec(
            "vcs_open",
            "Shows one of the IDE's VCS views: the Git log (at a commit when hash is given, or only the commits of a range), " +
                "a file's history, the Commit tool window, or the GitHub/GitLab pull or merge requests view. Use it to put what " +
                "you found in front of the user, and range to compare a branch with a tag or a release with the previous one.",
            listOf(
                Param("view", "log, history, commit or pull_requests"),
                Param("hash", "Commit to select in the log, 4 to 64 hex characters (view=log only)", required = false),
                Param(
                    "range",
                    "exclusive..inclusive as git log takes it, e.g. v1.2.0..HEAD; inclusive defaults to HEAD. Opens a log tab " +
                        "with only those commits (view=log only)",
                    required = false,
                ),
                Param("path", "File whose history to show, absolute or relative to the project root (view=history)", required = false),
            ),
        )

        val VCS_ACTION = ToolSpec(
            "vcs_action",
            "Opens one of the IDE's own Git, GitHub or GitLab menu entries (" + ForgeActions.ACTIONS.keys.joinToString() +
                ") for the user to finish: every item of the Git menu and its GitHub and GitLab submenus, by name. Give path for " +
                "the entries that act on a file (annotate, compare_same_version, file_history, shelve, rollback) or hash " +
                "for one that acts on a commit. It returns as soon as the dialog opens; nothing is changed until the user " +
                "confirms there.",
            listOf(Param("action", "One of the names above")) + TargetContext.PARAMS,
            mutates = true,
        )
    }
}
