package dev.lain.claudejb.process

/**
 * Pure parsing helpers for the interactive `claude auth login` output streamed off a PTY (see [ClaudeLoginFlow]).
 *
 * The binary renders an Ink/React TUI full of ANSI escapes and cursor positioning; we only need three signals
 * out of it: the OAuth **authorize URL** (to open the browser ourselves), the **"paste code" prompt** (so we
 * know the binary is waiting for stdin), and a coarse **success/failure** read of the final output. Keeping this
 * logic pure (no process, no IO) makes it unit-testable in plain JVM.
 */
object LoginOutputParser {

    // CSI / OSC / two-char and single-char ESC sequences emitted by the Ink renderer. Broad on purpose — we
    // want clean text for substring/URL matching, not a faithful terminal emulation.
    private val ANSI = Regex("(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^]*(?:|\\\\)?|[()][0-9A-Za-z]|[=>78])")

    // The OAuth authorize URL. Restricted to URL-safe characters so it stops at the first whitespace/control the
    // renderer inserts around it. We match the authorize endpoint specifically to avoid grabbing a help link.
    private val AUTH_URL = Regex("https://[\\w.\\-]+/[\\w./\\-]*oauth/authorize\\?[\\w./?=&%+\\-~:]+")

    // Hints stored in normalized (alphanumeric-only, lower-case) form: the Ink renderer often lays words out
    // with cursor-move escapes instead of literal spaces, so after stripping ANSI the words run together
    // ("Paste" + "code" + "here" → "pastecodehere"). Normalizing both sides makes the match layout-agnostic.
    private val CODE_PROMPT_HINTS = listOf("pastecodehere", "pastethecode", "enterthecode", "enteryourcode")
    private val SUCCESS_HINTS = listOf("loginsuccessful", "loggedin", "successfully", "youreallset", "authenticated")
    private val FAILURE_HINTS = listOf("invalidcode", "loginfailed", "authenticationfailed", "didnotmatch", "expired", "error")

    /** Removes ANSI escapes so the remaining text can be matched/grepped. */
    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    /** Lower-cases and drops every non-alphanumeric char — collapses cursor-positioned layout into stable tokens. */
    private fun normalize(text: String): String = stripAnsi(text).lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The OAuth authorize URL the binary is sending the user to, or null if it hasn't appeared yet. */
    fun extractAuthUrl(text: String): String? = AUTH_URL.find(stripAnsi(text))?.value

    /** True once the binary is prompting for the authorization code on its TTY stdin. */
    fun isCodePrompt(text: String): Boolean {
        val t = normalize(text)
        return CODE_PROMPT_HINTS.any { it in t }
    }

    /** Coarse failure read of the final (already exited) output — used to override a 0 exit if the text says otherwise. */
    fun looksLikeFailure(text: String): Boolean {
        val t = normalize(text)
        return FAILURE_HINTS.any { it in t }
    }

    /**
     * A short, human-facing result line distilled from the final output. On success returns a confirmation; on
     * failure tries to surface the binary's own error wording, falling back to a generic retry message.
     */
    fun resultMessage(text: String, success: Boolean): String {
        val lines = stripAnsi(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (success) {
            return lines.lastOrNull { l -> SUCCESS_HINTS.any { it in normalize(l) } } ?: "You're signed in."
        }
        return lines.lastOrNull { l -> FAILURE_HINTS.any { it in normalize(l) } }
            ?: "Login failed. Please try again."
    }
}
