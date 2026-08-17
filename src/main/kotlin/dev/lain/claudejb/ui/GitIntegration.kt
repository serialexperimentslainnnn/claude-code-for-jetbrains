package dev.lain.claudejb.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.ui.jcef.JcefGitData
import java.io.File

/**
 * The Git view's **runtime**: it collects what the view draws, and it runs what a button on it asks for.
 *
 * One per project, because a project has one working tree and one Git chat — the state of "Commit with Claude"
 * is not a property of whichever tab happens to be on screen.
 *
 * **Three kinds of action, three different executors** ([GitActionCatalog.Kind]), and the split is the whole
 * design: the plugin itself runs only the one command that has nothing to decide, the agent runs the ones whose
 * value is in *why* the change was made, and the IDE runs the ones it already does better than a chat card
 * could. Which kind an id is comes from [GitActionCatalog] and is never restated here.
 *
 * **This file lives in `ui/`, not in `git/`, deliberately.** `GitReadOnlyContractTest` forbids
 * [GeneralCommandLine] inside `dev.lain.claudejb.git`, and that contract must stay true: the read-only package
 * is what guarantees the branch, the log and the change list can never become a write path. The one command the
 * plugin does run therefore lives outside it, in plain sight, with its argv fixed in this file.
 *
 * **Nothing the page sends reaches a command line.** The inbound message carries an id and nothing else; the id
 * is looked up in the catalogue and an unknown one is dropped. Every argv below is a literal, the working
 * directory is the project's own base path, and no shell is involved — so there is no string for a caller to
 * inject into.
 *
 * **Threading.** [refresh] and [perform] are EDT entry points (the browser bridge delivers there, and reading
 * which file is in the editor is an EDT-only question). Everything that spawns a process — `git log` behind
 * [GitHistoryService.recentCommits], and `git init` itself — runs on a pooled thread and comes back to the EDT
 * to publish.
 */
@Service(Service.Level.PROJECT)
internal class GitIntegration(private val project: Project) {

    /** The last collected snapshot, or null before the first collection. EDT-confined. */
    private var snapshot: JcefGitData.Snapshot? = null

    /** id → how the action the user launched is going. Absent = never run. EDT-confined. */
    private val states = mutableMapOf<String, JcefGitData.ActionState>()

    /** True while a collection is in flight; [again] is a collection asked for while one was. EDT-confined. */
    private var collecting = false
    private var again = false

    /** What the view should draw right now, or null if nothing has been collected yet. */
    fun snapshot(): JcefGitData.Snapshot? = snapshot

    /**
     * Re-reads the repository and calls [onChanged] once the new snapshot is published.
     *
     * Collapsed rather than throttled: a second request made while one is in flight does not queue a second
     * `git log`, it marks the in-flight one as stale and re-runs exactly once when it lands. Turn edges fire
     * this several times a turn, and a timer-based throttle would drop the last one — which is the one that
     * matters, since it is the one after the commit.
     */
    fun refresh(onChanged: () -> Unit) {
        if (collecting) {
            again = true
            return
        }
        collecting = true
        // The editor is an EDT-only question, so it is answered here and carried into the background read.
        val openFile = EditorContextProvider.currentFilePath(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val collected = runCatching { collect(openFile) }.getOrElse {
                LOG.warn("Git snapshot collection failed for ${project.name}", it)
                null
            }
            edt {
                collecting = false
                if (collected != null) snapshot = collected
                onChanged()
                if (again) {
                    again = false
                    refresh(onChanged)
                }
            }
        }
    }

    /**
     * Runs the catalogue action [id] asked for by the Git view.
     *
     * [chat] is how a prompted action reaches the Git conversation — it opens or selects that tab, so it is
     * called only for the kind that needs it. [onChanged] is fired whenever an action's state moves, which for
     * a prompted action is twice: when it starts, and when the turn it started settles.
     */
    fun perform(id: String, chat: () -> ClaudeSession, onChanged: () -> Unit) {
        // The page is a trust boundary: an id this build does not know is dropped, never passed along.
        val action = GitActionCatalog.byId(id) ?: run {
            LOG.warn("Git view asked for an unknown action id: $id")
            return
        }
        when (action.kind) {
            GitActionCatalog.Kind.DIRECT -> runDirect(action, onChanged)

            GitActionCatalog.Kind.PROMPT -> runPrompt(action, chat, onChanged)

            // Deliberately stateless: an IDE action opens a dialog and the answer is the user's, not ours.
            // Painting a "running" pill over a dialog nobody has answered yet would be a state we invented.
            GitActionCatalog.Kind.IDE -> invokeIde(action)
        }
    }

    // ── collection ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the repository. **Off the EDT**: [GitHistoryService.recentCommits] spawns `git log` and refuses to
     * run on it.
     *
     * `available` is the Git *plugin*, not a repository: a project with no repository is precisely the state
     * that offers `init`, so the view has to be drawable there. Whether there is a repository is `repo.present`.
     */
    private fun collect(openFilePath: String?): JcefGitData.Snapshot {
        if (!GitAvailability.isGitPluginEnabled()) return JcefGitData.Snapshot(available = false)
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot()
        if (root == null) {
            return JcefGitData.Snapshot(available = true, actionStates = states.toMap())
        }
        val changes = history.workingTreeChanges()
        return JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(
                present = true,
                branch = history.currentBranch(),
                head = history.headRevision(),
                root = root,
            ),
            changes = changes,
            commits = history.recentCommits(),
            changedFileOpen = relativeChangedFile(root, changes, openFilePath) != null,
            actionStates = states.toMap(),
        )
    }

    /**
     * The editor's file as a repository-relative path, and only when Git reports it as changed.
     *
     * Matched against the change list rather than merely relativised, for the two reasons the gear entry gives:
     * an unchanged file produces a prompt that restores nothing, and a file outside the repository produces one
     * naming a path `git restore` cannot resolve.
     */
    private fun relativeChangedFile(root: String, changes: List<String>, absolutePath: String?): String? {
        val absolute = absolutePath ?: return null
        return GitCommitInfo.relativize(root, absolute).takeIf { it in changes }
    }

    // ── DIRECT: the one command the plugin runs itself ────────────────────────────────────────────────────────

    /**
     * `git init`, on a pooled thread, then makes the IDE see the repository.
     *
     * Refuses on a directory that already has one. The catalogue only offers `init` when there is no repository,
     * but the offer travels through the browser and a stale view is a normal thing to have on screen — so the
     * precondition is re-checked where it is acted on, not where it is drawn.
     */
    private fun runDirect(action: GitActionCatalog.GitAction, onChanged: () -> Unit) {
        if (action.id != INIT) {
            LOG.warn("No direct command is wired for Git action '${action.id}'")
            return
        }
        val root = project.basePath?.let(::File)
        if (root == null || !root.isDirectory || File(root, DOT_GIT).exists()) {
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        states[action.id] = JcefGitData.ActionState.RUNNING
        onChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = gitInit(root)
            edt {
                settle(action.id, if (ok) JcefGitData.ActionState.COMPLETED else JcefGitData.ActionState.FAILED, onChanged)
                if (ok) registerRepository(root)
            }
        }
    }

    /**
     * `git init -b main`, with a fallback for a Git that predates `-b` (2.28, Jul 2020).
     *
     * A bare `git init` leaves the repository on `master` unless the user set `init.defaultBranch` — not a
     * default anyone chose, just what Git still does for compatibility. The fallback moves HEAD with
     * `symbolic-ref` rather than `git branch -m`, because on a repository with no commits there is no branch to
     * rename: `branch -m` on an unborn HEAD only started working in Git 2.30, which is *later* than the version
     * the fallback exists for. `symbolic-ref` is what `-b` does internally and works on every version.
     */
    private fun gitInit(root: File): Boolean {
        if (runGit(root, "init", "-b", GitPromptedActions.INITIAL_BRANCH)) return true
        return runGit(root, "init") && runGit(root, "symbolic-ref", "HEAD", "refs/heads/${GitPromptedActions.INITIAL_BRANCH}")
    }

    /**
     * Runs `git` with a fixed argument vector. **No shell**, so nothing here can be quoted, expanded or chained
     * into something else, and every argument is a literal from this file.
     *
     * `git` is resolved through `PATH` the same way the IDE's own terminal resolves it; the plugin holds no
     * second opinion about where the user's Git lives.
     */
    private fun runGit(root: File, vararg args: String): Boolean {
        val output = runCatching {
            val cmd = GeneralCommandLine(listOf(GIT) + args)
                .withWorkingDirectory(root.toPath())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(GIT_TIMEOUT_MS, true)
        }.getOrElse {
            LOG.warn("Could not run `git ${args.joinToString(" ")}` in $root", it)
            return false
        }
        if (output.isTimeout || output.exitCode != 0) {
            LOG.warn("`git ${args.joinToString(" ")}` failed in $root (exit ${output.exitCode}): ${output.stderr.trim()}")
            return false
        }
        return true
    }

    /**
     * Makes the IDE notice the repository that was just created, without the user reopening the project.
     *
     * Two things have to happen and they are not the same one: the VFS has to learn that `.git` exists, and the
     * VCS mapping list has to name the root — a refresh alone leaves the Local Changes view empty and the Git
     * Log unavailable. The mapping is **appended**, never assigned over: `setDirectoryMappings` replaces the
     * whole list, so passing only the new one would silently drop every mapping the project already had.
     * Read-append-set is not a workaround, it is the platform's own idiom — `VcsIntegrationEnabler.addVcsRoots`
     * does exactly this, and the API type exposes no incremental registration to prefer over it.
     *
     * **`getDirectoryMappings()` is called as a function on purpose, and `manager.directoryMappings` is a bug.**
     * `ProjectLevelVcsManager` was converted from Java to Kotlin, and the conversion left a source-compatibility
     * `var directoryMappings` behind it — `@JvmSynthetic`, `@JvmName("getDirectoryMappingsDoNotUse")` and
     * `@Deprecated`. Kotlin property syntax binds to that shim rather than to the live abstract method, so the
     * short spelling compiles, works, and ships a deprecated platform API: `verifyPlugin` failed the build on
     * `DEPRECATED_API_USAGES` in all six verified IDEs for this one line. The same shape as the banned
     * `PluginId.getId(…)`: a platform type that became Kotlin under a call site that never changed.
     *
     * `"Git"` is the VCS's registered name, deliberately as a string: naming `git4idea`'s own class here would
     * put a type from an optional plugin on a code path that runs whether or not it is enabled.
     */
    private fun registerRepository(root: File) {
        val dir = LocalFileSystem.getInstance().findFileByPath(root.path) ?: return
        // markDirtyAndRefresh(async, recursive, reloadChildren, file) — a Java API, so the flags cannot be named
        // at the call site. Async keeps the walk off the hot path; recursive + reloadChildren are what make a
        // directory the VFS has never heard of (`.git`) actually appear.
        VfsUtil.markDirtyAndRefresh(true, true, true, dir)
        if (!GitAvailability.isGitPluginEnabled()) return
        val manager = ProjectLevelVcsManager.getInstance(project)
        val existing = manager.getDirectoryMappings()
        if (existing.any { it.vcs == GIT_VCS_NAME && it.directory == root.path }) return
        manager.setDirectoryMappings(existing + VcsDirectoryMapping(root.path, GIT_VCS_NAME))
    }

    // ── PROMPT: the agent does it, in the Git chat, behind an approval card ───────────────────────────────────

    /**
     * Writes the action's prompt into the Git chat and follows that turn to its end.
     *
     * The prompt texts are [GitPromptedActions]' pure builders — the same ones the gear entries use and the same
     * ones the tests pin. A second copy of a prompt whose prohibitions are the safety margin is how the button
     * and the menu come to ask for different things.
     */
    private fun runPrompt(action: GitActionCatalog.GitAction, chat: () -> ClaudeSession, onChanged: () -> Unit) {
        val text = promptFor(action)
        if (text == null) {
            // The state moved under the view between drawing the button and pressing it (nothing left to commit,
            // the editor moved on). Failing loudly beats sending a prompt that names nothing.
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        val session = chat()
        states[action.id] = JcefGitData.ActionState.RUNNING
        onChanged()
        session.addListener(TurnWatch(action.id, session, onChanged))
        // `send` queues, so a Git chat whose process is still starting keeps the prompt instead of dropping it.
        session.send(text)
    }

    private fun promptFor(action: GitActionCatalog.GitAction): String? {
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot() ?: return null
        return when (action.id) {
            COMMIT -> history.workingTreeChanges().takeIf { it.isNotEmpty() }?.let(GitPromptedActions::commitPrompt)

            REVERT_FILE -> relativeChangedFile(root, history.workingTreeChanges(), EditorContextProvider.currentFilePath(project))
                ?.let(GitPromptedActions::revertFilePrompt)

            else -> {
                LOG.warn("No prompt is wired for Git action '${action.id}'")
                null
            }
        }
    }

    /**
     * Settles a prompted action when the turn it started ends.
     *
     * [started] is why this is a listener and not a callback on `send`: the prompt is *queued*, so if a turn was
     * already running in the Git chat the next `TURN_DONE` belongs to that one. Waiting to see the turn actually
     * begin is what stops the button reporting "completed" for work that has not started.
     */
    private inner class TurnWatch(
        private val id: String,
        private val session: ClaudeSession,
        private val onChanged: () -> Unit,
    ) : SessionListener {

        private var started = false

        override fun onStateChanged() {
            if (session.turnActive) started = true
        }

        override fun onAttention(reason: AttentionReason) {
            // A permission card is the middle of the turn, not its end — that is exactly when a Git action is
            // waiting for the user, which is still `running`.
            if (reason == AttentionReason.PERMISSION || !started) return
            session.removeListener(this)
            val state = if (reason == AttentionReason.ERROR) {
                JcefGitData.ActionState.FAILED
            } else {
                JcefGitData.ActionState.COMPLETED
            }
            settle(id, state, onChanged)
        }
    }

    // ── IDE: the platform's own action, by id ─────────────────────────────────────────────────────────────────

    /**
     * Invokes the platform action the catalogue names, in this project's context.
     *
     * `ActionUtil.performAction` and **not** `ActionUtil.invokeAction`: all three `invokeAction` overloads are
     * `@Deprecated` on the platform this plugin compiles against, and this repository does not ship deprecated
     * API — `verifyPlugin` fails the build on `DEPRECATED_API_USAGES`. `IdeActionApiContractTest` pins that
     * choice against the build classpath so the next deprecation is caught in milliseconds instead of at release
     * time.
     *
     * An id the running IDE does not have is skipped rather than thrown on: the Git plugin can be disabled, and
     * an action id is JetBrains' to rename.
     */
    private fun invokeIde(action: GitActionCatalog.GitAction) {
        val actionId = action.ideActionId ?: return
        val target = ActionManager.getInstance().getAction(actionId) ?: run {
            LOG.warn("This IDE has no action '$actionId'; the Git view's '${action.id}' button does nothing")
            return
        }
        val event = AnActionEvent.createEvent(
            target,
            SimpleDataContext.getProjectContext(project),
            null,
            ActionPlaces.TOOLWINDOW_CONTENT,
            ActionUiKind.NONE,
            null,
        )
        ActionUtil.performAction(target, event)
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun settle(id: String, state: JcefGitData.ActionState, onChanged: () -> Unit) {
        states[id] = state
        onChanged()
    }

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, ModalityState.any())

    companion object {

        fun getInstance(project: Project): GitIntegration = project.service()

        /** The catalogue ids this file knows how to act on. */
        const val INIT = "init"
        const val COMMIT = "commit"
        const val REVERT_FILE = "revertFile"

        /** The VCS's registered name — see [registerRepository] for why it is a string and not a class. */
        private const val GIT_VCS_NAME = "Git"

        private const val GIT = "git"
        private const val DOT_GIT = ".git"

        /** `git init` is local and instant; anything past this is a hung binary, not a slow one. */
        private const val GIT_TIMEOUT_MS = 15_000

        private val LOG = logger<GitIntegration>()
    }
}
