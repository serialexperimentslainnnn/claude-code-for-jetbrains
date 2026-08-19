package dev.lain.claudejb.headless

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.ui.GitActionCatalog
import dev.lain.claudejb.ui.GitIdeMenu

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
        const val SEPARATOR = "—"
    }
}
