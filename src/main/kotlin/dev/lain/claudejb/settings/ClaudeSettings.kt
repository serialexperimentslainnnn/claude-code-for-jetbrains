package dev.lain.claudejb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.lain.claudejb.process.EnvScriptLoader
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Persisted launch defaults for the session. Applied on (re)start; the GUI menus mutate the live
 * session directly, while this stores what to use next time. Extensible to the full settings.json surface.
 *
 * The no-arg constructor exists for the project service and for plain unit tests; [project] is null
 * in tests so the trust-flag helpers degrade gracefully (treat the project as untrusted).
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSettings", storages = [Storage("claude-code.xml")])
class ClaudeSettings(private val project: Project? = null) : PersistentStateComponent<ClaudeSettings.State> {

    class State {
        @JvmField var model: String = ClaudeSession.DEFAULT_MODEL
        @JvmField var effort: String = "medium"
        @JvmField var permissionMode: String = "default"
        @JvmField var thinkingTokens: Int = 0
        @JvmField var includePartialMessages: Boolean = true
        @JvmField var settingSources: String = "user,project,local"
        @JvmField var allowedTools: String = ""
        @JvmField var disallowedTools: String = ""
        @JvmField var ideMcpEnabled: Boolean = false
        @JvmField var ideMcpTransport: String = "sse"
        @JvmField var ideMcpPort: Int = ClaudeSession.DEFAULT_IDE_MCP_PORT
        @JvmField var customMcpServers: String = ""
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

    /**
     * Effective process env: the sourced script's environment first, then explicit overrides on top.
     *
     * SECURITY (trust-on-open): `claude-code.xml` is project-level and may be versioned in the repo, so a
     * malicious project could ship a [State.sourceScript] that runs at session start. This method does NOT
     * gate execution itself (it may be called off-EDT); callers that start a session from project-persisted
     * settings should first consult [requiresTrustPrompt] (i.e. [hasRiskyExecConfig] + [isExecutionTrusted])
     * and obtain user consent before running. The current start flow is intentionally left unchanged here.
     */
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
            ideMcpEnabled = state.ideMcpEnabled,
            ideMcpTransport = state.ideMcpTransport,
            ideMcpPort = state.ideMcpPort,
            customMcpServers = state.customMcpServers,
        )
    }

    // --- Trust gate (trust-on-open) -------------------------------------------------------------
    // Lightweight, non-blocking consent flag for potentially dangerous execution coming from
    // project-persisted settings (sourceScript / custom stdio MCP servers). These helpers only read
    // and store the flag and classify the config; they NEVER show dialogs. The "ask the user" wiring
    // lives elsewhere (e.g. ClaudeSession / a startup activity), which should call requiresTrustPrompt().

    /** Per-project flag: the user has explicitly trusted this project to run sourceScript / custom MCP. */
    fun isExecutionTrusted(): Boolean =
        project?.let { PropertiesComponent.getInstance(it).getBoolean(TRUST_KEY, false) } ?: false

    /** Persists the per-project trust flag. No-op without a project (unit tests). */
    fun setExecutionTrusted(trusted: Boolean) {
        project?.let { PropertiesComponent.getInstance(it).setValue(TRUST_KEY, trusted) }
    }

    /**
     * True when the persisted settings carry execution risk beyond what the UI already validates:
     * a non-blank [State.sourceScript], or a custom MCP server of `stdio` type with a `command`.
     * The custom-server JSON is parsed leniently; if it does not parse, it is treated as adding no
     * extra risk here (the settings UI validates that JSON on save).
     */
    fun hasRiskyExecConfig(): Boolean =
        state.sourceScript.isNotBlank() || customMcpServersHaveStdioCommand()

    /** Convenience: there is risky config and the user has not (yet) trusted it. */
    fun requiresTrustPrompt(): Boolean = hasRiskyExecConfig() && !isExecutionTrusted()

    /** Lenient scan of the custom MCP servers JSON for any `stdio` server carrying a `command`. */
    private fun customMcpServersHaveStdioCommand(): Boolean {
        val raw = state.customMcpServers.trim()
        if (raw.isEmpty()) return false
        val root = runCatching { LENIENT_JSON.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        return root.values.any { server ->
            val obj = server as? JsonObject ?: return@any false
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val command = obj["command"]?.jsonPrimitive?.contentOrNull
            // stdio is the default transport when unspecified; flag it whenever a command is present.
            !command.isNullOrBlank() && (type == null || type.equals("stdio", ignoreCase = true))
        }
    }

    companion object {
        private const val TRUST_KEY = "claudejb.trustedExecOnOpen"
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
