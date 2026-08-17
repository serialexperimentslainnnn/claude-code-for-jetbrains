package dev.lain.claudejb.git

/**
 * One commit, flattened into plain Kotlin types.
 *
 * Deliberately carries **nothing from the VCS API**: no `Hash`, no `VcsUser`, no `Change`. Those types live in
 * platform modules that only reach this plugin's classloader *through* the optional Git dependency, so letting
 * one leak into a public signature would put a git4idea-shaped hole in every caller. [GitGateway] converts at the
 * boundary; everything above it — and every unit test — works on this.
 *
 * [changedPaths] are relative to the **repository** root (not the project root): a repository can legitimately
 * sit above the project directory, and silently dropping the files outside it would misreport what a commit did.
 * Callers that intend to *open* one of these must still resolve it and check containment
 * (`DiffPresenter.isWithinRoot`) — see [GitLogNavigator].
 *
 * [parents] is what makes a *graph* drawable instead of a list. It is empty on a root commit and it has more than
 * one entry on a merge, and both of those are the point: without it the only honest drawing is a single rail,
 * because a fork or a merge inferred from ordering alone is invented — and an invented topology in a Git view is
 * worse than none, since nothing on screen tells a drawn branch from a real one. It defaults to empty so that
 * "nobody read the parents" stays representable and the branch map simply does not appear, rather than appearing
 * with every commit drawn as a root.
 */
data class GitCommitInfo(
    val hash: String,
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAtMillis: Long,
    val changedPaths: List<String>,
    val parents: List<String> = emptyList(),
) {

    /** The abbreviated hash, as the Git Log and `git log --oneline` show it. */
    val shortHash: String get() = shortHash(hash)

    companion object {

        /** Git's own default abbreviation length. Not configurable here: this is for display, not for lookup. */
        const val SHORT_HASH_LENGTH = 7

        /** [hash] abbreviated to [SHORT_HASH_LENGTH]; a shorter (or empty) input is returned unchanged. */
        fun shortHash(hash: String): String = if (hash.length <= SHORT_HASH_LENGTH) hash else hash.take(SHORT_HASH_LENGTH)

        /**
         * The commit subject — the first non-blank line of [fullMessage], trimmed. Used only as the fallback for
         * the subject the VCS API already parses, which is empty for a commit made with an empty message.
         */
        fun subjectOf(fullMessage: String): String = fullMessage.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        /**
         * [path] expressed relative to [root], or [path] unchanged when it is not strictly underneath it.
         *
         * Both sides are VFS-style paths (`/` separators on every OS, including Windows — that is what
         * `FilePath.getPath()` returns), so no separator juggling is needed. The comparison is case-SENSITIVE:
         * on a case-insensitive filesystem the worst outcome is an absolute path shown where a relative one
         * would have been prettier, which is strictly better than mis-stripping a prefix that is not a parent.
         */
        fun relativize(root: String, path: String): String {
            val trimmedRoot = root.trimEnd('/')
            if (trimmedRoot.isEmpty()) return path
            val prefix = "$trimmedRoot/"
            return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
        }
    }
}
