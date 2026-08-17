package dev.lain.claudejb.forge

import dev.lain.claudejb.settings.SecretStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The facade's gates — the four questions asked before anything is sent.
 *
 * **This class opens no socket and that is a property of the code, not of the fixtures.** A store is
 * installed and left EMPTY, so `ForgeTokens.get` answers null and the token gate returns before a request is
 * ever built. If a refactor moved the token lookup after the transport, these tests would start reaching the
 * network — which is why the last one asserts the ordering directly rather than trusting it.
 */
class ForgeServiceTest {

    private val github = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")

    @BeforeEach
    fun installAnEmptyStore() {
        SecretStore.storeOverride = mutableMapOf()
    }

    @AfterEach
    fun releaseTheStore() {
        SecretStore.storeOverride = null
    }

    @Test
    fun `no token for the host is a silence, not an error and not a prompt`() {
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.NO_TOKEN),
            ForgeService.openPullRequests(github, "main"),
        )
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_TOKEN), ForgeService.lastRun(github, "main"))
    }

    @Test
    fun `a detached head has no branch to ask about`() {
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_BRANCH), ForgeService.openPullRequests(github, ""))
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_BRANCH), ForgeService.lastRun(github, "   "))
    }

    @Test
    fun `a host that is not a hostname is refused before a URL is built from it`() {
        // The host arrives from a remote URL inside a repository the user may merely have cloned, and the
        // request it would build carries an access token in a header. A re-pointed request is a token handed
        // to whoever wrote that remote.
        listOf(
            "github.com/evil@attacker.test",
            "github.com:8443@attacker.test",
            "github.com?x=",
            "attacker test",
            "",
        ).forEach { host ->
            assertEquals(
                ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST),
                ForgeService.openPullRequests(github.copy(host = host), "main"),
            ) { host }
        }
    }

    @Test
    fun `an ordinary host with a port is accepted`() {
        assertTrue(isUsableHost("git.acme.example"))
        assertTrue(isUsableHost("git.acme.example:8443"))
        assertTrue(isUsableHost("localhost"))
        assertFalse(isUsableHost("git.acme.example/x"))
        assertFalse(isUsableHost("git.acme.example#"))
    }

    @Test
    fun `the host gate runs before the token gate, which is what keeps a bad host off the network`() {
        // Both gates would produce "no card"; the ORDER is the thing that matters, because only one of them
        // runs before a URL exists. Asserted through the reason, which is the only observable difference.
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST),
            ForgeService.lastRun(github.copy(host = "not a host"), "main"),
        )
    }
}
