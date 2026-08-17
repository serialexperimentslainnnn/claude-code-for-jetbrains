package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The status-code triage and the transport's two refusals. **Nothing here opens a socket**: the plaintext
 * refusal happens before the client is ever touched, and the triage is a pure function.
 *
 * The reason these are worth pinning is that every branch below produces the SAME thing on screen — no card —
 * so a wrong mapping is invisible in use and only ever shows up as a support question nobody can answer from
 * the log.
 */
class ForgeHttpTest {

    @Test
    fun `a success has no silence and its body is read`() {
        assertNull(ForgeHttp.silenceFor(200))
        assertNull(ForgeHttp.silenceFor(204))
        assertNull(ForgeHttp.silenceFor(299))
    }

    @Test
    fun `401 is a token the host rejected`() {
        assertEquals(ForgeSilence.UNAUTHORIZED, ForgeHttp.silenceFor(401))
    }

    @Test
    fun `403 and 404 are one answer, because both providers make them one answer`() {
        // A repository the token cannot see is reported as absent, deliberately, so a token cannot be used to
        // enumerate private repositories. Inventing a distinction here would only mislead the log.
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(403))
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(404))
    }

    @Test
    fun `a redirect is not followed, so it lands as unreachable rather than as a token handed elsewhere`() {
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(301))
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(302))
    }

    @Test
    fun `a server error and a rate limit are unreachable, not a card`() {
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(429))
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(500))
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(502))
    }

    @Test
    fun `a plaintext URL is refused before anything is sent`() {
        // The only function in the package that can put a token on a wire asserts the scheme itself. This
        // returns without a socket, which is also why the test can name a host that does not exist.
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST),
            ForgeHttp.fetch(ForgeRequest(URI.create("http://never.invalid/x"), mapOf("Authorization" to "Bearer s"))),
        )
    }

    @Test
    fun `the response bound is a real ceiling, not a comment`() {
        // Pinned because the failure mode of losing it is unbounded heap on a background thread, which does
        // not look like a forge bug when it happens.
        assertTrue(ForgeHttp.MAX_RESPONSE_BYTES in 1..(4 * 1024 * 1024))
    }
}
