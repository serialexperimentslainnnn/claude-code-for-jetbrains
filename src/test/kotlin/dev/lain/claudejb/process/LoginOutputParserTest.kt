package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [LoginOutputParser] against the real, ANSI-laden output that `claude auth login` streams over a PTY
 * (captured from the binary): we must reliably pull the OAuth URL out of the Ink TUI noise, recognise the
 * "paste code" prompt even when the renderer positions words with cursor moves instead of spaces, and read
 * success/failure from the final frame.
 */
class LoginOutputParserTest {

    private val ESC = "\u001B"

    // A representative chunk of the live capture: cursor moves (ESC[..G), the authorize URL emitted as one
    // contiguous write, and the code prompt whose words are laid out by column (no literal spaces between them).
    private val live =
        "${ESC}[2G${ESC}[36mOpening${ESC}[12Gbrowser${ESC}[20Gto sign in…${ESC}[0m\r\n\r\n" +
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
            "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback" +
            "&scope=user%3Ainference&code_challenge=StKwwTdqdASd8zkCF4PZXhzcR6-qQdeatQLqNZ6ggPU" +
            "&code_challenge_method=S256&state=Al24Qfn_vHWhm1SctZU013WFytPzD47q1TBIeX9T9T8\r\n\r\n" +
            "${ESC}[2GPaste${ESC}[8Gcode${ESC}[13Ghere${ESC}[18Gif${ESC}[21Gprompted${ESC}[30G> "

    @Test
    fun `extracts the full authorize URL out of the ANSI noise`() {
        val url = LoginOutputParser.extractAuthUrl(live)
        assertEquals(
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback" +
                "&scope=user%3Ainference&code_challenge=StKwwTdqdASd8zkCF4PZXhzcR6-qQdeatQLqNZ6ggPU" +
                "&code_challenge_method=S256&state=Al24Qfn_vHWhm1SctZU013WFytPzD47q1TBIeX9T9T8",
            url,
        )
    }

    @Test
    fun `no URL yet returns null`() {
        assertNull(LoginOutputParser.extractAuthUrl("${ESC}[36mOpening browser to sign in…${ESC}[0m"))
    }

    @Test
    fun `recognises the cursor-positioned paste-code prompt despite missing spaces`() {
        assertTrue(LoginOutputParser.isCodePrompt(live))
        assertFalse(LoginOutputParser.isCodePrompt("${ESC}[36mOpening browser to sign in…${ESC}[0m"))
    }

    @Test
    fun `reads success and failure from the final output`() {
        assertFalse(LoginOutputParser.looksLikeFailure("${ESC}[32mLogin successful! You're all set.${ESC}[0m"))
        assertTrue(LoginOutputParser.looksLikeFailure("${ESC}[31mInvalid code, please try again.${ESC}[0m"))
        assertEquals("Login successful!", LoginOutputParser.resultMessage("Login successful!", success = true))
        assertEquals(
            "Invalid code, please try again.",
            LoginOutputParser.resultMessage("Invalid code, please try again.", success = false),
        )
    }

    @Test
    fun `result falls back to generic wording when no marker line is present`() {
        assertEquals("You're signed in.", LoginOutputParser.resultMessage("(some unrelated frame)", success = true))
        assertEquals("Login failed. Please try again.", LoginOutputParser.resultMessage("(noise)", success = false))
    }
}
