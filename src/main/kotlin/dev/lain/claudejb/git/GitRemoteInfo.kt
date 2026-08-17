package dev.lain.claudejb.git

/**
 * The `origin` remote: the URL Git holds, and what can be read out of it.
 *
 * Plain Kotlin and a pure parse, deliberately in a file that imports nothing — not from git4idea, not from the
 * platform — so the shapes below are unit-tested on a bare JVM instead of behind a running IDE. [GitGateway] does
 * nothing here but hand over the string it read from the repository's config.
 *
 * [owner] and [repo] are a **reading of a string**, never a fact checked against a server: nothing here contacts a
 * remote, and nothing here may start to. A URL that names no host, or names one and nothing after it, simply leaves
 * them null.
 *
 * [host] is reported for the same reason [owner] is, and it is the field a caller needs most: it decides which API
 * a self-hosted instance is spoken to at, and it is the key an access token is stored under — one token per host, so
 * a company GitLab and gitlab.com are two credentials and never one. It is lowercased with credentials and port
 * removed, because it is used as that key: `git@github.com` and `https://user@GitHub.com` must not become two.
 */
data class GitRemoteInfo(
    val url: String,
    val provider: GitRemoteProvider,
    val host: String?,
    val owner: String?,
    val repo: String?,
) {

    companion object {

        /**
         * Reads [url] as a Git remote. Never throws and never returns null: an unrecognised string still yields the
         * URL itself, which is the one thing a caller can always show.
         *
         * **The two spellings Git accepts for the same remote are not variants of one syntax.**
         * `https://host/owner/repo.git` is a URL; `git@host:owner/repo.git` is scp syntax, where the colon is the
         * separator between host and path and there is no scheme at all. Parsing the second with a URL parser puts
         * `owner/repo.git` in the *port*, which is the classic way this goes wrong silently.
         *
         * What the shapes have in common once the host is off: **the repository is the last path segment and the
         * owner is everything before it.** That is what makes a nested GitLab group (`group/subgroup/repo`) come out
         * as `group/subgroup` + `repo` rather than losing a level, and it costs nothing on a flat GitHub path.
         *
         * **[owner] is only reported when the URL named a host**, and that is the rule that keeps it meaningful: a
         * local remote (`../sibling`, `/srv/git/repo.git`) has leading directories, not a namespace, and rendering
         * `srv/git` where a UI expects an organisation is worse than rendering nothing at all.
         */
        fun parse(url: String): GitRemoteInfo {
            val trimmed = url.trim()
            val (host, path) = hostAndPath(trimmed)
            val segments = path.trim('/').removeSuffix(GIT_SUFFIX).split('/').filter { it.isNotBlank() }
            val owner = segments.dropLast(1).takeIf { host != null && it.isNotEmpty() }
            return GitRemoteInfo(
                url = trimmed,
                provider = providerOf(host),
                host = host,
                owner = owner?.joinToString("/"),
                repo = segments.lastOrNull(),
            )
        }

        /**
         * Splits [url] into its host (lowercased, credentials and port removed) and the path after it.
         *
         * Three cases, in the order they can be told apart: a scheme, then scp syntax, then anything else — which
         * is a filesystem path and has no host. **The scp branch has to prove the text before the colon is a host**
         * (`user@` or a dot in it) and not a path: `C:/repos/thing` is a Windows path whose first character would
         * otherwise become the hostname, and everything downstream — provider, owner — would be read off it.
         */
        private fun hostAndPath(url: String): Pair<String?, String> {
            val scheme = url.indexOf(SCHEME_SEPARATOR)
            if (scheme >= 0) {
                val rest = url.substring(scheme + SCHEME_SEPARATOR.length)
                return hostOf(rest.substringBefore('/')) to rest.substringAfter('/', "")
            }
            val colon = url.indexOf(':')
            val authority = if (colon > 0) url.substring(0, colon) else ""
            if (looksLikeHost(authority)) return hostOf(authority) to url.substring(colon + 1)
            return null to url
        }

        /** Scp syntax only when the text before the colon carries a credential or a dotted name, and no path yet. */
        private fun looksLikeHost(authority: String): Boolean =
            '/' !in authority && ('@' in authority || '.' in authority)

        /** `user:token@host:22` → `host`. Credentials are dropped rather than carried around: they are a secret. */
        private fun hostOf(authority: String): String? =
            authority.substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotBlank() }

        /**
         * The provider, decided on a whole dot-separated LABEL of the host rather than on a substring of it.
         *
         * `gitlab.example.com` and `github.acme.com` are the self-hosted shapes and must be recognised; `mygithub.io`
         * must not, and a substring test says yes to it. A self-hosted instance under a name that mentions neither
         * (`git.example.com`) is [GitRemoteProvider.OTHER], and correctly so — the URL is the only evidence there is,
         * and inventing a provider from nothing would send a caller building links to the wrong service.
         */
        private fun providerOf(host: String?): GitRemoteProvider {
            val labels = host?.split('.').orEmpty()
            return when {
                GITHUB in labels -> GitRemoteProvider.GITHUB
                GITLAB in labels -> GitRemoteProvider.GITLAB
                else -> GitRemoteProvider.OTHER
            }
        }

        private const val SCHEME_SEPARATOR = "://"
        private const val GIT_SUFFIX = ".git"
        private const val GITHUB = "github"
        private const val GITLAB = "gitlab"
    }
}

/**
 * Which service the remote's host belongs to, as far as the URL is willing to say.
 *
 * Three values and no more: the two whose web layout a caller could act on, and the honest answer for everything
 * else. This is not an inventory of forges — adding one means something in the plugin genuinely behaves differently
 * for it.
 */
enum class GitRemoteProvider {
    GITHUB,
    GITLAB,
    OTHER,
}
