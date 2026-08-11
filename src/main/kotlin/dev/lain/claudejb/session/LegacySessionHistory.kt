package dev.lain.claudejb.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Reads the open-chat list where it used to live: `workspace.xml`, under the component name
 * `ClaudeCodeSessionHistory`.
 *
 * **This exists only to migrate.** [SessionHistory] moved that list to `~/.claude` in 5.5.0 so the plugin
 * writes it itself instead of waiting for the platform's save cycle. Without reading the old value once,
 * everyone upgrading would lose their open tabs on the first start after the update — the very thing the
 * move was meant to make more reliable.
 *
 * The component name and storage are deliberately identical to the old ones, because that is what makes the
 * platform hand us the XML that is already on disk. Nothing writes through here: migration is one-way, and
 * the old entry is simply left where it is, inert.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSessionHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class LegacySessionHistory : PersistentStateComponent<LegacySessionHistory.State> {

    class State {
        /** The old field: a JSON array of session ids, as one string. */
        @JvmField var openJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    /** The ids recorded by a pre-5.5.0 version, or empty when there are none (a fresh install). */
    fun openSessions(): List<String> = SessionHistory.decodeIds(state.openJson)

    companion object {
        fun getInstance(project: Project): LegacySessionHistory = project.service()
    }
}
