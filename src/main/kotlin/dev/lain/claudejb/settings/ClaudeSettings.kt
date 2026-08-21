package dev.lain.claudejb.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import kotlinx.serialization.json.JsonObject

@Service(Service.Level.PROJECT)
class ClaudeSettings(internal val project: Project? = null) {

    @kotlinx.serialization.Serializable
    class State {
        @JvmField var model: String = ClaudeSession.DEFAULT_MODEL

        @JvmField var effort: String = "high"

        @JvmField var permissionMode: String = "default"

        @JvmField var thinkingTokens: Int = ClaudeSession.THINKING_ON

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

        @JvmField var provider: String = Provider.DEFAULT.id

        @JvmField var envVars: String = ""

        @JvmField var sourceScript: String = ""

        @JvmField var alwaysAllowTools: String = ""

        @JvmField var restoreOpenChatsOnStartup: Boolean = true

        @JvmField var reduceMotion: Boolean = false

        @JvmField var workloadWindowMinutes: Int = WorkloadWindow.DEFAULT_MINUTES

        @JvmField var vulnConsent: String = ""

        @JvmField var enableFileCheckpointing: Boolean = true

        @JvmField var rewindFallback: String = ""

        @JvmField var executionTrusted: Boolean = false

        @JvmField var sensitiveExtraGlobs: String = ""

        @JvmField var guardMode: String = GuardMode.DEFAULT.wire

        @JvmField var guardDisabledUntil: Long = 0

        @JvmField var disabledSecurityRules: String = ""

        @JvmField var securityRuleSuspensions: String = ""

        @JvmField var securityExtraBlockedDomains: String = ""

        @JvmField var securityCommandWhitelist: String = ""

        @JvmField var securityCategoryWhitelists: String = ""

        @JvmField var securityRuleWhitelists: String = ""

        @JvmField var securityBlockCredentials: Boolean = true

        @JvmField var securityBlockDangerousCommands: Boolean = true

        @JvmField var securityBlockTempDirs: Boolean = true

        @JvmField var securityBlockForeignOtherUserHome: Boolean = true

        @JvmField var securityBlockForeignNetworkMounts: Boolean = true

        @JvmField var securityBlockForeignWslMounts: Boolean = true

        @JvmField var securityBlockOutsideProject: Boolean = true

        @JvmField var maxTurns: Int = 0

        @JvmField var maxBudgetUsd: Double = 0.0

        @JvmField var fallbackModel: String = ""

        @JvmField var addDirs: String = ""

        @JvmField var betas: String = ""

        @JvmField var strictMcpConfig: Boolean = false
    }

    val restoreOpenChatsOnStartup: Boolean get() = state.restoreOpenChatsOnStartup

    val reduceMotion: Boolean get() = state.reduceMotion

    val workloadWindowMinutes: Int
        get() = state.workloadWindowMinutes.takeIf { it in WorkloadWindow.WINDOW_MINUTES }
            ?: WorkloadWindow.DEFAULT_MINUTES

    val enableFileCheckpointing: Boolean get() = state.enableFileCheckpointing

    var rewindFallback: String
        get() = state.rewindFallback
        set(value) = update { it.rewindFallback = value }

    val claudePath: String get() = state.claudePath.ifBlank { System.getProperty(FAKE_CLAUDE_PROP).orEmpty() }
    val nodePath: String get() = state.nodePath
    val sourceScript: String get() = state.sourceScript

    val provider: Provider get() = Provider.fromId(state.provider)

    private fun providerKeyName(provider: Provider) = "providerApiKey:${provider.id}"

    private fun providerKeyCredentials(provider: Provider) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", providerKeyName(provider)))

    fun getProviderApiKey(provider: Provider): String =
        runCatching { SecretStore.readCredential(providerKeyName(provider), providerKeyCredentials(provider)) }
            .getOrNull().orEmpty()

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

    val anthropicApiKey: String get() = getProviderApiKey(Provider.ANTHROPIC)

    val maxTurns: Int? get() = state.maxTurns.takeIf { it > 0 }

    val maxBudgetUsd: Double? get() = state.maxBudgetUsd.takeIf { it > 0.0 }

    val fallbackModel: String? get() = state.fallbackModel.trim().ifBlank { null }

    val addDirs: List<String>
        get() = state.addDirs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    val betas: String?
        get() = state.betas.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",").ifBlank { null }

    val strictMcpConfig: Boolean get() = state.strictMcpConfig

    val scope: SettingsScope by lazy { SettingsScope.of(project) }

    var signedOut: Boolean
        get() = runCatching { SecretStore.get(SecretStore.SIGNED_OUT) }.getOrNull().toBoolean()
        set(value) = runCatching {
            if (value) SecretStore.set(SecretStore.SIGNED_OUT, true.toString()) else SecretStore.clear(SecretStore.SIGNED_OUT)
        }.getOrDefault(Unit)

    private var loaded: State? = null

    val state: State
        @Synchronized get() = loaded ?: run {
            project?.let { runCatching { LegacyProjectSettings.getInstance(it).migrate(it, scope) } }
            SettingsStore.load(scope).also { loaded = it }
        }

    @org.jetbrains.annotations.TestOnly
    fun replaceState(s: State) = replace(s)

    fun update(block: (State) -> Unit) {
        block(state)
        val target = scope
        writes.execute { SettingsStore.mutate(target, block) }
    }

    fun save() = SettingsStore.save(scope, state)

    fun wipe(): Boolean {
        val cleared = SettingsStore.wipe(scope)
        if (cleared) replace(State())
        return cleared
    }

    fun reload(onReloaded: () -> Unit) {
        val target = scope
        writes.execute {
            val fresh = SettingsStore.loadOrNull(target)
            ApplicationManager.getApplication()?.invokeLater({
                if (fresh != null) replace(fresh)
                onReloaded()
            }, ModalityState.any())
        }
    }

    @Synchronized
    private fun replace(s: State) {
        loaded = s
    }

    fun applyTo(session: ClaudeSession) {
        session.settings.changeModel(state.model.ifBlank { null })
        session.settings.changeEffort(state.effort.ifBlank { null })
        session.settings.changePermissionMode(state.permissionMode.ifBlank { "default" })
        session.settings.changeThinkingTokens(state.thinkingTokens.takeIf { it > 0 })
        session.settings.configureLaunchOptions(
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

    val alwaysAllow = AlwaysAllowTools(this)

    @Suppress("UNUSED_PARAMETER")
    fun isToolAlwaysAllowed(toolName: String, input: JsonObject): Boolean = toolName in alwaysAllow

    companion object {
        private const val FAKE_CLAUDE_PROP = "claudejb.fakeClaude"

        private val writes = AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude Code settings", 1)

        @org.jetbrains.annotations.TestOnly
        fun awaitWrites() {
            writes.submit(Runnable { }).get()
        }

        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
