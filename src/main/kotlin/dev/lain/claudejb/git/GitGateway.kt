package dev.lain.claudejb.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
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
 * Every method must be called **off the EDT**: `git log` is a child process. [GitHistoryService] enforces that.
 */
internal object GitGateway {

    /** The VCS roots of every Git repository registered in [project]; empty when the project is not a working copy. */
    fun repositoryRoots(project: Project): List<VirtualFile> = repositories(project).map { it.root }

    /** The checked-out branch of the repository rooted at [root], or null when detached, fresh or unknown. */
    fun currentBranchName(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentBranchName

    /** The revision `HEAD` points at in the repository rooted at [root], or null on a fresh repository. */
    fun currentRevision(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentRevision

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
        )
    }

    private fun repositories(project: Project): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    private fun repositoryAt(project: Project, root: VirtualFile): GitRepository? =
        repositories(project).firstOrNull { it.root == root }
}
