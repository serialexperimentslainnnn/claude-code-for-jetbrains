package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.HookProgressInfo
import dev.lain.claudejb.protocol.HookResponseInfo
import dev.lain.claudejb.protocol.HookStartedInfo

/**
 * Turns the binary's native hook telemetry (system/hook_started → hook_progress → hook_response) into ONE
 * evolving transcript row per hook, keyed by hook id (falling back to hook name): started inserts the row,
 * progress mutates it in place (last non-blank output line), response finalizes it (✓/✗) and drops the key — so
 * a chatty hook can't flood the transcript with separate rows.
 *
 * EDT-confined: every method mutates the [TranscriptModel], which (like the rest of the session) is touched only
 * on the EDT. Distinct from [HookBroker], which answers the hook_callback *control request*; this narrates only
 * the informational system events.
 */
class HookActivityNarrator(private val transcript: TranscriptModel) {

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
            // No started row (e.g. we joined mid-hook): surface a failure, silently drop a success.
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

    /** Drops all tracked rows (stop/terminate) so a later session reuse starts clean. */
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
            if (line != null) return line.take(120)
        }
        return null
    }
}
