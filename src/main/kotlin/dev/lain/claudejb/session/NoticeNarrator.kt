package dev.lain.claudejb.session

import com.intellij.openapi.diagnostic.Logger
import dev.lain.claudejb.protocol.ClaudeEvent

class NoticeNarrator(
    private val log: Logger,
    private val systemNotice: (String) -> Unit,
    private val addRow: (Speaker, String, String?) -> Unit,
    private val notifyInfo: (String) -> Unit,
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

            is ClaudeEvent.MirrorError -> {
                log.warn("mirror_error: ${event.info.error}")
                systemNotice("Warning: failed to persist part of the session transcript.")
            }

            is ClaudeEvent.WorkerShuttingDown -> log.info("worker_shutting_down: ${event.info.reason}")

            is ClaudeEvent.Other -> log.debug("Ignored ${event.type}/${event.subtype}")
        }
    }

    private fun onNotification(event: ClaudeEvent.Notification) {
        val text = event.info.text
        if (text.isBlank()) return
        systemNotice(text)
        if (event.info.priority == "high" || event.info.priority == "immediate") notifyInfo(text)
    }

    private fun onPermissionDenied(event: ClaudeEvent.PermissionDenied) = edt {
        val i = event.info
        val reason = i.message.ifBlank { i.decisionReason ?: i.decisionReasonType ?: "denied" }
        addRow(Speaker.ERROR, "Denied ${i.toolName}: $reason", null)
    }

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

    private fun onModelRefusalNoFallback(event: ClaudeEvent.ModelRefusalNoFallback) = edt {
        val i = event.info
        val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val msg = i.content.ifBlank { "The model declined to respond$cat and no fallback model was configured." }
        addRow(Speaker.ERROR, msg, null)
    }

    private fun onInformational(event: ClaudeEvent.Informational) {
        val i = event.info
        val text = i.content.trim()
        val prominent = i.level == "warning" || i.level == "suggestion" || i.preventContinuation
        if (text.isNotEmpty() && prominent) {
            systemNotice(if (i.level == "warning") "Warning: $text" else text)
        }
    }
}
