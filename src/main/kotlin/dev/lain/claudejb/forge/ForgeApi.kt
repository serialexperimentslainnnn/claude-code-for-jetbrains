package dev.lain.claudejb.forge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One HTTP request, ready to send.
 *
 * **This class exists to not be a `data class`, and that is its entire reason for being.** [headers] carries
 * the access token — `Authorization: Bearer …` on GitHub, `PRIVATE-TOKEN: …` on GitLab. A generated
 * `toString()` prints every property, so the token would ride into the first log line, exception message or
 * debugger label that ever interpolated one of these, which is exactly how a credential escapes without
 * anybody writing a line that logs it. [toString] names the URI and nothing else, and `ForgeSecrecyTest`
 * fails if that stops being true.
 */
internal class ForgeRequest(val uri: URI, val headers: Map<String, String>) {

    /** The URI only. The headers hold the token — see the class KDoc; do not "improve" this. */
    override fun toString(): String = "ForgeRequest(uri=$uri)"
}

/**
 * What a provider has to be able to do: name the two URLs, sign them, and read the two replies.
 *
 * Split into *build* and *parse* on purpose. Everything either half does is a pure function of its inputs, so
 * both are unit-testable over recorded JSON with no network anywhere near the suite; the only impure step in
 * the package is [ForgeHttp.fetch], which sits between them and knows nothing about either provider.
 */
internal interface ForgeApi {

    /** Open pull/merge requests whose SOURCE branch is [branch]. */
    fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest

    /** The single most recent CI run of [branch]. */
    fun latestRun(repo: ForgeRepo, branch: String, token: String): ForgeRequest

    /** Decodes the reply to [pullRequests]. Never throws: a body it cannot read is `Silent(MALFORMED)`. */
    fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>>

    /** Decodes the reply to [latestRun]. `Known(null)` means "asked, and there is no run" — see [ForgeAnswer]. */
    fun parseLatestRun(body: String): ForgeAnswer<ForgeRun?>
}

/** The provider registry: one row per [ForgeProvider], exhaustive by construction. */
internal fun apiFor(provider: ForgeProvider): ForgeApi = when (provider) {
    ForgeProvider.GITHUB -> GitHubApi
    ForgeProvider.GITLAB -> GitLabApi
}

/**
 * The package's decoder. `ignoreUnknownKeys` for the same reason `ProtocolJson` has it — both APIs add fields
 * between releases and a new one must never turn a working card into an error.
 *
 * Deliberately NOT lenient, unlike the protocol's `ClaudeJson`. That instance is tolerant because it reads a
 * local binary's own output; this one reads whatever answered on port 443, which may be a captive portal or a
 * corporate proxy. Leniency there buys nothing and costs the ability to tell a real payload from a plausible
 * one, so a body that is not strict JSON is `Silent(MALFORMED)` and draws no card.
 */
internal val ForgeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Decodes [body] with [serializer] and maps it to the model, or answers `Silent(MALFORMED)`.
 *
 * The catch is deliberately total: a truncated body, a field of the wrong type and an HTML error page served
 * with a 200 are all the same event to a card that should simply not draw.
 *
 * The serializer is passed explicitly rather than reified from a type parameter. That keeps the wire shapes
 * `private` to the file that owns them — a reified call would materialise them at this function's own site —
 * and it keeps the failure a value rather than an exception, which is the whole contract of this package.
 */
internal fun <W, T> decodeForge(body: String, serializer: KSerializer<W>, map: (W) -> T): ForgeAnswer<T> =
    runCatching { map(ForgeJson.decodeFromString(serializer, body)) }
        .fold(
            onSuccess = { mapped -> ForgeAnswer.Known(mapped) },
            onFailure = { ForgeAnswer.Silent(ForgeSilence.MALFORMED) },
        )

/**
 * True when [host] can safely be pasted into a URL as a host.
 *
 * **This is an injection gate, not a validity check.** The host arrives from a parsed `origin` remote, i.e.
 * from a file inside a repository the user may merely have cloned. A value like
 * `github.com/evil@attacker.test` or one carrying `?`, `#`, whitespace or a userinfo `@` would re-point the
 * request — and the request carries an access token in a header, so a re-pointed request is a token handed to
 * whoever wrote that remote. Only a plain hostname (with an optional port) gets through; anything else is
 * `UNSUPPORTED_HOST` and the card stays empty.
 */
internal fun isUsableHost(host: String): Boolean =
    host.length <= MAX_HOST_LENGTH && HOSTNAME.matches(host)

/** DNS's own ceiling. Past it there is nothing to resolve, so this rejects only what could never work. */
private const val MAX_HOST_LENGTH = 253

private val HOSTNAME =
    Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*(?::\d{1,5})?""")

/**
 * One path segment, percent-encoded — including `/`, which becomes `%2F`.
 *
 * Both uses need that. GitLab identifies a project by its URL-encoded full path, so `platform/backend` is
 * literally required to arrive as `platform%2Fbackend`. GitHub's owner and repo cannot contain a slash, and
 * encoding them anyway is what stops an owner of `../../` from walking off `/repos/…` onto another endpoint
 * with the token attached.
 *
 * `URLEncoder` is form encoding, which spells a space `+`; in a path that is a literal plus sign, so it is
 * rewritten to `%20`.
 */
internal fun pathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

/**
 * One query-string value, form-encoded.
 *
 * `+` for a space is correct here and must not be "fixed": this is exactly the encoding a query expects.
 * Branch names routinely contain `/` (`feature/release_5.5.0`) and may contain `#` and `?`, each of which
 * would end the query early and silently change the question being asked.
 */
internal fun queryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
