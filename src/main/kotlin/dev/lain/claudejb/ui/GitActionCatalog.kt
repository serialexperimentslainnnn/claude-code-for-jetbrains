package dev.lain.claudejb.ui

/**
 * What the Git view can do, as data — **one catalogue, read by both the payload and the executor.**
 *
 * The page draws a button per entry and sends back its [GitAction.id]; the host looks the id up here and acts
 * on [GitAction.kind]. Describing the actions twice — once to draw them, once to run them — is how a button
 * ends up labelled one thing and doing another, so there is exactly one list and it is pure, which also makes
 * it the thing the tests pin.
 *
 * The four kinds are four different answers to "who does this", and the split is the design:
 *  - [Kind.DIRECT] — the plugin **spawns `git`**. Exactly one entry, `init`, because it is the one command with
 *    nothing to decide and the one that makes every other entry reachable on a project with no repository.
 *  - [Kind.PROMPT] — Claude does it, in the Git chat, behind an approval card. For the work where knowing
 *    *why* the change was made is the point (a commit message written from the turn, not from the diff), and
 *    for the work that writes at all, so the command is on screen before it runs.
 *  - [Kind.IDE] — the platform's own action, by id. Branching, merging, rebasing and stashing are dialogs
 *    with a branch list, a conflict view and an undo; no chat card improves on them.
 *  - [Kind.HOST] — the plugin answers it itself, in the IDE, **running no `git` and asking no agent**: showing
 *    a commit in the log, putting a hash on the clipboard.
 */
internal object GitActionCatalog {

    /**
     * Who performs an entry. See the class KDoc for what each one means and why they are not interchangeable.
     *
     * [HOST] is deliberately **not** [DIRECT]. `DIRECT` is the carved-out exception to "the plugin runs no
     * `git`" — one fixed argv, spawned by `GitIntegration.gitInit` — and an exception that admits a second
     * member is a category. A `HOST` entry spawns nothing at all: it reads what the read-only `git/` package has
     * already collected and hands it to a viewer, or writes the clipboard.
     *
     * It is also deliberately not [PROMPT]. A prompted read would cost a second `claude` process, a turn and
     * real money to render, as text in a transcript, a diff the IDE draws natively — and the approval card that
     * makes `PROMPT` safe protects nothing here, because there is nothing to approve: no file is written and no
     * ref moves. Sending a read through a model buys the risk of a wandering turn and none of the control.
     */
    enum class Kind { DIRECT, PROMPT, IDE, HOST }

    /** When an entry is offered. Re-derived on every push, so creating a repo makes the list change by itself. */
    enum class Requires {
        /** Only without a repository — i.e. the one action an empty project can take. */
        NO_REPO,

        /** Any repository. */
        REPO,

        /** A repository with uncommitted changes. */
        CHANGES,

        /** A repository whose editor holds one of those changed files. */
        CHANGED_FILE,

        /**
         * One specific commit — the entry is drawn on a history row and carries that row's hash.
         *
         * Never part of the action bar: [applicable] answers false for it whatever the repository state, because
         * a bar has no commit to act on and a "View diff" button with no commit behind it is a button that can
         * only fail. The history rail asks for these by themselves ([commitActions]).
         */
        COMMIT,
    }

    data class GitAction(
        val id: String,
        val label: String,
        val hint: String,
        val kind: Kind,
        val requires: Requires,
        /** For [Kind.IDE]: the platform action id to invoke. Null for the others. */
        val ideActionId: String? = null,
        /** Grouping header in the view; entries keep their order within a group. */
        val group: String,
        /**
         * Opens a new block: in [GitIdeMenu]'s submenu a divider is drawn before this entry.
         *
         * Presentation, and deliberately here rather than in the menu: the divider positions only mean anything
         * against the order below, so keeping them anywhere else is a second list that can disagree with this
         * one about which entries exist.
         */
        val startsBlock: Boolean = false,
    ) {

        /**
         * True when this entry acts on ONE commit, whose hash the page sends alongside the id.
         *
         * **Derived, not stored.** "Which commit" and "when is it offered" are the same fact here — an entry
         * that needs a hash is exactly one drawn on a history row — and two fields that must always agree are
         * two fields that can disagree. The executor reads this to decide whether to look at the hash at all;
         * for every other entry the hash is ignored, so a value off the wire cannot reach a prompt that has no
         * commit in it.
         */
        val takesCommit: Boolean get() = requires == Requires.COMMIT
    }

    /**
     * Every action, in view order.
     *
     * This is also what [GitIdeMenu] builds its submenu from, entry for entry and divider for divider: the page
     * and the gear menu offer the same [Kind.IDE] actions because they read the same list, not because two
     * lists happen to agree.
     *
     * The IDE ids are read out of `vcs-git`'s own descriptor rather than remembered, and pinned by
     * `GitIdeMenuHeadlessTest` against the running IDE, because a renamed id does not fail — it silently
     * removes a button. Deliberately not everything `git4idea` declares: `Git.Reset`, `Git.Uncommit` and the
     * rebase continue/abort family belong to the flow that starts them, which is the Git Log and the conflict
     * view rather than a menu in a chat tool window.
     */
    val ACTIONS: List<GitAction> = listOf(
        GitAction(
            id = "init",
            label = "Initialize repository",
            hint = "Run git init -b main in the project root",
            kind = Kind.DIRECT,
            requires = Requires.NO_REPO,
            group = "Repository",
        ),
        GitAction(
            id = "commit",
            label = "Commit with Claude",
            hint = "Claude stages the changes and writes the commit message",
            kind = Kind.PROMPT,
            requires = Requires.CHANGES,
            group = "Ask Claude",
        ),
        GitAction(
            id = "revertFile",
            label = "Revert this file with Claude",
            hint = "Restore the file open in the editor to its committed state",
            kind = Kind.PROMPT,
            requires = Requires.CHANGED_FILE,
            group = "Ask Claude",
        ),
        // ── one commit at a time: drawn on a history row, never in the action bar ────────────────────────────
        // The two reads are [Kind.HOST] — see [Kind] for why they are neither DIRECT nor PROMPT. The two writes
        // are prompted like every other write here, so the command reaches the user as a card before it runs.
        commitAction("commitDiff", "View diff", "Show this commit and its changes in the IDE", Kind.HOST),
        commitAction("commitCopyHash", "Copy hash", "Put the full commit hash on the clipboard", Kind.HOST),
        commitAction(
            "commitRevertToBranch",
            "Revert to this commit on a new branch",
            "Ask Claude to create a branch at this commit — the branch you are on does not move",
            Kind.PROMPT,
        ),
        commitAction(
            "commitRevert",
            "Revert just this commit",
            "Ask Claude to record a new commit undoing this one, keeping the history",
            Kind.PROMPT,
        ),
        // Blocks: where you are · the remote · integrating someone else's work · putting work aside · the rest.
        //
        // `branches` has THREE doors on the page, not one, and they are all this entry on purpose. It is a
        // button in the action bar; it is the branch chip in the view's header, which used to be dead text
        // saying which branch you were on with no way to leave it; and it is every ref on the branch map. The
        // temptation was a second, branch-scoped entry so a chip could switch to *that* branch directly — and
        // it is refused, because a branch NAME off the page is a free-form value exactly like a commit hash,
        // and the only thing that could act on one is a checkout, which this plugin does not perform. What the
        // platform's own popup does instead is offer the real branch list, with its own enablement, its own
        // conflict handling and its own undo. So: one id, one executor, three places you can reach it from.
        // Renaming it is not a compile error — it silently unwires all three.
        ideAction("branches", "Branches", "Switch, create or compare branches", "Git.Branches"),
        ideAction("newBranch", "New branch", "Create a branch from here", "Git.CreateNewBranch"),
        ideAction("pull", "Pull", "Pull from the remote", "Git.Pull", startsBlock = true),
        ideAction("fetch", "Fetch", "Fetch from the remote", "Git.Fetch"),
        ideAction("push", "Push", "Push to the remote", "Vcs.Push"),
        ideAction("merge", "Merge", "Merge a branch into this one", "Git.Merge", startsBlock = true),
        ideAction("rebase", "Rebase", "Rebase this branch", "Git.Rebase"),
        ideAction("stash", "Stash", "Put the current changes aside", "Git.Stash", startsBlock = true),
        ideAction("unstash", "Unstash", "Bring stashed changes back", "Git.Unstash"),
        ideAction("commitDialog", "Commit dialog", "The IDE's own commit dialog", "CheckinProject", startsBlock = true),
        // NB no Git Log entry. `Git.Log` resolves, which is exactly what makes it a trap: it is
        // `git4idea.log.GitShowExternalLogAction` — *Show Git Repository Log…*, a directory chooser for a
        // repository OUTSIDE the project — so a button labelled "Git Log" would open something else entirely,
        // and the id resolving is all a test of ids can see. Opening this project's log is
        // `git/GitLogNavigator.showLog`, which the gear menu already offers through `GitContextActions`.
    )

    /** Looks an action up by the id the page sent back. Null for an id this build does not know. */
    fun byId(id: String): GitAction? = ACTIONS.firstOrNull { it.id == id }

    /**
     * True when [hash] has the SHAPE of a Git object name: hexadecimal, [MIN_HASH_LENGTH]–[MAX_HASH_LENGTH] long.
     *
     * **The hash is the only free-form value on this wire, and the wire is a trust boundary.** An id is checked
     * by looking it up here and an unknown one is dropped; a hash cannot be checked that way, because the whole
     * point of it is to be a value this build has never seen. It is then interpolated into prompt text that
     * names commands, so anything that is not an object name — a newline that starts a line of its own prose, a
     * backtick that closes the code span, a second pathspec, a `--force` — is refused before a prompt exists to
     * carry it. Nothing on the page is supposed to send such a value; that is exactly why it is checked.
     *
     * Checking the shape is also what lets the prompts interpolate a hash **without** the `oneLine` rendering
     * every repository-sourced string in [GitPromptedActions] goes through: hex has no line break, no control
     * character and no backtick, so there is nothing left to render.
     *
     * The bounds are Git's own: 4 is the shortest abbreviation `git` will resolve, 40 is a SHA-1 object name and
     * 64 a SHA-256 one — both exist, so the ceiling is the larger. Written as explicit ranges rather than
     * `Char.isDigit()`, which accepts every Unicode decimal digit (Arabic-Indic, Devanagari, …) and none of
     * those is hex.
     */
    fun isCommitHash(hash: String): Boolean =
        hash.length in MIN_HASH_LENGTH..MAX_HASH_LENGTH &&
            hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** The subset that applies to the given repository state, in view order. */
    fun applicable(hasRepo: Boolean, hasChanges: Boolean, hasChangedFile: Boolean): List<GitAction> =
        ACTIONS.filter {
            when (it.requires) {
                Requires.NO_REPO -> !hasRepo

                Requires.REPO -> hasRepo

                Requires.CHANGES -> hasRepo && hasChanges

                Requires.CHANGED_FILE -> hasRepo && hasChangedFile

                // Never in the bar, whatever the state: these need a commit, and the bar has none to give them.
                Requires.COMMIT -> false
            }
        }

    /** The subset drawn on each commit of the history rail, in view order. Every one of them takes a hash. */
    fun commitActions(): List<GitAction> = ACTIONS.filter { it.requires == Requires.COMMIT }

    /** The subset the IDE runs itself, in view order — what [GitIdeMenu] turns into a submenu. */
    fun ideActions(): List<GitAction> = ACTIONS.filter { it.kind == Kind.IDE }

    /**
     * One history-row entry. The group is a label the bar never draws — these buttons sit on the commit itself —
     * but it is filled in rather than left blank so the payload keeps one shape for every entry.
     */
    private fun commitAction(id: String, label: String, hint: String, kind: Kind) = GitAction(
        id = id,
        label = label,
        hint = hint,
        kind = kind,
        requires = Requires.COMMIT,
        group = "Commit",
    )

    private fun ideAction(
        id: String,
        label: String,
        hint: String,
        actionId: String,
        startsBlock: Boolean = false,
    ) = GitAction(
        id = id,
        label = label,
        hint = hint,
        kind = Kind.IDE,
        requires = Requires.REPO,
        ideActionId = actionId,
        group = "IDE actions",
        startsBlock = startsBlock,
    )

    /** The shortest abbreviation `git` resolves. See [isCommitHash]. */
    private const val MIN_HASH_LENGTH = 4

    /** A SHA-256 object name; a SHA-1 one is 40. See [isCommitHash]. */
    private const val MAX_HASH_LENGTH = 64
}
