package dev.lain.claudejb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.lain.claudejb.session.ClaudeSession

/**
 * Persisted launch defaults for the session. Applied on (re)start; the GUI menus mutate the live
 * session directly, while this stores what to use next time. Extensible to the full settings.json surface.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSettings", storages = [Storage("claude-code.xml")])
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    class State {
        @JvmField var model: String = "opusplan"
        @JvmField var effort: String = ""
        @JvmField var permissionMode: String = "default"
        @JvmField var thinkingTokens: Int = 0
        @JvmField var includePartialMessages: Boolean = true
        @JvmField var settingSources: String = "user,project,local"
        @JvmField var allowedTools: String = ""
        @JvmField var disallowedTools: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    /** Seeds the session's launch options from persisted defaults (call before start()). */
    fun applyTo(session: ClaudeSession) {
        session.changeModel(state.model.ifBlank { null })
        session.changeEffort(state.effort.ifBlank { null })
        session.changePermissionMode(state.permissionMode.ifBlank { "default" })
        session.changeThinkingTokens(state.thinkingTokens.takeIf { it > 0 })
        session.configureLaunchOptions(
            allowedTools = state.allowedTools,
            disallowedTools = state.disallowedTools,
            settingSources = state.settingSources,
            includePartialMessages = state.includePartialMessages,
        )
    }

    companion object {
        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
