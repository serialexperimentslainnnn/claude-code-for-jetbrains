package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.session.ClaudeSession

/**
 * Read-only graphical views over the session's query controls: context usage, session cost, MCP
 * server status and agents — the GUI equivalents of /context, /cost, /mcp and /agents.
 */
object InfoDialogs {

    fun showContextUsage(project: Project, session: ClaudeSession) {
        session.requestContextUsage { usage ->
            val text = if (usage == null) {
                "No context data available."
            } else {
                buildString {
                    append("Tokens: ${usage.totalTokens} / ${usage.maxTokens}  (${"%.1f".format(usage.percentage)}%)\n\n")
                    usage.categories.sortedByDescending { it.tokens }.forEach { append("• ${it.name}: ${it.tokens}\n") }
                }
            }
            Messages.showInfoMessage(project, text, "Context Usage")
        }
    }

    fun showSessionCost(project: Project, session: ClaudeSession) {
        session.requestSessionCost { payload ->
            Messages.showInfoMessage(project, payload?.toString() ?: "No cost data available.", "Session Cost")
        }
    }

    fun showMcpStatus(project: Project, session: ClaudeSession) {
        session.requestMcpStatus { payload ->
            Messages.showInfoMessage(project, payload?.toString() ?: "No MCP servers configured.", "MCP Servers")
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
}
