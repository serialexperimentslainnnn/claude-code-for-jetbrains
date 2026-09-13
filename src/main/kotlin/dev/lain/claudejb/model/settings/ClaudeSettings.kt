package dev.lain.claudejb.model.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.model.settings.guard.AlwaysAllowTools
import dev.lain.claudejb.model.settings.guard.GuardMode
import dev.lain.claudejb.model.settings.legacy.LegacyProjectSettings

@Service(Service.Level.PROJECT)
class ClaudeSettings(internal val project: Project? = null) {

    @kotlinx.serialization.Serializable
    class State {
        @JvmField var model: String = LaunchDefaults.DEFAULT_MODEL

        @JvmField var effort: String = "high"

        @JvmField var permissionMode: String = "default"

        @JvmField var thinkingTokens: Int = LaunchDefaults.THINKING_ON

        @JvmField var includePartialMessages: Boolean = true

        @JvmField var settingSources: String = "user,project,local"

        @JvmField var allowedTools: String = ""

        @JvmField var disallowedTools: String = ""

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

        @JvmField var guardLogRetentionDays: Int = DEFAULT_GUARD_LOG_RETENTION_DAYS

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

        @JvmField var ideMcp: IdeMcpState = IdeMcpState()
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

    fun getProviderApiKey(provider: Provider): String = ProviderApiKeys.get(provider)

    fun setProviderApiKey(provider: Provider, key: String) = ProviderApiKeys.set(provider, key)

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

    val alwaysAllow = AlwaysAllowTools(this)

    fun isToolAlwaysAllowed(toolName: String): Boolean = toolName in alwaysAllow

    fun warm() {
        state
    }

    companion object {
        const val DEFAULT_GUARD_LOG_RETENTION_DAYS = 30

        private const val FAKE_CLAUDE_PROP = "claudejb.fakeClaude"

        private val writes = AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude Code settings", 1)

        @org.jetbrains.annotations.TestOnly
        fun awaitWrites() {
            writes.submit(Runnable { }).get()
        }

        fun getInstance(project: Project): ClaudeSettings = project.service()
    }
}
