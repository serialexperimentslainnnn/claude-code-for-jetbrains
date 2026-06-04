package dev.lain.claudejb.ui

import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.protocol.AuthStatusInfo
import dev.lain.claudejb.ui.AccountInfoPanel.Tone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [AccountInfoPanel] (no Swing instance): which rows render for a complete vs. partial
 * account, the empty state, the auth-status banner (re-auth / error), and the friendly plan/provider
 * labels. These pin the readable contract the [AccountInfoPanel.show] painting relies on.
 */
class AccountInfoPanelTest {

    private fun describe(account: AccountInfo, status: AuthStatusInfo? = null) =
        AccountInfoPanel.describe(account, status)

    // --- describe: account fields ---

    @Test
    fun `complete account renders every field with friendly labels`() {
        val rows = describe(
            AccountInfo(
                email = "dev@example.com",
                organization = "Acme",
                subscriptionType = "max",
                apiProvider = "firstParty",
                apiKeySource = "ANTHROPIC_API_KEY",
            ),
        )
        val byLabel = rows.associate { it.label to it.value }
        assertEquals("dev@example.com", byLabel["Email"])
        assertEquals("Acme", byLabel["Organization"])
        assertEquals("Max", byLabel["Plan"])
        assertEquals("Anthropic", byLabel["Provider"])
        assertEquals("ANTHROPIC_API_KEY", byLabel["API key source"])
        // All NORMAL tone, no banner.
        assertTrue(rows.all { it.tone == Tone.NORMAL && it.label.isNotEmpty() })
    }

    @Test
    fun `partial account omits empty fields`() {
        val rows = describe(AccountInfo(email = "dev@example.com", subscriptionType = "pro"))
        val labels = rows.map { it.label }
        assertEquals(listOf("Email", "Plan"), labels)
        assertFalse(labels.contains("Organization"))
        assertFalse(labels.contains("Provider"))
        assertFalse(labels.contains("API key source"))
    }

    @Test
    fun `empty account yields a not-signed-in notice`() {
        val rows = describe(AccountInfo())
        assertEquals(1, rows.size)
        assertEquals("", rows[0].label)
        assertTrue(rows[0].value.contains("Not signed in"))
    }

    // --- describe: auth status banner ---

    @Test
    fun `auth error shows a red banner before the account fields`() {
        val rows = describe(
            AccountInfo(email = "dev@example.com"),
            AuthStatusInfo(error = "token expired"),
        )
        assertEquals("", rows[0].label)
        assertEquals(Tone.ERROR, rows[0].tone)
        assertTrue(rows[0].value.contains("token expired"))
        // Account field still rendered after the banner.
        assertTrue(rows.any { it.label == "Email" && it.value == "dev@example.com" })
    }

    @Test
    fun `re-authenticating shows an amber banner`() {
        val rows = describe(AccountInfo(), AuthStatusInfo(isAuthenticating = true))
        assertEquals(Tone.WARNING, rows[0].tone)
        assertTrue(rows[0].value.contains("Re-authenticating"))
    }

    @Test
    fun `error takes precedence over the authenticating flag`() {
        val row = AccountInfoPanel.authStatusRow(
            AuthStatusInfo(isAuthenticating = true, error = "boom"),
        )
        assertEquals(Tone.ERROR, row?.tone)
        assertTrue(row!!.value.contains("boom"))
    }

    @Test
    fun `settled error-free status produces no banner`() {
        assertNull(AccountInfoPanel.authStatusRow(AuthStatusInfo()))
        assertNull(AccountInfoPanel.authStatusRow(AuthStatusInfo(error = "  ")))
        assertNull(AccountInfoPanel.authStatusRow(null))
    }

    // --- pretty labels ---

    @Test
    fun `plan labels are humanised and unknown values pass through`() {
        assertEquals("Pro", AccountInfoPanel.prettyPlan("pro"))
        assertEquals("Max", AccountInfoPanel.prettyPlan("MAX"))
        assertEquals("Enterprise", AccountInfoPanel.prettyPlan("enterprise"))
        assertEquals("", AccountInfoPanel.prettyPlan(""))
        assertEquals("custom-plan", AccountInfoPanel.prettyPlan("custom-plan"))
    }

    @Test
    fun `provider labels map backends and unknown values pass through`() {
        assertEquals("Anthropic", AccountInfoPanel.prettyProvider("firstParty"))
        assertEquals("Amazon Bedrock", AccountInfoPanel.prettyProvider("bedrock"))
        assertEquals("Google Vertex AI", AccountInfoPanel.prettyProvider("vertex"))
        assertEquals("Azure AI Foundry", AccountInfoPanel.prettyProvider("foundry"))
        assertEquals("", AccountInfoPanel.prettyProvider(""))
        assertEquals("weird", AccountInfoPanel.prettyProvider("weird"))
    }
}
