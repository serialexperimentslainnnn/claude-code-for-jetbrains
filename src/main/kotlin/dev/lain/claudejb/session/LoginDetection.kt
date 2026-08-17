package dev.lain.claudejb.session

/**
 * What an authentication failure from the binary means for the caller.
 *
 * The distinction is the whole point: an expired access token and a missing identity read almost alike in the
 * error text and call for opposite responses from the GUI.
 */
enum class AuthFailure {
    /** Not an authentication failure: an unrelated error, or a billing/quota one. */
    NONE,

    /**
     * The access token died mid-session **and a renewal can bring it back** — so no identity is missing and
     * the sign-in card would be wrong. Only [LoginDetection.resolve] can answer this, because renewability is
     * a fact about the credential safe and not about the text; [LoginDetection.classify] answers on the text
     * alone and its `EXPIRED` means no more than "the wording is an access-token expiry".
     */
    EXPIRED,

    /** There is no usable identity: the user must sign in. */
    NO_IDENTITY,
}

/**
 * Pure classifier over an error text from the binary (a failed `result`, or an `auth_status` error): is this
 * an authentication failure, and if so which kind. When there is no usable identity the session offers an
 * interactive sign-in outside itself, because the OAuth flow cannot run inside the TTY-less stream-json
 * session.
 *
 * Text is all [classify] decides on. **Whether a refresh token is actually available is never guessed here** —
 * that is the credential safe's knowledge, so [resolve] takes it as a parameter and no code path in this file
 * goes looking for it. No IDE type, no process, no filesystem, no clock: pure, so it unit-tests on a plain JVM.
 *
 * Order of decision:
 *  1. Billing and quota phrasing wins outright → [AuthFailure.NONE]. "Credit balance too low" is not a login
 *     problem however much auth vocabulary surrounds it.
 *  2. No login phrasing at all → [AuthFailure.NONE].
 *  3. Within login phrasing, a mention of the refresh token → [AuthFailure.NO_IDENTITY], whatever else the
 *     text says. A dead refresh token is not self-healing: nothing can mint a token from it.
 *  4. Otherwise an access-token expiry phrase → [AuthFailure.EXPIRED].
 *  5. Anything else that reads as auth → [AuthFailure.NO_IDENTITY], the conservative default: one sign-in
 *     card too many is a nuisance, a swallowed authentication failure is a session that never works again.
 *
 * [EXPIRY_PHRASES] are only ever consulted **inside** [LOGIN_HINTS], so sorting a text into a kind can never
 * take it out of the set: what reads as an authentication failure stays one, whichever kind it turns out to be.
 *
 * **[resolve] is what a caller acts on**, and the reason is [AuthFailure.EXPIRED]: on the text alone it means
 * only that the wording is an access-token expiry, and whether that heals itself depends on a refresh token
 * this file cannot see. Deciding the sign-in card from [classify] shows no card to a user whose identity has
 * ended.
 *
 * It reads text and returns an enum: it never returns, embeds or logs the text it was handed, so no token or
 * token fragment can ride out of here.
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

    /**
     * Phrasing the binary uses for an ACCESS token that ran out — the transient, self-healing case. Every
     * entry is anchored on the access token, so a generic "expired" belonging to a plan, a trial or a link
     * cannot reach [AuthFailure.EXPIRED].
     */
    private val EXPIRY_PHRASES = listOf(
        "oauth access token has expired",
        "access token has expired",
        "access token is expired",
        // The same event said shorter. The OAuth token IS the access token here — it is what rides to the
        // binary as `CLAUDE_CODE_OAUTH_TOKEN` — and the refresh token is already excluded above, so this
        // cannot swallow the one expiry that really does end the identity.
        "oauth token has expired",
        "oauth token is expired",
    )

    /**
     * What an expiry phrase must NOT be about. A refresh token's own expiry ends the identity — it is the
     * thing renewal spends — so it is a sign-in, not a self-healing blip, and it must not be quietly
     * downgraded to something the caller ignores.
     */
    private const val REFRESH_TOKEN = "refresh token"

    /** Which kind of authentication failure [text] describes. Null or blank → [AuthFailure.NONE]. */
    fun classify(text: String?): AuthFailure {
        val t = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return AuthFailure.NONE
        if (EXCLUSIONS.any { it in t }) return AuthFailure.NONE
        if (LOGIN_HINTS.none { it in t }) return AuthFailure.NONE
        if (REFRESH_TOKEN in t) return AuthFailure.NO_IDENTITY
        return if (EXPIRY_PHRASES.any { it in t }) AuthFailure.EXPIRED else AuthFailure.NO_IDENTITY
    }

    /**
     * What the GUI must actually do about [text] — the answer every caller wants, and the only one that may
     * decide whether to raise the sign-in card.
     *
     * [classify] reads wording; this adds the one fact wording cannot carry. An access-token expiry heals
     * itself only while a renewal is possible, and that lives in the credential safe: with a live refresh
     * token the session continues on its own ([AuthFailure.EXPIRED]), without one the identity is over and the
     * user must sign in ([AuthFailure.NO_IDENTITY]). Deciding this from the text alone would swallow exactly
     * the failure that ends a session, because the same sentence describes both.
     *
     * @param renewable asked ONLY for text that reads as an access-token expiry, so an ordinary error costs no
     *   look at the safe. It must answer whether a renewal *would* succeed and must renew nothing itself.
     */
    fun resolve(text: String?, renewable: () -> Boolean): AuthFailure {
        val failure = classify(text)
        if (failure != AuthFailure.EXPIRED) return failure
        return if (renewable()) AuthFailure.EXPIRED else AuthFailure.NO_IDENTITY
    }
}
