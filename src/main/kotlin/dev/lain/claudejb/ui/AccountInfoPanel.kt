package dev.lain.claudejb.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.protocol.AuthStatusInfo
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * A read-only, IDE-themed view of the signed-in account reported by the binary's `initialize`
 * handshake ([AccountInfo]: email / organization / plan / provider / api-key source) — the GUI
 * equivalent of the CLI's account header.
 *
 * When the binary is re-authenticating or has surfaced an auth error ([AuthStatusInfo]) the panel
 * reflects that state at the top (an amber "Re-authenticating…" line, or a red error line) so the
 * user understands why account data may be stale or missing.
 *
 * The human-readable shape is computed by the pure [AccountInfoPanel.Companion.describe] so it is
 * unit-testable without a Swing instance; [show] only turns those rows into labels.
 */
object AccountInfoPanel {

    /** One labelled row of the panel, with an optional emphasis for status lines. */
    enum class Tone { NORMAL, WARNING, ERROR }

    /** A rendered row: a left-hand [label] (blank for status banners) and its [value], with a [tone]. */
    data class Row(val label: String, val value: String, val tone: Tone = Tone.NORMAL)

    /**
     * Build the account panel. Pass the latest [account] (from `session.account`) and, if known,
     * the current [authStatus] (from `session.authStatus`); a re-auth/error status is surfaced as a
     * banner row above the account fields.
     */
    fun show(account: AccountInfo, authStatus: AuthStatusInfo?): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 10)

        val rows = describe(account, authStatus)
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.LINE_START
            insets = JBUI.insets(2, 0)
        }
        rows.forEachIndexed { i, row ->
            gbc.gridy = i
            if (row.label.isEmpty()) {
                // A full-width banner (status line / empty-state notice).
                gbc.gridx = 0
                gbc.gridwidth = 2
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                panel.add(valueLabel(row), gbc)
            } else {
                gbc.gridwidth = 1
                gbc.fill = GridBagConstraints.NONE
                gbc.gridx = 0
                gbc.weightx = 0.0
                gbc.insets = JBUI.insets(2, 0, 2, 12)
                panel.add(keyLabel(row.label), gbc)
                gbc.gridx = 1
                gbc.weightx = 1.0
                gbc.insets = JBUI.insets(2, 0)
                panel.add(valueLabel(row), gbc)
            }
        }
        return panel
    }

    private fun keyLabel(text: String): JLabel = JLabel(text).apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
    }

    private fun valueLabel(row: Row): JLabel = JLabel(row.value).apply {
        foreground = when (row.tone) {
            Tone.ERROR -> ChatTheme.ERROR
            Tone.WARNING -> ChatTheme.WARNING
            Tone.NORMAL -> ChatTheme.TEXT
        }
        font = if (row.tone == Tone.NORMAL) ChatTheme.body else ChatTheme.small
    }

        /**
         * Pure, Swing-free description of the panel: an ordered list of [Row]s. The auth-status banner
         * (if any) comes first, then the account fields, each shown only when it carries a value; an
         * empty account with no status yields a single "Not signed in" notice.
         *
         * Kept pure so the readable rendering (labels, which fields appear, error/warning surfacing) is
         * unit-testable without instantiating any UI.
         */
        fun describe(account: AccountInfo, authStatus: AuthStatusInfo?): List<Row> {
            val rows = mutableListOf<Row>()

            authStatusRow(authStatus)?.let { rows += it }

            field("Email", account.email)?.let { rows += it }
            field("Organization", account.organization)?.let { rows += it }
            field("Plan", prettyPlan(account.subscriptionType))?.let { rows += it }
            field("Provider", prettyProvider(account.apiProvider))?.let { rows += it }
            field("API key source", account.apiKeySource)?.let { rows += it }

            // Nothing at all to show (no account and no status) → an honest empty state.
            if (rows.isEmpty()) {
                rows += Row("", "Not signed in (connect the session first).", Tone.NORMAL)
            }
            return rows
        }

        /** A banner row for the current auth status, or null when the binary is settled and error-free. */
        fun authStatusRow(status: AuthStatusInfo?): Row? {
            if (status == null) return null
            val err = status.error?.takeIf { it.isNotBlank() }
            return when {
                err != null -> Row("", "Authentication error: $err", Tone.ERROR)
                status.isAuthenticating -> Row("", "Re-authenticating…", Tone.WARNING)
                else -> null
            }
        }

        private fun field(label: String, value: String): Row? =
            value.trim().takeIf { it.isNotEmpty() }?.let { Row(label, it) }

        /** Human-friendly plan label; passes through unknown values verbatim. */
        fun prettyPlan(subscriptionType: String): String = when (subscriptionType.trim().lowercase()) {
            "" -> ""
            "pro" -> "Pro"
            "max" -> "Max"
            "team" -> "Team"
            "enterprise" -> "Enterprise"
            "free" -> "Free"
            else -> subscriptionType.trim()
        }

        /** Human-friendly auth-backend label; passes through unknown values verbatim. */
        fun prettyProvider(apiProvider: String): String = when (apiProvider.trim().lowercase()) {
            "" -> ""
            "firstparty" -> "Anthropic"
            "bedrock", "anthropicaws" -> "Amazon Bedrock"
            "vertex" -> "Google Vertex AI"
            "foundry" -> "Azure AI Foundry"
            "mantle" -> "Mantle"
            "gateway" -> "Gateway"
            else -> apiProvider.trim()
        }
}
