package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession

/**
 * The Git entries that **change** something — and the plugin runs none of them.
 *
 * Each one writes a prompt into the Git chat and lets the agent do the work. That is not a shortcut: it is what
 * keeps the `git/` package read-only by construction (`GitReadOnlyContractTest`), and it means every write
 * arrives as a `can_use_tool` request, through [dev.lain.claudejb.permission.SensitiveGuard] and a card the user
 * has to accept — the same route as any other tool call, with the command in plain sight before it runs.
 *
 * **The card is the control, not the prompt.** The text below names one command and forbids the rest, but a
 * model can wander off it; what stops a wandering turn is the approval, which is why the Git chat runs with
 * forced approval whatever the permission mode says ([ClaudeSession.gitIntegration]).
 *
 * **It talks in its own tab** ([TabSessionCommands.gitChat]), never in the chat you are working in, and it is a
 * conversation rather than a button press: answer it (*"squash those two"*, *"not that file"*) and it carries
 * on from there.
 *
 * Read-only Git — the branch, the history, the log — is [GitContextActions]; the split is deliberate, so that
 * file can keep naming nothing but its two read-only collaborators.
 */
internal object GitPromptedActions {

    /**
     * The write entries, in menu order. Each hides itself when it does not apply — no repository yet, nothing to
     * commit, no changed file in the editor — so the caller adds them unconditionally.
     */
    // No *Initialize repository* here: creating a repository is a fixed command with nothing to decide, so it
    // runs directly (GitIntegration, GitActionCatalog.Kind.DIRECT) from the Git view's own button. An entry
    // asking Claude to do the same thing would be a second path to one action, differing only in whether a
    // model is in the way.
    fun gearEntries(project: Project, gitChat: () -> ClaudeSession): List<AnAction> = listOf(
        CommitChangesAction(project, gitChat),
        RevertFileAction(project, gitChat),
    )

    /**
     * The integration's front door, in the tool window's title bar: **open the Git chat**.
     *
     * It exists because the entries above did not: they are gear-menu items that hide themselves, so the one
     * that matters on a project with no repository — *Initialize* — was three clicks deep in a menu you had to
     * already suspect it was in. A feature nobody can find is the same as a feature that is not there, which is
     * a mistake this plugin has made before.
     *
     * Unlike everything else here it is **always visible** while the Git plugin is enabled, repository or not:
     * the state in which the integration is least discoverable and most useful is exactly the empty one.
     *
     * And opening it on a project with no repository **asks to create one straight away**, because that is the
     * only thing the integration can do there — offering a chat that can do nothing until you think to ask is
     * the same dead end in a nicer place. Only when the chat is being *opened*, though: coming back to a
     * conversation you already have is not a new request, so it is not re-asked.
     */
    fun toolbarAction(project: Project, gitChat: () -> ClaudeSession): AnAction = OpenGitChatAction(project, gitChat)

    // ── what gets asked (pure: this is what the tests pin) ─────────────────────────────────────────────────────

    // NB no `initPrompt`. Creating a repository is a fixed command with nothing to decide and no *why* an agent
    // could contribute, so it runs directly ([GitIntegration]) instead of being described to a model. Every
    // prompt that remains here says what to do and then what NOT to do: the prohibition is the load-bearing
    // half, because a capable agent asked to "commit this" will also reasonably push, amend or branch.

    /**
     * Stage and commit, with the message left to the agent — the one thing it is better placed to write than the
     * IDE is, since it has just done the work and knows why.
     *
     * [changed] is listed so the turn does not start by re-deriving what the plugin already knows; it is also
     * what makes "commit **these**" unambiguous when the user has other work in flight.
     *
     * **Every path goes through [oneLine], and the prohibitions stay last.** The list is repository content —
     * a clone, or a tree an earlier compromised turn wrote — and the threat model this repository declares
     * (ADR 0002, `docs/adr/0002-threat-model.md`) assumes prompt injection succeeds rather than pretending to
     * detect it. A name is allowed to contain anything but `/` and NUL, so an unrendered one puts
     * attacker-chosen *lines* into the plugin's own control text, at the same level as the prohibitions and
     * ahead of them.
     */
    fun commitPrompt(changed: List<String>): String {
        val files = changed.take(MAX_LISTED_FILES).joinToString("\n") { "- ${oneLine(it)}" }
        val more = (changed.size - MAX_LISTED_FILES).takeIf { it > 0 }?.let { "\n- …and $it more\n" }.orEmpty()
        return "Commit the current changes in this repository.\n\n" +
            "The working tree has these changes:\n$files\n$more\n" +
            "Stage them and make ONE commit, writing the message yourself: a short imperative subject line, " +
            "and a body only if the change needs a reason. Do not push, do not amend an existing commit, do " +
            "not rebase, and do not create, switch or delete any branch. Tell me the subject line you used."
    }

    /**
     * Restore one file to its committed state.
     *
     * The path is repeated in the prohibition on purpose: `git checkout --` and `git restore` both take a
     * pathspec that quietly means *everything* when it is wrong, and this is the one action here that destroys
     * work rather than recording it.
     *
     * The path is rendered through [oneLine] for the reason [commitPrompt] gives, and it matters more here: this
     * prompt names a **destructive** command, so a path carrying a newline could append a second, unprohibited
     * instruction to it. The trade-off is deliberate and is the safe side of the two — a path that had to be
     * altered names a file that does not exist, so the turn fails with an error instead of restoring the wrong
     * one.
     */
    fun revertFilePrompt(path: String): String {
        val safe = oneLine(path)
        return "Restore `$safe` to its committed state, using `git restore -- $safe` (or `git checkout -- $safe` " +
            "on an older Git).\n\n" +
            "That one file, and no other. Do not pass any other pathspec, do not use `.` or `-A`, do not run " +
            "`git reset`, `git clean` or `git stash`, and do not touch the index for anything else. This " +
            "discards uncommitted work in that file, so run exactly the command above and nothing more."
    }

    /**
     * One repository path, rendered so that it can only ever *be* a path: one line, no control characters, no
     * backticks.
     *
     * Three separate things are being prevented and they are not the same one. A control character — `\n` above
     * all — ends the line the path was written on and lets the rest of the name become prose the model reads as
     * the plugin speaking, **before** the prohibition block that every one of these prompts ends with; being the
     * last word is precisely what makes that block work. A backtick closes the code span the path sits inside,
     * so the remainder is no longer marked as a literal. And the two Unicode separator categories — `Zl`
     * (`U+2028`) and `Zp` (`U+2029`) — are line breaks to plenty of renderers while not being ISO controls, so
     * they are excluded BY CATEGORY rather than by naming the code points: a character literal for either would
     * be an invisible line break sitting in this source file, which is the same class of trick the rule exists
     * to defeat.
     *
     * **Replaced, never deleted, and never trimmed.** The rendered path stays the same length and keeps its
     * leading and trailing spaces, both of which can be part of a real POSIX name; a deletion would silently
     * turn one existing path into another. Nothing bounds the length either, because the operating system
     * already does — the caller's list is capped at [MAX_LISTED_FILES] entries and each is at most a `PATH_MAX`.
     *
     * This is a rendering rule, not a security control: what stops a turn that wanders off the prompt is the
     * approval card, which the Git chat forces regardless of permission mode.
     */
    private fun oneLine(path: String): String = path.map { if (isRenderable(it)) it else ' ' }.joinToString("")

    /** True when a character may appear in a rendered path verbatim — see [oneLine] for what each exclusion is. */
    private fun isRenderable(ch: Char): Boolean =
        ch != '`' && !Character.isISOControl(ch) && Character.getType(ch) !in SEPARATOR_CATEGORIES

    // ── the entries ───────────────────────────────────────────────────────────────────────────────────────────

    /** [toolbarAction]'s implementation — see its KDoc for why it is always visible and why it auto-asks. */
    private class OpenGitChatAction(private val project: Project, private val gitChat: () -> ClaudeSession) :
        AnAction("Git", "Open the Git chat — Claude runs the Git commands, you approve each one", AllIcons.Vcs.Branch) {

        override fun update(e: AnActionEvent) {
            // Deliberately NOT gated on there being a repository: an empty project is where this is worth most.
            e.presentation.isVisible = !project.isDisposed && GitAvailability.isGitPluginEnabled()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (project.isDisposed) return
            // Opening the tab is all this does. What to offer there — including *Initialize repository* on a
            // project that is not a repository yet — is the Git view's own business, drawn from
            // `GitActionCatalog` and pushed with the rest of the payload. Sending a prompt from here as well
            // would put a turn in the transcript for a button the user has not pressed.
            gitChat()
        }

        /** BGT: the plugin registry is in-memory state, and the repository read happens in `actionPerformed`. */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    /** Offered while the working tree has something to record. */
    private class CommitChangesAction(project: Project, gitChat: () -> ClaudeSession) :
        PromptEntry(
            project,
            gitChat,
            "Commit Changes with Claude",
            "Ask Claude to stage the current changes and commit them, message included",
        ) {

        override fun isApplicable(history: GitHistoryService): Boolean =
            history.isAvailable() && history.workingTreeChanges().isNotEmpty()

        override fun prompt(history: GitHistoryService): String? =
            history.workingTreeChanges().takeIf { it.isNotEmpty() }?.let { commitPrompt(it) }
    }

    /** Offered when the file in the editor is one of the changed ones — otherwise there is nothing to restore. */
    private class RevertFileAction(project: Project, gitChat: () -> ClaudeSession) :
        PromptEntry(
            project,
            gitChat,
            "Revert This File with Claude",
            "Ask Claude to restore the file in the editor to its committed state",
        ) {

        override fun isApplicable(history: GitHistoryService): Boolean = changedFile(history) != null

        override fun prompt(history: GitHistoryService): String? = changedFile(history)?.let { revertFilePrompt(it) }

        /**
         * The editor's file as a repository-relative path, but only if Git reports it as changed.
         *
         * Matched against [GitHistoryService.workingTreeChanges] rather than just relativised: an unchanged file
         * would produce a prompt that restores nothing, and a file outside the repository would produce one that
         * names a path `git restore` cannot resolve. Both are answered by hiding the entry.
         */
        private fun changedFile(history: GitHistoryService): String? {
            if (!history.isAvailable()) return null
            val absolute = EditorContextProvider.currentFilePath(project) ?: return null
            val root = history.primaryRepositoryRoot() ?: return null
            val relative = GitCommitInfo.relativize(root, absolute)
            return relative.takeIf { it in history.workingTreeChanges() }
        }
    }

    /**
     * Shared behaviour: find the service, decide whether the entry applies, and — when clicked — put the prompt
     * into the Git chat.
     *
     * **Absent, not greyed**, the same rule [GitContextActions] follows: an "Initialize Git Repository" that
     * does nothing because there already is one is worse than no entry. Visibility is re-derived on every menu
     * open, so `git init` (or a first edit) takes effect without reopening the tool window.
     */
    private abstract class PromptEntry(
        protected val project: Project,
        private val gitChat: () -> ClaudeSession,
        text: String,
        description: String,
    ) : AnAction(text, description, null) {

        /** True when this entry means something in the project's current state. */
        abstract fun isApplicable(history: GitHistoryService): Boolean

        /** What to ask, or null if the state changed between the menu opening and the click. */
        abstract fun prompt(history: GitHistoryService): String?

        protected fun history(): GitHistoryService? =
            if (project.isDisposed) null else project.service<GitHistoryService>()

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = history()?.let { isApplicable(it) } == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = history()?.let { prompt(it) } ?: return
            // `gitChat()` finds the tab or opens one, and `send` queues — so a chat whose process is still
            // starting keeps the prompt and writes it when there is one, instead of dropping it silently.
            gitChat().send(text)
        }

        /**
         * BGT: every read behind [isApplicable] is in-memory platform state — the repository registry, the
         * change list the Local Changes view already computed, and the selected editor. Nothing spawns a
         * process (`GitHistoryService.recentCommits` is the one that does, and none of these calls it).
         */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    /** How many changed paths are listed in the commit prompt before the rest become a count. */
    private const val MAX_LISTED_FILES = 40

    /**
     * The Unicode general categories that are a line break without being an ISO control: `Zl` (`U+2028`) and
     * `Zp` (`U+2029`). Named by category rather than by code point — see [oneLine].
     */
    private val SEPARATOR_CATEGORIES =
        setOf(Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())

    /**
     * What a repository created from here is called.
     *
     * Not left to Git's default: a bare `git init` lands on `master` unless `init.defaultBranch` is set, which
     * is not a default anyone chose. `GitIntegration.gitInit` is what passes it — creating a repository is the
     * one Git command the plugin runs itself rather than asking the agent for, so there is no prompt here to
     * point at.
     */
    const val INITIAL_BRANCH = "main"
}
