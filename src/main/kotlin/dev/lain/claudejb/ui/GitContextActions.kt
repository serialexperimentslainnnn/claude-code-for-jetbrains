package dev.lain.claudejb.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.git.GitLogNavigator
import javax.swing.JList

/**
 * The tool window's Git entries: where this project's checkout stands, and the two doors into the IDE's own
 * Git UI. This is the **only** user-reachable entry point of the `git/` package — a fully tested service that
 * nothing calls is a dead feature, which this plugin has shipped once already (see the `/login` terminal
 * lookups in `CLAUDE.md`).
 *
 * **We reuse the IDE's Git Log and build no Git UI of our own.** Everything an integration would be tempted to
 * draw — a commit graph, a revision diff, file history — the platform already has, in the user's theme and with
 * their shortcuts. So the only thing rendered here is a one-line-per-commit chooser (a picker, not a viewer),
 * and acting on history is handed to [GitLogNavigator]. Same reasoning that keeps diffs on `DiffManager`.
 *
 * **Read-only, and nothing here can change that.** These actions call [GitHistoryService] and [GitLogNavigator]
 * and nothing else: no ref moves, no history rewriting, no remote traffic, and no process of our own. The
 * `git/` package's `GitReadOnlyContractTest` guards that package; the way it stays true up here is that this
 * file names no Git API beyond those two read-only collaborators.
 *
 * **Absent, not greyed.** With the Git plugin disabled, or in a project that is not a working copy, the entries
 * are hidden ([GitEntry.update]) rather than shown dead: a menu item that does nothing when clicked is worse
 * than no menu item. Visibility is *re-derived* on every menu open, so `git init` — or enabling the Git plugin —
 * takes effect without reopening the tool window.
 *
 * **Threading.** [GitHistoryService.recentCommits] spawns `git log` and refuses to run on the EDT (it returns
 * an empty list and logs), so the commit read happens on a pooled thread and only the popup comes back to the
 * EDT. The branch/HEAD reads and the availability check are in-memory platform state and are cheap enough for
 * an action `update`, which is why these run on `BGT`.
 */
internal object GitContextActions {

    /**
     * The gear-menu entries, in menu order. Each one hides itself while Git is unavailable, so the caller adds
     * them unconditionally and never has to ask.
     */
    fun gearEntries(project: Project): List<AnAction> = listOf(
        RecentCommitsAction(project),
        CurrentFileHistoryAction(project),
        OpenGitLogAction(project),
    )

    // NB there is deliberately no Git Log button anywhere but here. The tool window has no title actions at
    // all any more, and the chat's own action row carries exactly ONE Git control: the door to the repository
    // view (`app-composer-actions.js` → `openGitView`), which is the entry that would otherwise be invisible
    // on a project that is not a repository yet. Reading history is already discoverable from this menu, so a
    // button for it would spend the one place people look on the surface that needs it least.

    // ── what the entries say (pure: this is what the tests pin) ───────────────────────────────────────────────

    /**
     * The "recent commits" label. It names the checked-out branch when there is one, so the gear menu itself
     * answers *"which branch is Claude working on"* without opening anything — the cheapest possible form of
     * "show the current branch". Long branch names are cut with the same ellipsis rule as the chat tabs.
     */
    fun menuText(branch: String?): String {
        val name = branch?.trim().orEmpty()
        if (name.isEmpty()) return "Recent Commits…"
        return "Recent Commits on ${TabSessionCommands.truncate(name, BRANCH_LABEL_MAX)}…"
    }

    /** The chooser's title: the branch (or the detached-HEAD wording) and the revision `HEAD` points at. */
    fun popupTitle(branch: String?, head: String?): String {
        val where = branch?.trim()?.ifBlank { null } ?: DETACHED_HEAD
        val revision = head?.trim()?.ifBlank { null }?.let { " · ${GitCommitInfo.shortHash(it)}" }.orEmpty()
        return "Recent commits · $where$revision"
    }

    /**
     * One commit on one line: `a1b2c3d  Subject  ·  Author  ·  3d ago  ·  4 files`.
     *
     * **Plain text on purpose.** A commit subject and an author name are content this plugin did not write, and
     * a Swing renderer that is handed a string starting with `<html>` interprets the rest as markup. Rendering
     * two pretty lines of HTML here would mean escaping every field correctly, forever; one plain line cannot be
     * got wrong.
     */
    fun commitRow(commit: GitCommitInfo, now: Long = System.currentTimeMillis()): String {
        val subject = commit.subject.trim().ifBlank { NO_SUBJECT }
        val author = commit.authorName.trim().ifBlank { commit.authorEmail.trim() }.ifBlank { UNKNOWN_AUTHOR }
        val age = TabSessionCommands.relativeTime(commit.authoredAtMillis, now)
        return "${commit.shortHash}  ${TabSessionCommands.truncate(subject, SUBJECT_MAX)}" +
            "  ·  $author  ·  $age  ·  ${fileCount(commit.changedPaths.size)}"
    }

    /** `1 file` / `4 files` — the size of the change, not its content; the Git Log is where content lives. */
    private fun fileCount(files: Int): String = if (files == 1) "1 file" else "$files files"

    // ── the entries ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the branch and the last [GitHistoryService.DEFAULT_COMMIT_LIMIT] commits **off the EDT**, then
     * offers them in a chooser.
     *
     * **The commits are the CHECKED-OUT branch's, and that is a promise this entry makes twice on screen** —
     * in its own label (*Recent Commits on `<branch>`…*) and in the chooser's title, which names the branch and
     * the short `HEAD`. So it takes the read at its default scope, [dev.lain.claudejb.git.GitLogScope]'s narrow
     * one, and the dashboard's commit graph is the surface that asks the same method for every line of
     * development instead. Reading wider here would not look like a defect: the rows would render perfectly,
     * under a title naming one branch, with nothing saying which of them came from somewhere else.
     *
     * Picking one opens the IDE's Git Log, and the popup's ad line says exactly that rather than implying the
     * log will land on that commit: [GitLogNavigator] exposes no jump-to-revision, and inventing one here would
     * put Git navigation in the UI layer instead of behind the gateway.
     */
    private class RecentCommitsAction(project: Project) :
        GitEntry(project, menuText(null), "Show this project's branch and its most recent commits") {

        override fun update(e: AnActionEvent) {
            super.update(e)
            if (!e.presentation.isVisible) return
            e.presentation.text = menuText(history()?.currentBranch())
        }

        override fun actionPerformed(e: AnActionEvent) {
            val history = history() ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                // Off-EDT on purpose: `recentCommits()` runs `git log` and refuses the EDT outright.
                val branch = history.currentBranch()
                val head = history.headRevision()
                val commits = history.recentCommits()
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) present(branch, head, commits)
                }, ModalityState.any())
            }
        }

        private fun present(branch: String?, head: String?, commits: List<GitCommitInfo>) {
            if (commits.isEmpty()) {
                Messages.showInfoMessage(project, NO_COMMITS, DIALOG_TITLE)
                return
            }
            val now = System.currentTimeMillis()
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(commits)
                .setTitle(popupTitle(branch, head))
                .setAdText(AD_OPENS_LOG)
                // Subclassed, not built through `SimpleListCellRenderer.create(…)`: BOTH factory overloads are
                // marked for removal at 262, and this repository does not ship an API scheduled to disappear.
                // The class itself is not going anywhere — only the static shorthands are.
                .setRenderer(
                    object : SimpleListCellRenderer<GitCommitInfo>() {
                        override fun customize(
                            list: JList<out GitCommitInfo>,
                            value: GitCommitInfo?,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean,
                        ) {
                            text = value?.let { commitRow(it, now) }.orEmpty()
                        }
                    },
                )
                .setItemChosenCallback { openLog(project) }
                .setRequestFocus(true)
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    /**
     * Hands the file open in the editor to the IDE's own file-history view. The containment check
     * (`DiffPresenter.isWithinRoot`) lives inside [GitLogNavigator.showFileHistory]; a refusal — no file, outside
     * the project, or a VCS with no history provider — is reported instead of being swallowed.
     */
    private class CurrentFileHistoryAction(project: Project) :
        GitEntry(project, "Git History for the Current File", "Open the IDE's Git history for the file in the active editor") {

        override fun actionPerformed(e: AnActionEvent) {
            val path = EditorContextProvider.currentFilePath(project)
            if (path == null) {
                Messages.showInfoMessage(project, NO_FILE, DIALOG_TITLE)
                return
            }
            if (!GitLogNavigator.showFileHistory(project, path)) {
                Messages.showInfoMessage(project, NO_FILE_HISTORY, DIALOG_TITLE)
            }
        }
    }

    /** The direct door: brings up the Version Control tool window that hosts the Git Log. */
    private class OpenGitLogAction(project: Project) :
        GitEntry(project, "Open Git Log", "Bring up the IDE's Version Control tool window") {

        override fun actionPerformed(e: AnActionEvent) = openLog(project)
    }

    /**
     * Shared behaviour of the entries: how they find the service, and the fact that they **disappear**
     * instead of greying out when there is no Git to talk to.
     */
    private abstract class GitEntry(protected val project: Project, text: String, description: String) :
        AnAction(text, description, null) {

        /** The project's read-only Git service, or null once the project is gone (an action outlives its tab). */
        protected fun history(): GitHistoryService? =
            if (project.isDisposed) null else project.service<GitHistoryService>()

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = history()?.isAvailable() == true
        }

        /**
         * BGT: availability and the branch name are reads of the platform's in-memory repository registry — no
         * Swing, no PSI, no editor — so there is no reason to make the menu wait for the EDT.
         */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    /** Activates the IDE's Version Control tool window, saying so when the IDE has none for this project. */
    private fun openLog(project: Project) {
        if (!GitLogNavigator.showLog(project)) {
            Messages.showInfoMessage(project, NO_LOG, DIALOG_TITLE)
        }
    }

    // ── wording and limits ────────────────────────────────────────────────────────────────────────────────────

    /** Max characters of a branch name in the menu label before it is ellipsized. */
    private const val BRANCH_LABEL_MAX = 24

    /** Max characters of a commit subject in a chooser row; the full message lives in the Git Log. */
    private const val SUBJECT_MAX = 72

    private const val DETACHED_HEAD = "detached HEAD"
    private const val NO_SUBJECT = "(no commit message)"
    private const val UNKNOWN_AUTHOR = "unknown author"

    private const val DIALOG_TITLE = "Claude Code"
    private const val AD_OPENS_LOG = "Choosing a commit opens the IDE's Git Log"
    private const val NO_COMMITS = "This repository has no commits yet."
    private const val NO_FILE = "Open a file in the editor first."
    private const val NO_FILE_HISTORY =
        "No Git history is available for this file. It has to live inside the project and be tracked by Git."
    private const val NO_LOG = "This IDE has no Version Control tool window for this project."
}
