package dev.lain.claudejb.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Which subagents belong to a **plugin** session, and what the user did with their tabs.
 *
 * **Why this exists at all.** The binary keeps every subagent of a session in one directory
 * (`<sessionId>/subagents/`), and the same session id can be resumed from the terminal — so the directory
 * mixes agents this plugin spawned with agents it never saw. The filesystem cannot tell them apart, and
 * showing all of them would mean reopening a heavy session and getting dozens of tabs for work the plugin
 * never ran. So the plugin writes down what it witnessed: an agent is admitted only if its spawn was seen
 * here, and that record is what survives a restart.
 *
 * It also carries the tab state, which is the other half of the contract: a tab the user **closed stays
 * closed** across restarts. Nothing is destroyed by closing it — the agent's transcript is the binary's file
 * on disk, and the card that spawned it is still in the main transcript, so clicking that card reopens the
 * tab. Closing is a view decision, not a delete.
 *
 * **Stored under `~/.claude`, deliberately NOT in the project's `.idea/`.** The project directory is shared,
 * gets committed by accident and is routinely synced, so anything written there is effectively published —
 * and an agent's identity alone hints at what the user is working on. `~/.claude` is where this
 * conversation's data already lives, is private to the user, and is the source of truth the plugin reads
 * anyway. The file sits in its own namespaced directory so nothing of ours can ever be mistaken for one of
 * the binary's own files: `~/.claude/ide/claude-code-native/agent-index.json`.
 *
 * Even there it records **ids and two booleans** (`AgentIndexPrivacyTest`): titles, prompts and transcripts
 * are read from the binary's files on demand, so duplicating them buys nothing and creates a second copy to
 * leak or go stale.
 *
 * IO is best-effort and tolerant: an unreadable or corrupt file behaves as an empty index rather than
 * throwing, and a failed write costs the tab layout of the next restart, nothing else.
 */
@Service(Service.Level.PROJECT)
class PluginAgentIndex {

    /** One admitted agent. [open] is the tab state; [closedByUser] is what makes a close stick. */
    @Serializable
    data class AgentRecord(
        val agentId: String,
        val open: Boolean = true,
        val closedByUser: Boolean = false,
    )

    private val cache = LinkedHashMap<String, MutableList<AgentRecord>>()
    private var loaded = false

    /**
     * Records that this plugin saw [agentId] spawn in [sessionId]. Idempotent: re-admitting an agent the
     * user had closed does NOT reopen its tab, because a re-admission is just the same agent being seen
     * again, not a new intent from the user.
     */
    @Synchronized
    fun admit(sessionId: String, agentId: String) {
        val list = records(sessionId)
        if (list.none { it.agentId == agentId }) {
            list += AgentRecord(agentId)
            flush()
        }
    }

    /** Whether [agentId] was spawned under a plugin session — the admission gate for [AgentRegistry]. */
    @Synchronized
    fun isAdmitted(sessionId: String, agentId: String): Boolean =
        records(sessionId).any { it.agentId == agentId }

    /** Every agent this plugin has ever admitted for [sessionId], in admission order. */
    @Synchronized
    fun admittedAgents(sessionId: String): List<String> = records(sessionId).map { it.agentId }

    /** Admitted agents of [sessionId] whose tab should be reopened on restore, in admission order. */
    @Synchronized
    fun openAgents(sessionId: String): List<String> =
        records(sessionId).filter { it.open && !it.closedByUser }.map { it.agentId }

    /**
     * The user closed (or reopened) an agent's tab. A close is remembered as **theirs**, so restore leaves
     * it closed; reopening from the transcript card clears that, which is the documented way back.
     */
    @Synchronized
    fun setTabOpen(sessionId: String, agentId: String, open: Boolean) {
        val list = records(sessionId)
        val i = list.indexOfFirst { it.agentId == agentId }
        if (i < 0) {
            list += AgentRecord(agentId, open = open, closedByUser = !open)
        } else {
            list[i] = list[i].copy(open = open, closedByUser = !open)
        }
        flush()
    }

    /** Drops everything known about [sessionId] — used when its chat is closed for good. */
    @Synchronized
    fun forget(sessionId: String) {
        if (load().remove(sessionId) != null) flush()
    }

    private fun records(sessionId: String): MutableList<AgentRecord> =
        load().getOrPut(sessionId) { mutableListOf() }

    private fun load(): LinkedHashMap<String, MutableList<AgentRecord>> {
        if (!loaded) {
            cache.clear()
            val body = indexFile()?.let { f -> runCatching { Files.readString(f) }.getOrNull() }.orEmpty()
            cache.putAll(decode(body).mapValues { it.value.toMutableList() })
            loaded = true
        }
        return cache
    }

    private fun flush() {
        val file = indexFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, encode(cache))
        }
    }

    /** `~/.claude/ide/claude-code-native/agent-index.json`, or null when the JVM reports no home. */
    private fun indexFile(): Path? = homeOverride?.let { Paths.get(it) }
        ?.let { it.resolve(DIR_IDE).resolve(DIR_PLUGIN).resolve(FILE) }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

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

        fun getInstance(project: Project): PluginAgentIndex = project.service()

        /** Serializes the whole index. Pure — unit-testable without a project. */
        fun encode(map: Map<String, List<AgentRecord>>): String =
            runCatching { JSON.encodeToString(map) }.getOrDefault("")

        /** Parses the index back; blank or corrupt input yields an empty map rather than throwing. */
        fun decode(text: String): Map<String, List<AgentRecord>> {
            if (text.isBlank()) return emptyMap()
            return runCatching { JSON.decodeFromString<Map<String, List<AgentRecord>>>(text) }
                .getOrDefault(emptyMap())
        }
    }
}
