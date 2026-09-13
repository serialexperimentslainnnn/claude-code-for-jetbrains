package dev.lain.claudejb.controller.session.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.process.ClaudeBinaryLocator
import dev.lain.claudejb.controller.session.AttentionReason
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.controller.session.auth.AuthFailure
import dev.lain.claudejb.controller.session.auth.LoginDetection
import dev.lain.claudejb.controller.session.turn.ReviewPrompt
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.session.launch.SessionLauncher
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.util.thisLogger

class ConversationEvents(
    private val s: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val fireAttention: (AttentionReason) -> Unit,
) {

    private val log = thisLogger()

    fun onConversation(event: ClaudeEvent.Conversation) {
        when (event) {
            is ClaudeEvent.Init -> onInit(event)

            is ClaudeEvent.ToolUse -> s.events.toolEvents.onToolUse(event)

            is ClaudeEvent.ToolResult -> s.events.toolEvents.onToolResult(event)

            is ClaudeEvent.Result -> onTurnResult(event)

            is ClaudeEvent.AssistantThinking -> edt { s.reconciler.finalizeThinking(event.text, event.parentToolUseId) }

            is ClaudeEvent.MessageStart -> edt {
                s.tokens.foldIntoSession()
                s.turn.liveThinkingTokens = 0
                s.reconciler.onMessageBoundary()
            }

            is ClaudeEvent.LocalCommandOutput -> edt {
                if (event.content.isNotBlank()) s.transcript.add(Speaker.SYSTEM, event.content)
            }

            is ClaudeEvent.AssistantText -> edt { s.reconciler.finalizeAssistant(event.text, event.parentToolUseId) }
        }
    }

    private fun onInit(event: ClaudeEvent.Init) {
        s.sessionId = event.info.sessionId
        s.agentScanner.restoreAdmitted(onTasksReplayed = fireState)
        s.launch = s.launch.copy(model = s.launch.model ?: event.info.model.ifBlank { null }, fork = false)
        if (event.info.outputStyle.isNotBlank()) s.catalog.outputStyle = event.info.outputStyle
        val ours = SessionLauncher.binaryPermissionMode(s.launch.permissionMode)
        if (event.info.permissionMode.isNotBlank() && event.info.permissionMode != ours) {
            s.write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), ours))
        }
        s.lifecycle.ready = true
        edt {
            s.systemNotice("Connected · ${event.info.model.ifBlank { "claude" }} · ${event.info.cwd}")
            fireState()
            s.prompts.pump()
        }
    }

    private fun onTurnResult(event: ClaudeEvent.Result) = edt {
        s.tokens.foldIntoSession()
        s.reconciler.onMessageBoundary()
        s.turn.reset()
        s.poll.pollQuota()
        if (event.result.isError) {
            val message = event.result.result.ifBlank {
                event.result.errors.joinToString("\n").ifBlank { "Turn ended with error: ${event.result.subtype}" }
            }
            surfaceAuthFailure(message, message)
        } else {
            s.lifecycle.needsLogin = false
            s.login.onCleanResult()
            ReviewPrompt.onSuccessfulTurn(project)
        }
        s.diffs.refreshTouched()
        s.agentScanner.scan()
        fireState()
        s.prompts.pump()
        s.sessionId?.let { id -> s.persistence.recordOpenAndTitle(id) }
        fireAttention(if (event.result.isError) AttentionReason.ERROR else AttentionReason.TURN_DONE)
    }

    fun surfaceAuthFailure(failureText: String, display: String) {
        when (LoginDetection.resolve(failureText, s.lifecycle.auth::canRenewCredential)) {
            AuthFailure.EXPIRED -> {
                s.transcript.add(Speaker.SYSTEM, EXPIRED_TOKEN_NOTICE)
                renewRejectedCredential()
            }

            AuthFailure.NO_IDENTITY -> {
                s.transcript.add(Speaker.ERROR, display)
                s.lifecycle.onLoginNeeded()
            }

            AuthFailure.NONE -> s.transcript.add(Speaker.ERROR, display)
        }
    }

    private fun renewRejectedCredential() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = ClaudeSettings.getInstance(project)
            val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return@executeOnPooledThread
            if (!s.lifecycle.auth.renewRejected(binary, settings)) return@executeOnPooledThread
            log.info("the rejected credential was renewed; restarting the session on the new one")
            edt { s.restart() }
        }
    }

    private companion object {
        const val EXPIRED_TOKEN_NOTICE =
            "Your access token expired while this chat was open. The sign-in itself is still valid and is " +
                "renewed when a session starts, but a running one cannot pick up the new token — so this turn " +
                "did not complete, and sending it again will fail the same way. Close this chat and open it " +
                "again to continue."
    }
}
