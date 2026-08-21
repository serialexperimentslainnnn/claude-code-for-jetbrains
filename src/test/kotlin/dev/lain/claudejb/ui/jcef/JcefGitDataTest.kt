package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.forge.ForgeRun
import dev.lain.claudejb.forge.ForgeRunStatus
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitRefInfo
import dev.lain.claudejb.git.GitRefKind
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

class JcefGitDataTest {

    private val commit = GitCommitInfo(
        hash = "0123456789abcdef0123456789abcdef01234567",
        subject = "Add the Git view",
        authorName = "Lain",
        authorEmail = "lain@example.invalid",
        authoredAtMillis = 1_000_000L,
        changedPaths = listOf("src/a.kt", "src/b.kt", "README.md"),
    )

    private val merge = GitCommitInfo(
        hash = "89abcdef89abcdef89abcdef89abcdef89abcdef",
        subject = "Merge branch 'feature/git'",
        authorName = "Lain",
        authorEmail = "lain@example.invalid",
        authoredAtMillis = 1_200_000L,
        changedPaths = listOf("src/a.kt"),
        parents = listOf(commit.hash, OTHER_PARENT),
    )

    private val twoRefs = listOf(
        GitRefInfo("main", GitRefKind.LOCAL, commit.hash, current = true),
        GitRefInfo("origin/main", GitRefKind.REMOTE, commit.hash, current = false),
    )

    private fun populated(
        changes: List<String> = listOf("src/a.kt"),
        commits: List<GitCommitInfo> = listOf(commit),
        refs: List<GitRefInfo> = emptyList(),
        changedFileOpen: Boolean = false,
        actionStates: Map<String, JcefGitData.ActionState> = emptyMap(),
    ) = JcefGitData.Snapshot(
        available = true,
        repo = JcefGitData.Repo(present = true, branch = "feature/git", head = "0123456", root = "/home/u/proj"),
        changes = changes,
        commits = commits,
        refs = refs,
        changedFileOpen = changedFileOpen,
        actionStates = actionStates,
    )

    private fun noRepo() = JcefGitData.Snapshot(available = true, repo = JcefGitData.Repo(present = false))

    @Test
    fun `the same snapshot serialises identically twice, so an unchanged push is deduplicated`() {
        val snapshot = populated(commits = listOf(commit, merge))

        assertEquals(JcefGitData.gitJson(snapshot).toString(), JcefGitData.gitJson(snapshot).toString())
    }

    @Test
    fun `a commit is dated absolutely, so the payload does not change with the clock`() {
        val commits = JcefGitData.gitJson(populated())!!["commits"]!!.jsonArray

        assertEquals(commit.authoredAtMillis, commits[0].jsonObject["authoredAtMillis"]!!.jsonPrimitive.long)
        assertNull(commits[0].jsonObject["ageMillis"])
    }

    @Test
    fun `an unconfigured forge says so instead of leaving the tabs to guess`() {
        val forge = JcefGitData.gitJson(populated())!!["forge"]!!.jsonObject

        assertFalse(forge["configured"]!!.jsonPrimitive.boolean)
        assertFalse(forge["answered"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a configured forge that answered carries every run, not just the newest`() {
        val snapshot = populated().copy(
            forgeConfigured = true,
            forgeProvider = "gitlab",
            runs = listOf(
                ForgeRun(name = "Second", status = ForgeRunStatus.RUNNING, url = "https://h/2", finishedAtIso = null),
                ForgeRun(name = "First", status = ForgeRunStatus.FAILED, url = "https://h/1", finishedAtIso = "x"),
            ),
        )

        val git = JcefGitData.gitJson(snapshot)!!
        val forge = git["forge"]!!.jsonObject

        assertTrue(forge["configured"]!!.jsonPrimitive.boolean)
        assertTrue(forge["answered"]!!.jsonPrimitive.boolean)
        assertEquals("gitlab", forge["provider"]!!.jsonPrimitive.content)
        assertEquals(2, git["runs"]!!.jsonArray.size)
        assertEquals("Second", git["runs"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    private fun idsOf(git: JsonObject): List<String> =
        git["actions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `payload carries availability, repo, changes, commits and actions`() {
        val git = JcefGitData.gitJson(populated())!!

        assertEquals(
            setOf(
                "available", "repo", "changes", "commits", "refs", "actions", "commitActions", "topology",
                "forge",
            ),
            git.keys,
        )
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
        val git = JcefGitData.gitJson(populated())!!
        val c = git["commits"]!!.jsonArray.single().jsonObject

        assertEquals(setOf("hash", "short", "subject", "author", "authoredAtMillis", "files", "parents"), c.keys)
        assertEquals(commit.hash, c["hash"]!!.jsonPrimitive.content)
        assertEquals("0123456", c["short"]!!.jsonPrimitive.content)
        assertEquals("Add the Git view", c["subject"]!!.jsonPrimitive.content)
        assertEquals("Lain", c["author"]!!.jsonPrimitive.content)
        assertEquals(1_000_000L, c["authoredAtMillis"]!!.jsonPrimitive.long)
        assertEquals(3, c["files"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a commit dated in the future travels untouched, for the view to judge`() {
        val ahead = commit.copy(authoredAtMillis = Long.MAX_VALUE)
        val git = JcefGitData.gitJson(populated(commits = listOf(ahead)))!!
        val c = git["commits"]!!.jsonArray.single().jsonObject

        assertEquals(Long.MAX_VALUE, c["authoredAtMillis"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a commit carries its parents as full hashes, in commit order`() {
        val git = JcefGitData.gitJson(populated(commits = listOf(merge, commit)))!!
        val parents = git["commits"]!!.jsonArray.first().jsonObject["parents"]!!.jsonArray

        assertEquals(listOf(commit.hash, OTHER_PARENT), parents.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a root commit reports an empty parent list, which is a fact and not an omission`() {
        val git = JcefGitData.gitJson(populated())!!
        val parents = git["commits"]!!.jsonArray.single().jsonObject["parents"]!!.jsonArray

        assertTrue(parents.isEmpty())
    }

    @Test
    fun `a ref names itself, its kind, its commit and whether HEAD is on it`() {
        val git = JcefGitData.gitJson(populated(refs = twoRefs))!!
        val emitted = git["refs"]!!.jsonArray.map { it.jsonObject }

        assertEquals(setOf("name", "kind", "hash", "short", "current"), emitted.first().keys)
        assertEquals(listOf("main", "origin/main"), emitted.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("local", "remote"), emitted.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("0123456", emitted.first()["short"]!!.jsonPrimitive.content)
        assertEquals(listOf(true, false), emitted.map { it["current"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun `a detached HEAD travels as its own kind, not as a branch that does not exist`() {
        val detached = JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(present = true),
            refs = listOf(GitRefInfo("HEAD", GitRefKind.HEAD, commit.hash, current = true)),
        )
        val emitted = JcefGitData.gitJson(detached)!!["refs"]!!.jsonArray.single().jsonObject

        assertEquals("head", emitted["kind"]!!.jsonPrimitive.content)
        assertTrue(emitted["current"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `refs are emitted even when empty, so the page tells a clean answer from an absent one`() {
        val git = JcefGitData.gitJson(populated())!!

        assertNotNull(git["refs"])
        assertTrue(git["refs"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a blank branch, head or root is null rather than an empty string`() {
        val snapshot = JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(present = true, branch = "", head = "   ", root = null),
        )
        val repo = JcefGitData.gitJson(snapshot)!!["repo"]!!.jsonObject

        assertEquals(JsonNull, repo["branch"])
        assertEquals(JsonNull, repo["head"])
        assertEquals(JsonNull, repo["root"])
    }

    @Test
    fun `no snapshot at all emits no git value`() {
        assertNull(JcefGitData.gitJson(null))
    }

    @Test
    fun `without Git the payload is availability and nothing else`() {
        val git = JcefGitData.gitJson(JcefGitData.Snapshot(available = false))!!

        assertEquals(setOf("available"), git.keys)
        assertFalse(git["available"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an empty working tree and an empty history emit empty arrays, not null`() {
        val snapshot = JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(present = true, branch = "main"),
        )
        val git = JcefGitData.gitJson(snapshot)!!

        assertNotNull(git["changes"])
        assertNotNull(git["commits"])
        assertTrue(git["changes"]!!.jsonArray.isEmpty())
        assertTrue(git["commits"]!!.jsonArray.isEmpty())
        assertEquals(
            setOf(
                "available", "repo", "changes", "commits", "refs", "actions", "commitActions", "topology",
                "forge",
            ),
            git.keys,
        )
    }

    @Test
    fun `the action list is the catalogue's, in the catalogue's order`() {
        val snapshot = populated(changedFileOpen = true)
        val git = JcefGitData.gitJson(snapshot)!!

        val expected = GitActionCatalog.applicable(hasRepo = true, hasChanges = true, hasChangedFile = true).map { it.id }
        assertEquals(expected, idsOf(git))
    }

    @Test
    fun `a project with no repository is offered init and nothing else`() {
        val git = JcefGitData.gitJson(noRepo())!!

        assertEquals(listOf("init"), idsOf(git))
    }

    @Test
    fun `a clean tree drops the change-driven actions and keeps the IDE ones`() {
        val git = JcefGitData.gitJson(populated(changes = emptyList()))!!
        val ids = idsOf(git)

        assertFalse(ids.contains("init"))
        assertFalse(ids.contains("commit"))
        assertFalse(ids.contains("revertFile"))
        assertTrue(ids.containsAll(listOf("branches", "newBranch", "pull", "fetch", "push", "merge", "rebase", "stash", "unstash")))
        assertTrue(ids.contains("commitDialog"))
    }

    @Test
    fun `the per-file action appears only when the open file is one of the changed ones`() {
        assertFalse(idsOf(JcefGitData.gitJson(populated(changedFileOpen = false))!!).contains("revertFile"))
        assertTrue(idsOf(JcefGitData.gitJson(populated(changedFileOpen = true))!!).contains("revertFile"))
    }

    @Test
    fun `an action restates the catalogue's own label, hint, kind and group`() {
        val git = JcefGitData.gitJson(populated(changedFileOpen = true))!!
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
        val git = JcefGitData.gitJson(populated(changedFileOpen = true))!!
        val entries = git["actions"]!!.jsonArray.map { it.jsonObject }
        val byId = entries.associate { it["id"]!!.jsonPrimitive.content to it }

        val init = JcefGitData.gitJson(noRepo())!!["actions"]!!.jsonArray.single().jsonObject
        assertEquals("direct", init["kind"]!!.jsonPrimitive.content)
        assertEquals("prompt", byId["commit"]!!["kind"]!!.jsonPrimitive.content)
        assertEquals("ide", byId["branches"]!!["kind"]!!.jsonPrimitive.content)

        val groups = entries.map { it["group"]!!.jsonPrimitive.content }.toSet()
        assertTrue(setOf("Repository", "Ask Claude", "IDE actions").containsAll(groups))
    }

    @Test
    fun `an action that has not been run carries a null status`() {
        val git = JcefGitData.gitJson(populated())!!

        git["actions"]!!.jsonArray.forEach { assertEquals(JsonNull, it.jsonObject["status"]) }
    }

    @Test
    fun `a launched action carries its state, and only its own`() {
        val states = mapOf("commit" to JcefGitData.ActionState.RUNNING, "push" to JcefGitData.ActionState.FAILED)
        val git = JcefGitData.gitJson(populated(actionStates = states))!!
        val byId = git["actions"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }

        assertEquals("running", byId["commit"]!!["status"]!!.jsonPrimitive.content)
        assertEquals("failed", byId["push"]!!["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, byId["fetch"]!!["status"])
    }

    @Test
    fun `an unknown action id contributes no entry and no invented key`() {
        val states = mapOf("nonexistent" to JcefGitData.ActionState.RUNNING)
        val git = JcefGitData.gitJson(populated(actionStates = states))!!

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

    private companion object {

        const val OTHER_PARENT = "fedcba9876543210fedcba9876543210fedcba98"
    }
}
