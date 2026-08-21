package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpHeaders

class ForgeHttpTest {

    @Test
    fun `a success has no silence and its body is read`() {
        assertNull(ForgeHttp.silenceFor(200, NONE))
        assertNull(ForgeHttp.silenceFor(204, NONE))
        assertNull(ForgeHttp.silenceFor(299, NONE))
    }

    @Test
    fun `401 is a token the host rejected`() {
        assertEquals(ForgeSilence.UNAUTHORIZED, ForgeHttp.silenceFor(401, NONE))
    }

    @Test
    fun `403 and 404 are one answer, because both providers make them one answer`() {
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(403, NONE))
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(404, NONE))
    }

    @Test
    fun `an exhausted GitHub quota is a rate limit and not a permission problem`() {
        assertEquals(ForgeSilence.RATE_LIMITED, ForgeHttp.silenceFor(403, headers("x-ratelimit-remaining" to "0")))
    }

    @Test
    fun `the quota header is matched without regard to case`() {
        assertEquals(ForgeSilence.RATE_LIMITED, ForgeHttp.silenceFor(403, headers("X-RateLimit-Remaining" to "0")))
    }

    @Test
    fun `GitLab spells the quota header its own way and is read too`() {
        assertEquals(ForgeSilence.RATE_LIMITED, ForgeHttp.silenceFor(403, headers("RateLimit-Remaining" to "0")))
    }

    @Test
    fun `429 is a rate limit whatever the headers say`() {
        assertEquals(ForgeSilence.RATE_LIMITED, ForgeHttp.silenceFor(429, NONE))
        assertEquals(ForgeSilence.RATE_LIMITED, ForgeHttp.silenceFor(429, headers("x-ratelimit-remaining" to "99")))
    }

    @Test
    fun `a 403 with quota to spare stays a genuine denial`() {
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(403, headers("x-ratelimit-remaining" to "57")))
    }

    @Test
    fun `a quota header that is not a number decides nothing`() {
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(403, headers("x-ratelimit-remaining" to "lots")))
    }

    @Test
    fun `an exhausted quota never turns a 404 into a rate limit`() {
        assertEquals(ForgeSilence.NOT_VISIBLE, ForgeHttp.silenceFor(404, headers("x-ratelimit-remaining" to "0")))
    }

    @Test
    fun `a redirect is not followed, so it lands as unreachable rather than as a token handed elsewhere`() {
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(301, NONE))
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(302, NONE))
    }

    @Test
    fun `a server error is unreachable, not a card`() {
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(500, NONE))
        assertEquals(ForgeSilence.UNREACHABLE, ForgeHttp.silenceFor(502, NONE))
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

    @Test
    fun `the User-Agent names this plugin and impersonates nothing`() {
        assertTrue(ForgeHttp.USER_AGENT.startsWith("ClaudeCodeNative/")) { ForgeHttp.USER_AGENT }
        assertTrue(ForgeHttp.PLUGIN_VERSION in ForgeHttp.USER_AGENT) { ForgeHttp.USER_AGENT }
        assertFalse("Mozilla" in ForgeHttp.USER_AGENT) {
            "A browser User-Agent buys nothing GitHub asks for, ages into a fingerprint, and an invalid one " +
                "earns a 403 this client would report as a permission problem. Name the plugin instead."
        }
    }

    @Test
    fun `the advertised version is the one the build ships`() {
        val declared = DECLARED_VERSION.find(File("build.gradle.kts").readText())?.groupValues?.get(1)

        assertEquals(
            declared,
            ForgeHttp.PLUGIN_VERSION,
            "ForgeHttp.PLUGIN_VERSION drifted from `version` in build.gradle.kts, so every forge request now " +
                "announces a version this plugin is not.",
        )
    }

    private companion object {

        val DECLARED_VERSION = Regex("""^version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)

        fun headers(vararg named: Pair<String, String>): HttpHeaders =
            HttpHeaders.of(named.associate { (name, value) -> name to listOf(value) }) { _, _ -> true }

        val NONE: HttpHeaders = headers()
    }
}
