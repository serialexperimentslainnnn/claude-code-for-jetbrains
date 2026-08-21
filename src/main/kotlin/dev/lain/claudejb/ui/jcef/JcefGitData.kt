package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.forge.ForgeAccess
import dev.lain.claudejb.forge.ForgePullRequest
import dev.lain.claudejb.forge.ForgeRun
import dev.lain.claudejb.git.GitBranchTopology
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitRefInfo
import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.ui.GitActionCatalog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefGitData {

    enum class ActionState(private val equivalent: AgentStatus) {
        RUNNING(AgentStatus.RUNNING),
        COMPLETED(AgentStatus.COMPLETED),
        FAILED(AgentStatus.FAILED),
        ;

        val word: String get() = JcefStatus.of(equivalent)
    }

    data class Repo(
        val present: Boolean,
        val branch: String? = null,
        val head: String? = null,
        val root: String? = null,
    )

    data class Snapshot(
        val available: Boolean,
        val repo: Repo = Repo(present = false),
        val changes: List<String> = emptyList(),
        val commits: List<GitCommitInfo> = emptyList(),
        val refs: List<GitRefInfo> = emptyList(),
        val changedFileOpen: Boolean = false,
        val actionStates: Map<String, ActionState> = emptyMap(),
        val topology: GitBranchTopology = GitBranchTopology.NONE,
        val pullRequests: List<ForgePullRequest>? = null,
        val runs: List<ForgeRun>? = null,
        val lastRun: ForgeRun? = null,
        val forgeConfigured: Boolean = false,
        val forgeProvider: String? = null,
        val forgeAccess: ForgeAccess? = null,
    )

    fun gitJson(snapshot: Snapshot?): JsonObject? {
        if (snapshot == null) return null
        if (!snapshot.available) return buildJsonObject { put("available", false) }
        return buildJsonObject {
            put("available", true)
            put("repo", repoJson(snapshot.repo))
            put("changes", buildJsonArray { snapshot.changes.forEach { add(it) } })
            put("commits", commitsJson(snapshot.commits))
            put("refs", refsJson(snapshot.refs))
            put("actions", actionsJson(snapshot))
            put("commitActions", commitActionsJson())
            put("topology", topologyJson(snapshot.topology))
            snapshot.pullRequests?.let { put("pullRequests", pullRequestsJson(it)) }
            snapshot.runs?.let { put("runs", buildJsonArray { it.forEach { run -> add(runJson(run)) } }) }
            snapshot.lastRun?.let { put("lastRun", runJson(it)) }
            put("forge", forgeStateJson(snapshot))
        }
    }

    private fun topologyJson(topology: GitBranchTopology): JsonObject = buildJsonObject {
        put("branch", topology.branch)
        put("upstream", topology.upstream)
        put("ahead", topology.ahead)
        put("behind", topology.behind)
        put("mergeBase", topology.mergeBase)
    }

    private fun pullRequestsJson(pulls: List<ForgePullRequest>) = buildJsonArray {
        pulls.forEach { pull ->
            addJsonObject {
                put("number", pull.number)
                put("title", pull.title)
                put("url", pull.url)
                put("state", pull.state)
                put("draft", pull.draft)
                put("author", pull.author)
                put("sourceBranch", pull.sourceBranch)
            }
        }
    }

    private fun forgeStateJson(snapshot: Snapshot): JsonObject = buildJsonObject {
        put("configured", snapshot.forgeConfigured)
        put("answered", snapshot.pullRequests != null || snapshot.runs != null)
        put("provider", snapshot.forgeProvider)
        put("access", accessJson(snapshot.forgeAccess))
    }

    private fun accessJson(access: ForgeAccess?): JsonElement {
        if (access == null) return JsonNull
        return buildJsonObject {
            put("level", access.level.wire)
            put("login", access.login)
            put("canComment", access.canComment)
            put("canApprove", access.canApprove)
            put("canRunPipelines", access.canRunPipelines)
            put("canMerge", access.canMerge)
            put("canOpen", access.canOpen)
        }
    }

    private fun runJson(run: ForgeRun): JsonObject = buildJsonObject {
        put("id", run.id)
        put("name", run.name)
        put("status", run.status.wire)
        put("url", run.url)
        put("finishedAt", run.finishedAtIso)
    }

    private fun repoJson(repo: Repo): JsonObject = buildJsonObject {
        put("present", repo.present)
        put("branch", repo.branch?.takeIf { it.isNotBlank() })
        put("head", repo.head?.takeIf { it.isNotBlank() })
        put("root", repo.root?.takeIf { it.isNotBlank() })
    }

    private fun commitsJson(commits: List<GitCommitInfo>) = buildJsonArray {
        commits.forEach { c ->
            addJsonObject {
                put("hash", c.hash)
                put("short", c.shortHash)
                put("subject", c.subject)
                put("author", c.authorName)
                put("authoredAtMillis", c.authoredAtMillis)
                put("files", c.changedPaths.size)
                put("parents", buildJsonArray { c.parents.forEach { add(it) } })
            }
        }
    }

    private fun refsJson(refs: List<GitRefInfo>) = buildJsonArray {
        refs.forEach { ref ->
            addJsonObject {
                put("name", ref.name)
                put("kind", ref.kind.wire)
                put("hash", ref.hash)
                put("short", GitCommitInfo.shortHash(ref.hash))
                put("current", ref.current)
            }
        }
    }

    private fun commitActionsJson() = buildJsonArray {
        GitActionCatalog.commitActions().forEach { action ->
            addJsonObject {
                put("id", action.id)
                put("label", action.label)
                put("hint", action.hint)
            }
        }
    }

    private fun actionsJson(snapshot: Snapshot) = buildJsonArray {
        val applicable = GitActionCatalog.applicable(
            hasRepo = snapshot.repo.present,
            hasChanges = snapshot.changes.isNotEmpty(),
            hasChangedFile = snapshot.changedFileOpen,
        )
        applicable.forEach { action ->
            addJsonObject {
                put("id", action.id)
                put("label", action.label)
                put("hint", action.hint)
                put("kind", action.kind.name.lowercase())
                put("group", action.group)
                put("status", snapshot.actionStates[action.id]?.word)
            }
        }
    }
}
