package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prompts the Git integration sends, pinned.
 *
 * They are the only part of the feature that is testable without an IDE, and they are worth pinning for a
 * reason that is not style: **each one is a command plus a list of things not to do**, and the prohibitions are
 * what keep a capable agent from doing the reasonable extra thing the button did not ask for (writing a
 * `.gitignore` while initialising, pushing after committing, restoring more than the one file). A prompt that
 * loses its prohibitions still reads fine and still works most of the time, which is exactly why nothing but a
 * test would notice.
 *
 * What these do NOT claim is that the prohibitions hold: a model can ignore any of them. That is what the
 * forced-approval card is for (`PermissionBrokerMatrixTest`'s `forceAsk` cases), and the two are complementary.
 */
class GitPromptedActionsTest {

    // NB nothing pins an init prompt: creating a repository is not asked of the model. It is a fixed command
    // run by `GitIntegration`, whose argv is pinned by `IdeActionApiContractTest` instead.

    @Test
    fun `commit lists the changed files and rules out everything past one commit`() {
        val prompt = GitPromptedActions.commitPrompt(listOf("src/App.kt", "README.md"))

        assertTrue(prompt.contains("- src/App.kt"))
        assertTrue(prompt.contains("- README.md"))
        assertTrue(prompt.contains("ONE commit"), "two commits is a different action than the one offered")
        assertTrue(prompt.contains("Do not push"))
        assertTrue(prompt.contains("do not rebase"))
        assertTrue(prompt.contains("branch"), "branch moves are the other way this quietly turns into a mess")
    }

    @Test
    fun `commit summarises a long change list instead of pasting it whole`() {
        val many = (1..60).map { "file$it.kt" }
        val prompt = GitPromptedActions.commitPrompt(many)

        assertTrue(prompt.contains("- file40.kt"))
        assertFalse(prompt.contains("- file41.kt"), "past the cap the rest become a count, not forty more lines")
        assertTrue(prompt.contains("20 more"))
    }

    @Test
    fun `revert names the one path in both the command and the prohibition`() {
        val prompt = GitPromptedActions.revertFilePrompt("src/App.kt")

        assertTrue(prompt.contains("git restore -- src/App.kt"))
        // The destructive one. A wrong pathspec here throws away work that was never committed, so the ways to
        // widen it are enumerated rather than left to "and nothing else".
        assertTrue(prompt.contains("no other"))
        assertTrue(prompt.contains("git clean"))
        assertTrue(prompt.contains("git reset"))
        assertTrue(prompt.contains("discards uncommitted work"), "the user is told what they are approving")
    }
}
