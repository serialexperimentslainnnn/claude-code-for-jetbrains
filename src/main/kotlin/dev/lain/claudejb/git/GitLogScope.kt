package dev.lain.claudejb.git

/**
 * Which lines of development a `git log` read covers.
 *
 * **It exists because two surfaces ask [GitHistoryService.recentCommits] the same question and mean different
 * things by it — and the difference is invisible in the answer.** A list of commits looks identical whichever
 * refs produced it, so widening the read one layer down changes what a surface says without changing a line of
 * that surface's code. The gear menu's *Recent Commits on `<branch>`…* is a question about the branch you are
 * standing on, and its own title promises that; the dashboard's commit graph is a question about the
 * repository, and a graph drawn from `HEAD` alone can only ever be a straight line, because the commits that
 * would fork off it were never in the answer.
 *
 * Plain Kotlin with no `git4idea` in sight, like every other value type in this package: [GitGateway] turns the
 * choice into `git log` arguments, which is the one file allowed to know what those are.
 */
enum class GitLogScope {

    /**
     * What `HEAD` reaches, and nothing else — `git log`'s own default, said out loud.
     *
     * The conservative reading, and therefore the DEFAULT: an unqualified call keeps meaning exactly what this
     * read has always meant, so no surface can start showing another branch's work because a parameter was
     * forgotten a layer below it. The two directions are not symmetrical — widening puts foreign commits under
     * a title that names one branch and nothing on screen says so, while narrowing shows up immediately as a
     * graph with nothing to draw.
     */
    CURRENT_BRANCH,

    /**
     * Every branch, every remote-tracking branch and every tag — plus `HEAD`, for the detached case no ref
     * covers.
     *
     * The set a graph needs: a fork is only drawable when both sides of it are in the list. Deliberately NOT
     * `--all`, which means every ref under `refs/` and so takes in `refs/stash` and `refs/notes` — commits that
     * belong to no line of development and would arrive as parentless roots in a lane of their own.
     */
    EVERY_LINE_OF_DEVELOPMENT,
}
