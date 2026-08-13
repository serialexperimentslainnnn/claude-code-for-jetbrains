package dev.lain.claudejb.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonObject

/**
 * Persisted launch defaults for the session. Applied on (re)start; the GUI menus mutate the live
 * session directly, while this stores what to use next time. Extensible to the full settings.json surface.
 *
 * The no-arg constructor exists for the project service and for plain unit tests; [project] is null
 * in tests so the trust-flag helpers degrade gracefully (treat the project as untrusted).
 */
// NB no longer a PersistentStateComponent, and no longer `.idea/claude-code.xml`. The settings are GLOBAL
// (one set for every project) and live in the IDE's PasswordSafe — see SettingsStore for the reasons, the
// one that matters most being that `envVars` was stored in the clear, in a file people commit.
// LegacyProjectSettings reads the old file once so nobody loses their configuration on upgrade.
@Service(Service.Level.PROJECT)
class ClaudeSettings(internal val project: Project? = null) {

    // Serializable because SettingsStore's JSON document IS this class, field for field: an unknown key
    // from a newer version is ignored, a missing key falls back to the property's default.
    @kotlinx.serialization.Serializable
    class State {
        @JvmField var model: String = ClaudeSession.DEFAULT_MODEL

        /**
         * Reasoning effort on a fresh install (or when no configuration could be read): **high**.
         *
         * The pinned model is the top Opus tier, and pairing it with a middling effort is choosing the
         * expensive model and then asking it not to think. A user who wants cheaper answers changes one
         * combo; a user who never opens Settings gets the tier they are paying for.
         */
        @JvmField var effort: String = "high"

        @JvmField var permissionMode: String = "default"

        // Adaptive thinking ON by default (any positive value = on; see ClaudeSession.THINKING_ON). The model
        // decides depth; this just enables the `--thinking adaptive --thinking-display summarized` launch flags.
        @JvmField var thinkingTokens: Int = ClaudeSession.THINKING_ON

        @JvmField var includePartialMessages: Boolean = true

        @JvmField var settingSources: String = "user,project,local"

        @JvmField var allowedTools: String = ""

        @JvmField var disallowedTools: String = ""

        @JvmField var ideMcpEnabled: Boolean = false

        @JvmField var ideMcpTransport: String = "sse"

        @JvmField var ideMcpPort: Int = ClaudeSession.DEFAULT_IDE_MCP_PORT

        @JvmField var customMcpServers: String = ""

        /**
         * The user pressed Log out and has not signed in since.
         *
         * Needed because the plugin will otherwise ride the binary's OWN login when it holds no credential
         * of its own — which is what makes it work on macOS, and what would otherwise make Log out look
         * broken: the safe is cleared, the binary's store is not, and the session simply starts again.
         * Cleared by any successful sign-in.
         */
        @JvmField var signedOut: Boolean = false

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

        /**
         * Flatten the chat's animations (tool-card state fade, row entrance, permission cards).
         *
         * Off by default, and an explicit setting rather than an inherited one — both learned the hard way.
         * The web layer first asked the BROWSER via `@media (prefers-reduced-motion: reduce)`, which reports
         * `true` inside JCEF regardless of the desktop (measured: the query matched while GNOME had animations
         * enabled), so every animation died for everyone. The fix then asked `UISettings.animateWindows`, which
         * is the IDE's TOOL-WINDOW animation toggle, not an accessibility preference — and reproduced the same
         * outcome for anyone who had it off. Neither source was answering the question being asked, so the
         * question is now asked directly, of the only party who knows.
         */
        @JvmField var reduceMotion: Boolean = false

        /** Enable the binary's file checkpointing so the native rewind (rollback to a turn) works. Default on. */
        @JvmField var enableFileCheckpointing: Boolean = true

        /** Remembered fallback choice when native rewind is unavailable: "" = ask, "ide" = revert via IDE, "never" = do nothing. */
        @JvmField var rewindFallback: String = ""

        // --- Sensitive-data guard (see permission/SensitiveGuard.kt) ----------------------------------------

        /**
         * EXTRA sensitive-path globs, one per line — **added** to the built-in blacklist, never replacing it.
         * There is no way to shrink the built-in list itself; the only knob here is making the net wider.
         */
        @JvmField var sensitiveExtraGlobs: String = ""

        /**
         * Per-rule enforcement toggles (Settings ▸ Claude Code ▸ Security), one per [SensitiveGuard.Policy]
         * `enforce*` field — all default **on**, reproducing the original hard-lock behaviour exactly. Turning one
         * off never silently allows a matching call: [SensitiveGuard.verdict] always downgrades a disabled rule's
         * hit to ASK (a card, every time, for every caller) rather than either a silent allow or an unchanged DENY.
         */
        @JvmField var securityBlockCredentials: Boolean = true

        @JvmField var securityBlockDangerousCommands: Boolean = true

        @JvmField var securityBlockForeignOtherUserHome: Boolean = true

        @JvmField var securityBlockForeignNetworkMounts: Boolean = true

        @JvmField var securityBlockForeignWslMounts: Boolean = true

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

    val reduceMotion: Boolean get() = state.reduceMotion
    val enableFileCheckpointing: Boolean get() = state.enableFileCheckpointing

    /**
     * The remembered "don't ask me again" answer for the rewind fallback.
     *
     * Writes through [update], not a bare `state.rewindFallback = value`: this is a *remembered* choice, so a
     * value that does not survive the restart means the dialog the user told us never to show again is shown
     * again. Same failure mode the "Always allow" mutators had.
     */
    var rewindFallback: String
        get() = state.rewindFallback
        set(value) = update { it.rewindFallback = value }

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
    //
    // Reached through SecretStore's read/write pair rather than PasswordSafe directly: these were the only
    // other door onto the safe in the plugin, and a door that skips the test seam is a door a test can leak
    // through. The service name is unchanged, so an already-stored key is found exactly where it was.
    private fun providerKeyName(provider: Provider) = "providerApiKey:${provider.id}"

    private fun providerKeyCredentials(provider: Provider) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", providerKeyName(provider)))

    /** The stored API key for [provider] (isolated per provider), or "" when unset/unavailable. */
    fun getProviderApiKey(provider: Provider): String =
        runCatching { SecretStore.readCredential(providerKeyName(provider), providerKeyCredentials(provider)) }
            .getOrNull().orEmpty()

    /** Persist (or clear, on blank) [provider]'s isolated API key in the IDE password safe. */
    fun setProviderApiKey(provider: Provider, key: String) {
        val trimmed = key.trim()
        runCatching {
            SecretStore.writeCredential(
                providerKeyName(provider),
                providerKeyCredentials(provider),
                trimmed.ifEmpty { null },
            )
        }
    }

    /**
     * The Anthropic API key, in its OWN slot (`providerApiKey:anthropic`) like every other provider's —
     * a DeepSeek key and an Anthropic key are separate entries and can never be mistaken for each other.
     *
     * Unlike a third-party provider this one carries NO base URL: it is an alternative first-party identity,
     * so [Provider.launchEnv] rightly emits nothing for it. The key is applied at launch by
     * [ClaudeSession.effectiveLaunchEnv], which is also where the subscription credential is resolved, so
     * one place decides which identity a session runs as.
     */
    val anthropicApiKey: String get() = getProviderApiKey(Provider.ANTHROPIC)

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

    // NB the launch environment (`parseEnv`/`resolveEnv`) lives in SettingsLaunchEnv.kt, the sensitive-data
    // lock's policy in SettingsSensitivePolicy.kt and the trust-on-open gate in SettingsExecutionTrust.kt —
    // same package, extension functions, so every call site reads exactly as it did. What is left in this
    // file is the persistence document and the operations that must go through `update`/`save`.

    /**
     * The settings, loaded once from the IDE's PasswordSafe (see [SettingsStore]). There is no settings file.
     *
     * **Global, not per project, and written by us.** They used to be a `PersistentStateComponent` in
     * `.idea/claude-code.xml`, which had three problems the move fixes: the platform decided when it reached
     * disk, deleting `.idea` (or a fresh clone) lost them, and `envVars` — where a key or a credentialed
     * proxy URL ends up — sat in plaintext in a file people commit. One model, one permission mode, one set
     * of allowed tools for every project is also what the user asked for.
     */
    private var loaded: State? = null

    val state: State
        @Synchronized get() = loaded ?: run {
            // Migration runs before the first read, so an upgrading user never sees defaults: the old
            // project file is adopted (or dropped, if another project already won) and then removed.
            project?.let { runCatching { LegacyProjectSettings.getInstance(it).migrate(it) } }
            SettingsStore.load().also { loaded = it }
        }

    /**
     * Replaces the in-memory settings without touching disk.
     *
     * For tests, which need a known starting point on a project service the light fixture reuses across
     * methods. It does NOT save, so a test that wants persistence has to ask for it — and should point
     * [PluginAgentIndex.homeOverride] at a temp directory first, for the reason `CredentialsVault` learned
     * the hard way.
     */
    @org.jetbrains.annotations.TestOnly
    @Synchronized
    fun replaceState(s: State) {
        loaded = s
    }

    /**
     * Mutates the settings and persists them, in one call.
     *
     * **This is the only way to change a setting, and that is deliberate.** Nothing saves for us since the
     * settings became the plugin's own file, so a bare `state.x = y` is a change that silently does not
     * survive a restart — and six such sites already existed the moment the persistence changed. Making the
     * mutation and the write one operation removes that failure mode instead of relying on everyone
     * remembering.
     */
    fun update(block: (State) -> Unit) {
        block(state)
        save()
    }

    /** Persists the current settings. Prefer [update]; this is for the settings form, which edits in bulk. */
    fun save() = SettingsStore.save(state)

    // NB no explicit `getState()`: the `state` property already generates one with that exact JVM
    // signature, so declaring both is a platform clash. Callers that used `getState()` keep working —
    // it is the property's own getter.

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

    /** The remembered "Always allow" tool names — see [AlwaysAllowTools], which owns the whole subject. */
    val alwaysAllow = AlwaysAllowTools(this)

    /**
     * True when [toolName] was previously marked "Always allow".
     *
     * [input] is kept for future-proofing (per-command / per-path rules) even though it is unused: the
     * broker's callback signature is the place a narrower rule would arrive.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isToolAlwaysAllowed(toolName: String, input: JsonObject): Boolean = toolName in alwaysAllow

    companion object {
        /** UI-test harness hook (set only by `runIdeForUiTests`; unset in shipped IDEs). */
        private const val FAKE_CLAUDE_PROP = "claudejb.fakeClaude"

        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
