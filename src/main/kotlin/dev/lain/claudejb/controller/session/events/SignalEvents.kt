package dev.lain.claudejb.controller.session.events

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.parse.isHiddenUsageWindow
import dev.lain.claudejb.util.thisLogger

class SignalEvents(
    private val s: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val fireMetadata: () -> Unit,
) {

    private val log = thisLogger()

    fun onSessionSignal(event: ClaudeEvent.SessionSignal) {
        when (event) {
            is ClaudeEvent.RateLimit -> onRateLimit(event)

            is ClaudeEvent.AuthStatus -> onAuthStatus(event)

            is ClaudeEvent.ControlRequestProgress -> onControlRequestProgress(event)

            is ClaudeEvent.SessionStateChanged -> {
                s.signals.sessionState = event.info.state
                edt { fireState() }
            }

            is ClaudeEvent.ThinkingTokens -> edt {
                s.turn.liveThinkingTokens = event.info.estimatedTokens
                fireState()
            }

            is ClaudeEvent.ApiRetry -> {
                val of = if (event.info.maxRetries > 0) "/${event.info.maxRetries}" else ""
                s.systemNotice("Retrying (attempt ${event.info.attempt}$of)…")
            }

            is ClaudeEvent.CommandsChanged -> edt {
                s.catalog.commands = event.info.commands
                fireMetadata()
            }

            is ClaudeEvent.PromptSuggestion -> s.prompts.suggest(event.info.suggestion)
        }
    }

    private fun onRateLimit(event: ClaudeEvent.RateLimit) {
        val incoming = event.info
        log.debug {
            "rate_limit_event: window=${incoming.rateLimitType} status=${incoming.status}" +
                " utilization=${incoming.utilization} -> pct=${incoming.utilizationPercent()}"
        }
        val window = incoming.rateLimitType
        if (isHiddenUsageWindow(window)) return
        val previous = window?.let { s.signals.rateLimits[it] } ?: s.signals.rateLimit.takeIf { it?.rateLimitType == window }
        val merged = if (incoming.utilization == null) incoming.copy(utilization = previous?.utilization) else incoming
        s.signals.rateLimit = merged
        if (window != null) s.signals.rateLimits = s.signals.rateLimits + (window to merged)
        edt { fireState() }
    }

    private fun onAuthStatus(event: ClaudeEvent.AuthStatus) {
        s.signals.authStatus = event.info
        event.info.error?.takeIf { it.isNotBlank() }?.let {
            edt { s.events.conversation.surfaceAuthFailure(it, "Authentication error: $it") }
        }
        edt { fireState() }
    }

    private fun onControlRequestProgress(event: ClaudeEvent.ControlRequestProgress) {
        val i = event.info
        when (i.status) {
            "api_retry" -> {
                val of = (i.maxRetries ?: 0).takeIf { it > 0 }?.let { "/$it" } ?: ""
                s.systemNotice("Retrying (attempt ${i.attempt ?: 1}$of)…")
            }

            "started" -> s.controlClient.onProgress(i.requestId)

            else -> log.debug { "control_request_progress: ${i.status} for ${i.requestId}" }
        }
    }
}
