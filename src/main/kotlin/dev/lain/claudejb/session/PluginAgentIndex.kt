package dev.lain.claudejb.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What belongs to a **plugin** session: every agent, subagent and background task it started, each with its
 * parent and its children, plus what the user did with its tab.
 *
 * **Why it exists.** The binary keeps every subagent of a session in one directory
 * (`<sessionId>/subagents/`), and the same session id can be resumed from the terminal — so that directory
 * mixes agents this plugin spawned with agents it never saw (84, in one real session). The filesystem cannot
 * tell them apart. This file is the plugin's own record of what it witnessed, and it is what survives a
 * restart. Background tasks have no sidecar at all, so for them it is the ONLY record there is.
 *
 * **The shape is the point.** It held one id per agent, on the reasoning that the binary's sidecars carry the
 * parent and the type anyway. True, and they still are where the CONTENT comes from — but a record that
 * cannot be read on its own cannot be checked, cannot be debugged from the file, and says nothing about a
 * task. So each node now states what it is, what it hangs off, and what hangs off it:
 *
 * ```json
 * { "type": "subagent", "id": "a2f…", "parent": { "type": "agent", "id": "a1c…" },
 *   "childs": [ { "type": "backgroundtask", "id": "b0k…" } ] }
 * ```
 *
 * `childs` is DERIVED from the parents when the file is written, so the two can never disagree: it is there
 * to be read, not to be maintained.
 *
 * **What it deliberately does NOT carry** (`AgentIndexPrivacyTest`): descriptions, prompts, transcripts,
 * command text. An agent's description ("Translate the SAP standards") already says what the user is working
 * on. Those live in the binary's own files and are read on demand, so a copy here would buy nothing and
 * create a second thing to leak or to go stale.
 *
 * **Stored under `~/.claude`, deliberately NOT in the project's `.idea/`.** That directory is shared, gets
 * committed by accident and is routinely synced, so anything written there is effectively published.
 * `~/.claude` is private to the user and is where this data already lives. The file sits in its own
 * namespaced directory: `~/.claude/ide/claude-code-native/agent-index.json`.
 *
 * IO is best-effort and tolerant: an unreadable or corrupt file behaves as an empty index rather than
 * throwing, and a failed write costs the tab layout of the next restart, nothing else.
 */
@Service(Service.Level.PROJECT)
class PluginAgentIndex {

    private val log = logger<PluginAgentIndex>()

    /** What a node is. The chat is only ever a PARENT — it is the session itself, not a row in the list. */
    object Kind {
        const val AGENT = "agent"
        const val SUBAGENT = "subagent"
        const val TASK = "backgroundtask"
        const val CHAT = "chat"
    }

    /** A reference to another node: what it is and which one. */
    @Serializable
    data class Ref(val type: String, val id: String)

    /**
     * One node of the session's tree.
     *
     * [open]/[closedByUser] are the tab state — a close is remembered as the user's, so a restore leaves it
     * closed. [agentType] is the registered agent type (`general-purpose`, a custom agent) and [toolUseId]
     * is, for a task, the call that launched it: the card to jump back to and the join key against the
     * binary's transcript when the task's output is replayed ([BackgroundTaskReplay]).
     */
    @Serializable
    data class Node(
        val type: String,
        val id: String,
        val parent: Ref? = null,
        val childs: List<Ref> = emptyList(),
        val agentType: String? = null,
        val toolUseId: String? = null,
        val open: Boolean = true,
        val closedByUser: Boolean = false,
    )

    @Serializable
    data class SessionRecord(val nodes: List<Node> = emptyList())

    /** The file's whole contents. [version] is what lets a future shape change be a migration, not a loss. */
    @Serializable
    data class Index(
        val version: Int = FORMAT_VERSION,
        val sessions: Map<String, SessionRecord> = emptyMap(),
    )

    private val cache = LinkedHashMap<String, SessionRecord>()
    private var loaded = false

    // ── agents ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Records that this plugin saw [node] spawn in [sessionId], or updates its shape if it has changed.
     *
     * Idempotent, and re-admitting an agent the user had closed does NOT reopen its tab: a re-admission is
     * the same agent being seen again, not a new intent from the user.
     */
    @Synchronized
    fun admit(sessionId: String, node: AgentNode) {
        val id = AgentMeta.bareAgentId(node.agentId)
        val parentId = node.parentAgentId?.let { AgentMeta.bareAgentId(it) }
        upsert(
            sessionId,
            id,
        ) { existing ->
            Node(
                // An agent of the chat's own turn is an `agent`; one spawned inside another is a `subagent`.
                type = if (parentId == null) Kind.AGENT else Kind.SUBAGENT,
                id = id,
                parent = parentId?.let { Ref(parentTypeOf(sessionId, it), it) } ?: Ref(Kind.CHAT, sessionId),
                agentType = node.meta.agentType,
                open = existing?.open ?: true,
                closedByUser = existing?.closedByUser ?: false,
            )
        }
    }

    /** Whether [agentId] was spawned under a plugin session — the admission gate for [AgentRegistry]. */
    @Synchronized
    fun isAdmitted(sessionId: String, agentId: String): Boolean =
        agents(sessionId).any { it.id == AgentMeta.bareAgentId(agentId) }

    /** Every agent this plugin has ever admitted for [sessionId], in admission order. */
    @Synchronized
    fun admittedAgents(sessionId: String): List<String> = agents(sessionId).map { it.id }

    /** Admitted agents of [sessionId] whose tab should be reopened on restore, in admission order. */
    @Synchronized
    fun openAgents(sessionId: String): List<String> =
        agents(sessionId).filter { it.open && !it.closedByUser }.map { it.id }

    /** The whole recorded tree of [sessionId] — agents, subagents and tasks, with parents and children. */
    @Synchronized
    fun nodes(sessionId: String): List<Node> = session(sessionId).nodes

    /**
     * The user closed (or reopened) an agent's tab. A close is remembered as **theirs**, so restore leaves it
     * closed; reopening from the transcript card clears that, which is the documented way back.
     */
    @Synchronized
    fun setTabOpen(sessionId: String, agentId: String, open: Boolean) {
        val id = AgentMeta.bareAgentId(agentId)
        upsert(sessionId, id) { existing ->
            (existing ?: Node(type = Kind.AGENT, id = id, parent = Ref(Kind.CHAT, sessionId)))
                .copy(open = open, closedByUser = !open)
        }
    }

    // ── background tasks ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Records a background task of [sessionId], or fills in its owner once that becomes known.
     *
     * Tasks are dropped by the binary's level signal the moment they end, so this is the only place the
     * plugin can say "this task was mine, and this agent ran it". Their OUTPUT is not copied here: it is
     * replayed from the binary's transcript ([BackgroundTaskReplay]).
     */
    @Synchronized
    fun recordTask(sessionId: String, taskId: String, toolUseId: String?, ownerAgentId: String?) {
        val owner = ownerAgentId?.let { AgentMeta.bareAgentId(it) }
        upsert(sessionId, taskId) { existing ->
            Node(
                type = Kind.TASK,
                id = taskId,
                parent = owner?.let { Ref(parentTypeOf(sessionId, it), it) }
                    ?: existing?.parent
                    ?: Ref(Kind.CHAT, sessionId),
                toolUseId = toolUseId ?: existing?.toolUseId,
                open = existing?.open ?: true,
                closedByUser = existing?.closedByUser ?: false,
            )
        }
    }

    /** Every background task recorded for [sessionId], in the order they were first seen. */
    @Synchronized
    fun taskIds(sessionId: String): List<String> =
        session(sessionId).nodes.filter { it.type == Kind.TASK }.map { it.id }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────────────

    /** Drops everything known about [sessionId] — used when its chat is closed for good. */
    @Synchronized
    fun forget(sessionId: String) {
        if (load().remove(sessionId) != null) flush()
    }

    private fun agents(sessionId: String): List<Node> =
        session(sessionId).nodes.filter { it.type == Kind.AGENT || it.type == Kind.SUBAGENT }

    /** An agent's own kind, so a child can name its parent correctly without re-deriving the tree. */
    private fun parentTypeOf(sessionId: String, parentId: String): String =
        session(sessionId).nodes.firstOrNull { it.id == parentId }?.type ?: Kind.AGENT

    private fun upsert(sessionId: String, id: String, build: (Node?) -> Node) {
        val session = session(sessionId)
        val nodes = session.nodes.toMutableList()
        val i = nodes.indexOfFirst { it.id == id }
        val next = build(nodes.getOrNull(i))
        if (i >= 0 && nodes[i] == next) return
        if (i >= 0) nodes[i] = next else nodes += next
        cache[sessionId] = SessionRecord(nodes)
        flush()
    }

    private fun session(sessionId: String): SessionRecord =
        load().getOrPut(sessionId) { SessionRecord() }

    private fun load(): LinkedHashMap<String, SessionRecord> {
        if (!loaded) {
            cache.clear()
            val body = indexFile()?.let { f -> runCatching { Files.readString(f) }.getOrNull() }.orEmpty()
            cache.putAll(decode(body))
            loaded = true
            // Rewrite once whenever the file was not already in the current shape — a legacy `agent-<id>` id,
            // or an older layout. The migration is then paid on the first read and never again, and the file
            // on disk stops disagreeing with what is compared against it.
            if (body.isNotBlank() && !body.contains("\"version\":$FORMAT_VERSION")) flush()
        }
        return cache
    }

    private fun flush() {
        val file = indexFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, encode(cache))
        }.onFailure {
            // Best-effort by design — a failed write costs the next restart's tab layout and nothing else —
            // but it is logged, because "my agent tabs come back sometimes" is otherwise unexplainable.
            log.warn("could not persist the agent index to ${file.parent}", it)
        }
    }

    /** `~/.claude/ide/claude-code-native/agent-index.json`, or null when there is no home to write into. */
    private fun indexFile(): Path? = homeDir()?.let { Paths.get(it) }
        ?.let { it.resolve(DIR_IDE).resolve(DIR_PLUGIN).resolve(FILE) }

    companion object {
        // encodeDefaults ON: the file is meant to be READ — by a person debugging a restore, and by the
        // version check below. A record that omits every default is a record where "not set" and "false"
        // look identical, and where `version` disappears the moment it equals the current one.
        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        /**
         * Bumped when the on-disk shape changes.
         *
         * v1 was `{sessionId: [{agentId, open, closedByUser}]}` — ids and two flags, with the tree implicit in
         * the binary's sidecars and background tasks not recorded at all. v3 is a list of typed nodes, each
         * naming its parent and its children. A v1 file is migrated rather than discarded: the ids in it are
         * what admits those agents, and the rest fills itself in on the first scan.
         */
        const val FORMAT_VERSION = 3

        private const val DIR_IDE = "ide"
        private const val DIR_PLUGIN = "claude-code-native"
        private const val FILE = "agent-index.json"

        /**
         * The `~/.claude` directory to write into. Overridable **for tests only**, following the same rule
         * `CredentialsVault.homeOverride` established: a test JVM must never write into the developer's real
         * home, which is how an earlier test run harvested and deleted live credentials.
         */
        @Volatile
        var homeOverride: String? = defaultHome()

        private fun defaultHome(): String? =
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { "$it/.claude" }

        /**
         * The directory to actually use — null in a test JVM that has not pointed [homeOverride] somewhere
         * of its own.
         *
         * **This is not belt-and-braces, it is a defect that shipped.** Everything under `~/.claude/ide/` is
         * written through here: the agent index, the open-chat list and — since the settings moved out of
         * `.idea` — the user's whole configuration. A headless test that constructs the settings page and
         * calls `apply()` therefore wrote to the DEVELOPER'S OWN home, and it did exactly that: a real
         * `settings.json` on this machine was found holding `some-unlisted-model` and, before it, `haiku` —
         * both of them string literals from test cases, landing in a live configuration and looking for all
         * the world like the plugin corrupting itself on reinstall. The correlation was the give-away: the
         * config "broke on reinstall" because the reinstall followed a `./gradlew test`.
         *
         * `CredentialsVault.inertHere()` learned this same lesson about credentials; this is the same rule
         * for the same reason, one directory up. A test that WANTS to exercise persistence still can — it
         * just has to say where, and every one of them already does.
         */
        internal fun homeDir(): String? {
            homeOverride?.let { if (it != defaultHome()) return it }
            // A null Application is a plain-JVM unit test, which is no place to be writing into a home
            // either — the same reading `CredentialsVault.inertHere()` makes.
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            return if (app == null || app.isUnitTestMode) null else homeOverride
        }

        fun getInstance(project: Project): PluginAgentIndex = project.service()

        /**
         * Serializes the whole index, DERIVING each node's `childs` from the parents.
         *
         * Derived rather than stored-and-updated so the two halves of the tree cannot drift: a child list
         * maintained by hand is a second source of truth, and the first bug it produces is a node that
         * claims a child which no longer exists.
         */
        fun encode(sessions: Map<String, SessionRecord>): String {
            val withChildren = sessions.mapValues { (_, rec) ->
                SessionRecord(
                    rec.nodes.map { node ->
                        node.copy(
                            childs = rec.nodes
                                .filter { it.parent?.id == node.id }
                                .map { Ref(it.type, it.id) },
                        )
                    },
                )
            }
            return runCatching { JSON.encodeToString(Index(FORMAT_VERSION, withChildren)) }.getOrDefault("")
        }

        /**
         * Parses the index back, accepting the current shape and the v1 one.
         *
         * A v1 file (a bare `{sessionId: [{agentId,…}]}` map) becomes agents hanging off the chat — which is
         * exactly what it knew — and is rewritten in the current shape on the first save. Blank or corrupt
         * input yields an empty index rather than throwing.
         */
        fun decode(text: String): LinkedHashMap<String, SessionRecord> {
            val out = LinkedHashMap<String, SessionRecord>()
            if (text.isBlank()) return out
            runCatching { JSON.decodeFromString<Index>(text) }.getOrNull()?.let { index ->
                if (index.sessions.isNotEmpty()) {
                    index.sessions.forEach { (id, rec) -> out[id] = rec.normalised(id) }
                    return out
                }
            }
            runCatching { JSON.decodeFromString<Map<String, List<LegacyRecord>>>(text) }.getOrNull()
                ?.forEach { (id, legacy) ->
                    out[id] = SessionRecord(
                        legacy.map {
                            Node(
                                type = Kind.AGENT,
                                id = AgentMeta.bareAgentId(it.agentId),
                                parent = Ref(Kind.CHAT, id),
                                open = it.open,
                                closedByUser = it.closedByUser,
                            )
                        },
                    ).normalised(id)
                }
            return out
        }

        /** The v1 record, kept only so a file written by 5.5.0's first builds can still be read. */
        @Serializable
        private data class LegacyRecord(
            val agentId: String,
            val open: Boolean = true,
            val closedByUser: Boolean = false,
        )

        /**
         * Canonical ids, no duplicates, every parent resolvable.
         *
         * The prefixed shape (`agent-<id>`) outlived the code that produced it: 5.5.0's first builds took the
         * file name as the identity, and once the identity became the bare id — the shape the sidecars' own
         * `parentAgentId` uses — those records matched nothing. A restored chat had its agents on disk, in
         * the index, and not one tab. The FIRST record of a duplicate wins: it carries the user's decision
         * about that tab.
         */
        private fun SessionRecord.normalised(sessionId: String): SessionRecord {
            val seen = LinkedHashMap<String, Node>()
            nodes.forEach { n ->
                val id = if (n.type == Kind.TASK) n.id else AgentMeta.bareAgentId(n.id)
                val parent = n.parent?.let {
                    if (it.type == Kind.CHAT) Ref(Kind.CHAT, sessionId) else Ref(it.type, AgentMeta.bareAgentId(it.id))
                }
                seen.putIfAbsent(id, n.copy(id = id, parent = parent ?: Ref(Kind.CHAT, sessionId)))
            }
            return SessionRecord(seen.values.toList())
        }
    }
}
