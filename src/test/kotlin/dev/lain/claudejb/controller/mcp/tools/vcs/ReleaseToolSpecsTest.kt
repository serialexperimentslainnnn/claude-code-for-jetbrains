package dev.lain.claudejb.controller.mcp.tools.vcs

import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.util.PluginIdentity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseToolSpecsTest {

    private val ops = listOf(
        PullRequestOpsTools.PR_CREATE,
        PullRequestOpsTools.PR_COMMENT,
        PullRequestOpsTools.PR_CHECKS,
        PullRequestOpsTools.PR_MERGE,
    )
    private val release = listOf(ReleaseTools.TAGS, ReleaseTools.WORKFLOW_RUNS, ReleaseTools.RELEASE, ReleaseTools.MARKETPLACE)
    private val all = ops + release

    @Test
    fun `the tool names are pinned per domain, four each`() {
        assertEquals(listOf("pr_create", "pr_comment", "pr_checks", "pr_merge"), ops.map { it.name })
        assertEquals(listOf("tags", "workflow_runs", "release", "marketplace"), release.map { it.name })
        assertEquals(all.size, all.map { it.name }.toSet().size, "two release tools share a name")
    }

    @Test
    fun `what writes to GitHub says so, and what only reads does not`() {
        listOf(PullRequestOpsTools.PR_CREATE, PullRequestOpsTools.PR_COMMENT, PullRequestOpsTools.PR_MERGE)
            .forEach { assertTrue(it.mutates, "${it.name} writes to GitHub and must say so") }
        (release + PullRequestOpsTools.PR_CHECKS).forEach { assertFalse(it.mutates, "${it.name} is read-only") }
    }

    @Test
    fun `a merge says it may publish, refuses when not green, and takes only a number and the commit text`() {
        val merge = PullRequestOpsTools.PR_MERGE
        assertTrue("publishes" in merge.description, "pr_merge must warn that merging can publish")
        assertTrue("refuses" in merge.description, "pr_merge must say it refuses when not green")
        assertEquals(listOf("number", "subject", "body"), merge.params.map { it.name })
        assertEquals(listOf("number"), merge.params.filter { it.required }.map { it.name })
    }

    @Test
    fun `checks wait like the long tools do, and say whether they settled`() {
        val checks = PullRequestOpsTools.PR_CHECKS
        assertEquals(listOf("number", "wait"), checks.params.map { it.name })
        assertTrue("settled" in checks.description)
    }

    @Test
    fun `a pull request is created from named branches, never from a path`() {
        assertEquals(listOf("base", "head", "title", "body", "draft"), PullRequestOpsTools.PR_CREATE.params.map { it.name })
        assertEquals(listOf("base", "head", "title"), PullRequestOpsTools.PR_CREATE.params.filter { it.required }.map { it.name })
    }

    @Test
    fun `no parameter is spelled like a shell command, and none is a filesystem location`() {
        all.flatMap { spec -> spec.params.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
            assertFalse(param == "path" || param == "paths", "$tool.$param: nothing here touches the filesystem")
        }
    }

    @Test
    fun `the Marketplace id is the plugin's own listing`() {
        assertEquals(31965, PluginIdentity.MARKETPLACE_ID)
        assertTrue("no account" in ReleaseTools.MARKETPLACE.description, "marketplace must say it sends no account")
    }
}
