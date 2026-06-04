package dev.lain.claudejb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
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
        /** API provider id (see [Provider]): "anthropic" (default, native auth) or a compatible endpoint. */
        @JvmField var provider: String = Provider.DEFAULT.id
        @JvmField var envVars: String = ""
        @JvmField var sourceScript: String = ""
        /** Comma-separated tool names the user chose to "Always allow" (auto-approve without a card). */
        @JvmField var alwaysAllowTools: String = ""
        /** Reopen the chats that were open last time when the tool window starts. */
        @JvmField var restoreOpenChatsOnStartup: Boolean = true

        // --- Advanced launch options (neutral defaults = flag omitted) ------------------------------
        /** `--max-turns N`: cap conversation turns. 0 = no cap (flag omitted). */
        @JvmField var maxTurns: Int = 0
        /** `--max-budget-usd N`: stop the query past this USD budget. 0 = no cap (flag omitted). */
        @JvmField var maxBudgetUsd: Double = 0.0
        /** `--fallback-model M`: model to retry with on overload. Blank = omitted. */
        @JvmField var fallbackModel: String = ""
        /** `--add-dir PATH` (repeatable): extra accessible roots, one path per line. Blank = none. */
        @JvmField var addDirs: String = ""
        /** `--betas a,b`: comma-separated beta feature flags. Blank = omitted. */
        @JvmField var betas: String = ""
        /** `--strict-mcp-config`: only use MCP servers from --mcp-config, ignore other sources. */
        @JvmField var strictMcpConfig: Boolean = false
    }

    val restoreOpenChatsOnStartup: Boolean get() = state.restoreOpenChatsOnStartup

    /**
     * Resolved `claude` binary path. In production this is exactly the persisted [State.claudePath]
     * (blank → auto-detection in [dev.lain.claudejb.process.ClaudeBinaryLocator]). For the RemoteRobot
     * UI-test harness ONLY, when the persisted value is blank and the IDE-under-test was launched with
     * `-Dclaudejb.fakeClaude=<abs path>` (see the `runIdeForUiTests` task in build.gradle.kts), that
     * property is used so the plugin drives the deterministic `bin/fake-claude` stand-in. The property is
     * never set in a shipped IDE, so this is a no-op in production.
     */
    val claudePath: String get() = state.claudePath.ifBlank { System.getProperty(FAKE_CLAUDE_PROP).orEmpty() }
    val nodePath: String get() = state.nodePath
    val sourceScript: String get() = state.sourceScript

    /** Selected API provider (default Anthropic). Decides the `ANTHROPIC_BASE_URL` override at launch. */
    val provider: Provider get() = Provider.fromId(state.provider)

    // --- Provider API keys (third-party providers only) ----------------------------------------------
    // SECURITY: stored in the IDE **password safe** (keychain/credential store), NOT in claude-code.xml —
    // a project-level file that can be committed. This deliberately avoids the plaintext-secret-at-rest
    // smell. Each provider has its OWN isolated credential (keyed by provider id), so switching providers
    // never mixes keys and a stored DeepSeek key survives a round-trip through Anthropic. runCatching keeps
    // pure unit tests (no platform) from throwing; they exercise Provider.launchEnv directly instead.
    private fun providerKeyCredentials(provider: Provider) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", "providerApiKey:${provider.id}"))

    /** The stored API key for [provider] (isolated per provider), or "" when unset/unavailable. */
    fun getProviderApiKey(provider: Provider): String =
        runCatching { PasswordSafe.instance.get(providerKeyCredentials(provider))?.getPasswordAsString().orEmpty() }
            .getOrDefault("")

    /** Persist (or clear, on blank) [provider]'s isolated API key in the IDE password safe. */
    fun setProviderApiKey(provider: Provider, key: String) {
        val trimmed = key.trim()
        runCatching {
            PasswordSafe.instance.set(
                providerKeyCredentials(provider),
                if (trimmed.isEmpty()) null else Credentials(provider.id, trimmed),
            )
        }
    }

    /** Env that routes the binary to the selected provider — empty for Anthropic (native auth). */
    private fun providerEnv(): Map<String, String> = Provider.launchEnv(provider, getProviderApiKey(provider))

    // --- Advanced launch accessors (for ClaudeSession.launchOptions mapping) ---------------------
    /** `--max-turns` value, or null when no cap is set (0). */
    val maxTurns: Int? get() = state.maxTurns.takeIf { it > 0 }
    /** `--max-budget-usd` value, or null when no cap is set (≤ 0). */
    val maxBudgetUsd: Double? get() = state.maxBudgetUsd.takeIf { it > 0.0 }
    /** `--fallback-model` value, or null when blank. */
    val fallbackModel: String? get() = state.fallbackModel.trim().ifBlank { null }
    /** `--add-dir` paths (one per line; trimmed, non-empty). Empty list = no flag. */
    val addDirs: List<String>
        get() = state.addDirs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    /** `--betas` value (trimmed, normalized CSV), or null when blank. */
    val betas: String?
        get() = state.betas.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",").ifBlank { null }
    /** `--strict-mcp-config` toggle. */
    val strictMcpConfig: Boolean get() = state.strictMcpConfig

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
        EnvScriptLoader.load(state.sourceScript) + parseEnv() + fakeFixtureEnv() + providerEnv()

    /**
     * UI-test-only env seeding. When the IDE-under-test is launched with `-Dclaudejb.fakeFixture=<abs path>`
     * (see `runIdeForUiTests`), forward it to the subprocess as `FAKE_FIXTURE` so `bin/fake-claude` replays
     * that JSONL scenario. Explicit `FAKE_FIXTURE` in [State.envVars] still wins (parseEnv is applied after
     * EnvScriptLoader but before this, and a later map entry overrides — so we only set it when absent).
     * Empty in production (property unset).
     */
    private fun fakeFixtureEnv(): Map<String, String> {
        if (parseEnv().containsKey("FAKE_FIXTURE")) return emptyMap()
        val fixture = System.getProperty(FAKE_FIXTURE_PROP).orEmpty()
        return if (fixture.isNotBlank()) mapOf("FAKE_FIXTURE" to fixture) else emptyMap()
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
            ideMcpEnabled = state.ideMcpEnabled,
            ideMcpTransport = state.ideMcpTransport,
            ideMcpPort = state.ideMcpPort,
            customMcpServers = state.customMcpServers,
            maxTurns = maxTurns,
            maxBudgetUsd = maxBudgetUsd,
            fallbackModel = fallbackModel,
            addDirs = addDirs,
            betas = betas,
            strictMcpConfig = strictMcpConfig,
        )
    }

    // --- "Always allow" per tool ----------------------------------------------------------------
    // Remembers tool names the user opted to auto-approve. Keyed by tool name only; path containment
    // for reviewable writes is enforced independently by the broker (isWithinRoot), so a remembered
    // write outside the project root still falls through to a manual card. The [input] param is kept
    // for future-proofing (e.g. per-command/per-path rules) even though it is currently unused.

    private fun alwaysAllowSet(): Set<String> =
        state.alwaysAllowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** True when [toolName] was previously marked "Always allow". */
    @Suppress("UNUSED_PARAMETER")
    fun isToolAlwaysAllowed(toolName: String, input: JsonObject): Boolean =
        toolName.isNotBlank() && toolName in alwaysAllowSet()

    /** Adds [toolName] to the remembered "Always allow" set (idempotent) and persists. */
    fun rememberToolAlwaysAllow(toolName: String) {
        if (toolName.isBlank()) return
        val current = alwaysAllowSet()
        if (toolName in current) return
        state.alwaysAllowTools = (current + toolName).joinToString(",")
    }

    /** The remembered "Always allow" tool names: trimmed, non-empty, de-duplicated, order-stable. */
    fun alwaysAllowedTools(): List<String> =
        state.alwaysAllowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Replaces the remembered "Always allow" set with [tools] (trimmed, non-empty, de-duplicated) and persists. */
    fun setAlwaysAllowedTools(tools: List<String>) {
        state.alwaysAllowTools = tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
    }

    /** Removes [toolName] from the remembered "Always allow" set and persists. */
    fun forgetToolAlwaysAllow(toolName: String) {
        val target = toolName.trim()
        if (target.isEmpty()) return
        state.alwaysAllowTools = alwaysAllowSet().filterNot { it == target }.joinToString(",")
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
        /** UI-test harness hooks (set only by `runIdeForUiTests`; unset in shipped IDEs). */
        private const val FAKE_CLAUDE_PROP = "claudejb.fakeClaude"
        private const val FAKE_FIXTURE_PROP = "claudejb.fakeFixture"
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
