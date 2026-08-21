package dev.lain.claudejb.forge

import dev.lain.claudejb.settings.SecretStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_TOKEN), ForgeService.runs(github, "main"))
    }

    @Test
    fun `a detached head has no branch to ask about`() {
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_BRANCH), ForgeService.openPullRequests(github, ""))
        assertEquals(ForgeAnswer.Silent(ForgeSilence.NO_BRANCH), ForgeService.runs(github, "   "))
    }

    @Test
    fun `a host that is not a hostname is refused before a URL is built from it`() {
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
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST),
            ForgeService.runs(github.copy(host = "not a host"), "main"),
        )
    }
}
