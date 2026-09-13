package dev.lain.claudejb.controller.git

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import dev.lain.claudejb.model.git.GitBranchTopology
import dev.lain.claudejb.model.git.GitCommitInfo
import dev.lain.claudejb.model.git.GitLogScope
import dev.lain.claudejb.model.git.GitRefInfo
import dev.lain.claudejb.model.git.GitRefKind
import dev.lain.claudejb.model.git.GitRemoteInfo
import git4idea.GitCommit
import git4idea.GitRevisionNumber
import git4idea.branch.GitBranchesCollection
import git4idea.history.GitFileHistory
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

    @Throws(VcsException::class)
    fun commit(project: Project, root: VirtualFile, hash: String): GitCommitInfo? =
        GitHistoryUtils.history(project, root, hash, "-n", "1").firstOrNull()?.let { toInfo(it, root.path) }

    @Throws(VcsException::class)
    fun fileHistory(project: Project, root: VirtualFile, relativePath: String, limit: Int): List<GitCommitInfo> {
        val path = VcsUtil.getFilePath(root.path + "/" + relativePath, false)
        val hashes = GitFileHistory.collectHistory(project, path, "-n", limit.toString()).map { it.revisionNumber.asString() }
        if (hashes.isEmpty()) return emptyList()
        @Suppress("SpreadOperator")
        val commits = GitHistoryUtils.history(project, root, *GitHistoryUtils.formHashParameters(project, hashes))
        return hashes.mapNotNull { hash -> commits.firstOrNull { it.id.asString() == hash } }.map { toInfo(it, root.path) }
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

    fun midOperation(project: Project, root: VirtualFile): Boolean =
        repositoryAt(project, root)?.state in RESOLVING_STATES

    private val RESOLVING_STATES = setOf(
        Repository.State.MERGING,
        Repository.State.REBASING,
        Repository.State.GRAFTING,
    )

    private fun repositories(project: Project): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    private fun repositoryAt(project: Project, root: VirtualFile): GitRepository? =
        repositories(project).firstOrNull { it.root == root }
}
