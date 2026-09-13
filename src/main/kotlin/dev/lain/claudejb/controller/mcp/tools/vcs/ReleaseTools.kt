package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.github.GitHubAvailability
import dev.lain.claudejb.controller.github.GitHubGateway
import dev.lain.claudejb.controller.github.MarketplaceGateway
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.util.PluginIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ReleaseTools(private val project: Project, gateway: () -> GitHubGateway = { GitHubGateway(project) }) {

    private val github: GitHubGateway by lazy { GitHubAvailability.require().let { gateway() } }

    fun domain(): ToolDomain = ToolDomain(
        "release",
        "What a release leaves behind, read through the IDE's GitHub account and the public Marketplace API: the repository's " +
            "tags, the workflow runs of a branch, one GitHub Release by tag, and the plugin's versions on the Marketplace",
        listOf(Tool(TAGS, ::tags), Tool(WORKFLOW_RUNS, ::workflowRuns), Tool(RELEASE, ::release), Tool(MARKETPLACE, ::marketplace)),
    )

    private suspend fun tags(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val rows = rows(github.getJson("/tags?per_page=$max"))
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put(
                    "tags",
                    buildJsonArray {
                        rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("name", text(row, "name"))
                                    put("sha", text(map(row, "commit"), "sha"))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun workflowRuns(args: ToolArgs): ToolResult {
        val branch = args.optionalString("branch")
        val max = args.int("max", DEFAULT_MAX)
        val query = "/actions/runs?per_page=$max" + (branch?.let { "&branch=$it" } ?: "")
        val rows = rows(map(github.getJson(query), "workflow_runs"))
        return ToolResult.toon(
            buildJsonObject {
                put("branch", branch ?: "")
                put("count", rows.size)
                put("runs", buildJsonArray { rows.forEach { add(run(it)) } })
            },
        )
    }

    private fun run(row: Map<*, *>): JsonObject = buildJsonObject {
        put("id", text(row, "id"))
        put("name", text(row, "name"))
        put("event", text(row, "event"))
        put("branch", text(row, "head_branch"))
        put("sha", text(row, "head_sha").take(SHORT_SHA))
        put("status", text(row, "status"))
        put("conclusion", text(row, "conclusion"))
        put("started", text(row, "run_started_at"))
        put("url", text(row, "html_url"))
    }

    private suspend fun release(args: ToolArgs): ToolResult {
        val tag = args.string("tag")
        val row = github.getJson("/releases/tags/$tag") as? Map<*, *> ?: throw ToolException("no GitHub Release for tag $tag")
        val assets = rows(row["assets"])
        return ToolResult.toon(
            buildJsonObject {
                put("tag", text(row, "tag_name"))
                put("name", text(row, "name"))
                put("draft", text(row, "draft") == "true")
                put("prerelease", text(row, "prerelease") == "true")
                put("published", text(row, "published_at"))
                put("url", text(row, "html_url"))
                put(
                    "assets",
                    buildJsonArray {
                        assets.forEach { asset ->
                            add(
                                buildJsonObject {
                                    put("name", text(asset, "name"))
                                    put("size", text(asset, "size"))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun marketplace(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MARKETPLACE)
        val updates = withContext(Dispatchers.IO) { MarketplaceGateway.updates(PluginIdentity.MARKETPLACE_ID, max) }
        return ToolResult.toon(
            buildJsonObject {
                put("plugin", PluginIdentity.MARKETPLACE_ID)
                put("count", updates.size)
                put(
                    "versions",
                    buildJsonArray {
                        updates.forEach { update ->
                            add(
                                buildJsonObject {
                                    put("version", update.version)
                                    put("channel", update.channel)
                                    put("listed", update.listed)
                                    put("approved", update.approved)
                                    put("published", update.publishedAt)
                                    put("ides", update.range)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun rows(value: Any?): List<Map<*, *>> = (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun map(value: Any?, key: String): Map<*, *> = (value as? Map<*, *>)?.get(key) as? Map<*, *> ?: emptyMap<Any, Any>()

    private fun text(row: Map<*, *>, key: String): String = row[key]?.toString().orEmpty()

    companion object {

        private const val DEFAULT_MAX = 20
        private const val DEFAULT_MARKETPLACE = 5
        private const val SHORT_SHA = 12

        val TAGS = ToolSpec(
            "tags",
            "The repository's tags on GitHub, newest first, with the commit each points at.",
            listOf(Param("max", "Maximum tags (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val WORKFLOW_RUNS = ToolSpec(
            "workflow_runs",
            "The GitHub Actions runs of the repository, newest first, optionally of one branch: workflow name, event, status " +
                "(queued, in_progress, completed), conclusion (success, failure, cancelled…) and url.",
            listOf(
                Param("branch", "Only the runs of this branch (default: all)", required = false),
                Param("max", "Maximum runs (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val RELEASE = ToolSpec(
            "release",
            "The GitHub Release of a tag: name, draft, prerelease, published date, url and its assets with sizes; refused " +
                "when the tag has no Release.",
            listOf(Param("tag", "The tag, e.g. v6.0.0")),
        )

        val MARKETPLACE = ToolSpec(
            "marketplace",
            "The plugin's versions on the JetBrains Marketplace, newest first: version, channel, listed, approved, published " +
                "date and the IDE range; read from the public Marketplace API, no account involved.",
            listOf(Param("max", "Maximum versions (default $DEFAULT_MARKETPLACE)", type = "integer", required = false)),
        )
    }
}
