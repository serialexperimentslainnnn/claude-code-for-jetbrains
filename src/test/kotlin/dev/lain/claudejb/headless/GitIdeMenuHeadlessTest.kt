package dev.lain.claudejb.headless

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.ui.GitActionCatalog
import dev.lain.claudejb.ui.GitIdeMenu

/**
 * Headless: **every action id the Git submenu offers still resolves, and the submenu is the catalogue.**
 *
 * The first part is worth having because its failure mode is invisible. The entries are resolved by string id
 * and a miss is skipped rather than thrown — so an id JetBrains renames does not produce an error, a crash or a
 * log line. It produces a menu with one fewer item, in a submenu nobody opens on the build machine. Nothing but
 * an assertion notices.
 *
 * The ids come from [GitActionCatalog] rather than a copy: a copy that drifts still passes, which would leave
 * this checking ids the menu no longer shows while the ones it does show go unchecked.
 */
class GitIdeMenuHeadlessTest : BasePlatformTestCase() {

    fun `test every id the Git submenu offers is a real action in this IDE`() {
        val actions = ActionManager.getInstance()
        val missing = ideActionIds().filter { actions.getAction(it) == null }
        assertEquals(
            "These action ids no longer exist, so the Git submenu is quietly missing entries: $missing",
            emptyList<String>(),
            missing,
        )
    }

    fun `test the Git plugin is actually loaded here, or the check above proves nothing`() {
        // Belt and braces: if Git4Idea were absent from the test IDE, every `Git.*` id above would resolve to
        // null and the assertion would be reporting a fixture problem as a product one.
        assertTrue(
            "Git4Idea is not enabled in the test IDE — the id check is meaningless without it",
            GitAvailability.isGitPluginEnabled(),
        )
    }

    fun `test the submenu is built from the catalogue, entry for entry and divider for divider`() {
        val actions = ActionManager.getInstance()
        val expected = GitActionCatalog.ideActions().flatMap { entry ->
            listOfNotNull(SEPARATOR.takeIf { entry.startsBlock }, entry.ideActionId)
        }

        val children = (GitIdeMenu.gearEntry() as ActionGroup).getChildren(null)

        assertEquals(
            "The gear submenu no longer matches the catalogue the Git view draws from",
            expected,
            children.map { child -> if (child is Separator) SEPARATOR else actions.getId(child) },
        )
    }

    private fun ideActionIds(): List<String> = GitActionCatalog.ideActions().mapNotNull { it.ideActionId }

    private companion object {
        /** How a divider reads in the comparison above; the menu itself uses a `Separator` instance. */
        const val SEPARATOR = "—"
    }
}
