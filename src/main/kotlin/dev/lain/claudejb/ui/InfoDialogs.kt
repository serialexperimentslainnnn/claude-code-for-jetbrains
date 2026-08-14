package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ⚙ menu's read-only text views over the session: the agent catalogue, the responder binary's version and
 * the effective merged settings — the GUI equivalents of /agents, /version and /config.
 *
 * Context usage, cost, the account and MCP server status are NOT here: they are cards in the JCEF dashboard
 * (⚙ ▸ Session Info), which is one formatted surface instead of four plain-text windows.
 *
 * The formatting lives in pure functions ([formatBinaryVersion], [formatEffectiveSettings]) so it can be
 * unit-tested without a live session or a real Swing display.
 */
object InfoDialogs {

    /** /version equivalent: shows the responder binary's CLI version (from `get_binary_version`). */
    fun showBinaryVersion(project: Project, session: ClaudeSession) {
        session.queries.requestBinaryVersion { payload ->
            Messages.showInfoMessage(project, formatBinaryVersion(payload), "Claude Binary Version")
        }
    }

    /** /config equivalent: shows the effective merged settings (from `get_settings`) as readable text. */
    fun showEffectiveSettings(project: Project, session: ClaudeSession) {
        session.queries.requestSettings { payload ->
            Messages.showInfoMessage(project, formatEffectiveSettings(payload), "Effective Settings")
        }
    }

    fun showAgents(project: Project, session: ClaudeSession) {
        val agents = session.agents
        val text = if (agents.isEmpty()) {
            "No agents available (connect the session first)."
        } else {
            agents.joinToString("\n\n") { "• ${it.name}\n  ${it.description}" }
        }
        Messages.showInfoMessage(project, text, "Agents")
    }

    // -----------------------------------------------------------------------
    // Pure formatting (unit-testable, no session / no Swing display)
    // -----------------------------------------------------------------------

    /** Formats the `get_binary_version` payload (tolerant of `version`/`binary_version` keys). */
    fun formatBinaryVersion(payload: JsonObject?): String {
        val version = payload?.str("version")
            ?: payload?.str("binary_version")
            ?: payload?.str("claude_code_version")
        return if (version.isNullOrBlank()) "Binary version unavailable." else "claude $version"
    }

    /**
     * Renders the `get_settings` payload as a sorted `key: value` list. Scalars print inline; objects/arrays
     * print their compact JSON. An empty/absent payload yields a friendly placeholder.
     */
    fun formatEffectiveSettings(payload: JsonObject?): String {
        // The response may nest the merged map under "settings"/"effective"; fall back to the top level.
        val settings = (payload?.get("settings") as? JsonObject)
            ?: (payload?.get("effective") as? JsonObject)
            ?: payload
        if (settings == null || settings.isEmpty()) return "No settings reported."
        return settings.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
            val rendered = (v as? JsonPrimitive)?.content ?: v.toString()
            "$k: $rendered"
        }
    }
}
