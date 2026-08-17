package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.forge.ForgePullRequest
import dev.lain.claudejb.forge.ForgeRun
import dev.lain.claudejb.git.GitBranchTopology
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitRefInfo
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
 *   commits: [{ hash, short, subject, author, ageMillis, files, parents:[hash] }],
 *   refs:    [{ name, kind, hash, current }],                       // kind: local | remote | head
 *   actions:       [{ id, label, hint, kind, group, status|null }],
 *   commitActions: [{ id, label, hint }]
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
 * Both action lists are [GitActionCatalog]'s — `actions` filtered by the state in the snapshot, `commitActions`
 * the entries that act on one commit. Neither is ever restated here: the page sends an id back and the host
 * looks it up in that same catalogue, so a second list is a button labelled one thing and doing another.
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
        /**
         * Every branch, and the commit it points at — the other half of what a branch graph needs.
         *
         * [commits] carry their parents, which is the SHAPE of the history; these say which of those lines is
         * `main`, which is `origin/main` and which one `HEAD` is standing on. Neither half is derivable from the
         * other, and a graph drawn from only one of them names its lanes by guesswork.
         */
        val refs: List<GitRefInfo> = emptyList(),
        val changedFileOpen: Boolean = false,
        val actionStates: Map<String, ActionState> = emptyMap(),
        /**
         * The branch's place in the graph — where it came from and how far it has drifted — or `NONE` when
         * there is nothing to say (detached, unborn, no upstream, or `git` refused).
         */
        val topology: GitBranchTopology = GitBranchTopology.NONE,
        /**
         * The forge's answer about this branch, when there is one at all.
         *
         * **`null` means draw nothing**, and it is not the same as an empty list. No remote, an unknown host,
         * no token stored, a network that did not answer — all of those are the ordinary state of a plugin
         * nobody has configured for a forge, and a card that appears only to say so is a card asking to be
         * configured for a feature the user has not asked for. An empty list, by contrast, is an answer: this
         * branch has no open pull request, and saying so is worth a row.
         */
        val pullRequests: List<ForgePullRequest>? = null,
        val lastRun: ForgeRun? = null,
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
            put("refs", refsJson(snapshot.refs))
            put("actions", actionsJson(snapshot))
            put("commitActions", commitActionsJson())
            put("topology", topologyJson(snapshot.topology))
            // ABSENT, not null and not empty, when there is nothing to say: the page draws a card only for a
            // key that is there. `null` would be a third state the page has to interpret, and an empty array
            // would be the claim "no open pull requests", which is a different sentence from "we never asked".
            snapshot.pullRequests?.let { put("pullRequests", pullRequestsJson(it)) }
            snapshot.lastRun?.let { put("lastRun", runJson(it)) }
        }
    }

    /**
     * Where the branch sits: what it tracks, how far it has drifted, and where the two last agreed.
     *
     * Every field is nullable and stays that way. `ahead`/`behind` are null when the count could not be read,
     * and rendering that as `0` would say "in sync" — the one answer we do not have. The page omits what is
     * absent rather than printing a zero it was not given.
     */
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
            }
        }
    }

    /** The last CI run, in the page's own status vocabulary — `ForgeRunStatus.wire` is those four words. */
    private fun runJson(run: ForgeRun): JsonObject = buildJsonObject {
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

    /**
     * `short` and `files` are derived here rather than sent, so the page never recomputes what the host knows.
     *
     * `parents` is emitted as the FULL hashes and in commit order — first parent first — because both matter to
     * the drawing: the page joins a commit to a parent by matching hashes, and the first parent is what tells
     * the mainline from the branch that was merged into it. An empty array is a root commit and is a fact; the
     * page treats a payload where NOTHING has parents as "no topology was read" and draws no graph at all,
     * which is why this is always emitted rather than omitted when empty.
     */
    private fun commitsJson(commits: List<GitCommitInfo>, nowMillis: Long) = buildJsonArray {
        commits.forEach { c ->
            addJsonObject {
                put("hash", c.hash)
                put("short", c.shortHash)
                put("subject", c.subject)
                put("author", c.authorName)
                put("ageMillis", (nowMillis - c.authoredAtMillis).coerceAtLeast(0))
                put("files", c.changedPaths.size)
                put("parents", buildJsonArray { c.parents.forEach { add(it) } })
            }
        }
    }

    /**
     * The refs, in the order [dev.lain.claudejb.git.GitGateway.refs] put them: the checked-out one first, then
     * local before remote, then by name.
     *
     * `short` rides along for the same reason it does on a commit — the page shows seven characters and must
     * never be the thing that decides which seven. `current` is a flag rather than a state word because it is
     * not one of [JcefStatus]'s four: "you are standing here" is a different axis from "how did it go", and
     * borrowing that vocabulary would put a `running` chip on a branch.
     */
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

    /**
     * What every commit of the history rail can be asked to do — ONE list, applied to every row.
     *
     * Not filtered by the snapshot, because none of it depends on the working tree: these entries act on a
     * commit that already exists, and the commit is named by the row rather than by the repository's current
     * state. Absent from a payload the page would draw no buttons at all, so it is always emitted once Git is
     * available, even for a repository with no commits yet — the rail is then empty and there are no rows to
     * apply it to.
     *
     * **No `status`, deliberately, and that is the answer to a real problem rather than an omission.** An
     * action's state is keyed by its id ([Snapshot.actionStates]), and this list is drawn once per commit — so
     * a status carried here would paint the SAME pill on every row of the history the moment one commit's
     * button was pressed, which says the wrong thing about every other commit. The alternatives were to widen
     * the key to id+hash or to leave the per-row buttons stateless; stateless wins because the feedback these
     * entries actually produce is elsewhere and is better: a prompted revert becomes a turn in the Git chat
     * with an approval card, and the two host reads (the log jump, the clipboard) are instantaneous and their
     * result is on screen. A pill would be a second, weaker report of something the user is already looking at.
     * The ids are consequently absent from [actionsJson] too — `GitActionCatalog.applicable` never returns a
     * commit-scoped entry — so nothing anywhere draws a state for them.
     */
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
                // Lowercase on the wire because that is what the page keys its styling off; `lowercase()` with
                // no locale is locale-INdependent, which a Turkish IDE locale would otherwise turn into "ıde".
                put("kind", action.kind.name.lowercase())
                put("group", action.group)
                put("status", snapshot.actionStates[action.id]?.word)
            }
        }
    }
}
