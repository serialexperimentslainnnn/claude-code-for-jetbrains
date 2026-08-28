package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefNavigationGuardTest {

    private val page = "http://claude-code.localhost/index.html"

    private val loopback = "http://127.0.0.1:53421/index.html"

    private fun allowed(url: String?) = isOwnPageUrl(url, page, loopback)

    @Test
    fun `the pages the host loads itself are allowed`() {
        listOf(
            page,
            "$page?v=2",
            loopback,
            "$loopback?token=abc",
            "about:blank",
            "",
            null,
        ).forEach { assertTrue(allowed(it), "must not cancel our own page: $it") }
    }

    @Test
    fun `anything else is cancelled — navigation is the one egress the CSP cannot close`() {
        listOf(
            "https://attacker.example/?d=stolen",
            "http://attacker.example/",
            "https://claude-code.localhost.attacker.example/",
            "http://127.0.0.1:9999/other",
            "file:///etc/passwd",
            "data:text/html,<script>alert(1)</script>",
            "javascript:fetch('https://attacker.example')",
            "chrome://settings",
            "devtools://devtools/bundled/inspector.html",
        ).forEach { assertFalse(allowed(it), "must be cancelled: $it") }
    }

    @Test
    fun `with no loopback bound only the scheme page is allowed`() {
        assertTrue(isOwnPageUrl(page, page, null))
        assertTrue(isOwnPageUrl(page, page, ""))
        assertFalse(isOwnPageUrl(loopback, page, null))
        assertFalse(isOwnPageUrl("https://attacker.example", page, null))
    }
}
