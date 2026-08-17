package dev.lain.claudejb.git

/**
 * One ref — a local branch, a remote-tracking branch, or a detached `HEAD` — and the commit it points at.
 *
 * Like [GitCommitInfo] and [GitBranchTopology] this carries **nothing from the VCS API**: no `GitLocalBranch`, no
 * `GitRemoteBranch`, no `Hash`. Those types only reach this plugin's classloader through the optional Git
 * dependency, so one of them in a public signature would put a git4idea-shaped hole in every caller.
 * [GitGateway] converts at the boundary.
 *
 * **This is the half of the branch map that cannot be derived.** A commit's parents say what the shape of the
 * history is; only a ref says which of those lines is `main`, which is `origin/main`, and which one you are
 * standing on. Without refs a graph is a picture of anonymous dots, and naming a line by guessing — the newest
 * commit "must be" the current branch — is exactly the invented topology the Git view refuses to draw.
 *
 * [name] is the ref as a person would type it: `main` for a local branch, `origin/main` for a remote-tracking one
 * (the *local-operations* spelling, which is the one that resolves on this machine), `HEAD` when detached.
 */
data class GitRefInfo(
    val name: String,
    val kind: GitRefKind,
    val hash: String,
    /** True for the ref `HEAD` currently resolves to — the branch you are on, or `HEAD` itself when detached. */
    val current: Boolean,
)

/**
 * What kind of ref this is.
 *
 * Three cases and not two, because a detached `HEAD` is neither a local nor a remote branch and it is the one
 * state in which the view has nothing else to mark "you are here" with. Collapsing it into `LOCAL` would put a
 * branch name on screen that does not exist.
 */
enum class GitRefKind {
    LOCAL,
    REMOTE,
    HEAD,
    ;

    /** The lowercase word the page keys off. Locale-independent by construction — these are ASCII literals. */
    val wire: String get() = name.lowercase()
}
