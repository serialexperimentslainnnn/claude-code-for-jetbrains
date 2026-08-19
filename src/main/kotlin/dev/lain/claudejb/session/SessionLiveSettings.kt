package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable

/**
 * Changing a LIVE session's options: model, permission mode, effort, provider, extended thinking, and the
 * launch flags. Reached as `session.settings.changeModel(…)`.
 *
 * **Why these seven are one subject.** Each answers the same question in a different place — *what happens
 * when the user changes this while a session is already running* — and the three possible answers are the
 * whole content of this file: it is a control request the binary may refuse (model), a control request it
 * cannot refuse (permission mode), or a launch flag that needs a restart to take effect (effort, thinking,
 * provider, the rest). Grouping them anywhere else spreads that one distinction across a 2,600-line class.
 *
 * **What it holds and what it borrows.** The state itself stays on [ClaudeSession] — the UI reads those
 * properties directly and they are `@Volatile` for that reason — so this writes them there rather than owning
 * a second copy that would have to be kept in step. What it takes as constructor arguments is exactly the
 * three things it cannot reach: dispatching to the EDT, telling the listeners, and writing a line to the
 * binary. They arrive as function references because they are private to the session, which is the same shape
 * [LoginCoordinator], [SessionQueries] and [PollSchedule] already use here.
 */
class SessionLiveSettings(
    private val session: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val write: (String) -> Unit,
) {

    fun changeModel(value: String?) {
        // "default" is no longer a selectable model (the UI pins a concrete tier). Map any legacy/persisted
        // "default" to the preferred concrete model so both the display and what's sent to the binary agree;
        // null stays null (unset — the Init handler fills it from the binary's reported model).
        val resolved = if (value == ClaudeSession.RECOMMENDED_ALIAS) session.preferredDefaultModel() else value
        val previous = session.model
        session.model = resolved
        if (session.isRunning()) {
            // Correlated, not fire-and-forget, because "Other models" can offer a model this ACCOUNT cannot
            // run (the list is curated from ids the binary knows — it cannot know what the plan grants). A
            // refusal that only changed the pill would leave the tab pointed at a model every later turn
            // fails on, with nothing saying why.
            session.controlClient.send({ id -> ControlProtocol.setModelRequest(id, resolved) }) { res ->
                if (!res.success) edt { revertModel(previous, resolved, res.error) }
            }
        }
        fireState()
    }

    /**
     * Puts the previously selected model back after the binary refused a change, and says so in the transcript.
     *
     * EDT-only (it writes session state the UI reads). Silent when a newer selection has raced ahead of this
     * reply — reverting then would undo a choice the user made after the failure.
     *
     * Scope, stated because it is narrower than it looks: this catches a refusal of the `set_model` control
     * request itself. A binary that ACCEPTS the id and only fails later, when the turn reaches the API, is a
     * different signal and arrives as an ordinary turn error.
     */
    private fun revertModel(previous: String?, attempted: String?, error: String?) {
        if (session.model != attempted) return
        session.model = previous
        // Labelled from the curated list, NOT from the UI layer: naming a model is not a rendering decision,
        // and reaching into `ui.jcef` from here would invert the dependency this package deliberately keeps.
        val name = attempted?.let { LegacyModels.labelFor(it) ?: it } ?: "That model"
        val kept = previous?.let { LegacyModels.labelFor(it) ?: it } ?: "the previous model"
        val reason = error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        session.transcript.add(Speaker.SYSTEM, "$name is not available on this account$reason — kept $kept.")
        fireState()
    }

    fun changePermissionMode(mode: String) {
        session.permissionMode = mode
        // Persist so new tabs / restarts launch in this mode instead of falling back to "default".
        // `save()` is explicit since 5.5.0: the settings are the plugin's own file now, so nothing writes
        // them for us and a mutation without it is a setting that silently does not stick.
        ClaudeSettings.getInstance(project).update { it.permissionMode = mode }
        if (session.isRunning()) {
            val wire = SessionLauncher.binaryPermissionMode(session.permissionMode)
            write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), wire))
        }
        fireState()
    }

    /** Effort is a launch flag; it takes effect on the next (re)start. */
    fun changeEffort(value: String?) {
        session.effort = value
        fireState()
    }

    /**
     * Switch the API provider. The provider's `ANTHROPIC_BASE_URL`/`ANTHROPIC_API_KEY` are launch env, so the
     * change requires a restart (we invalidate the cached env and resume via `--resume`).
     *
     * SECURITY: a third-party provider needs its OWN isolated key. If none is stored we do NOT switch and do
     * NOT restart — we prompt the user to configure it (Settings → password safe). Restarting into a keyless
     * third-party provider would silently fall back to Anthropic's native auth, which is confusing and not what
     * the user asked for; and we never reuse Anthropic credentials for another provider.
     */
    fun changeProvider(target: Provider) {
        val settings = ClaudeSettings.getInstance(project)
        if (target == settings.provider) return
        if (target.requiresApiKey && settings.getProviderApiKey(target).isBlank()) {
            notifyConfigureProviderKey(target)
            return
        }
        val wasRunning = session.isRunning()
        settings.update { it.provider = target.id }
        session.cachedEnv = null // provider env changed → re-resolve on next start
        fireState()
        if (wasRunning) {
            session.systemNotice("Provider → ${target.label} — restarting session.")
            session.restart(resume = true)
        }
    }

    /** Warn that a third-party provider needs its own key and offer to open Settings. No provider switch. */
    private fun notifyConfigureProviderKey(target: Provider) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "${target.label} needs its own API key. Configure it in Settings — the provider isn't switched " +
                    "until a key is set, and your Anthropic credentials are never used for another provider.",
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
            .notify(project)
    }

    /**
     * Extended thinking is a launch flag now (`--thinking`), not a runtime control — the deprecated
     * `set_max_thinking_tokens` no longer surfaces reasoning on current models. So toggling it restarts the
     * session (resuming the same conversation via `--resume`) to re-launch with the new flag. Any non-null
     * value means "on" (adaptive); the exact token count is no longer sent (adaptive lets the model decide).
     */
    fun changeThinkingTokens(tokens: Int?) {
        if (tokens == session.thinkingTokens) return
        val wasRunning = session.isRunning()
        session.thinkingTokens = tokens
        fireState()
        if (wasRunning) {
            val state = if (tokens != null) "on" else "off"
            session.systemNotice("Extended thinking $state — restarting session.")
            session.restart(resume = true)
        }
    }

    /** Launch-time options (tool allow/deny lists, setting sources, partial streaming). Take effect on (re)start. */
    @Suppress("LongParameterList")
    fun configureLaunchOptions(
        allowedTools: String,
        disallowedTools: String,
        settingSources: String,
        includePartialMessages: Boolean,
        ideMcpEnabled: Boolean = false,
        ideMcpTransport: String = "sse",
        ideMcpPort: Int = ClaudeSession.DEFAULT_IDE_MCP_PORT,
        customMcpServers: String = "",
        maxTurns: Int? = null,
        maxBudgetUsd: Double? = null,
        fallbackModel: String? = null,
        addDirs: List<String> = emptyList(),
        betas: String? = null,
        strictMcpConfig: Boolean = false,
    ) {
        session.allowedTools = allowedTools
        session.disallowedTools = disallowedTools
        session.settingSources = settingSources
        session.includePartialMessages = includePartialMessages
        session.ideMcpEnabled = ideMcpEnabled
        session.ideMcpTransport = ideMcpTransport.ifBlank { "sse" }
        session.ideMcpPort = ideMcpPort.takeIf { it in ClaudeSession.VALID_PORTS } ?: ClaudeSession.DEFAULT_IDE_MCP_PORT
        session.customMcpServers = customMcpServers
        session.maxTurns = maxTurns
        session.maxBudgetUsd = maxBudgetUsd
        session.fallbackModel = fallbackModel
        session.addDirs = addDirs
        session.betas = betas
        session.strictMcpConfig = strictMcpConfig
        fireState()
    }

    fun cyclePermissionMode() {
        val order = ClaudeSession.PERMISSION_MODES_CYCLE
        val idx = order.indexOf(session.permissionMode).let { if (it < 0) 0 else it }
        changePermissionMode(order[(idx + 1) % order.size])
    }
}
