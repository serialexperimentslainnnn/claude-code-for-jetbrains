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

    private val esc = "\u001B"

    // A representative chunk of the live capture: cursor moves (esc[..G), the authorize URL emitted as one
    // contiguous write, and the code prompt whose words are laid out by column (no literal spaces between them).
    private val live =
        "$esc[2G$esc[36mOpening$esc[12Gbrowser$esc[20Gto sign in…$esc[0m\r\n\r\n" +
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
            "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback" +
            "&scope=user%3Ainference&code_challenge=StKwwTdqdASd8zkCF4PZXhzcR6-qQdeatQLqNZ6ggPU" +
            "&code_challenge_method=S256&state=Al24Qfn_vHWhm1SctZU013WFytPzD47q1TBIeX9T9T8\r\n\r\n" +
            "$esc[2GPaste$esc[8Gcode$esc[13Ghere$esc[18Gif$esc[21Gprompted$esc[30G> "

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
        assertNull(LoginOutputParser.extractAuthUrl("$esc[36mOpening browser to sign in…$esc[0m"))
    }

    @Test
    fun `recognises the cursor-positioned paste-code prompt despite missing spaces`() {
        assertTrue(LoginOutputParser.isCodePrompt(live))
        assertFalse(LoginOutputParser.isCodePrompt("$esc[36mOpening browser to sign in…$esc[0m"))
    }

    @Test
    fun `reads success and failure from the final output`() {
        assertFalse(LoginOutputParser.looksLikeFailure("$esc[32mLogin successful! You're all set.$esc[0m"))
        assertTrue(LoginOutputParser.looksLikeFailure("$esc[31mInvalid code, please try again.$esc[0m"))
        assertEquals("Login successful!", LoginOutputParser.resultMessage("Login successful!", success = true))
        assertEquals(
            "Invalid code, please try again.",
            LoginOutputParser.resultMessage("Invalid code, please try again.", success = false),
        )
    }

    // ── the frames the login TUI renders before exiting ──────────────────────────────────────────────────

    /** The frame the binary renders on success under a PTY, right before it exits 0. */
    private val successScreen =
        "$esc[2mLogged in as dev@example.com$esc[0m\r\n" +
            "$esc[32mLogin successful. Press $esc[1mEnter$esc[0m$esc[32m to continue…$esc[0m"

    @Test
    fun `surfaces the binary's own wording for a failed OAuth exchange`() {
        val screen = "$esc[31mOAuth error: invalid_grant$esc[0m\r\nPress Enter to retry."
        assertTrue(LoginOutputParser.looksLikeFailure(screen))
        assertEquals("OAuth error: invalid_grant", LoginOutputParser.resultMessage(screen, success = false))
    }

    @Test
    fun `result message drops the terminal-only keypress instruction`() {
        assertEquals(
            "Login successful.",
            LoginOutputParser.resultMessage(successScreen, success = true),
        )
    }

    @Test
    fun `redactSecrets strips ANSI and masks tokens`() {
        val token = "sk-ant-oat01-" + "c".repeat(40)
        val out = LoginOutputParser.redactSecrets("$esc[32mtoken: $token$esc[0m")
        assertFalse(out.contains(token))
        assertFalse(out.contains(esc))
        assertTrue(out.contains("sk-ant-…"))
    }

    @Test
    fun `result falls back to generic wording when no marker line is present`() {
        assertEquals("You're signed in.", LoginOutputParser.resultMessage("(some unrelated frame)", success = true))
        assertEquals("Login failed. Please try again.", LoginOutputParser.resultMessage("(noise)", success = false))
    }

    // ── setup-token ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `extracts the setup token, taking the LAST match past placeholder text`() {
        val token = "sk-ant-oat01-" + "a".repeat(40)
        val out = "$esc[2mExample: sk-ant-oat01-xxxxxxxxxxxxxxxxxxxx$esc[0m\nYour token:\n$token\n"
        assertEquals(token, LoginOutputParser.extractSetupToken(out))
        assertNull(LoginOutputParser.extractSetupToken("no token here"))
        // Too short to be real — placeholder-sized fragments must not be captured as credentials.
        assertNull(LoginOutputParser.extractSetupToken("sk-ant-short"))
    }

    @Test
    fun `result messages never carry a token`() {
        val token = "sk-ant-oat01-" + "b".repeat(40)
        val msg = LoginOutputParser.resultMessage("Login successful! Token: $token", success = true)
        assertFalse(msg.contains(token), "a secret leaked into a user-facing message")
    }
}
