package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.ui.GitActionCatalog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of the Git view's payload: the shape the page is promised, the two ways it degrades when
 * there is no Git, and the two things it must not decide for itself — the status vocabulary (owned by
 * [JcefStatus]) and the action list (owned by [GitActionCatalog]).
 */
class JcefGitDataTest {

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────

    private val commit = GitCommitInfo(
        hash = "0123456789abcdef0123456789abcdef01234567",
        subject = "Add the Git view",
        authorName = "Lain",
        authorEmail = "lain@example.invalid",
        authoredAtMillis = 1_000_000L,
        changedPaths = listOf("src/a.kt", "src/b.kt", "README.md"),
    )

    private fun populated(
        changes: List<String> = listOf("src/a.kt"),
        commits: List<GitCommitInfo> = listOf(commit),
        changedFileOpen: Boolean = false,
        actionStates: Map<String, JcefGitData.ActionState> = emptyMap(),
    ) = JcefGitData.Snapshot(
        available = true,
        repo = JcefGitData.Repo(present = true, branch = "feature/git", head = "0123456", root = "/home/u/proj"),
        changes = changes,
        commits = commits,
        changedFileOpen = changedFileOpen,
        actionStates = actionStates,
    )

    private fun noRepo() = JcefGitData.Snapshot(available = true, repo = JcefGitData.Repo(present = false))

    private fun idsOf(git: JsonObject): List<String> =
        git["actions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    // ── shape ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `payload carries availability, repo, changes, commits and actions`() {
        val git = JcefGitData.gitJson(populated(), nowMillis = 1_500_000L)!!

        assertEquals(setOf("available", "repo", "changes", "commits", "actions"), git.keys)
        assertTrue(git["available"]!!.jsonPrimitive.boolean)

        val repo = git["repo"]!!.jsonObject
        assertTrue(repo["present"]!!.jsonPrimitive.boolean)
        assertEquals("feature/git", repo["branch"]!!.jsonPrimitive.content)
        assertEquals("0123456", repo["head"]!!.jsonPrimitive.content)
        assertEquals("/home/u/proj", repo["root"]!!.jsonPrimitive.content)

        assertEquals(listOf("src/a.kt"), git["changes"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a commit reports its short hash, its file count and its age`() {
        val git = JcefGitData.gitJson(populated(), nowMillis = 1_500_000L)!!
        val c = git["commits"]!!.jsonArray.single().jsonObject

        assertEquals(setOf("hash", "short", "subject", "author", "ageMillis", "files"), c.keys)
        assertEquals(commit.hash, c["hash"]!!.jsonPrimitive.content)
        assertEquals("0123456", c["short"]!!.jsonPrimitive.content)
        assertEquals("Add the Git view", c["subject"]!!.jsonPrimitive.content)
        assertEquals("Lain", c["author"]!!.jsonPrimitive.content)
        assertEquals(500_000L, c["ageMillis"]!!.jsonPrimitive.long)
        assertEquals(3, c["files"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a commit dated in the future reads as age zero, never negative`() {
        val git = JcefGitData.gitJson(populated(), nowMillis = 900_000L)!!
        val c = git["commits"]!!.jsonArray.single().jsonObject

        assertEquals(0L, c["ageMillis"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a blank branch, head or root is null rather than an empty string`() {
        val snapshot = JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(present = true, branch = "", head = "   ", root = null),
        )
        val repo = JcefGitData.gitJson(snapshot, nowMillis = 0L)!!["repo"]!!.jsonObject

        assertEquals(JsonNull, repo["branch"])
        assertEquals(JsonNull, repo["head"])
        assertEquals(JsonNull, repo["root"])
    }

    // ── degradation ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no snapshot at all emits no git value`() {
        assertNull(JcefGitData.gitJson(null, nowMillis = 0L))
    }

    @Test
    fun `without Git the payload is availability and nothing else`() {
        val git = JcefGitData.gitJson(JcefGitData.Snapshot(available = false), nowMillis = 0L)!!

        assertEquals(setOf("available"), git.keys)
        assertFalse(git["available"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an empty working tree and an empty history emit empty arrays, not null`() {
        val snapshot = JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(present = true, branch = "main"),
        )
        val git = JcefGitData.gitJson(snapshot, nowMillis = 0L)!!

        assertNotNull(git["changes"])
        assertNotNull(git["commits"])
        assertTrue(git["changes"]!!.jsonArray.isEmpty())
        assertTrue(git["commits"]!!.jsonArray.isEmpty())
        assertEquals(setOf("available", "repo", "changes", "commits", "actions"), git.keys)
    }

    // ── actions: the catalogue decides, this builder only serializes ─────────────────────────────────────

    @Test
    fun `the action list is the catalogue's, in the catalogue's order`() {
        val snapshot = populated(changedFileOpen = true)
        val git = JcefGitData.gitJson(snapshot, nowMillis = 0L)!!

        val expected = GitActionCatalog.applicable(hasRepo = true, hasChanges = true, hasChangedFile = true).map { it.id }
        assertEquals(expected, idsOf(git))
    }

    @Test
    fun `a project with no repository is offered init and nothing else`() {
        val git = JcefGitData.gitJson(noRepo(), nowMillis = 0L)!!

        assertEquals(listOf("init"), idsOf(git))
    }

    @Test
    fun `a clean tree drops the change-driven actions and keeps the IDE ones`() {
        val git = JcefGitData.gitJson(populated(changes = emptyList()), nowMillis = 0L)!!
        val ids = idsOf(git)

        assertFalse(ids.contains("init"))
        assertFalse(ids.contains("commit"))
        assertFalse(ids.contains("revertFile"))
        assertTrue(ids.containsAll(listOf("branches", "newBranch", "pull", "fetch", "push", "merge", "rebase", "stash", "unstash")))
        assertTrue(ids.contains("commitDialog"))
    }

    @Test
    fun `the per-file action appears only when the open file is one of the changed ones`() {
        assertFalse(idsOf(JcefGitData.gitJson(populated(changedFileOpen = false), nowMillis = 0L)!!).contains("revertFile"))
        assertTrue(idsOf(JcefGitData.gitJson(populated(changedFileOpen = true), nowMillis = 0L)!!).contains("revertFile"))
    }

    @Test
    fun `an action restates the catalogue's own label, hint, kind and group`() {
        val git = JcefGitData.gitJson(populated(changedFileOpen = true), nowMillis = 0L)!!
        val byId = git["actions"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }

        GitActionCatalog.applicable(hasRepo = true, hasChanges = true, hasChangedFile = true).forEach { action ->
            val emitted = byId[action.id]!!
            assertEquals(setOf("id", "label", "hint", "kind", "group", "status"), emitted.keys)
            assertEquals(action.label, emitted["label"]!!.jsonPrimitive.content)
            assertEquals(action.hint, emitted["hint"]!!.jsonPrimitive.content)
            assertEquals(action.group, emitted["group"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `kind is lowercase on the wire and group is one of the three the contract names`() {
        val git = JcefGitData.gitJson(populated(changedFileOpen = true), nowMillis = 0L)!!
        val entries = git["actions"]!!.jsonArray.map { it.jsonObject }
        val byId = entries.associate { it["id"]!!.jsonPrimitive.content to it }

        val init = JcefGitData.gitJson(noRepo(), nowMillis = 0L)!!["actions"]!!.jsonArray.single().jsonObject
        assertEquals("direct", init["kind"]!!.jsonPrimitive.content)
        assertEquals("prompt", byId["commit"]!!["kind"]!!.jsonPrimitive.content)
        assertEquals("ide", byId["branches"]!!["kind"]!!.jsonPrimitive.content)

        val groups = entries.map { it["group"]!!.jsonPrimitive.content }.toSet()
        assertTrue(setOf("Repository", "Ask Claude", "IDE actions").containsAll(groups))
    }

    // ── status: the vocabulary is JcefStatus's ───────────────────────────────────────────────────────────

    @Test
    fun `an action that has not been run carries a null status`() {
        val git = JcefGitData.gitJson(populated(), nowMillis = 0L)!!

        git["actions"]!!.jsonArray.forEach { assertEquals(JsonNull, it.jsonObject["status"]) }
    }

    @Test
    fun `a launched action carries its state, and only its own`() {
        val states = mapOf("commit" to JcefGitData.ActionState.RUNNING, "push" to JcefGitData.ActionState.FAILED)
        val git = JcefGitData.gitJson(populated(actionStates = states), nowMillis = 0L)!!
        val byId = git["actions"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }

        assertEquals("running", byId["commit"]!!["status"]!!.jsonPrimitive.content)
        assertEquals("failed", byId["push"]!!["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, byId["fetch"]!!["status"])
    }

    @Test
    fun `an unknown action id contributes no entry and no invented key`() {
        val states = mapOf("nonexistent" to JcefGitData.ActionState.RUNNING)
        val git = JcefGitData.gitJson(populated(actionStates = states), nowMillis = 0L)!!

        assertFalse(idsOf(git).contains("nonexistent"))
        git["actions"]!!.jsonArray.forEach { assertEquals(JsonNull, it.jsonObject["status"]) }
    }

    @Test
    fun `every state word this builder can emit belongs to the JcefStatus vocabulary`() {
        val vocabulary = AgentStatus.entries.map { JcefStatus.of(it) }.toSet()
        val emitted = JcefGitData.ActionState.entries.map { it.word }.toSet()

        assertEquals(setOf("running", "completed", "failed", "stopped"), vocabulary)
        assertTrue(vocabulary.containsAll(emitted))
        assertEquals(setOf("running", "completed", "failed"), emitted)
    }
}
