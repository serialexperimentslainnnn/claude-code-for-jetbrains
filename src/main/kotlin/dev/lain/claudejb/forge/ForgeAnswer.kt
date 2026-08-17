package dev.lain.claudejb.forge

/**
 * What this package answers: either the thing that was asked for, or [Silent] — *we have nothing to show*.
 *
 * **Why this is a type and not a nullable return.** There are seven distinct ways to end up with no pull
 * requests and no run, and every one of them has to look identical on screen: no token for the host, a host
 * we do not speak to, a network that is not there, a repository the token cannot see, a body we could not
 * read. A `null` return would have collapsed all of those into one value that also has to mean "the API
 * answered and there genuinely are no open pull requests" — and those two are not the same card. `Known`
 * with an empty list draws an empty card; [Silent] draws nothing at all.
 *
 * **[Silent] is for the log, never for the user.** The card must not say "configure me": the user did not ask
 * for a forge integration, and an IDE that nags about a feature nobody enabled is worse than one that stays
 * quiet. The reason exists so a support question can be answered from `idea.log`, and the UI's only correct
 * reaction to [Silent] is to draw no card.
 */
sealed interface ForgeAnswer<out T> {

    /** The API answered and this is what it said. An empty collection here is a real, drawable answer. */
    data class Known<out T>(val value: T) : ForgeAnswer<T>

    /** No answer, for [reason]. The UI draws nothing; the reason goes to the log. */
    data class Silent(val reason: ForgeSilence) : ForgeAnswer<Nothing>
}

/**
 * Why there is nothing to show. Diagnostic only — see [ForgeAnswer.Silent].
 *
 * Each value is a *different cause with a different fix*, which is the whole reason they are not one value:
 * `NO_TOKEN` is answered by storing a token, `UNAUTHORIZED` by storing a valid one, `NOT_VISIBLE` by widening
 * its scopes, `UNREACHABLE` by looking at the network. Folding them together would make the log say
 * "something went wrong", which is what it said before.
 */
enum class ForgeSilence {

    /** Detached HEAD, an unborn branch, or no repository. There is no branch to ask a question about. */
    NO_BRANCH,

    /** No access token is stored for this host. The ordinary state, and not an error. */
    NO_TOKEN,

    /** The host is not a shape we will build a URL from — see `isUsableHost`. */
    UNSUPPORTED_HOST,

    /** 401. The token exists and the host rejected it (revoked, expired, or for a different host). */
    UNAUTHORIZED,

    /** 403 or 404. Either the repository does not exist or the token's scopes do not reach it — the two are
     *  indistinguishable by design on both providers, which is what stops a token being used to enumerate
     *  private repositories. */
    NOT_VISIBLE,

    /** No usable HTTP exchange: offline, DNS, TLS, timeout, a redirect we refuse to follow, or a 5xx. */
    UNREACHABLE,

    /** The response exceeded [ForgeHttp.MAX_RESPONSE_BYTES] and was abandoned unread. */
    OVERSIZED,

    /** A 2xx whose body did not decode. A proxy's error page and a genuine API change both land here. */
    MALFORMED,

    /** Asked on the EDT. A programming error, refused rather than served, so it cannot freeze the IDE. */
    ON_EDT,
}
