package dev.lain.claudejb.controller.session.events

import com.intellij.openapi.application.ApplicationManager
import dev.lain.claudejb.controller.session.AttentionReason
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.turn.StreamBuffer

class SessionEventRouter(
    private val s: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    fireState: () -> Unit,
    fireMetadata: () -> Unit,
    fireAttention: (AttentionReason) -> Unit,
    private val notices: NoticeNarrator,
) {

    private val stream = StreamBuffer()

    val toolEvents = ToolEvents(s, edt, fireState)
    private val taskEvents = TaskEvents(s, edt, fireState)
    private val signalEvents = SignalEvents(s, edt, fireState, fireMetadata)
    private val controlEvents = ControlEvents(s, edt)
    val conversation = ConversationEvents(s, s.project, edt, fireState, fireAttention)

    fun onEvent(event: ClaudeEvent) {
        if (event is ClaudeEvent.Stream) {
            stream.buffer(event)
            return
        }
        flushDeltas()
        when (event) {
            is ClaudeEvent.Conversation -> conversation.onConversation(event)
            is ClaudeEvent.Control -> controlEvents.onControl(event)
            is ClaudeEvent.Task -> taskEvents.onTask(event)
            is ClaudeEvent.SessionSignal -> signalEvents.onSessionSignal(event)
            is ClaudeEvent.HookTelemetry -> controlEvents.onHookTelemetry(event)
            is ClaudeEvent.Notice -> notices.onNotice(event)
            is ClaudeEvent.Stream -> {}
        }
    }

    fun flushDeltas() {
        val drained = stream.drain() ?: return
        val apply = {
            for ((isThinking, text) in drained.runs) {
                if (isThinking) s.reconciler.appendThinking(text) else s.reconciler.appendAssistant(text)
            }
            drained.usage?.let { s.tokens.onLiveUsage(it[0], it[1], it[2], it[3]) }
        }
        if (ApplicationManager.getApplication().isDispatchThread) apply() else edt { apply() }
    }
}
