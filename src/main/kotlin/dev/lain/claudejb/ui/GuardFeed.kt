package dev.lain.claudejb.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardAlert
import dev.lain.claudejb.settings.GuardAlertLog
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope
import dev.lain.claudejb.ui.jcef.JcefGuardData

internal class GuardFeed(private val panel: JcefChatPanel) {

    fun push() {
        val scope = scope()
        val sessionId = panel.session.sessionId
        val recorded = panel.session.guardLog.recorded
        val dropped = panel.session.guardLog.dropped
        offEdt {
            val json = JcefGuardData.guardJson(
                alerts = read(scope, sessionId),
                recorded = recorded,
                dropped = dropped,
                recording = !SecretStore.inert(),
                max = GuardAlertLog.MAX_ENTRIES,
            )
            onEdt { panel.host.exec("window.cc.guard && window.cc.guard($json)") }
        }
    }

    fun explain(id: String) {
        val scope = scope()
        val sessionId = panel.session.sessionId
        offEdt {
            val alert = read(scope, sessionId).firstOrNull { JcefGuardData.idOf(it) == id }
            val prompt = alert?.let(GuardPromptedActions::explainBlockPrompt)
            onEdt {
                if (prompt == null) {
                    panel.session.systemNotice(GuardPromptedActions.ENTRY_GONE)
                } else {
                    panel.session.sendSideQuestion(prompt)
                }
            }
        }
    }

    private fun scope(): SettingsScope = ClaudeSettings.getInstance(panel.project).scope

    private fun read(scope: SettingsScope, sessionId: String?): List<GuardAlert> {
        if (sessionId.isNullOrBlank()) return emptyList()
        return runCatching { GuardAlertLog.forSession(scope, sessionId) }
            .onFailure { logger.warn("Claude Code could not read the guard alert log", it) }
            .getOrDefault(emptyList())
    }

    private fun offEdt(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block).onFailure { logger.warn("Claude Code could not answer the guard view", it) }
        }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!panel.project.isDisposed) block()
        }, ModalityState.any())
    }

    private companion object {
        private val logger = Logger.getInstance(GuardFeed::class.java)
    }
}
