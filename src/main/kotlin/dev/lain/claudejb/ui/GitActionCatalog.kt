package dev.lain.claudejb.ui

/**
 * What the Git view can do, as data — **one catalogue, read by both the payload and the executor.**
 *
 * The page draws a button per entry and sends back its [GitAction.id]; the host looks the id up here and acts
 * on [GitAction.kind]. Describing the actions twice — once to draw them, once to run them — is how a button
 * ends up labelled one thing and doing another, so there is exactly one list and it is pure, which also makes
 * it the thing the tests pin.
 *
 * The three kinds are three different answers to "who does this", and the split is the design:
 *  - [Kind.DIRECT] — the plugin runs it. Only where the command is fixed, safe and has nothing to decide.
 *  - [Kind.PROMPT] — Claude does it, in the Git chat, behind an approval card. For the work where knowing
 *    *why* the change was made is the point (a commit message written from the turn, not from the diff).
 *  - [Kind.IDE] — the platform's own action, by id. Branching, merging, rebasing and stashing are dialogs
 *    with a branch list, a conflict view and an undo; no chat card improves on them.
 */
internal object GitActionCatalog {

    enum class Kind { DIRECT, PROMPT, IDE }

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
    )

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
        // Blocks: where you are · the remote · integrating someone else's work · putting work aside · the rest.
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

    /** The subset that applies to the given repository state, in view order. */
    fun applicable(hasRepo: Boolean, hasChanges: Boolean, hasChangedFile: Boolean): List<GitAction> =
        ACTIONS.filter {
            when (it.requires) {
                Requires.NO_REPO -> !hasRepo
                Requires.REPO -> hasRepo
                Requires.CHANGES -> hasRepo && hasChanges
                Requires.CHANGED_FILE -> hasRepo && hasChangedFile
            }
        }

    /** The subset the IDE runs itself, in view order — what [GitIdeMenu] turns into a submenu. */
    fun ideActions(): List<GitAction> = ACTIONS.filter { it.kind == Kind.IDE }

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
}
