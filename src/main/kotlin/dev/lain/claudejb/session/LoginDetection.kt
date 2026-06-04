package dev.lain.claudejb.session

/**
 * Pure heuristic that decides whether an error text from the binary (a failed `result`, or an `auth_status`
 * error) means the user needs to **log in** — as opposed to an unrelated failure (a tool error, a network
 * blip, a billing/quota issue). When true, the session offers to open an interactive terminal for `claude
 * login`, because the OAuth flow cannot run inside the TTY-less stream-json session.
 *
 * Deliberately conservative: matches auth/login phrasing the binary uses, and explicitly excludes
 * billing/quota wording ("credit balance", "rate limit") which is NOT a login problem. Pure → unit-testable.
 */
object LoginDetection {

    // Phrases that indicate an authentication / login problem (lower-cased substring match).
    private val LOGIN_HINTS = listOf(
        "/login",
        "please log in",
        "please login",
        "not logged in",
        "not authenticated",
        "authentication failed",
        "authentication error",
        "invalid api key",
        "unauthorized",
        "oauth",
        "log in to claude",
        "run `claude login`",
    )

    // Phrases that look auth-adjacent but are NOT a login problem — never prompt for these.
    private val EXCLUSIONS = listOf(
        "credit balance",
        "rate limit",
        "quota",
        "overage",
        "usage limit",
    )

    /** True when [text] reads like a login/auth failure and not a billing/quota one. Null/blank → false. */
    fun needsLogin(text: String?): Boolean {
        val t = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (EXCLUSIONS.any { it in t }) return false
        return LOGIN_HINTS.any { it in t }
    }
}
