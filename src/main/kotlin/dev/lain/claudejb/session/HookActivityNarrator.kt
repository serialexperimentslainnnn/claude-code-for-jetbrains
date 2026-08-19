package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.HookProgressInfo
import dev.lain.claudejb.protocol.HookResponseInfo
import dev.lain.claudejb.protocol.HookStartedInfo

class HookActivityNarrator(private val transcript: TranscriptModel) {

    private companion object {
        const val MAX_NARRATION_CHARS = 120
    }

    private val rows = HashMap<String, TranscriptEntry>()

    fun onStarted(info: HookStartedInfo) {
        val key = keyOf(info.hookId, info.hookName)
        rows[key] = transcript.add(Speaker.SYSTEM, running(info.hookEvent, info.hookName, null))
    }

    fun onProgress(info: HookProgressInfo) {
        val row = rows[keyOf(info.hookId, info.hookName)] ?: return
        transcript.replaceText(row, running(info.hookEvent, info.hookName, lastLine(info.output, info.stdout, info.stderr)))
    }

    fun onResponse(info: HookResponseInfo) {
        val label = label(info.hookEvent, info.hookName)
        val row = rows.remove(keyOf(info.hookId, info.hookName)) ?: run {
            if (info.outcome == "error") transcript.add(Speaker.SYSTEM, "✗ Hook $label failed")
            return
        }
        val text = when (info.outcome) {
            "error" -> "✗ Hook $label failed" + (info.exitCode?.let { " (exit $it)" } ?: "")
            "cancelled" -> "⊘ Hook $label cancelled"
            else -> "✓ Hook $label"
        }
        transcript.replaceText(row, text)
    }

    fun clear() = rows.clear()

    private fun running(event: String, name: String, tail: String?): String =
        "⚙ Hook ${label(event, name)} — running…" + (tail?.let { " · $it" } ?: "")

    private fun keyOf(hookId: String, hookName: String): String =
        hookId.ifBlank { hookName }.ifBlank { "hook" }

    private fun label(event: String, name: String): String = when {
        event.isNotBlank() && name.isNotBlank() -> "$event/$name"
        name.isNotBlank() -> name
        event.isNotBlank() -> event
        else -> "hook"
    }

    private fun lastLine(vararg sources: String): String? {
        for (s in sources) {
            val line = s.split('\n').asReversed().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (line != null) return line.take(MAX_NARRATION_CHARS)
        }
        return null
    }
}
