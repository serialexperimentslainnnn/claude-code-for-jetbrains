package dev.lain.claudejb.git

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil
import com.intellij.vcsUtil.VcsUtil
import dev.lain.claudejb.diff.DiffPresenter

/**
 * Hands history off to **the IDE's own Git Log**, and builds nothing of its own.
 *
 * The plugin has no business re-implementing a commit graph: the platform's Version Control tool window already
 * has the log, the file history, the diff viewer, search and every action a user expects, in their theme and with
 * their shortcuts. A second, worse log inside a chat panel is the classic way an integration becomes a
 * maintenance liability — the same reasoning that keeps diffs on `DiffManager` (see `diff/DiffPresenter`) rather
 * than rendering them in the web view.
 *
 * Every entry point here is **EDT-only** (they touch tool windows) and every one returns `false` instead of
 * throwing when there is nothing to show, so a caller can degrade without a `try`.
 */
object GitLogNavigator {

    /**
     * Brings the Version Control tool window — the one hosting the Git Log — to the front. False when the IDE has
     * no such tool window (no VCS mappings, or a product without it), which is a legitimate state, not an error.
     */
    fun showLog(project: Project): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return false
        toolWindow.activate(null, true)
        return true
    }

    /**
     * Selects commit [hash] in the project's own Git Log, opening the tool window if it is closed.
     *
     * **This is a navigation, not a diff we draw.** Landing on the commit puts the platform's commit details
     * pane — message, author, and the changed files with its own diff viewer — in front of the user, which is
     * the same reasoning that keeps every other diff on `DiffManager`: a second, worse commit view inside a
     * chat panel is a maintenance liability, and this one would also have to re-read the repository the IDE
     * has already indexed.
     *
     * [hash] is expected to be an object name and nothing else; the SHAPE check belongs to the boundary the
     * value crossed (`GitActionCatalog.isCommitHash`, at the browser bridge) rather than here, because that is
     * where the value stops being trusted. Blank is refused all the same, since it is the one value that would
     * reach the platform as a request to select nothing.
     *
     * **The platform call is made from inside the method body on purpose.** `com.intellij.vcs.log.*` reaches
     * this plugin's classloader transitively through the optional `Git4Idea` dependency (see
     * `META-INF/claude-git.xml`), so with the Git plugin disabled these classes are not on our classpath at
     * all. A reference in a signature or a supertype resolves eagerly at load time and would turn that into a
     * `NoClassDefFoundError`; behind [GitHistoryService.isAvailable] and inside a body, it is simply never
     * reached — the same containment `GitGateway` relies on for `git4idea` itself. The `runCatching` is the
     * second half of that and is not defensive noise: a `LinkageError` is what an unreachable module actually
     * throws, and here it means one button reports failure rather than a chat dying mid-turn.
     *
     * True means the jump was REQUESTED. The platform answers asynchronously (the log may still be loading),
     * and its own answer — the commit is not in the log because it is on an unfetched ref, say — arrives too
     * late to be a button's verdict, so it is deliberately not waited for.
     */
    fun showCommit(project: Project, hash: String): Boolean {
        if (hash.isBlank()) return false
        val history = project.service<GitHistoryService>()
        if (!history.isAvailable()) return false
        val root = history.primaryRepositoryRoot() ?: return false
        val rootFile = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(root))
            ?: return false
        return runCatching {
            VcsLogNavigationUtil.jumpToRevisionAsync(project, rootFile, HashImpl.build(hash))
            true
        }.getOrElse {
            LOG.warn("Could not show commit $hash in the Git Log", it)
            false
        }
    }

    /**
     * Opens the IDE's own file-history view for [path] (absolute, either separator).
     *
     * **The containment check is the point of this method.** A path can reach here from model output, so before
     * anything is resolved on disk it goes through [DiffPresenter.isWithinRoot] against the project root — the
     * same canonicalizing, symlink-resolving gate the write path uses. Outside the project, the answer is no:
     * `~/.ssh/config` does not get a history view because a tool call named it.
     *
     * False when Git is unavailable, when the path is outside the project, when the file does not exist, or when
     * the VCS registered for it offers no history provider.
     */
    fun showFileHistory(project: Project, path: String): Boolean {
        if (!project.service<GitHistoryService>().isAvailable()) return false
        if (!DiffPresenter.isWithinRoot(path, project.basePath)) {
            LOG.warn("Refusing to show file history outside the project root")
            return false
        }
        val file = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(path)) ?: return false
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return false
        val provider = vcs.vcsHistoryProvider ?: return false
        AbstractVcsHelper.getInstance(project).showFileHistory(provider, VcsUtil.getFilePath(file), vcs)
        return true
    }

    private val LOG = logger<GitLogNavigator>()
}
