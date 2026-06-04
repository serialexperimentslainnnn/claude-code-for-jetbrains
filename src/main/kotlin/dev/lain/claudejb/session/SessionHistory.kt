package dev.lain.claudejb.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure UI state: the ordered list of session ids of the tabs that were open, so they can be reopened on next
 * startup. No transcripts are persisted — those are reconstructed from the `claude` binary's own session files.
 * Stored in `workspace.xml` (not committed by convention), as a single JSON string field.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSessionHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SessionHistory : PersistentStateComponent<SessionHistory.State> {

    class State {
        /** Session ids of the tabs that were open, in tab order, as a JSON array string. */
        @JvmField var openJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    /** Records the currently-open tabs' session ids (in tab order) so they can be reopened on next startup. */
    @Synchronized
    fun setOpenSessions(ids: List<String>) {
        state.openJson = encodeIds(ids.filter { it.isNotBlank() })
    }

    /** Session ids of the tabs open at last save, in the stored order. */
    @Synchronized
    fun openSessions(): List<String> = decodeIds(state.openJson)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun getInstance(project: Project): SessionHistory = project.service()

        /** Serializes the open-session id list to a JSON string. Pure — unit-testable. */
        fun encodeIds(list: List<String>): String =
            runCatching { JSON.encodeToString(list) }.getOrDefault("")

        /** Parses a JSON string back to an id list; tolerates blank/corrupt input (→ empty, never throws). */
        fun decodeIds(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return runCatching { JSON.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
        }
    }
}
