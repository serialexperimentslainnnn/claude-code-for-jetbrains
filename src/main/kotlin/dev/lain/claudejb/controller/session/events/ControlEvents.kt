package dev.lain.claudejb.controller.session.events

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.protocol.control.DialogResponder
import dev.lain.claudejb.model.session.launch.SessionLauncher
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.util.thisLogger
import kotlinx.serialization.json.JsonObject

class ControlEvents(
    private val s: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
) {

    private val log = thisLogger()

    private val hookBroker = HookBroker { SessionLauncher.rulesBlock(s.launch) }

    fun onControl(event: ClaudeEvent.Control) {
        when (event) {
            is ClaudeEvent.PermissionRequest -> s.guard.onPermission(event.requestId, event.request)

            is ClaudeEvent.HookCallback -> onHookCallback(event.requestId, event.request)

            is ClaudeEvent.UserDialogRequest -> {
                s.write(DialogResponder.response(event.requestId))
                s.systemNotice(DialogResponder.notice(event.dialogKind))
            }

            is ClaudeEvent.Elicitation -> s.cards.presentElicitation(event.requestId, event.request)

            is ClaudeEvent.UnsupportedControlRequest -> s.guard.broker.rejectUnsupported(event.requestId, event.subtype)

            is ClaudeEvent.ControlCancel -> edt { s.cards.withdraw(event.requestId) }

            is ClaudeEvent.ControlResult -> s.controlClient.onControlResult(event)
        }
    }

    fun onHookTelemetry(event: ClaudeEvent.HookTelemetry) = edt {
        when (event) {
            is ClaudeEvent.HookStarted -> s.hookNarrator.onStarted(event.info)
            is ClaudeEvent.HookProgress -> s.hookNarrator.onProgress(event.info)
            is ClaudeEvent.HookResponse -> s.hookNarrator.onResponse(event.info)
        }
    }

    private fun onHookCallback(requestId: String, request: JsonObject) {
        val ctx = hookBroker.parse(request)
        if (ctx == null) {
            s.write(ControlProtocol.error(requestId, "Malformed hook_callback (missing input/hook_event_name)"))
            return
        }
        s.write(ControlProtocol.success(requestId, hookBroker.buildResponse(ctx)))
        val effects = hookBroker.sideEffects(ctx)
        if (effects.isEmpty()) return
        edt {
            for (effect in effects) {
                when (effect) {
                    is HookSideEffect.NotifyUser -> s.notifier.info(effect.message)

                    is HookSideEffect.RefreshFile -> {
                        s.diffs.markForRefresh(effect.path)
                        s.diffs.refreshTouched()
                    }

                    is HookSideEffect.TranscriptNote -> s.transcript.add(Speaker.SYSTEM, effect.text)

                    is HookSideEffect.Marker -> log.debug { "hook marker ${effect.event} ${effect.detail ?: ""}" }
                }
            }
        }
    }
}
