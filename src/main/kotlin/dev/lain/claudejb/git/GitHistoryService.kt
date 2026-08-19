package dev.lain.claudejb.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
class GitHistoryService(private val project: Project) {

    fun repositoryRoots(): List<String> = readGit(emptyList()) { GitGateway.repositoryRoots(project).map { it.path } }

    fun isAvailable(): Boolean = repositoryRoots().isNotEmpty()

    fun primaryRepositoryRoot(): String? {
        val roots = repositoryRoots()
        val base = project.basePath ?: return roots.firstOrNull()
        return roots.filter { base == it || base.startsWith("$it/") }.maxByOrNull { it.length } ?: roots.firstOrNull()
    }

    fun currentBranch(): String? = withPrimaryRoot { root -> GitGateway.currentBranchName(project, root) }

    fun headRevision(): String? = withPrimaryRoot { root -> GitGateway.currentRevision(project, root) }

    fun recentCommits(
        limit: Int = DEFAULT_COMMIT_LIMIT,
        scope: GitLogScope = GitLogScope.CURRENT_BRANCH,
    ): List<GitCommitInfo> {
        if (limit <= 0) return emptyList()
        if (refusedOnEdt("recentCommits()", "git log")) return emptyList()
        return withPrimaryRoot(emptyList()) { root -> GitGateway.recentCommits(project, root, limit, scope) }
    }

    fun branchTopology(): GitBranchTopology {
        if (refusedOnEdt("branchTopology()", "git rev-list / git merge-base")) return GitBranchTopology.NONE
        return withPrimaryRoot(GitBranchTopology.NONE) { root -> GitGateway.branchTopology(project, root) }
    }

    fun refs(): List<GitRefInfo> = withPrimaryRoot(emptyList()) { root -> GitGateway.refs(project, root) }

    fun primaryRemote(): GitRemoteInfo? =
        withPrimaryRoot<GitRemoteInfo?>(null) { root -> GitGateway.primaryRemote(project, root) }

    fun workingTreeChanges(): List<String> {
        val root = primaryRepositoryRoot() ?: return emptyList()
        return ChangeListManager.getInstance(project).allChanges
            .mapNotNull { change -> (change.afterRevision ?: change.beforeRevision)?.file?.path }
            .map { path -> GitCommitInfo.relativize(root, path) }
            .distinct()
            .sorted()
    }

    fun onRepositoryChanged(parent: Disposable, onChanged: () -> Unit) {
        if (!GitAvailability.isGitPluginEnabled()) return
        runCatching { GitGateway.onRepositoryChanged(project, parent, onChanged) }
            .onFailure { LOG.warn("Could not subscribe to Git repository changes for ${project.name}", it) }
    }

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

        const val DEFAULT_COMMIT_LIMIT = 20

        private val LOG = logger<GitHistoryService>()
    }
}
