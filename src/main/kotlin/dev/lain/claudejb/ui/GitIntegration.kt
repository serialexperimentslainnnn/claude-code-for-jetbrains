package dev.lain.claudejb.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.DataManager
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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.forge.ForgeAnswer
import dev.lain.claudejb.forge.ForgeProbe
import dev.lain.claudejb.forge.ForgeProvider
import dev.lain.claudejb.forge.ForgeRepo
import dev.lain.claudejb.forge.ForgeService
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.git.GitLogNavigator
import dev.lain.claudejb.git.GitLogScope
import dev.lain.claudejb.git.GitRemoteProvider
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.ui.jcef.JcefGitData
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * The Git view's **runtime**: it collects what the view draws, and it runs what a button on it asks for.
 *
 * One per project, because a project has one working tree and one Git chat — the state of "Commit with Claude"
 * is not a property of whichever tab happens to be on screen.
 *
 * **Four kinds of action, four different executors** ([GitActionCatalog.Kind]), and the split is the whole
 * design: the plugin itself runs only the one command that has nothing to decide, the agent runs the ones whose
 * value is in *why* the change was made, the IDE runs the ones it already does better than a chat card could,
 * and the host answers the pure reads with no process and no turn at all. Which kind an id is comes from
 * [GitActionCatalog] and is never restated here.
 *
 * **This file lives in `ui/`, not in `git/`, deliberately.** `GitReadOnlyContractTest` forbids
 * [GeneralCommandLine] inside `dev.lain.claudejb.git`, and that contract must stay true: the read-only package
 * is what guarantees the branch, the log and the change list can never become a write path. The one command the
 * plugin does run therefore lives outside it, in plain sight, with its argv fixed in this file.
 *
 * **Nothing the page sends reaches a command line.** The inbound message carries an id and a commit hash; the
 * id is looked up in the catalogue and an unknown one is dropped, and the hash is refused unless it has the
 * shape of a Git object name (see [perform]). Every argv below is a literal, the working directory is the
 * project's own base path, and no shell is involved — so there is no string for a caller to inject into.
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
     * Runs the catalogue action [id] asked for by the Git view, against commit [hash] when it needs one.
     *
     * [chat] is how a prompted action reaches the Git conversation — it opens or selects that tab, so it is
     * called only for the kind that needs it. [onChanged] is fired whenever an action's state moves, which for
     * a prompted action is twice: when it starts, and when the turn it started settles.
     *
     * **Two values arrive from the browser and they are checked by two different mechanisms**, because they are
     * two different kinds of value. [id] is closed: it is looked up in the catalogue and an id this build does
     * not know is dropped. [hash] is open by definition — the whole point of a commit hash is to be a value
     * never seen before — so it is checked for the SHAPE of a Git object name, HERE, before anything can be
     * built from it. Checking it at the boundary is what makes the refusal VISIBLE: a builder that rejects the
     * value returns null, which every caller already treats as "the repository moved under the view", so a
     * hostile value and a stale button would report the same thing and neither would name what happened.
     *
     * A hash is only looked at when the entry says it takes one (`takesCommit`). For every other entry the
     * value is ignored outright, so a page that sends one cannot make it reach a prompt with no commit in it.
     */
    fun perform(id: String, hash: String, chat: () -> ClaudeSession, onChanged: () -> Unit) {
        // The page is a trust boundary: an id this build does not know is dropped, never passed along.
        val action = GitActionCatalog.byId(id) ?: run {
            LOG.warn("Git view asked for an unknown action id: $id")
            return
        }
        if (action.takesCommit && !GitActionCatalog.isCommitHash(hash)) {
            // Not a warning about the user: nothing on the page can produce this. It is either a stale view
            // whose payload predates the commit list, or something sending its own messages — and in the
            // second case the value is exactly the one that must not reach a prompt naming git commands.
            LOG.warn("Git view asked for '$id' with a value that is not a commit hash; refusing")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        when (action.kind) {
            GitActionCatalog.Kind.DIRECT -> runDirect(action, onChanged)

            GitActionCatalog.Kind.PROMPT -> runPrompt(action, hash, chat, onChanged)

            // Deliberately stateless: an IDE action opens a dialog and the answer is the user's, not ours.
            // Painting a "running" pill over a dialog nobody has answered yet would be a state we invented.
            GitActionCatalog.Kind.IDE -> invokeIde(action, onChanged)

            GitActionCatalog.Kind.HOST -> runHost(action, hash, onChanged)
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
        val branch = history.currentBranch()
        val forge = forgeRepo(history)
        return JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(
                present = true,
                branch = branch,
                head = history.headRevision(),
                root = root,
            ),
            changes = changes,
            // EVERY line, not just the one HEAD is on, and that is the difference between a graph and a rail:
            // a fork can only be drawn when both sides of it are in the list, so with `HEAD` alone the page has
            // nothing to fork into and draws a straight line however good the layout is. Both arguments are
            // named here rather than left to their defaults because the gear menu reads this same method and
            // means one branch, and twenty commits, by it — see GRAPH_COMMIT_LIMIT for the second half.
            commits = history.recentCommits(limit = GRAPH_COMMIT_LIMIT, scope = GitLogScope.EVERY_LINE_OF_DEVELOPMENT),
            // The graph's other half. Read here rather than on the page because only the IDE knows which
            // refs exist: the commits carry their parents, which is the shape, and these say which line is
            // `main` and which one HEAD is on. Costs no process — it is the ref state already in memory.
            refs = history.refs(),
            changedFileOpen = relativeChangedFile(root, changes, openFilePath) != null,
            actionStates = states.toMap(),
            topology = history.branchTopology(),
            pullRequests = forge.drawable(branch) { repo, on -> ForgeService.openPullRequests(repo, on) },
            lastRun = forge.drawable(branch) { repo, on -> ForgeService.lastRun(repo, on) },
        )
    }

    /**
     * The repository as the forge knows it, or null when there is nothing to ask or nobody to ask.
     *
     * Three things have to line up and each absence is ordinary rather than an error: a remote to read
     * ([GitHistoryService.primaryRemote] — `origin`, else `upstream`, else the only one), an owner and a name
     * in it, and a token stored for that host.
     *
     * **The token is required BEFORE the provider is known, and that ordering is deliberate.** A host whose
     * name does not identify the forge is settled by asking its API ([ForgeProbe]) — a network request to a
     * server named by whatever repository happens to be open. Requiring the token first makes that request
     * something the user opted into by storing a credential for that host, rather than something the plugin
     * does to every remote it sees. It is also the only state in which the answer could be used.
     */
    private fun forgeRepo(history: GitHistoryService): ForgeRepo? {
        val remote = history.primaryRemote() ?: return null
        val host = remote.host ?: return null
        val owner = remote.owner ?: return null
        val name = remote.repo ?: return null
        val token = ForgeTokens.get(host) ?: return null
        val provider = when (remote.provider) {
            GitRemoteProvider.GITHUB -> ForgeProvider.GITHUB

            GitRemoteProvider.GITLAB -> ForgeProvider.GITLAB

            // The URL says nothing, which is the normal shape of a self-hosted instance. Ask the host.
            GitRemoteProvider.OTHER -> ForgeProbe.detect(host, token) ?: return null
        }
        return ForgeRepo(provider, host, owner, name)
    }

    /**
     * Runs [ask] and reduces its answer to "what the card should draw", where **null means draw no card**.
     *
     * [ForgeAnswer.Silent] is deliberately flattened to null here and nowhere earlier: the reasons matter to
     * `idea.log` and to nothing else, and the UI's only correct reaction to any of them is to show nothing.
     * A card that appears to say "no token" is a card asking to be configured for a feature the user has not
     * asked for. What must NOT be flattened with it is [ForgeAnswer.Known] of an empty list — "this branch
     * has no open pull request" is an answer, and it is worth a row.
     */
    private fun <T> ForgeRepo?.drawable(branch: String?, ask: (ForgeRepo, String) -> ForgeAnswer<T>): T? {
        val repo = this ?: return null
        val on = branch?.takeIf { it.isNotBlank() } ?: return null
        return when (val answer = ask(repo, on)) {
            is ForgeAnswer.Known -> answer.value
            is ForgeAnswer.Silent -> null
        }
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
    private fun runPrompt(
        action: GitActionCatalog.GitAction,
        hash: String,
        chat: () -> ClaudeSession,
        onChanged: () -> Unit,
    ) {
        val text = promptFor(action, hash)
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

    /**
     * The prompt text for a prompted entry, or null when the repository can no longer answer for it.
     *
     * The two commit entries re-check the hash inside their own builders rather than trusting [perform]'s
     * check. That is not the same rule enforced twice: those builders are public and pinned by
     * `GitPromptedActionsTest`, so they own the guarantee that a prompt they return can only ever name an
     * object name, whoever called them.
     */
    private fun promptFor(action: GitActionCatalog.GitAction, hash: String): String? {
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot() ?: return null
        return when (action.id) {
            COMMIT -> history.workingTreeChanges().takeIf { it.isNotEmpty() }?.let(GitPromptedActions::commitPrompt)

            REVERT_FILE -> relativeChangedFile(root, history.workingTreeChanges(), EditorContextProvider.currentFilePath(project))
                ?.let(GitPromptedActions::revertFilePrompt)

            COMMIT_REVERT_TO_BRANCH -> GitPromptedActions.revertToCommitOnNewBranchPrompt(hash)

            COMMIT_REVERT -> GitPromptedActions.revertCommitPrompt(hash)

            else -> {
                LOG.warn("No prompt is wired for Git action '${action.id}'")
                null
            }
        }
    }

    // ── HOST: the plugin answers it itself, running no `git` and asking no agent ──────────────────────────────

    /**
     * The two reads of the history rail: show the commit, or put its hash on the clipboard.
     *
     * Neither spawns a process and neither costs a turn (see [GitActionCatalog.Kind] for why they are not
     * `DIRECT` and not `PROMPT`). [hash] has already been established as an object name by [perform]; nothing
     * here re-derives it, and nothing here writes to the repository.
     */
    private fun runHost(action: GitActionCatalog.GitAction, hash: String, onChanged: () -> Unit) {
        val done = when (action.id) {
            // The FULL hash the row carried, not the abbreviation on screen: what a user pastes into a command
            // has to resolve without depending on how unique seven characters happen to be in this repository.
            COMMIT_COPY_HASH -> {
                CopyPasteManager.getInstance().setContents(StringSelection(hash))
                true
            }

            COMMIT_DIFF -> GitLogNavigator.showCommit(project, hash)

            else -> {
                LOG.warn("No host action is wired for Git action '${action.id}'")
                false
            }
        }
        settle(action.id, if (done) JcefGitData.ActionState.COMPLETED else JcefGitData.ActionState.FAILED, onChanged)
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
     * Invokes the platform action the catalogue names, in the context of the tool window it was pressed from.
     *
     * **The data context is the whole of this function, and getting it wrong made every one of these buttons
     * do nothing.** It used to be `SimpleDataContext.getProjectContext(project)`, which carries exactly one
     * key. A platform action resolves its target from the context it is handed — the repository, the selected
     * files, the change list — and when it cannot find one it disables itself in its `update`.
     * `DataManager.getDataContext(component)` is what the platform gives its own menus: the project, the
     * component, and every key the tool window's providers contribute.
     *
     * **What the context is NOT is a place for a popup to appear**, and reading it as one is what sends a
     * report about a misbehaving popup to this file. An action that opens one decides that itself:
     * `Git.Branches` is `git4idea.ui.branch.GitBranchesAction`, and its `actionPerformed` ends in
     * `showCenteredInCurrentWindow(project)`, which centres on the focused component's outermost window or
     * else the project frame and never asks the context for a component. Nothing passed from here can move,
     * size or re-parent one of these popups.
     *
     * **Nor can one of them be a lightweight Swing surface drawn under the browser's native window** — the
     * other thing a tool window made of JCEF invites people to assume. Both popup families on this path are
     * native windows already: `AbstractPopup.init` sets its heavyweight flag unconditionally and
     * `getMostSuitablePopupType()` answers `HEAVYWEIGHT` whenever it is set, while an action menu is an
     * `ActionPopupMenuImpl` menu, which extends `JBPopupMenu` — whose constructor calls
     * `setLightWeightPopupEnabled(false)`. So there is nothing here to force heavyweight, and the JVM-wide
     * switch that would force it belongs to the IDE and not to a plugin: it would change nothing on this path
     * and every popup in the application. `IdeActionApiContractTest` fails the build if one appears.
     *
     * **Nor, on Wayland, can one of them end up hanging off the browser's surface** — the same assumption a
     * layer down, and the one worth refuting in writing because a compositor decides who receives pointer
     * events while a popup is open, which makes it sound like the whole explanation for a popup that shows and
     * then ignores the mouse. It is not, and the reason is structural rather than circumstantial:
     * `WLComponentPeer.getToplevelFor` walks the AWT container chain and returns the first `Window` that is not
     * itself a popup, while `AbstractPopup.show` forces `SwingUtilities.getRoot(owner)` on Wayland. A child
     * component is not a candidate in either, so the parent toplevel is the project frame no matter what is
     * focused — which is also why "take focus out of the chat before invoking" is not a fix waiting to be
     * written. It does not even depend on how the browser is rendered, though that settles it a second time:
     * under the IDE's default (`ide.browser.jcef.osr.enabled`) the browser is off-screen-rendered, i.e. Swing
     * painting pixels, with no native surface to hand a grab to. The ONE thing that changes the owner is
     * undocking the tool window, because `AbstractPopup.getTargetWindow` returns a `FloatingDecorator` before
     * it ever reaches the frame. So a popup on this path that misbehaves under a compositor is the IDE's;
     * `docs/TROUBLESHOOTING.md` carries the symptom, what it is not, and the way round it.
     *
     * **The update must be RUN, and `performAction` does not run it.** Read on 253: `performAction` only
     * *reads* `event.presentation.isEnabled` and returns `ignored("action is disabled")` — so with a
     * presentation nobody has populated, every one of these buttons is decided by a default rather than by the
     * action. Populating it is therefore load-bearing, and it goes through **`ActionUtil.updateAction`**, never
     * `AnAction.update` directly: that method is `@ApiStatus.OverrideOnly` — a thing to implement, not to call
     * — and `OVERRIDE_ONLY_API_USAGES` is in `verifyPlugin`'s `failureLevel`, so calling it fails the build.
     * It is invisible to `IdeActionApiContractTest`, which asserts by reflection and cannot see a CLASS-retained
     * annotation; the verifier is the only gate that catches it.
     *
     * **A refusal is reported, not swallowed.** If the action is still disabled with a real context, that is
     * the IDE saying no (no repository, indexing, nothing selected) and the button says FAILED rather than
     * looking broken — silence is the failure mode this whole function is a fix for.
     *
     * `ActionUtil.performAction` and **not** `ActionUtil.invokeAction`: all three `invokeAction` overloads are
     * `@Deprecated` on the platform this plugin compiles against, and this repository does not ship deprecated
     * API — `verifyPlugin` fails the build on `DEPRECATED_API_USAGES`. `IdeActionApiContractTest` pins that
     * choice against the build classpath so the next deprecation is caught in milliseconds instead of at release
     * time. `performDumbAwareUpdate` and `lastUpdateAndCheckDumb` are the same trap in the other direction:
     * both are deprecated on 253, so either would trade this failure for that one.
     *
     * An id the running IDE does not have is skipped rather than thrown on: the Git plugin can be disabled, and
     * an action id is JetBrains' to rename.
     */
    private fun invokeIde(action: GitActionCatalog.GitAction, onChanged: () -> Unit) {
        val actionId = action.ideActionId ?: return
        val target = ActionManager.getInstance().getAction(actionId) ?: run {
            LOG.warn("This IDE has no action '$actionId'; the Git view's '${action.id}' button does nothing")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        val component = ClaudeToolWindowFactory.contextComponent(project)
        val context = if (component != null) {
            DataManager.getInstance().getDataContext(component)
        } else {
            SimpleDataContext.getProjectContext(project)
        }
        val event = AnActionEvent.createEvent(
            target,
            context,
            null,
            ActionPlaces.TOOLWINDOW_CONTENT,
            // TOOLBAR, not NONE: this is a button in the page's action bar, and the kind is what
            // `AnActionEvent.isFromActionToolbar` answers from — an action entitled to behave differently
            // there would otherwise be told the press came from nowhere.
            ActionUiKind.TOOLBAR,
            null,
        )
        ActionUtil.updateAction(target, event)
        if (!event.presentation.isEnabled || !event.presentation.isVisible) {
            LOG.warn("The IDE refused '$actionId' in this context (enabled=${event.presentation.isEnabled})")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
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

        /**
         * How much history the commit graph asks for, and why it is not [GitHistoryService.DEFAULT_COMMIT_LIMIT].
         *
         * That one answers *"what did the last stretch of work do"* about ONE branch, where twenty rows is a
         * generous screenful. This answers *"where does this branch sit against the others"*, and the same
         * number cannot serve both: twenty commits shared out over every live line is four or five each, and a
         * branch graph with five commits per branch draws no fork at all — the same straight line the
         * branch-only `git log` used to produce, reached from the other end.
         *
         * **The floor is what a REAL repository needs, and it is measured rather than guessed.** The window has
         * to reach the most recent bifurcation, and the worst case is not a busy repository — it is a release
         * branch that has been linear for a long stretch, where the nearest other line is as far back as the
         * branch is old. Walking this one with the command the gateway issues
         * (`git log --graph --oneline --topo-order HEAD --branches --remotes --tags`), the first row that is
         * not on the checked-out line sits in the seventies and the first merge just behind it. A hundred
         * clears that with room for the branch to keep advancing before the fork slides out of the window;
         * re-run that command before changing the number, because it is the only thing that justifies it.
         *
         * **The ceiling is cost, and it is real.** This is one `git log` over every ref that materialises the
         * changed-file list of every commit it returns, [refresh] runs it several times a turn while the Git
         * view is open, and the page then draws a row and an SVG gutter for each. Raising it buys older
         * history and pays a slower read and a taller card for it.
         *
         * Nothing downstream trims this further — `JcefGitData` serialises what it is handed and the page draws
         * every row it is sent — so this IS the number of commits on screen, and the card's own note is what
         * says so when a line continues past the oldest of them.
         */
        const val GRAPH_COMMIT_LIMIT = 100

        /** The catalogue ids this file knows how to act on. */
        const val INIT = "init"
        const val COMMIT = "commit"
        const val REVERT_FILE = "revertFile"

        /** The history rail's entries — the four that carry a commit hash. */
        const val COMMIT_DIFF = "commitDiff"
        const val COMMIT_COPY_HASH = "commitCopyHash"
        const val COMMIT_REVERT_TO_BRANCH = "commitRevertToBranch"
        const val COMMIT_REVERT = "commitRevert"

        /** The VCS's registered name — see [registerRepository] for why it is a string and not a class. */
        private const val GIT_VCS_NAME = "Git"

        private const val GIT = "git"
        private const val DOT_GIT = ".git"

        /** `git init` is local and instant; anything past this is a hung binary, not a slow one. */
        private const val GIT_TIMEOUT_MS = 15_000

        private val LOG = logger<GitIntegration>()
    }
}
