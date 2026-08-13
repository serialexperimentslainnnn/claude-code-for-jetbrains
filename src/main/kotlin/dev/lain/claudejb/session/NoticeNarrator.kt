package dev.lain.claudejb.session

import com.intellij.openapi.diagnostic.Logger
import dev.lain.claudejb.protocol.ClaudeEvent

/**
 * Everything the binary says that is not part of a turn: refusals, uploads, plugin installs, denials,
 * recalled memories, loop banners, and the odd warning that its own bookkeeping failed.
 *
 * One subject — "the binary said something; put it where the user will read it" — and the reason it is not
 * in [ClaudeSession] is that it needs none of a turn: no queue, no tokens, no diffs, no permission cards.
 * It writes rows and, twice, raises an IDE notification.
 *
 * Every method assumes the EDT except where it hops explicitly; the caller dispatches.
 */
class NoticeNarrator(
    private val log: Logger,
    /** A row in the transcript, from the SYSTEM speaker. */
    private val systemNotice: (String) -> Unit,
    /** Straight to the transcript with a chosen speaker — for the ones that are errors, not notices. */
    private val addRow: (Speaker, String, String?) -> Unit,
    /** An IDE notification, for what must not be missed while the user is looking at the editor. */
    private val notifyInfo: (String) -> Unit,
    /** Hops to the EDT — the notices arrive on the process reader thread. */
    private val edt: (() -> Unit) -> Unit,
) {

    fun onNotice(event: ClaudeEvent.Notice) {
        when (event) {
            is ClaudeEvent.StatusNotice -> systemNotice(event.text)

            is ClaudeEvent.MemoryRecall -> onMemoryRecall(event)

            is ClaudeEvent.FilesPersisted -> onFilesPersisted(event)

            is ClaudeEvent.PluginInstall -> onPluginInstall(event)

            is ClaudeEvent.ModelRefusalFallback -> onModelRefusalFallback(event)

            is ClaudeEvent.ModelRefusalNoFallback -> onModelRefusalNoFallback(event)

            is ClaudeEvent.Informational -> onInformational(event)

            is ClaudeEvent.Notification -> onNotification(event)

            is ClaudeEvent.PermissionDenied -> onPermissionDenied(event)

            // mirror_error → the binary lost transcript data; warn the user (their session file may be incomplete).
            is ClaudeEvent.MirrorError -> {
                log.warn("mirror_error: ${event.info.error}")
                systemNotice("Warning: failed to persist part of the session transcript.")
            }

            // Live-tail only: a resumed session may replay historical instances, so don't tear anything down —
            // just log it. (Reasons like host_exit/remote_control_disabled are host-set, not user input.)
            is ClaudeEvent.WorkerShuttingDown -> log.info("worker_shutting_down: ${event.info.reason}")

            is ClaudeEvent.Other -> log.debug("Ignored ${event.type}/${event.subtype}")
        }
    }

    /** notification → in-transcript notice; high/immediate also raises an IDE notification so it isn't missed. */
    private fun onNotification(event: ClaudeEvent.Notification) {
        val text = event.info.text
        if (text.isBlank()) return
        systemNotice(text)
        if (event.info.priority == "high" || event.info.priority == "immediate") notifyInfo(text)
    }

    /** permission_denied → render the denial (the model only otherwise sees an is_error tool_result). */
    private fun onPermissionDenied(event: ClaudeEvent.PermissionDenied) = edt {
        val i = event.info
        val reason = i.message.ifBlank { i.decisionReason ?: i.decisionReasonType ?: "denied" }
        addRow(Speaker.ERROR, "Denied ${i.toolName}: $reason", null)
    }

    /** memory_recall → a collapsible "Recalled N memories" row listing what context influenced the turn. */
    private fun onMemoryRecall(event: ClaudeEvent.MemoryRecall) {
        if (event.info.memories.isEmpty()) return
        edt {
            addRow(Speaker.MEMORY, MemoryRecallFormatter.body(event.info), MemoryRecallFormatter.summary(event.info))
        }
    }

    private fun onFilesPersisted(event: ClaudeEvent.FilesPersisted) {
        val files = event.info.files
        if (files.isNotEmpty()) {
            systemNotice("Uploaded ${files.size} file(s): " + files.joinToString(", ") { it.filename })
        }
        if (event.info.failed.isNotEmpty()) systemNotice("Failed to persist ${event.info.failed.size} file(s)")
    }

    private fun onPluginInstall(event: ClaudeEvent.PluginInstall) {
        val i = event.info
        log.debug("plugin_install status=${i.status} name=${i.name}")
        when (i.status) {
            "installed" -> systemNotice("Plugin installed${i.name?.let { ": $it" } ?: ""}")
            "failed" -> systemNotice("Plugin install failed${i.error?.let { ": $it" } ?: ""}")
        }
    }

    private fun onModelRefusalFallback(event: ClaudeEvent.ModelRefusalFallback) {
        val i = event.info
        val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val to = i.fallbackModel.takeIf { it.isNotBlank() }
            ?.let { " → retried on $it" } ?: " → retried on a fallback model"
        systemNotice("The model declined to respond$cat$to.")
    }

    /**
     * Refusal with no fallback configured → the turn ends in error. Surface it (the content is display prose)
     * so a refused turn never ends silently.
     */
    private fun onModelRefusalNoFallback(event: ClaudeEvent.ModelRefusalNoFallback) = edt {
        val i = event.info
        val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val msg = i.content.ifBlank { "The model declined to respond$cat and no fallback model was configured." }
        addRow(Speaker.ERROR, msg, null)
    }

    /**
     * Generic loop banner. Only the more prominent levels (suggestion/warning) plus any blocking message reach
     * the transcript; info/notice are already implied by the turn state and would just add noise.
     */
    private fun onInformational(event: ClaudeEvent.Informational) {
        val i = event.info
        val text = i.content.trim()
        val prominent = i.level == "warning" || i.level == "suggestion" || i.preventContinuation
        if (text.isNotEmpty() && prominent) {
            systemNotice(if (i.level == "warning") "Warning: $text" else text)
        }
    }
}
