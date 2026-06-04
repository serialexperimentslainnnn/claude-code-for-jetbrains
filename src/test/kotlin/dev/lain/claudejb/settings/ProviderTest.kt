package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the security-critical [Provider] launch-env rules: a third-party provider gets its base URL and key as
 * an ATOMIC PAIR and only when a key is present; Anthropic (or a missing key) gets nothing so the binary uses
 * its own login; we never emit `ANTHROPIC_AUTH_TOKEN`; and an Anthropic-shaped key is recognised so the UI can
 * refuse to send Anthropic credentials to another provider.
 */
class ProviderTest {

    @Test
    fun `deepseek with a key emits the base url and api key as a pair`() {
        val env = Provider.launchEnv(Provider.DEEPSEEK, "sk-deepseek-123")
        assertEquals("https://api.deepseek.com/anthropic", env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-deepseek-123", env["ANTHROPIC_API_KEY"])
        // Hard rule: never the bearer token, and exactly those two keys (no stray overrides).
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertEquals(setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY"), env.keys)
    }

    @Test
    fun `deepseek with a blank or whitespace key emits NOTHING (never a lone base url)`() {
        // A lone base URL would make the SDK ship the Anthropic OAuth bearer to the third party — forbidden.
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, "").isEmpty())
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, "   ").isEmpty())
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, null).isEmpty())
    }

    @Test
    fun `anthropic emits nothing even with a key (uses the binary's own login)`() {
        assertTrue(Provider.launchEnv(Provider.ANTHROPIC, "sk-ant-whatever").isEmpty())
    }

    @Test
    fun `the key is trimmed`() {
        assertEquals("k", Provider.launchEnv(Provider.DEEPSEEK, "  k  ")["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `requiresApiKey is true only for third-party providers`() {
        assertFalse(Provider.ANTHROPIC.requiresApiKey)
        assertTrue(Provider.DEEPSEEK.requiresApiKey)
    }

    @Test
    fun `fromId resolves known ids and falls back to the default`() {
        assertEquals(Provider.DEEPSEEK, Provider.fromId("deepseek"))
        assertEquals(Provider.ANTHROPIC, Provider.fromId("anthropic"))
        assertEquals(Provider.DEFAULT, Provider.fromId(null))
        assertEquals(Provider.DEFAULT, Provider.fromId("nonexistent"))
        assertEquals(Provider.ANTHROPIC, Provider.DEFAULT)
    }

    @Test
    fun `looksLikeAnthropicKey recognises the sk-ant- prefix`() {
        assertTrue(Provider.looksLikeAnthropicKey("sk-ant-api03-abc"))
        assertTrue(Provider.looksLikeAnthropicKey("  sk-ant-oat01-xyz  "))
        assertFalse(Provider.looksLikeAnthropicKey("sk-deepseek-123"))
        assertFalse(Provider.looksLikeAnthropicKey(""))
    }
}
