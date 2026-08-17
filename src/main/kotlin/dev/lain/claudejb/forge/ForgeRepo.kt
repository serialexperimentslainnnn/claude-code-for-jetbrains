package dev.lain.claudejb.forge

/**
 * Which hosting platform a repository's `origin` points at.
 *
 * Only the two the user asked for. A third one is a new entry here plus a new [ForgeApi] implementation and a
 * row in `apiFor` — deliberately three edits in three places rather than a flag, because each provider brings
 * its own endpoints, its own auth header and its own status vocabulary, and pretending otherwise is how a
 * "generic" client ends up with a `when` in every method.
 */
enum class ForgeProvider { GITHUB, GITLAB }

/**
 * The repository this package is asking about — provider, host, owner, name — and nothing else.
 *
 * **This type is an INPUT and this package does not produce it.** Parsing `origin` into these four fields is
 * `git/`'s job and is being written there; this is the shape it has to arrive in. Writing a second remote-URL
 * parser here would be the duplicated-parser defect this repository has already paid for once, so there is
 * deliberately no `fun parse(remoteUrl: String)` in this file and there must not be one.
 *
 * [host] is the *web* host as it appears in the remote (`github.com`, `gitlab.com`, `git.corp.example`) — not
 * an API base URL. Each [ForgeApi] derives its own base from it, because the two providers spell that
 * differently (`api.github.com` versus `github.example/api/v3`, always `gitlab.example/api/v4`), and it is
 * also the key the access token is stored under: one token per host, so a self-hosted GitLab and gitlab.com
 * are two credentials and never one.
 *
 * [owner] may itself contain slashes on GitLab, where a project can sit several groups deep
 * (`platform/backend/services`). Nothing here splits on `/`; the whole thing is percent-encoded at the point
 * it becomes a URL.
 */
data class ForgeRepo(
    val provider: ForgeProvider,
    val host: String,
    val owner: String,
    val name: String,
) {

    /** `owner/name`, the form both providers use to identify a project. Never pre-encoded — see [ForgeRepo]. */
    val path: String get() = "$owner/$name"
}
