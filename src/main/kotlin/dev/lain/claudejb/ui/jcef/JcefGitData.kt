package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.ui.GitActionCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Git view's payload — one card of [JcefSessionData]'s document, under the `git` key.
 *
 * <pre>
 * git: {
 *   available: Boolean,
 *   repo:    { present, branch|null, head|null, root|null },
 *   changes: [String],                                              // paths relative to the repository root
 *   commits: [{ hash, short, subject, author, ageMillis, files }],
 *   actions: [{ id, label, hint, kind, group, status|null }]
 * }
 * </pre>
 *
 * **Pure**, like every other `Jcef*` builder: it takes a [Snapshot] someone else collected and returns JSON. The
 * collection is the part that touches the IDE and must run off the EDT (`GitHistoryService.recentCommits` spawns
 * `git log`); keeping it out of here is what lets the shape be pinned on a plain JVM.
 *
 * **Null-safe in two steps, and they mean different things.** A null [Snapshot] is "nothing has been collected"
 * and emits no `git` key at all; a snapshot with `available = false` is "there is no Git here" and emits
 * `{available:false}` and nothing else. Both make the page omit the view, and neither invents an empty repository.
 * Once Git IS available the collections are emitted even when empty — `[]` is the honest answer to "what is
 * uncommitted" in a clean tree, and the page tells it apart from an absent key.
 *
 * The action list is [GitActionCatalog]'s, filtered by the state in the snapshot. It is never restated here: the
 * page sends an id back and the host looks it up in that same catalogue, so a second list is a button labelled
 * one thing and doing another.
 */
object JcefGitData {

    /**
     * How an action the user launched is going. Absent — the ordinary case — means it has not been run.
     *
     * The wire word comes from [JcefStatus], which owns the ONE vocabulary the page colours by; an action shares
     * the three outcomes of any other unit of work, so it reuses those three words rather than minting its own.
     */
    enum class ActionState(private val equivalent: AgentStatus) {
        RUNNING(AgentStatus.RUNNING),
        COMPLETED(AgentStatus.COMPLETED),
        FAILED(AgentStatus.FAILED),
        ;

        val word: String get() = JcefStatus.of(equivalent)
    }

    /** Where HEAD is. [present] false is a project with no repository — the one state that offers `init`. */
    data class Repo(
        val present: Boolean,
        val branch: String? = null,
        val head: String? = null,
        val root: String? = null,
    )

    /**
     * Everything the view draws, already read off the IDE.
     *
     * [available] is the **Git plugin** being there, and nothing more — deliberately not "and a repository too".
     * A project with no repository is the one state that offers *Initialize repository*, so folding the
     * repository into this flag would emit `{available:false}`, omit the view, and hide the feature's own
     * entry point. Whether a repository exists is [Repo.present].
     *
     * [changedFileOpen] is the question only the editor can answer — whether the file in front of the user is
     * one of [changes] — and it is what makes the per-file action appear.
     */
    data class Snapshot(
        val available: Boolean,
        val repo: Repo = Repo(present = false),
        val changes: List<String> = emptyList(),
        val commits: List<GitCommitInfo> = emptyList(),
        val changedFileOpen: Boolean = false,
        val actionStates: Map<String, ActionState> = emptyMap(),
    )

    /**
     * The `git` value, or null when there is nothing collected to draw.
     *
     * [nowMillis] is the reference instant for each commit's `ageMillis`: passed in so the payload is a pure
     * function of its inputs and the test does not have to reason about wall-clock drift. A commit dated in the
     * future — a skewed clock on the machine that wrote it — reads as age zero rather than as a negative number
     * the page would have to interpret.
     */
    fun gitJson(snapshot: Snapshot?, nowMillis: Long = System.currentTimeMillis()): JsonObject? {
        if (snapshot == null) return null
        if (!snapshot.available) return buildJsonObject { put("available", false) }
        return buildJsonObject {
            put("available", true)
            put("repo", repoJson(snapshot.repo))
            put("changes", buildJsonArray { snapshot.changes.forEach { add(it) } })
            put("commits", commitsJson(snapshot.commits, nowMillis))
            put("actions", actionsJson(snapshot))
        }
    }

    private fun repoJson(repo: Repo): JsonObject = buildJsonObject {
        put("present", repo.present)
        put("branch", repo.branch?.takeIf { it.isNotBlank() })
        put("head", repo.head?.takeIf { it.isNotBlank() })
        put("root", repo.root?.takeIf { it.isNotBlank() })
    }

    /** `short` and `files` are derived here rather than sent, so the page never recomputes what the host knows. */
    private fun commitsJson(commits: List<GitCommitInfo>, nowMillis: Long) = buildJsonArray {
        commits.forEach { c ->
            addJsonObject {
                put("hash", c.hash)
                put("short", c.shortHash)
                put("subject", c.subject)
                put("author", c.authorName)
                put("ageMillis", (nowMillis - c.authoredAtMillis).coerceAtLeast(0))
                put("files", c.changedPaths.size)
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
                // Lowercase on the wire because that is what the page keys its styling off; `lowercase()` with
                // no locale is locale-INdependent, which a Turkish IDE locale would otherwise turn into "ıde".
                put("kind", action.kind.name.lowercase())
                put("group", action.group)
                put("status", snapshot.actionStates[action.id]?.word)
            }
        }
    }
}
