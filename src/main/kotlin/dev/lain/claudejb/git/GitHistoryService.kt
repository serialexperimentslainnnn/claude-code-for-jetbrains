package dev.lain.claudejb.git

import com.intellij.openapi.Disposable
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
 * **Threading.** [recentCommits] and [branchTopology] spawn child processes and must not run on the EDT; they
 * refuse (and say so in the log) rather than freezing the IDE. Every other query reads state the platform already
 * keeps in memory and is safe anywhere.
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
        if (refusedOnEdt("recentCommits()", "git log")) return emptyList()
        return withPrimaryRoot(emptyList()) { root -> GitGateway.recentCommits(project, root, limit) }
    }

    /**
     * Where the checked-out branch of the primary repository stands against the branch it tracks: the upstream's
     * name, the ahead/behind counts and the merge base.
     *
     * [GitBranchTopology.NONE] when Git is unavailable, when `HEAD` is detached, when `git` fails, or when called
     * on the EDT — this runs `git rev-list` and `git merge-base` and belongs on a background thread.
     *
     * The counts come from the LOCAL remote-tracking ref, so they say "since the last fetch". Making them fresher
     * would mean fetching, which is a remote operation and deliberately not something this plugin performs.
     */
    fun branchTopology(): GitBranchTopology {
        if (refusedOnEdt("branchTopology()", "git rev-list / git merge-base")) return GitBranchTopology.NONE
        return withPrimaryRoot(GitBranchTopology.NONE) { root -> GitGateway.branchTopology(project, root) }
    }

    /**
     * Every branch of the primary repository, with the commit each one points at — or the single `HEAD` entry a
     * detached checkout has instead.
     *
     * Paired with [recentCommits]' parents, this is what a branch graph is drawn from: the parents give the
     * shape, the refs give the names and say which line you are standing on. Neither half is guessable from the
     * other, which is why both are read rather than one inferred.
     *
     * No EDT guard, and for the same reason [primaryRemote] has none: this reads the ref state the IDE already
     * holds in memory, spawns no process and touches no network. Empty when Git is unavailable or the project is
     * not a working copy.
     */
    fun refs(): List<GitRefInfo> = withPrimaryRoot(emptyList()) { root -> GitGateway.refs(project, root) }

    /**
     * The remote this repository is about — its URL, and the host/provider/owner/repo read out of it.
     *
     * `origin`, else `upstream`, else the only remote there is; null when Git is unavailable, there is no
     * repository, or several remotes disagree with no convention to break the tie. [GitGateway.primaryRemote]
     * argues each step.
     *
     * No EDT guard, because there is nothing to guard: this reads the parsed config the IDE already holds, spawns
     * no process and touches no network.
     */
    fun primaryRemote(): GitRemoteInfo? =
        withPrimaryRoot<GitRemoteInfo?>(null) { root -> GitGateway.primaryRemote(project, root) }

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

    /**
     * Calls [onChanged] whenever the IDE reports that a repository moved, until [parent] is disposed.
     *
     * Every other method here answers when asked, which made the Git view exactly as fresh as the moments
     * something happened to ask — the page loading, a turn ending, a button being pressed. A branch switch is
     * none of those, so the view kept naming the branch you had left. This is the IDE's own Git plugin telling
     * us instead, so a checkout made anywhere (its UI, this plugin, a terminal) lands the same way.
     *
     * Silently does nothing when the Git plugin is absent: there is no repository to change, and the caller
     * already draws no Git surface. **[onChanged] does not arrive on the EDT** — git4idea publishes from a
     * background thread — so a caller that touches the UI has to hop.
     */
    fun onRepositoryChanged(parent: Disposable, onChanged: () -> Unit) {
        if (!GitAvailability.isGitPluginEnabled()) return
        runCatching { GitGateway.onRepositoryChanged(project, parent, onChanged) }
            .onFailure { LOG.warn("Could not subscribe to Git repository changes for ${project.name}", it) }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * True when [caller] is running on the EDT, in which case it must return its empty answer instead of spawning
     * [commands].
     *
     * A refusal, not a `runBlocking` hop off the thread: a caller that asked from the EDT has a bug in *its*
     * threading, and hiding that behind a thread jump buys a Git query at the price of an answer that arrives after
     * the caller has already returned. The log line names the caller because the empty answer is otherwise
     * indistinguishable from "this project has no Git".
     */
    private fun refusedOnEdt(caller: String, commands: String): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) return false
        LOG.warn("$caller was called on the EDT; refusing to run `$commands` there. Move the call off the EDT.")
        return true
    }

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
