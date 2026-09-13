package dev.lain.claudejb.controller.session

import dev.lain.claudejb.controller.session.control.Asks
import dev.lain.claudejb.controller.session.events.HookBroker
import dev.lain.claudejb.model.protocol.models.AccountInfo
import dev.lain.claudejb.model.protocol.models.AgentInfo
import dev.lain.claudejb.model.protocol.models.InitializeResponse
import dev.lain.claudejb.model.protocol.models.ModelInfo
import dev.lain.claudejb.model.protocol.models.SlashCommand
import dev.lain.claudejb.model.session.launch.SessionLauncher
import dev.lain.claudejb.model.settings.LaunchDefaults
import dev.lain.claudejb.util.thisLogger

class BinaryCatalog(
    private val s: ClaudeSession,
    private val fireMetadata: () -> Unit,
) {

    private val log = thisLogger()

    var commands: List<SlashCommand> = emptyList()
        internal set

    var models: List<ModelInfo> = emptyList()
        private set

    var agents: List<AgentInfo> = emptyList()
        private set

    var availableOutputStyles: List<String> = emptyList()
        private set

    var account: AccountInfo = AccountInfo()
        private set

    @Volatile var outputStyle: String = "default"
        internal set

    @Volatile var initialized: Boolean = false
        internal set

    @Volatile var binaryVersion: String? = null

    fun preferredDefaultModel(): String = LaunchDefaults.preferredDefault(models)

    fun request() {
        val hooks = if (SessionLauncher.rulesBlock(s.launch).isBlank()) {
            emptyMap()
        } else {
            mapOf(HookBroker.USER_PROMPT_SUBMIT to HookBroker.IDE_RULES_CALLBACK)
        }
        s.queries.ask(Asks.initialize(hooks)) { info -> info?.let(::adopt) }
    }

    internal fun adopt(info: InitializeResponse) {
        commands = info.commands
        models = info.models
        agents = info.agents
        availableOutputStyles = info.availableOutputStyles
        account = info.account
        log.debug {
            "initialize reply: account(email=${info.account.email.isNotBlank()}," +
                " org=${info.account.organization.isNotBlank()}, plan='${info.account.subscriptionType}'," +
                " provider='${info.account.apiProvider}') models=${info.models.size}" +
                " commands=${info.commands.size} agents=${info.agents.size}"
        }
        initialized = true
        if (info.outputStyle.isNotBlank()) outputStyle = info.outputStyle
        val pinMissing = info.models.isNotEmpty() && info.models.none { it.value == LaunchDefaults.DEFAULT_MODEL }
        if (s.launch.model == LaunchDefaults.DEFAULT_MODEL && pinMissing) {
            s.settings.changeModel(LaunchDefaults.preferredDefault(info.models), persist = false)
        }
        fireMetadata()
    }
}
