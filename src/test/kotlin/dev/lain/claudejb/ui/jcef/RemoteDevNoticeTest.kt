package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class RemoteDevNoticeTest {

    @Test
    fun `command forwards the same port on both sides`() {
        assertTrue(RemoteDevNotice.sshCommand(6942).contains("-L 127.0.0.1:6942:127.0.0.1:6942"))
        assertTrue(RemoteDevNotice.sshCommand(1024).contains("-L 127.0.0.1:1024:127.0.0.1:1024"))
    }

    @Test
    fun `command leaves the destination as a placeholder`() {
        val cmd = RemoteDevNotice.sshCommand(7000)
        assertTrue(cmd.startsWith("ssh "), cmd)
        assertTrue(cmd.contains("<user>@<remote-host>"), cmd)
    }

    @Test
    fun `page names the port and carries the command`() {
        val html = RemoteDevNotice.html(6942)
        assertTrue(html.contains("6942"), "the port is what the user needs")
        assertTrue(html.contains("-L 127.0.0.1:6942:127.0.0.1:6942"), "the command is what fixes it")
        assertTrue(html.contains("&lt;user&gt;@&lt;remote-host&gt;"), "the placeholder is markup-escaped")
    }

    @Test
    fun `page executes nothing`() {
        val html = RemoteDevNotice.html(6942).lowercase()
        assertFalse(html.contains("<script"), "no script element")
        assertFalse(html.contains("javascript:"), "no script URL")
        assertFalse(Regex("""\son[a-z]+\s*=""").containsMatchIn(html), "no event-handler attribute")
        assertFalse(html.contains("unsafe-inline"), "no inline relaxation, style-src-attr included")
        assertFalse(html.contains("unsafe-eval"), "no eval relaxation")
        assertFalse(html.contains("style="), "no style attribute, since none may be allowed")
    }

    @Test
    fun `page loads nothing remote`() {
        val html = RemoteDevNotice.html(6942)
        assertFalse(html.contains("http://"), "no remote URL")
        assertFalse(html.contains("https://"), "no remote URL")
        assertFalse(html.contains("@import"), "no imported stylesheet")
        assertFalse(html.contains("url("), "no referenced resource")
    }

    @Test
    fun `csp denies everything by default`() {
        assertTrue(RemoteDevNotice.html(6942).contains("default-src 'none'"))
    }

    @Test
    fun `the style block is hash-pinned to what is emitted`() {
        val html = RemoteDevNotice.html(6942)
        val style = html.substringAfter("<style>").substringBefore("</style>")
        assertTrue(style.isNotBlank(), "there is a style block to pin")
        val digest = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(style.toByteArray(StandardCharsets.UTF_8)),
        )
        assertTrue(html.contains("style-src 'sha256-$digest'"), "the CSP pins the emitted block")
    }

    @Test
    fun `page carries nothing but the port`() {
        for (port in listOf(6942, 1024)) {
            val html = RemoteDevNotice.html(port)
            assertFalse(html.contains("/home"), "no POSIX home path")
            assertFalse(html.contains("/Users"), "no macOS home path")
            assertFalse(html.contains("\\Users"), "no Windows home path")
            assertFalse(html.contains("C:\\"), "no Windows drive path")
            assertFalse(html.contains(".claude"), "no agent home directory")
            assertFalse(html.contains("claude-code.localhost"), "no internal origin")
            assertFalse(html.contains("?k="), "no query string, so no one-shot token")
            assertFalse(html.contains(System.getProperty("user.home")), "not this machine's home")
            assertFalse(html.contains(System.getProperty("user.dir")), "not the working directory")
            assertEquals(
                0,
                Regex("""[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+""").findAll(html).count(),
                "the only address is the escaped <user>@<remote-host> placeholder",
            )
            assertFalse(
                Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
                    .containsMatchIn(html),
                "no session id or other uuid",
            )
        }
    }

    @Test
    fun `page is a legible english document`() {
        val html = RemoteDevNotice.html(6942)
        assertTrue(html.contains("<html lang=\"en\">"), "the language is declared")
        assertTrue(html.contains("<title>"), "the document is titled")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "exactly one first-level heading")
    }
}
