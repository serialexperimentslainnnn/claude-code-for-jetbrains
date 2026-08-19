package dev.lain.claudejb.git

data class GitCommitInfo(
    val hash: String,
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAtMillis: Long,
    val changedPaths: List<String>,
    val parents: List<String> = emptyList(),
) {

    val shortHash: String get() = shortHash(hash)

    companion object {

        const val SHORT_HASH_LENGTH = 7

        fun shortHash(hash: String): String = if (hash.length <= SHORT_HASH_LENGTH) hash else hash.take(SHORT_HASH_LENGTH)

        fun subjectOf(fullMessage: String): String = fullMessage.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        fun relativize(root: String, path: String): String {
            val trimmedRoot = root.trimEnd('/')
            if (trimmedRoot.isEmpty()) return path
            val prefix = "$trimmedRoot/"
            return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
        }
    }
}
