package dev.lain.claudejb.ui

import dev.lain.claudejb.ui.GitActionCatalog.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The Git action catalogue, pinned where it is a contract with something that cannot check it back.
 *
 * Two things here are load-bearing. **The ids are a wire contract**: the page sends one back and the host looks
 * it up, so a renamed id is not a compile error — it is a button that stops doing anything. And
 * **[GitActionCatalog.applicable] is what decides which buttons exist at all**, from three booleans, which is
 * eight states nobody exercises by hand; all eight are below because the interesting ones are the empty
 * project (where the only offer must be *initialize*) and the repository with nothing to commit (where
 * *commit* must not be offered at all rather than offered and refused).
 *
 * Expected values are written out literally, never derived from the catalogue: a test that rebuilds the answer
 * with the subject's own filter passes whatever the subject does.
 */
class GitActionCatalogTest {

    // ── shape ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the catalogue is exactly these actions, in this order`() {
        assertEquals(
            listOf("init", "commit", "revertFile") + IDE_IDS,
            GitActionCatalog.ACTIONS.map { it.id },
            "an id is what the page sends back — renaming one silently unwires its button",
        )
    }

    @Test
    fun `no id appears twice`() {
        val ids = GitActionCatalog.ACTIONS.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "byId() would resolve a duplicate to whichever came first")
    }

    @Test
    fun `every IDE entry names a platform action, and nothing else does`() {
        GitActionCatalog.ACTIONS.forEach { action ->
            if (action.kind == Kind.IDE) {
                assertNotNull(action.ideActionId, "${action.id} is an IDE entry with no action to invoke")
            } else {
                assertNull(action.ideActionId, "${action.id} is not an IDE entry, so an action id there is dead")
            }
        }
    }

    @Test
    fun `the IDE entries are the platform ids the submenu resolves`() {
        assertEquals(
            IDE_IDS_TO_ACTIONS,
            GitActionCatalog.ideActions().associate { it.id to it.ideActionId },
            "these are read out of vcs-git's own descriptor; an id the IDE does not have is skipped in silence",
        )
    }

    @Test
    fun `the IDE entries fall into the blocks the submenu draws dividers between`() {
        assertEquals(
            listOf("pull", "merge", "stash", "commitDialog"),
            GitActionCatalog.ideActions().filter { it.startsBlock }.map { it.id },
            "the first entry never opens a block, or the submenu would start with a divider",
        )
    }

    @Test
    fun `an id the build does not know resolves to nothing`() {
        assertEquals("commit", GitActionCatalog.byId("commit")?.id)
        assertNull(GitActionCatalog.byId("Commit"), "ids are matched exactly, not case-insensitively")
        assertNull(GitActionCatalog.byId("rm-rf"))
        assertNull(GitActionCatalog.byId(""))
    }

    // ── applicable(): the whole matrix ────────────────────────────────────────────────────────────────────────

    @Test
    fun `no repository offers only the one action that creates one`() {
        assertEquals(listOf("init"), applicable(hasRepo = false, hasChanges = false, hasChangedFile = false))
    }

    @Test
    fun `no repository ignores a changed file, since there is nothing to have changed against`() {
        assertEquals(listOf("init"), applicable(hasRepo = false, hasChanges = false, hasChangedFile = true))
    }

    @Test
    fun `no repository ignores changes, since they cannot be committed anywhere`() {
        assertEquals(listOf("init"), applicable(hasRepo = false, hasChanges = true, hasChangedFile = false))
    }

    @Test
    fun `no repository stays a single offer however much has changed`() {
        assertEquals(listOf("init"), applicable(hasRepo = false, hasChanges = true, hasChangedFile = true))
    }

    @Test
    fun `a clean repository offers the IDE actions, nothing to commit and no second initialize`() {
        assertEquals(IDE_IDS, applicable(hasRepo = true, hasChanges = false, hasChangedFile = false))
    }

    @Test
    fun `a changed file in the editor offers revert on its own account`() {
        // The three booleans are read independently: revert asks only whether the editor holds a changed file,
        // so it is offered here even though "nothing has changed" — a state the caller does not produce, and
        // the answer to which must still be the safe one rather than an accident.
        assertEquals(
            listOf("revertFile") + IDE_IDS,
            applicable(hasRepo = true, hasChanges = false, hasChangedFile = true),
        )
    }

    @Test
    fun `changes add commit, but reverting needs the changed file open`() {
        assertEquals(listOf("commit") + IDE_IDS, applicable(hasRepo = true, hasChanges = true, hasChangedFile = false))
    }

    @Test
    fun `changes with the file open offer both commit and revert, in view order`() {
        assertEquals(
            listOf("commit", "revertFile") + IDE_IDS,
            applicable(hasRepo = true, hasChanges = true, hasChangedFile = true),
        )
    }

    private fun applicable(hasRepo: Boolean, hasChanges: Boolean, hasChangedFile: Boolean): List<String> =
        GitActionCatalog.applicable(hasRepo, hasChanges, hasChangedFile).map { it.id }

    private companion object {
        /** The IDE entries, in view order. Literal, because it is the thing being checked. */
        val IDE_IDS = listOf(
            "branches",
            "newBranch",
            "pull",
            "fetch",
            "push",
            "merge",
            "rebase",
            "stash",
            "unstash",
            "commitDialog",
        )

        /** Catalogue id → the platform action id behind it, as declared in `vcs-git`'s `META-INF/plugin.xml`. */
        val IDE_IDS_TO_ACTIONS = mapOf(
            "branches" to "Git.Branches",
            "newBranch" to "Git.CreateNewBranch",
            "pull" to "Git.Pull",
            "fetch" to "Git.Fetch",
            "push" to "Vcs.Push",
            "merge" to "Git.Merge",
            "rebase" to "Git.Rebase",
            "stash" to "Git.Stash",
            "unstash" to "Git.Unstash",
            "commitDialog" to "CheckinProject",
            // NB no "log". `Git.Log` resolves but is `GitShowExternalLogAction` — a chooser for a repository
            // outside the project — so it is not offered; this project's log is `GitLogNavigator.showLog`.
        )
    }
}
