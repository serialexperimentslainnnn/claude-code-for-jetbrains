package dev.lain.claudejb.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import dev.lain.claudejb.git.GitAvailability

/**
 * The Git operations the IDE already does better than we ever would — **as the IDE's own actions**.
 *
 * Nothing here is reimplemented, wrapped or re-labelled: each entry IS the platform's action object, resolved by
 * id and handed straight to the menu. So Branches opens the real Branches popup, Push opens the real Push
 * dialog, each keeps its own icon, its own keyboard shortcut and — the part that matters — **its own
 * enablement**, which knows things about the user's repository that this plugin never will.
 *
 * That is also why this can exist without breaking anything: the plugin runs no Git and answers no Git question
 * here. It opens a door. The `git/` package's read-only contract (`GitReadOnlyContractTest`) is untouched,
 * because nothing in this file names a `git4idea` type or spawns a process — what it handles are action ids, and
 * the platform owns everything behind them.
 *
 * **Why not prompt Claude for these too.** Merging, rebasing and stashing are not tasks whose value is in
 * describing them: they are dialogs with a branch list, a conflict view and an undo, and a chat card that says
 * `git rebase -i main` is strictly worse than the interactive rebase editor sitting three menu items away. What
 * IS worth prompting is where the agent knows something the dialog cannot — why the change was made
 * ([GitPromptedActions]). The split is that, not caution.
 *
 * **What it offers is [GitActionCatalog]'s [GitActionCatalog.Kind.IDE] entries**, in catalogue order and with a
 * divider wherever the catalogue opens a block. The page's Git view draws buttons from that same list, so the
 * two surfaces cannot come to offer different things; a menu keeping its own copy of the ids is how they would.
 *
 * **The catalogue's ids are verified**, not remembered: every one appears in `vcs-git`'s own `META-INF/plugin.xml`
 * in the 253 distribution this plugin compiles against, either as a declared `<action id>` or as a `<reference>`
 * the descriptor would fail to load without. They are still resolved defensively — an id the running IDE does not
 * have is skipped rather than crashing a menu, and its block collapses with it rather than leaving a stray
 * divider — and resolved on **every** menu open, so enabling the Git plugin mid-session works without
 * reopening the tool window.
 */
internal object GitIdeMenu {

    /** The submenu, for the tool window's gear. Hides itself with the Git plugin, like every other Git entry. */
    fun gearEntry(): AnAction = IdeGitGroup()

    private class IdeGitGroup : ActionGroup("Git Operations", "The IDE's own Git actions", null) {

        init {
            isPopup = true
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            if (!GitAvailability.isGitPluginEnabled()) return EMPTY_ARRAY
            val actions = ActionManager.getInstance()
            val blocks = mutableListOf<MutableList<AnAction>>()
            for (entry in GitActionCatalog.ideActions()) {
                if (entry.startsBlock || blocks.isEmpty()) blocks.add(mutableListOf())
                val action = entry.ideActionId?.let { actions.getAction(it) } ?: continue
                blocks.last().add(action)
            }
            return buildList {
                for (block in blocks.filter { it.isNotEmpty() }) {
                    if (isNotEmpty()) add(Separator.getInstance())
                    addAll(block)
                }
            }.toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = GitAvailability.isGitPluginEnabled()
        }

        /** BGT: the only thing read is the plugin registry, in memory. Same reasoning as the other Git entries. */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}
