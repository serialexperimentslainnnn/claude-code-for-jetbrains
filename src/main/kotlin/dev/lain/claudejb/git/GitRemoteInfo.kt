package dev.lain.claudejb.git

data class GitRemoteInfo(
    val url: String,
    val provider: GitRemoteProvider,
    val host: String?,
    val owner: String?,
    val repo: String?,
) {

    companion object {

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

        private fun looksLikeHost(authority: String): Boolean =
            '/' !in authority && ('@' in authority || '.' in authority)

        private fun hostOf(authority: String): String? =
            authority.substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotBlank() }

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

enum class GitRemoteProvider {
    GITHUB,
    GITLAB,
    OTHER,
}
