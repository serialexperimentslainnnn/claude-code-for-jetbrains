package dev.lain.claudejb.forge

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * **Which forge is actually running on a host the URL does not identify.**
 *
 * `GitRemoteInfo` reads the provider off the remote's hostname, and it is right to refuse to guess: a
 * substring match says `mygithub.io` is GitHub, and a wrong guess sends a signed request to the wrong
 * service. But a self-hosted instance is very often under a name that says nothing — `git.corp.example`,
 * `code.internal` — and refusing to guess leaves those users with no cards at all, silently.
 *
 * The host does not have to say what it is: **its API does.** Each provider answers a path the other returns
 * 404 for, so one round trip settles it, and both answers double as a version:
 *
 *  - **GitHub / GHES** — `GET /api/v3/meta` carries `installed_version`, and *every* `/api/v3/` response
 *    carries the appliance version in `X-GitHub-Enterprise-Version`.
 *  - **GitLab** — `GET /api/v4/version` returns `{version, revision, enterprise}`.
 *
 * **A 401 is a positive identification, not a failure**, and that is the load-bearing detail: GitLab's
 * version endpoint requires a token, so an unauthenticated probe gets 401 — which only a host that *has*
 * that route can produce. GitHub answers 404 there. So "the path exists and refused me" and "the path is
 * not there" are the two outcomes being told apart, and neither needs the request to succeed.
 *
 * **Only ever called for a host the user has already stored a token for.** A probe is a network request to a
 * server named by the repository the user happens to have open; making one unbidden would mean the plugin
 * contacts hosts nobody asked it to. Storing a token for a host is that consent, and it is also the only
 * state in which the answer could be used for anything.
 *
 * Blocking; call off the EDT, like everything else in this package. The verdict is cached for the IDE's
 * lifetime — a host does not change which forge it runs — including the negative, so an unrecognised server
 * is probed once and not on every dashboard push.
 */
internal object ForgeProbe {

    private val LOG = logger<ForgeProbe>()

    /** Hosts already settled. A miss is stored as [UNKNOWN] so it is remembered as firmly as a hit. */
    private val verdicts = ConcurrentHashMap<String, String>()

    private const val UNKNOWN = "unknown"

    /**
     * What [host] is running, or null when it is neither or could not be reached.
     *
     * [token] is sent with both probes because it costs nothing and turns GitLab's 401 into a 200 whose body
     * also names the version — but the identification does not depend on it succeeding.
     */
    fun detect(host: String, token: String): ForgeProvider? {
        val cached = verdicts[host]
        if (cached != null) return ForgeProvider.entries.firstOrNull { it.name == cached }
        val found = probe(host, token)
        verdicts[host] = found?.name ?: UNKNOWN
        LOG.info("Forge probe: $host is ${found?.name ?: "neither GitHub nor GitLab, or unreachable"}")
        return found
    }

    /**
     * GitLab first, deliberately: its marker path is the cheaper of the two to be wrong about.
     *
     * A GitHub instance answers `/api/v4/version` with a 404, which this reads as "not GitLab" and moves on.
     * The reverse order would put a GHES-shaped request at a GitLab, which answers 404 just as harmlessly —
     * the order is a coin toss for correctness and a preference for the common self-hosted case.
     */
    private fun probe(host: String, token: String): ForgeProvider? {
        if (answersAt(host, "https://$host/api/v4/version", mapOf("PRIVATE-TOKEN" to token))) {
            return ForgeProvider.GITLAB
        }
        val github = mapOf(
            "Accept" to "application/vnd.github+json",
            // Required, and its absence is exactly the failure this probe would otherwise misread: GHES
            // rejects a request with no User-Agent with a 403, which is indistinguishable here from "the
            // path is not there" — so the probe would answer "not GitHub" about a GitHub.
            "User-Agent" to ForgeHttp.USER_AGENT,
            "Authorization" to "Bearer $token",
        )
        if (answersAt(host, "https://$host/api/v3/meta", github)) return ForgeProvider.GITHUB
        return null
    }

    /**
     * Whether [url] is a route this host actually serves.
     *
     * True for a success and for [ForgeSilence.UNAUTHORIZED] — the route exists and declined the credential,
     * which identifies the server just as well. False for everything else, including 403/404
     * ([ForgeSilence.NOT_VISIBLE]) and an unreachable host: none of those is evidence that the forge is here.
     */
    private fun answersAt(host: String, url: String, headers: Map<String, String>): Boolean =
        when (val answer = ForgeHttp.fetch(ForgeRequest(URI.create(url), headers))) {
            is ForgeAnswer.Known -> true
            is ForgeAnswer.Silent -> answer.reason == ForgeSilence.UNAUTHORIZED
        }.also { if (it) LOG.debug("Forge probe: $host answered at $url") }
}
