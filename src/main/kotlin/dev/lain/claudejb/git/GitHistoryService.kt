package dev.lain.claudejb.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Read-only Git context for the project: which branch is checked out, what the recent commits did, and what is
 * currently uncommitted. Everything the IDE already knows, handed over in plain Kotlin types so the chat, the
 * dashboard or a future tool call can use it without learning the VCS API.
 *
 * **Read-only is a property of the code, not a promise in a comment.** There is no write path in this package —
 * no `reset`, no rebase, no history rewriting, no remote operation — and `GitReadOnlyContractTest` fails the
 * build if one appears. Reverting a change is a NEW commit the user makes through the IDE's own Git UI.
 *
 * **Degradation.** Three separate things can be missing and none of them is an error:
 *  - the Git plugin is absent or disabled → [GitAvailability] says no, every method returns its empty answer;
 *  - the project is not a working copy → no repositories, same;
 *  - `git` itself fails (broken index, missing binary) → [VcsException], logged, same.
 *
 * **Threading.** [recentCommits] spawns `git log` and must not run on the EDT; it refuses (and says so in the
 * log) rather than freezing the IDE. The other queries read state the platform already keeps in memory.
 */
@Service(Service.Level.PROJECT)
class GitHistoryService(private val project: Project) {

    /** Absolute VFS paths of every Git root registered in this project. Empty when Git is unavailable. */
    fun repositoryRoots(): List<String> = readGit(emptyList()) { GitGateway.repositoryRoots(project).map { it.path } }

    /** True when the Git plugin is enabled AND this project has at least one Git repository. */
    fun isAvailable(): Boolean = repositoryRoots().isNotEmpty()

    /**
     * The repository this project's files actually live in: the DEEPEST root containing the project directory
     * (so a submodule wins over the outer repository), falling back to the first registered root. Null when Git
     * is unavailable.
     */
    fun primaryRepositoryRoot(): String? {
        val roots = repositoryRoots()
        val base = project.basePath ?: return roots.firstOrNull()
        return roots.filter { base == it || base.startsWith("$it/") }.maxByOrNull { it.length } ?: roots.firstOrNull()
    }

    /** The checked-out branch of the primary repository, or null when detached, unborn or unavailable. */
    fun currentBranch(): String? = withPrimaryRoot { root -> GitGateway.currentBranchName(project, root) }

    /** The revision `HEAD` points at in the primary repository, or null on a fresh repository / when unavailable. */
    fun headRevision(): String? = withPrimaryRoot { root -> GitGateway.currentRevision(project, root) }

    /**
     * The [limit] most recent commits of the primary repository, newest first, each with the files it touched
     * (paths relative to that repository root). Empty when Git is unavailable, when `git` fails, or when called
     * on the EDT — this runs a child process and belongs on a background thread.
     */
    fun recentCommits(limit: Int = DEFAULT_COMMIT_LIMIT): List<GitCommitInfo> {
        if (limit <= 0) return emptyList()
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("recentCommits() was called on the EDT; refusing to run `git log` there. Move the call off the EDT.")
            return emptyList()
        }
        return withPrimaryRoot(emptyList()) { root -> GitGateway.recentCommits(project, root, limit) }
    }

    /**
     * The files with uncommitted modifications, relative to the primary repository root.
     *
     * Read from the platform's own change list — already computed for the Local Changes view, so this costs
     * nothing and never spawns a process. A change reports its post-change path, falling back to the pre-change
     * one for a deletion.
     */
    fun workingTreeChanges(): List<String> {
        val root = primaryRepositoryRoot() ?: return emptyList()
        return ChangeListManager.getInstance(project).allChanges
            .mapNotNull { change -> (change.afterRevision ?: change.beforeRevision)?.file?.path }
            .map { path -> GitCommitInfo.relativize(root, path) }
            .distinct()
            .sorted()
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun <T> withPrimaryRoot(fallback: T, block: (VirtualFile) -> T): T =
        readGit(fallback) {
            val wanted = primaryRepositoryRoot() ?: return@readGit fallback
            val root = GitGateway.repositoryRoots(project).firstOrNull { it.path == wanted } ?: return@readGit fallback
            block(root)
        }

    private fun withPrimaryRoot(block: (VirtualFile) -> String?): String? = withPrimaryRoot<String?>(null, block)

    /**
     * Runs [block] only when the Git plugin is there, and turns the two failures that are *expected* into
     * [fallback]: a `git` invocation that failed, and — belt and braces — a classloader that turns out not to
     * carry `git4idea` after all. [GitGateway] is the only thing that can raise the latter, and only if the
     * availability gate above it were ever wrong; swallowing it here is what keeps a wrong gate from taking a
     * chat down with it.
     */
    private fun <T> readGit(fallback: T, block: () -> T): T {
        if (!GitAvailability.isGitPluginEnabled()) return fallback
        return try {
            block()
        } catch (e: VcsException) {
            LOG.warn("Git query failed for ${project.name}", e)
            fallback
        } catch (e: LinkageError) {
            LOG.warn("Git4Idea classes are not on this plugin's classpath; the Git surface stays off", e)
            fallback
        }
    }

    companion object {

        /** Enough to see what the last stretch of work did, small enough that `git log` stays instant. */
        const val DEFAULT_COMMIT_LIMIT = 20

        private val LOG = logger<GitHistoryService>()
    }
}
