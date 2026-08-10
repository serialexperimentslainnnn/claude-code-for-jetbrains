package dev.lain.claudejb.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
 * Stored in `workspace.xml` next to [SessionHistory] — same reasoning: this is per-user UI state, not
 * something to commit.
 *
 * **Ids and two booleans, nothing else, and that is a hard invariant** (`AgentIndexPrivacyTest`). No prompt,
 * no description, no transcript and no tool output ever goes into `.idea/`: those live in the binary's own
 * files under `~/.claude`, which is the single source of truth the whole plugin already relies on. The
 * project directory is shared, sometimes committed by accident and routinely synced, so anything written
 * there is effectively published — an agent's description alone can leak what the user is working on.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeAgentIndex", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PluginAgentIndex : PersistentStateComponent<PluginAgentIndex.State> {

    class State {
        /** `sessionId -> [AgentRecord]`, as one JSON string (same shape trick [SessionHistory] uses). */
        @JvmField var agentsJson: String = ""
    }

    /** One admitted agent. [open] is the tab state; [closedByUser] is what makes a close stick. */
    @Serializable
    data class AgentRecord(
        val agentId: String,
        val open: Boolean = true,
        val closedByUser: Boolean = false,
    )

    private var state = State()
    private val cache = LinkedHashMap<String, MutableList<AgentRecord>>()
    private var loaded = false

    override fun getState(): State = state
    override fun loadState(s: State) {
        XmlSerializerUtil.copyBean(s, state)
        loaded = false
    }

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
            cache.putAll(decode(state.agentsJson).mapValues { it.value.toMutableList() })
            loaded = true
        }
        return cache
    }

    private fun flush() {
        state.agentsJson = encode(cache)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

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
