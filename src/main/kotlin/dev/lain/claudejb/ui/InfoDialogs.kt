package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object InfoDialogs {

    fun showBinaryVersion(project: Project, session: ClaudeSession) {
        session.queries.requestBinaryVersion { payload ->
            Messages.showInfoMessage(project, formatBinaryVersion(payload), "Claude Binary Version")
        }
    }

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

    fun formatBinaryVersion(payload: JsonObject?): String {
        val version = payload?.str("version")
            ?: payload?.str("binary_version")
            ?: payload?.str("claude_code_version")
        return if (version.isNullOrBlank()) "Binary version unavailable." else "claude $version"
    }

    fun formatEffectiveSettings(payload: JsonObject?): String {
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
