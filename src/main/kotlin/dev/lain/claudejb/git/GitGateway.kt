package dev.lain.claudejb.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitCommit
import git4idea.GitRevisionNumber
import git4idea.branch.GitBranchesCollection
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitBranchTrackInfo
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

/**
 * **The only file in the plugin that names a `git4idea` type.** Everything else — [GitHistoryService],
 * [GitLogNavigator], the UI above them — speaks plain Kotlin and platform-core types.
 *
 * That containment is the whole degradation strategy, not a stylistic preference. The Git dependency is optional
 * (`claude-git.xml`), so on an IDE without the Git plugin `git4idea.*` is absent from this plugin's classloader.
 * Class loading resolves a class's **supertypes and annotations** eagerly, but the classes named inside method
 * bodies only when that code actually runs — so a service that mentions `GitRepositoryManager` anywhere is a
 * service the JVM may refuse to verify, while a service that only mentions *this object* loads everywhere and
 * simply never calls it. [GitAvailability] is the gate; this is the door behind it.
 *
 * Strictly **read-only**: it reads refs and log output and nothing else. There is no write path here by design —
 * no reset, no rebase, no history rewriting, no remote operation — and `GitReadOnlyContractTest` fails the build
 * if one appears. Reverting a change is a NEW commit made by the user through the IDE's own Git UI, never
 * something this plugin performs behind their back.
 *
 * **The methods that spawn a child process must be called off the EDT** — [recentCommits] and [branchTopology],
 * which between them run `git log`, `git rev-list` and `git merge-base`. The rest read the repository model the
 * IDE already holds in memory and are safe anywhere. [GitHistoryService] is what enforces the distinction, so that
 * a caller never has to know which is which.
 */
internal object GitGateway {

    /** The VCS roots of every Git repository registered in [project]; empty when the project is not a working copy. */
    fun repositoryRoots(project: Project): List<VirtualFile> = repositories(project).map { it.root }

    /** The checked-out branch of the repository rooted at [root], or null when detached, fresh or unknown. */
    fun currentBranchName(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentBranchName

    /** The revision `HEAD` points at in the repository rooted at [root], or null on a fresh repository. */
    fun currentRevision(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentRevision

    /**
     * Every branch of the repository rooted at [root], with the commit each one points at.
     *
     * **This is the half of a branch graph that cannot be computed.** [recentCommits] already carries each
     * commit's parents, which is the shape of the history; what no amount of arithmetic recovers is which of
     * those lines is `main`, which is `origin/main`, and which one `HEAD` is standing on. Drawing a graph
     * without it means naming lanes by guesswork, and a guessed branch name in a Git view is indistinguishable
     * on screen from a real one.
     *
     * **No process and no network**: `GitBranchesCollection` is the parsed ref state the IDE already holds in
     * memory — three maps and a lookup — so this is as cheap as [currentBranchName] and, like it, safe to call
     * from the EDT. `git4idea.branch` is on this plugin's allowlist for this one value type and nothing else;
     * the package's actual write surface (`GitBrancher`) stays forbidden, and there is no mutator here to reach.
     *
     * A branch whose hash the collection does not know is dropped rather than emitted with a blank hash: a ref
     * that points nowhere cannot be attached to a commit, and a chip floating free of the graph is a claim the
     * data does not support.
     *
     * A **detached** `HEAD` produces a single [GitRefKind.HEAD] entry, because it is then the only thing that can
     * mark where the user is standing — and putting a branch name there instead would name a branch they left.
     * Empty when there is no repository at [root].
     */
    fun refs(project: Project, root: VirtualFile): List<GitRefInfo> {
        val repository = repositoryAt(project, root) ?: return emptyList()
        val branches: GitBranchesCollection = repository.branches
        val head = repository.currentBranchName
        val local = branches.localBranches.mapNotNull { branch ->
            branches.getHash(branch)?.let {
                GitRefInfo(branch.name, GitRefKind.LOCAL, it.asString(), current = branch.name == head)
            }
        }
        val remote = branches.remoteBranches.mapNotNull { branch ->
            branches.getHash(branch)?.let {
                GitRefInfo(branch.nameForLocalOperations, GitRefKind.REMOTE, it.asString(), current = false)
            }
        }
        val detached = if (head == null) {
            repository.currentRevision?.let { listOf(GitRefInfo(DETACHED_HEAD, GitRefKind.HEAD, it, current = true)) }
        } else {
            null
        }
        return (detached.orEmpty() + local + remote)
            .sortedWith(compareByDescending<GitRefInfo> { it.current }.thenBy { it.kind }.thenBy { it.name })
    }

    /** What a detached `HEAD` is called on screen. Git's own spelling, so it is not mistaken for a branch. */
    private const val DETACHED_HEAD = "HEAD"

    /**
     * The [limit] most recent commits reachable from `HEAD` in the repository rooted at [root], newest first.
     *
     * Throws [VcsException] the way `GitHistoryUtils` does (a broken repository, a `git` that will not run); the
     * caller turns that into an empty result rather than letting it escape into the UI.
     */
    @Throws(VcsException::class)
    fun recentCommits(project: Project, root: VirtualFile, limit: Int): List<GitCommitInfo> =
        GitHistoryUtils.history(project, root, "-n", limit.toString()).map { commit -> toInfo(commit, root.path) }

    /**
     * Where the checked-out branch of the repository rooted at [root] stands against the branch it tracks.
     *
     * The branch and its upstream cost nothing — they are in the repository model the IDE already keeps — while
     * the counts and the merge base each **run a child process** (`git rev-list --count`,
     * `git merge-base`). Both are pure readers: neither moves a ref, touches the index or contacts a remote. The
     * counts in particular are read from the LOCAL remote-tracking ref, so they answer "since the last fetch", and
     * this package is not allowed to make that fresher — fetching is a remote operation and belongs to the IDE's
     * own Git UI.
     *
     * Returns [GitBranchTopology.NONE] when there is no repository at [root], and a topology carrying only the
     * branch when that branch tracks nothing: there is then no reference point, so a count would be an answer to a
     * question nobody could ask.
     *
     * Throws [VcsException] the way `GitHistoryUtils.getMergeBase` does; the caller turns that into the empty
     * answer rather than letting it escape into the UI.
     */
    @Throws(VcsException::class)
    fun branchTopology(project: Project, root: VirtualFile): GitBranchTopology {
        val repository = repositoryAt(project, root) ?: return GitBranchTopology.NONE
        val branch = repository.currentBranchName ?: return GitBranchTopology.NONE
        val track: GitBranchTrackInfo? = repository.getBranchTrackInfo(branch)
        val upstream = track?.remoteBranch?.nameForLocalOperations ?: return GitBranchTopology(branch = branch)
        val base: GitRevisionNumber? = GitHistoryUtils.getMergeBase(project, root, branch, upstream)
        return GitBranchTopology(
            branch = branch,
            upstream = upstream,
            ahead = countBetween(repository, from = upstream, to = branch),
            behind = countBetween(repository, from = branch, to = upstream),
            mergeBase = base?.asString(),
        )
    }

    /**
     * The commits reachable from [to] and not from [from] — `git rev-list --count from..to` — or null when the
     * command did not answer.
     *
     * The platform swallows the failure and returns null for it, so the two "no number" cases arrive as the same
     * value and [GitBranchTopology.commitCount] keeps them both distinct from a real zero.
     */
    private fun countBetween(repository: GitRepository, from: String, to: String): Int? =
        GitBranchTopology.commitCount(GitHistoryUtils.getNumberOfCommitsBetween(repository, from, to))

    /**
     * The remote this repository is *about*, or null when there is no repository and no remote with a URL.
     *
     * **Three candidates, in this order, and the order is the whole rule**: `origin`, then `upstream`, then the
     * only remote there is. Each step exists for a real repository shape and stops short of guessing:
     *
     *  - `origin` is the convention, and where it exists it is the answer;
     *  - `upstream` is the other half of a FORK, and a clone made by `gh repo fork` or by hand may have no
     *    `origin` at all — asking only for `origin` there means the view is blank on a repository that has a
     *    perfectly good remote;
     *  - one remote under any other name (`gitlab`, `company`, `fork`) is not ambiguous: there is nothing else
     *    it could mean.
     *
     * What it deliberately will NOT do is pick from SEVERAL remotes none of which is named `origin` or
     * `upstream`. There the name is the only evidence and it says nothing, so a guess would attach the branch
     * to someone else's project — and every link, owner and pull request built on it would be about that
     * project rather than this one, silently and plausibly.
     *
     * Reads the repository's parsed config, which the IDE already holds in memory: no process, no network, and
     * nothing here that could write a remote. Safe to call from anywhere, including the EDT.
     */
    fun primaryRemote(project: Project, root: VirtualFile): GitRemoteInfo? {
        val remotes = repositoryAt(project, root)?.remotes.orEmpty()
        val chosen = remotes.firstOrNull { it.name == GitRemote.ORIGIN }
            ?: remotes.firstOrNull { it.name == UPSTREAM }
            ?: remotes.singleOrNull()
        return chosen?.firstUrl?.let { GitRemoteInfo.parse(it) }
    }

    /** The other half of a fork. `git4idea` names `origin` for us; this one it does not. */
    private const val UPSTREAM = "upstream"

    /**
     * Converts one commit into the plugin's own model.
     *
     * **Paths come from `changes`, not from `getAffectedPaths()`.** The latter is cheaper — it reads the
     * `--name-status` output the log already carried, instead of materialising a `Change` per file — and it is
     * annotated `@ApiStatus.Internal`, which `verifyPlugin` reports and the JetBrains Marketplace has already
     * blocked a release of this plugin over once (2.1.0, `findEnabledPlugin`). An internal API is not a
     * performance trade-off, it is a publication risk, and this list is bounded at [GitHistoryService]'s limit
     * and drawn into a menu — there is no budget here worth that.
     *
     * A `Change` names the file on whichever side exists: the AFTER revision for an add or a modify, and only
     * the BEFORE one for a delete, which would otherwise vanish from the list of what a commit touched.
     *
     * **The parents cost nothing and are what make a graph possible.** `git log` already printed them and the
     * platform already parsed them into `GraphCommit.getParents()` — a public interface of the VCS-log API, not
     * an internal one — so reading them here spawns nothing and adds no round trip. They are kept in commit
     * order, first parent first, because that order is what tells the mainline from the branch that was merged
     * into it.
     */
    private fun toInfo(commit: GitCommit, repositoryRoot: String): GitCommitInfo {
        val subject = commit.subject.ifBlank { GitCommitInfo.subjectOf(commit.fullMessage) }
        val paths = commit.changes
            .mapNotNull { change -> (change.afterRevision ?: change.beforeRevision)?.file }
            .map { GitCommitInfo.relativize(repositoryRoot, it.path) }
            .distinct()
            .sorted()
        return GitCommitInfo(
            hash = commit.id.asString(),
            subject = subject,
            authorName = commit.author.name,
            authorEmail = commit.author.email,
            authoredAtMillis = commit.authorTime,
            changedPaths = paths,
            parents = commit.parents.map { it.asString() },
        )
    }

    /**
     * Calls [onChanged] whenever the IDE's own Git plugin says a repository moved, until [parent] is disposed.
     *
     * **This is a subscription, not a poll, and that is the point.** Everything else here answers a question
     * when asked, so the Git view was only ever as fresh as the moments something happened to ask: the page
     * loading, a turn ending, a button on the view being pressed. Switching branch is none of those — the
     * branch changed under a view that had no reason to look again, and it kept showing the old one until the
     * next turn. The IDE already knows: `GIT_REPO_CHANGE` is the topic git4idea publishes on for a ref update,
     * a checkout, a commit, an index change — whether the plugin did it, the IDE's own Git UI did, or a
     * terminal outside the IDE did.
     *
     * `GitRepositoryChangeListener` is a read-only *notification* interface — it is handed the repository that
     * moved and returns nothing — so it belongs in the allowlist `GitReadOnlyContractTest` keeps.
     *
     * The repository is deliberately ignored: this plugin draws one repository (the primary root), and the
     * answer is re-read from scratch. Publication is on a background thread, so the callback must hop to
     * wherever it needs to be — it does not run on the EDT.
     */
    fun onRepositoryChanged(project: Project, parent: Disposable, onChanged: () -> Unit) {
        project.messageBus.connect(parent).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { onChanged() },
        )
    }

    private fun repositories(project: Project): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    private fun repositoryAt(project: Project, root: VirtualFile): GitRepository? =
        repositories(project).firstOrNull { it.root == root }
}
