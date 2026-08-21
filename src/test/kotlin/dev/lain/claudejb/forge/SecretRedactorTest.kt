package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRedactorTest {

    private fun scrubbed(raw: String) = SecretRedactor.scrub(raw).text

    @Test
    fun `a token printed by the build does not survive into the prompt`() {
        val log = """
            Cloning repository...
            export GITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123
            export GITLAB_TOKEN=glpat-abcdefghijklmnopqrst
            aws key AKIAIOSFODNN7EXAMPLE
        """.trimIndent()

        val out = scrubbed(log)

        assertFalse(out.contains("ghp_abcdefghijklmnopqrstuvwxyz0123"))
        assertFalse(out.contains("glpat-abcdefghijklmnopqrst"))
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(out.contains("Cloning repository"), "everything that is not a secret is left alone")
    }

    @Test
    fun `a value is hidden but the name that labelled it stays, so the log still reads`() {
        val out = scrubbed("DATABASE_PASSWORD=hunter2000\nBUILD_ID=4711")

        assertTrue(out.contains("DATABASE_PASSWORD="), "which setting it was is not the secret")
        assertFalse(out.contains("hunter2000"))
        assertTrue(out.contains("BUILD_ID=4711"), "a plain value keeps its meaning")
    }

    @Test
    fun `a credential in a URL goes without taking the host with it`() {
        val out = scrubbed("fatal: could not read https://ada:s3cr3tvalue@git.example.com/x.git")

        assertFalse(out.contains("s3cr3tvalue"))
        assertTrue(out.contains("git.example.com"), "the host is what makes the error readable")
    }

    @Test
    fun `an authorization header and a private key are both taken whole`() {
        val out = scrubbed(
            "Authorization: Bearer abc.def.ghi\n" +
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKC\n-----END RSA PRIVATE KEY-----",
        )

        assertFalse(out.contains("abc.def.ghi"))
        assertFalse(out.contains("MIIEowIBAAKC"))
        assertTrue(out.contains("Authorization:"))
    }

    @Test
    fun `a JSON web token is recognised wherever it appears`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

        assertFalse(scrubbed("token is $jwt done").contains(jwt))
    }

    @Test
    fun `how much was hidden is counted, so the chat can say it rather than stay quiet`() {
        val once = SecretRedactor.scrub("PASSWORD=letmein")
        val none = SecretRedactor.scrub("Compiled 42 files in 3s")

        assertEquals(1, once.count)
        assertFalse(once.clean)
        assertEquals(0, none.count)
        assertTrue(none.clean)
        assertEquals("Compiled 42 files in 3s", none.text, "an ordinary log is passed through untouched")
    }

    @Test
    fun `a short harmless value is not mistaken for a secret`() {
        val out = scrubbed("retry_token=ok\nkeyboard=us")

        assertTrue(out.contains("keyboard=us"), "a word that merely contains 'key' is not a credential")
    }
}
