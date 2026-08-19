package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

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
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST),
            ForgeHttp.fetch(ForgeRequest(URI.create("http://never.invalid/x"), mapOf("Authorization" to "Bearer s"))),
        )
    }

    @Test
    fun `the response bound is a real ceiling, not a comment`() {
        assertTrue(ForgeHttp.MAX_RESPONSE_BYTES in 1..(4 * 1024 * 1024))
    }
}
