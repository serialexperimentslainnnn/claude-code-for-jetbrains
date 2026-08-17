package dev.lain.claudejb.git

/**
 * Where the checked-out branch stands relative to the branch it tracks: the upstream's name, how far the two have
 * drifted apart, and the commit they last agreed on.
 *
 * Like [GitCommitInfo] this carries **nothing from the VCS API** — no `GitLocalBranch`, no `GitBranchTrackInfo`, no
 * `GitRevisionNumber`. Those types only reach this plugin's classloader through the optional Git dependency, so one
 * of them in a public signature would put a git4idea-shaped hole in every caller. [GitGateway] converts at the
 * boundary.
 *
 * **The four fields are independently absent, and each absence means something different**, which is why they are
 * nullable rather than defaulted to zero:
 *  - [branch] null — `HEAD` is detached or the repository has no commit yet, so there is no branch to compare;
 *  - [upstream] null — the branch tracks nothing. [ahead] and [behind] are then meaningless, not zero;
 *  - [ahead] / [behind] null — `git rev-list --count` did not answer. Painting that as `0` would read as *in sync*,
 *    which is the one answer we do not have. A caller that cannot render "unknown" should render nothing;
 *  - [mergeBase] null — the two refs share no history (an unrelated upstream, a force-pushed rewrite).
 */
data class GitBranchTopology(
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val mergeBase: String? = null,
) {

    companion object {

        /** The answer when Git is unavailable, the project is not a working copy, or `HEAD` is detached. */
        val NONE = GitBranchTopology()

        /**
         * The number `git rev-list --count` printed, or null when it printed something that is not one.
         *
         * The platform hands this over as **text**, and hands over null when the command failed — it logs the
         * failure at debug and returns, so the only signal a caller gets is the shape of the string. Blank output,
         * an error line that reached stdout and a negative number are all "we do not know", and they have to stay
         * distinguishable from a genuine `0`: zero means *in sync* and is the most reassuring thing this model can
         * say. Guessing it from a failed command is how a branch eight commits behind reports itself as up to date.
         */
        fun commitCount(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
