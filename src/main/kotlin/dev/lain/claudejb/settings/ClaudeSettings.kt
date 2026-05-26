package dev.lain.claudejb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.lain.claudejb.process.EnvScriptLoader
import dev.lain.claudejb.session.ClaudeSession

/**
 * Persisted launch defaults for the session. Applied on (re)start; the GUI menus mutate the live
 * session directly, while this stores what to use next time. Extensible to the full settings.json surface.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSettings", storages = [Storage("claude-code.xml")])
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    class State {
        @JvmField var model: String = ClaudeSession.DEFAULT_MODEL
        @JvmField var effort: String = "medium"
        @JvmField var permissionMode: String = "default"
        @JvmField var thinkingTokens: Int = 0
        @JvmField var includePartialMessages: Boolean = true
        @JvmField var settingSources: String = "user,project,local"
        @JvmField var allowedTools: String = ""
        @JvmField var disallowedTools: String = ""
        @JvmField var claudePath: String = ""
        @JvmField var nodePath: String = ""
        @JvmField var envVars: String = ""
        @JvmField var sourceScript: String = ""
    }

    val claudePath: String get() = state.claudePath
    val nodePath: String get() = state.nodePath
    val sourceScript: String get() = state.sourceScript

    /** Parses the `KEY=VALUE` lines (one per line) into an env map; blank/`#`-comment lines ignored. */
    fun parseEnv(): Map<String, String> =
        state.envVars.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }
            .filterKeys { it.isNotEmpty() }

    /** Effective process env: the sourced script's environment first, then explicit overrides on top. */
    fun resolveEnv(): Map<String, String> =
        EnvScriptLoader.load(state.sourceScript) + parseEnv()

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
