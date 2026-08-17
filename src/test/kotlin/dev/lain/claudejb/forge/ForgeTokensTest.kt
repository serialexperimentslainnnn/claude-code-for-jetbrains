package dev.lain.claudejb.forge

import dev.lain.claudejb.settings.SecretStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The per-host token store, over a credential store this test installs for itself.
 *
 * `SecretStore.storeOverride = mutableMapOf()` in setUp and `= null` in tearDown is not ceremony: without it
 * the store is inert, every read answers null, and the assertions below would pass for the wrong reason —
 * and with the real one, a suite that shares an Application would be writing into whatever store the rest of
 * the run reads.
 */
class ForgeTokensTest {

    @BeforeEach
    fun installAStore() {
        SecretStore.storeOverride = mutableMapOf()
    }

    @AfterEach
    fun releaseTheStore() {
        SecretStore.storeOverride = null
    }

    @Test
    fun `a token round-trips under its host`() {
        ForgeTokens.set("github.com", "ghp_one")
        assertEquals("ghp_one", ForgeTokens.get("github.com"))
    }

    @Test
    fun `two hosts are two credentials, and neither can be sent to the other`() {
        // The whole reason the key is the host and not the provider: an internal GHE token must never be
        // presented to api.github.com because both say "GitHub".
        ForgeTokens.set("github.com", "ghp_public")
        ForgeTokens.set("github.acme.example", "ghp_internal")

        assertEquals("ghp_public", ForgeTokens.get("github.com"))
        assertEquals("ghp_internal", ForgeTokens.get("github.acme.example"))
    }

    @Test
    fun `the host is matched case-insensitively, as hostnames are`() {
        ForgeTokens.set("GitLab.Example.COM", "glpat_one")
        assertEquals("glpat_one", ForgeTokens.get("gitlab.example.com"))
        // The fully-qualified spelling of the same name is the same entry.
        assertEquals("glpat_one", ForgeTokens.get("gitlab.example.com."))
    }

    @Test
    fun `an unknown host simply has no token`() {
        assertNull(ForgeTokens.get("git.nowhere.example"))
    }

    @Test
    fun `a blank token clears the entry, so deleting needs no second control`() {
        ForgeTokens.set("github.com", "ghp_one")
        ForgeTokens.set("github.com", "   ")
        assertNull(ForgeTokens.get("github.com"))
    }

    @Test
    fun `clear forgets one host and leaves the others alone`() {
        ForgeTokens.set("github.com", "ghp_one")
        ForgeTokens.set("gitlab.com", "glpat_one")

        ForgeTokens.clear("github.com")

        assertNull(ForgeTokens.get("github.com"))
        assertEquals("glpat_one", ForgeTokens.get("gitlab.com"))
    }

    @Test
    fun `normalization is trimmed, lowercased and dot-free at the end`() {
        assertEquals("github.com", ForgeTokens.normalizeHost("  GitHub.COM.  "))
    }
}
