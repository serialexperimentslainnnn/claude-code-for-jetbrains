package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Read-only graphical views over the session's query controls: context usage, session cost, MCP
 * server status and agents — the GUI equivalents of /context, /cost, /mcp and /agents — plus the
 * interactive MCP runtime panel (reconnect/toggle per server) and the binary-version /
 * effective-settings dialogs.
 *
 * Formatting and the MCP-status payload parsing live in pure functions ([parseMcpServers],
 * [formatBinaryVersion], [formatEffectiveSettings], [buildMcpPanel]) so they can be unit-tested without
 * a live session or a real Swing display.
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
            // get_session_cost returns { text } with the $-denominated API cost (the /cost summary). The
            // subscription quota % lives in the rate-limit info, so show both: quota first, then the cost.
            val cost = payload?.str("text")?.takeIf { it.isNotBlank() }
            val rl = session.rateLimit
            val text = buildString {
                if (rl != null) {
                    append("Subscription quota (${rl.windowLabel()})\n")
                    rl.utilizationPercent()?.let { append("  Used: $it%\n") }
                        ?: append("  Used: not reported yet (the binary sends % near the limit)\n")
                    append("  Status: ${rl.status}")
                    if (rl.isUsingOverage) append(" · using overage")
                    append("\n")
                    rl.resetsAt?.let {
                        append("  Resets: " + java.time.Instant.ofEpochSecond(it)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n")
                    }
                    append("\n")
                }
                append(cost ?: "No cost data available.")
            }
            Messages.showInfoMessage(project, text, "Usage & Cost")
        }
    }

    /**
     * Interactive MCP runtime view: lists every server from `mcp_status` with a status line and per-server
     * Reconnect/Toggle buttons. A button click fires the matching session control request and then re-queries
     * the status so the panel reflects the new state. Replaces the previous raw-JSON dump.
     */
    fun showMcpStatus(project: Project, session: ClaudeSession) {
        session.requestMcpStatus { payload ->
            val servers = parseMcpServers(payload)
            if (servers.isEmpty()) {
                Messages.showInfoMessage(project, "No MCP servers configured.", "MCP Servers")
                return@requestMcpStatus
            }
            McpStatusDialog(project, session, servers).show()
        }
    }

    /** /version equivalent: shows the responder binary's CLI version (from `get_binary_version`). */
    fun showBinaryVersion(project: Project, session: ClaudeSession) {
        session.requestBinaryVersion { payload ->
            Messages.showInfoMessage(project, formatBinaryVersion(payload), "Claude Binary Version")
        }
    }

    /** /config equivalent: shows the effective merged settings (from `get_settings`) as readable text. */
    fun showEffectiveSettings(project: Project, session: ClaudeSession) {
        session.requestSettings { payload ->
            Messages.showInfoMessage(project, formatEffectiveSettings(payload), "Effective Settings")
        }
    }

    /**
     * Account & auth view (the GUI equivalent of the CLI account header): reads the session's last
     * `initialize` [ClaudeSession.account] and current [ClaudeSession.authStatus] and shows them in a
     * read-only [AccountInfoPanel]. Both are plain volatile fields populated by the protocol, so this
     * never blocks; if nothing has been reported yet the panel shows an honest empty state.
     */
    fun showAccount(project: Project, session: ClaudeSession) {
        val panel = AccountInfoPanel.show(session.account, session.authStatus)
        DialogBuilder(project).apply {
            setTitle("Account")
            setCenterPanel(panel)
            removeAllActions()
            addOkAction().setText("Close")
        }.show()
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
    // Pure parsing / formatting (unit-testable, no session / no Swing display)
    // -----------------------------------------------------------------------

    /** One MCP server row in the runtime panel. [enabled] is null when the payload omits it. */
    data class McpServerRow(val name: String, val status: String, val enabled: Boolean?)

    /**
     * Parses the `mcp_status` control response into rows. Tolerant of shape drift: the server list may live
     * under `mcp_servers` (the system/init key) or `servers`, and each entry is a JSON object with a `name`
     * and an optional `status`/`state` plus an optional `enabled` boolean. Entries without a name are dropped.
     */
    fun parseMcpServers(payload: JsonObject?): List<McpServerRow> {
        val arr = (payload?.get("mcp_servers") ?: payload?.get("servers")) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = o.str("status") ?: o.str("state") ?: "unknown"
            val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull
            McpServerRow(name, status, enabled)
        }
    }

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

    /**
     * Builds the interactive MCP server panel: one row per server (name + status + Reconnect/Toggle). The
     * callbacks are passed in so this stays display-agnostic and headless-testable; the dialog wires them to
     * the session. Returns a scrollable component.
     */
    fun buildMcpPanel(
        servers: List<McpServerRow>,
        onReconnect: (String) -> Unit,
        onToggle: (name: String, enabled: Boolean) -> Unit,
    ): JComponent {
        val list = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        servers.forEach { server ->
            list.add(serverRow(server, onReconnect, onToggle))
            list.add(Box.createVerticalStrut(6))
        }
        val wrapper = JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }
        return JBScrollPane(wrapper).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = JBUI.size(420, 260)
        }
    }

    private fun serverRow(
        server: McpServerRow,
        onReconnect: (String) -> Unit,
        onToggle: (name: String, enabled: Boolean) -> Unit,
    ): JComponent = JPanel(GridBagLayout()).apply {
        name = "mcp-row-${server.name}" // stable handle for headless tests
        border = JBUI.Borders.empty(4, 2)
        val gc = GridBagConstraints()

        gc.gridx = 0; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL; gc.anchor = GridBagConstraints.WEST
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(server.name).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(JBLabel(server.status).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
        }, gc)

        gc.gridx = 1; gc.weightx = 0.0; gc.fill = GridBagConstraints.NONE; gc.anchor = GridBagConstraints.EAST
        add(JButton("Reconnect").apply {
            name = "reconnect-${server.name}"
            horizontalAlignment = SwingConstants.CENTER
            addActionListener { onReconnect(server.name) }
        }, gc)

        // Toggle flips the current enabled state; default to "currently enabled" when unknown, so the button
        // offers to disable (the safe, reversible direction).
        gc.gridx = 2
        val currentlyEnabled = server.enabled ?: true
        add(JButton(if (currentlyEnabled) "Disable" else "Enable").apply {
            name = "toggle-${server.name}"
            horizontalAlignment = SwingConstants.CENTER
            addActionListener { onToggle(server.name, !currentlyEnabled) }
        }, gc)
    }

    /**
     * Dialog hosting [buildMcpPanel]. Each action fires the session control request and then re-queries
     * `mcp_status`, rebuilding the panel in place so the user sees the effect. Closeable with a single button.
     * This is a deliberate manager surface (the only InfoDialog with mutating buttons); the chat/diff cards
     * stay non-modal as before.
     */
    private class McpStatusDialog(
        private val project: Project,
        private val session: ClaudeSession,
        initial: List<McpServerRow>,
    ) : DialogWrapper(project) {
        private val host = JPanel(BorderLayout())
        private var servers = initial

        init {
            title = "MCP Servers"
            setOKButtonText("Close")
            rebuild()
            init()
        }

        private fun rebuild() {
            host.removeAll()
            host.add(
                buildMcpPanel(
                    servers,
                    onReconnect = { name -> session.reconnectMcp(name); refresh() },
                    onToggle = { name, enabled -> session.toggleMcp(name, enabled); refresh() },
                ),
                BorderLayout.CENTER,
            )
            host.revalidate(); host.repaint()
        }

        private fun refresh() {
            session.requestMcpStatus { payload ->
                servers = parseMcpServers(payload).ifEmpty { servers }
                rebuild()
            }
        }

        override fun createCenterPanel(): JComponent = host
        override fun createActions() = arrayOf(okAction)
    }
}
