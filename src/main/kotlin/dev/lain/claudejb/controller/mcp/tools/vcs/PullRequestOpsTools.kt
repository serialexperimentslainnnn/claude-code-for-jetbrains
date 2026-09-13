package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.github.GitHubAvailability
import dev.lain.claudejb.controller.github.GitHubGateway
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.tools.run.Jobs
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PullRequestOpsTools(
    private val project: Project,
    private val reveal: Reveal,
    gateway: () -> GitHubGateway = { GitHubGateway(project) },
    private val pollMillis: Long = POLL_MILLIS,
) {

    private val github: GitHubGateway by lazy { GitHubAvailability.require().let { gateway() } }

    fun domain(): ToolDomain = ToolDomain(
        "pull_request_ops",
        "Pull requests as data through the IDE's GitHub account: create one, comment on one, read its checks and mergeability " +
            "until they settle, and merge it with a merge commit once everything is green",
        listOf(Tool(PR_CREATE, ::create), Tool(PR_COMMENT, ::comment), Tool(PR_CHECKS, ::checks), Tool(PR_MERGE, ::merge)),
    )

    private suspend fun create(args: ToolArgs): ToolResult {
        val base = args.string("base")
        val head = args.string("head")
        val title = args.string("title")
        val body = args.optionalString("body").orEmpty()
        val draft = args.boolean("draft", false)
        val created = github.createPullRequest(base, head, title, body, draft)
        if (reveal.mirroring) reveal.requests()
        return ToolResult.toon(
            buildJsonObject {
                put("number", created.head.number)
                put("title", created.head.title)
                put("base", base)
                put("head", head)
                put("draft", created.head.draft)
                put("url", created.head.url)
            },
        )
    }

    private suspend fun comment(args: ToolArgs): ToolResult {
        val number = number(args)
        val body = args.string("body")
        val url = github.comment(number, body)
        return ToolResult.toon(
            buildJsonObject {
                put("number", number)
                put("url", url)
                put("posted", true)
            },
        )
    }

    private suspend fun checks(args: ToolArgs): ToolResult {
        val number = number(args)
        val deadline = Jobs.deadline(args)
        var state = github.mergeability(number)
        while (!settled(state) && !deadline.expired()) {
            delay(minOf(pollMillis, deadline.remaining()))
            state = github.mergeability(number)
        }
        return ToolResult.toon(mergeabilityJson(number, state))
    }

    private suspend fun merge(args: ToolArgs): ToolResult {
        val number = number(args)
        val request = github.pullRequest(number)
        val state = github.mergeability(number)
        if (!settled(state)) throw ToolException("#$number is not settled yet (${state.mergeState}); pr_checks waits for it")
        val failed = state.checks.filter { it.state in FAILED }
        if (!state.canMerge || failed.isNotEmpty() || state.mergeState == UNSTABLE) {
            val names = failed.joinToString { it.name }.ifEmpty { "none" }
            throw ToolException("#$number cannot be merged: ${state.mergeState}, failed: $names")
        }
        val subject = args.optionalString("subject") ?: "${request.head.title} (#$number)"
        val body = args.optionalString("body").orEmpty()
        github.merge(number, subject, body, state.headSha)
        return ToolResult.toon(
            buildJsonObject {
                put("number", number)
                put("subject", subject)
                put("head_sha", state.headSha)
                put("merged", true)
            },
        )
    }

    private fun settled(state: GitHubGateway.Mergeability): Boolean =
        state.mergeState != UNKNOWN && state.checks.none { it.state in PENDING }

    private fun number(args: ToolArgs): Long {
        val number = args.int("number", 0).toLong()
        if (number <= 0) throw ToolException("number must be a positive pull request number")
        return number
    }

    private fun mergeabilityJson(number: Long, state: GitHubGateway.Mergeability): JsonObject = buildJsonObject {
        put("number", number)
        put("settled", settled(state))
        put("mergeable", state.mergeable)
        put("merge_state", state.mergeState)
        put("can_merge", state.canMerge && state.mergeState != UNSTABLE && state.checks.none { it.state in FAILED })
        put("head_sha", state.headSha)
        put(
            "checks",
            buildJsonArray {
                state.checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("name", check.name)
                            put("state", check.state)
                            put("required", check.required)
                            put("url", check.url)
                        },
                    )
                }
            },
        )
    }

    companion object {

        private const val POLL_MILLIS = 10_000L
        private const val UNKNOWN = "unknown"
        private const val UNSTABLE = "unstable"
        private val PENDING = setOf("pending", "expected", "queued", "in_progress", "waiting")
        private val FAILED = setOf("failure", "error", "cancelled", "timed_out", "action_required", "startup_failure")

        private val NUMBER = Param("number", "The pull request number", type = "integer")

        val PR_CREATE = ToolSpec(
            "pr_create",
            "Opens a pull request in the GitHub repository this project's remote points at, through the IDE's account: base " +
                "and head branch names, title, body (Markdown), draft. The IDE's Pull Requests view is shown. The head branch " +
                "must already be pushed (git_remote).",
            listOf(
                Param("base", "The branch to merge into, e.g. develop"),
                Param("head", "The branch to merge, e.g. feature/x"),
                Param("title", "The pull request title"),
                Param("body", "The description, Markdown (default empty)", required = false),
                Param("draft", "true to open it as a draft (default false)", type = "boolean", required = false),
            ),
            mutates = true,
        )

        val PR_COMMENT = ToolSpec(
            "pr_comment",
            "Posts a comment on a pull request's conversation, as the IDE's account.",
            listOf(NUMBER, Param("body", "The comment, Markdown")),
            mutates = true,
        )

        val PR_CHECKS = ToolSpec(
            "pr_checks",
            "The mergeability of a pull request and every check on its head commit (name, state, required, url), polled " +
                "every 10 s until nothing is pending or wait runs out; settled says which. can_merge is true only when the " +
                "merge state is clean and no check failed.",
            listOf(NUMBER, Jobs.WAIT),
        )

        val PR_MERGE = ToolSpec(
            "pr_merge",
            "Merges a pull request with a merge commit, through the IDE's account, only when its checks have settled green " +
                "and the merge state is clean; otherwise it refuses and names what blocks it. subject defaults to the title " +
                "with the number. Merging into a branch that publishes on merge publishes.",
            listOf(
                NUMBER,
                Param("subject", "The merge commit subject (default: title (#number))", required = false),
                Param("body", "The merge commit body (default empty)", required = false),
            ),
            mutates = true,
        )
    }
}
