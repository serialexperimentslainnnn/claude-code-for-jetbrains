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

internal object GitGateway {

    fun repositoryRoots(project: Project): List<VirtualFile> = repositories(project).map { it.root }

    fun currentBranchName(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentBranchName

    fun currentRevision(project: Project, root: VirtualFile): String? = repositoryAt(project, root)?.currentRevision

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

    private const val DETACHED_HEAD = "HEAD"

    @Throws(VcsException::class)
    fun recentCommits(
        project: Project,
        root: VirtualFile,
        limit: Int,
        scope: GitLogScope = GitLogScope.CURRENT_BRANCH,
    ): List<GitCommitInfo> {
        @Suppress("SpreadOperator")
        val commits = GitHistoryUtils.history(project, root, *revisionsOf(scope), "--topo-order", "-n", limit.toString())
        return commits.map { commit -> toInfo(commit, root.path) }
    }

    private fun revisionsOf(scope: GitLogScope): Array<String> = when (scope) {
        GitLogScope.CURRENT_BRANCH -> arrayOf("HEAD")
        GitLogScope.EVERY_LINE_OF_DEVELOPMENT -> arrayOf("HEAD", "--branches", "--remotes", "--tags")
    }

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

    private fun countBetween(repository: GitRepository, from: String, to: String): Int? =
        GitBranchTopology.commitCount(GitHistoryUtils.getNumberOfCommitsBetween(repository, from, to))

    fun primaryRemote(project: Project, root: VirtualFile): GitRemoteInfo? {
        val remotes = repositoryAt(project, root)?.remotes.orEmpty()
        val chosen = remotes.firstOrNull { it.name == GitRemote.ORIGIN }
            ?: remotes.firstOrNull { it.name == UPSTREAM }
            ?: remotes.singleOrNull()
        return chosen?.firstUrl?.let { GitRemoteInfo.parse(it) }
    }

    private const val UPSTREAM = "upstream"

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
