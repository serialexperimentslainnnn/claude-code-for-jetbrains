package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.ui.InfoDialogs
import javax.swing.JButton

/**
 * Headless: [InfoDialogs.buildMcpPanel] renders one row per MCP server with Reconnect/Toggle buttons and
 * wires their callbacks (toggle flips the current enabled state). Runs on the EDT (BasePlatformTestCase)
 * so the Swing component work is safe. No [dev.lain.claudejb.session.ClaudeSession] dependency — the panel
 * takes callbacks directly.
 */
class InfoDialogsMcpPanelHeadlessTest : BasePlatformTestCase() {

    private fun rows() = listOf(
        InfoDialogs.McpServerRow("jetbrains", "connected", enabled = true),
        InfoDialogs.McpServerRow("ctx7", "failed", enabled = false),
        InfoDialogs.McpServerRow("legacy", "unknown", enabled = null),
    )

    fun `test panel renders one row per server`() {
        val panel = InfoDialogs.buildMcpPanel(rows(), onReconnect = {}, onToggle = { _, _ -> })
        assertNotNull("each server must have a named row", findByName(panel, "mcp-row-jetbrains"))
        assertNotNull(findByName(panel, "mcp-row-ctx7"))
        assertNotNull(findByName(panel, "mcp-row-legacy"))
    }

    fun `test reconnect fires the callback with the server name`() {
        var reconnected: String? = null
        val panel = InfoDialogs.buildMcpPanel(rows(), onReconnect = { reconnected = it }, onToggle = { _, _ -> })

        click(findButton(panel, "reconnect-ctx7"))
        assertEquals("ctx7", reconnected)
    }

    fun `test toggle flips an enabled server to disabled`() {
        var toggled: Pair<String, Boolean>? = null
        val panel = InfoDialogs.buildMcpPanel(rows(), onReconnect = {}, onToggle = { n, e -> toggled = n to e })

        val btn = findButton(panel, "toggle-jetbrains")
        assertEquals("Disable", btn.text) // currently enabled → offers to disable
        click(btn)
        assertEquals("jetbrains" to false, toggled)
    }

    fun `test toggle flips a disabled server to enabled`() {
        var toggled: Pair<String, Boolean>? = null
        val panel = InfoDialogs.buildMcpPanel(rows(), onReconnect = {}, onToggle = { n, e -> toggled = n to e })

        val btn = findButton(panel, "toggle-ctx7")
        assertEquals("Enable", btn.text) // currently disabled → offers to enable
        click(btn)
        assertEquals("ctx7" to true, toggled)
    }

    fun `test unknown enabled defaults to currently enabled`() {
        val panel = InfoDialogs.buildMcpPanel(rows(), onReconnect = {}, onToggle = { _, _ -> })
        // null enabled → treated as enabled, so the button offers to disable.
        assertEquals("Disable", findButton(panel, "toggle-legacy").text)
    }

    // --- tiny Swing tree helpers ---

    private fun findByName(c: java.awt.Component, name: String): java.awt.Component? {
        if (c.name == name) return c
        if (c is java.awt.Container) c.components.forEach { findByName(it, name)?.let { found -> return found } }
        return null
    }

    private fun findButton(c: java.awt.Component, name: String): JButton =
        requireNotNull(findByName(c, name) as? JButton) { "button '$name' not found" }

    private fun click(b: JButton) = b.actionListeners.forEach {
        it.actionPerformed(java.awt.event.ActionEvent(b, java.awt.event.ActionEvent.ACTION_PERFORMED, "click"))
    }
}
