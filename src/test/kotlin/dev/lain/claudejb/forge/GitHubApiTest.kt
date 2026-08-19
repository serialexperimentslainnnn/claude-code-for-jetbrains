package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubApiTest {

    private val repo = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")

    @Test
    fun `the pulls URL filters by open state and by owner-qualified head branch`() {
        assertEquals(
            "https://api.github.com/repos/acme/widget/pulls?state=open&per_page=20&head=acme%3Afeature%2Fx",
            GitHubApi.pullRequests(repo, "feature/x", "t").uri.toString(),
        )
    }

    @Test
    fun `the runs URL asks for one page of one, newest first by the API's own default`() {
        assertEquals(
            "https://api.github.com/repos/acme/widget/actions/runs?branch=feature%2Fx&per_page=1",
            GitHubApi.latestRun(repo, "feature/x", "t").uri.toString(),
        )
    }

    @Test
    fun `an enterprise host goes through its own api v3 base`() {
        val ghe = repo.copy(host = "github.acme.example")
        assertTrue(
            GitHubApi.latestRun(ghe, "main", "t").uri.toString()
                .startsWith("https://github.acme.example/api/v3/repos/acme/widget/"),
        )
    }

    @Test
    fun `an owner that tries to walk out of the repos path is percent-encoded, not obeyed`() {
        val hostile = repo.copy(owner = "../../orgs")
        val uri = GitHubApi.pullRequests(hostile, "main", "t").uri.toString()
        assertTrue("/repos/..%2F..%2Forgs/widget/pulls" in uri) { uri }
    }

    @Test
    fun `a pull request is read onto the shared model`() {
        val pulls = known(GitHubApi.parsePullRequests(TWO_PULLS))

        assertEquals(
            ForgePullRequest(42, "Add the thing", "https://github.com/acme/widget/pull/42", "open", false, "ada"),
            pulls[0],
        )
        assertTrue(pulls[1].draft)
        assertEquals("grace", pulls[1].author)
    }

    @Test
    fun `an empty list is a real answer and not a silence`() {
        assertEquals(ForgeAnswer.Known(emptyList<ForgePullRequest>()), GitHubApi.parsePullRequests("[]"))
    }

    @Test
    fun `a malformed body draws no card`() {
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.MALFORMED),
            GitHubApi.parsePullRequests("""{ "message": "Not Found" """),
        )
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.MALFORMED),
            GitHubApi.parsePullRequests("""[{"number": "forty-two"}]"""),
        )
    }

    @Test
    fun `a successful run is completed and carries its finish time`() {
        val run = runFrom(status = "completed", conclusion = "success")

        assertEquals(ForgeRunStatus.COMPLETED, run?.status)
        assertEquals("completed", run?.status?.wire)
        assertEquals("2026-08-17T09:31:02Z", run?.finishedAtIso)
        assertEquals("CI", run?.name)
        assertEquals("https://github.com/acme/widget/actions/runs/900", run?.url)
    }

    @Test
    fun `a run still going is running and reports no finish time`() {
        val run = runFrom(status = "in_progress", conclusion = null)

        assertEquals(ForgeRunStatus.RUNNING, run?.status)
        assertNull(run?.finishedAtIso)
    }

    @Test
    fun `every queued shape is running too`() {
        listOf("queued", "waiting", "requested", "pending").forEach { state ->
            assertEquals(ForgeRunStatus.RUNNING, runFrom(state, null)?.status) { state }
        }
    }

    @Test
    fun `the terminal conclusions map onto the four words the page colours by`() {
        assertEquals(ForgeRunStatus.FAILED, runFrom("completed", "failure")?.status)
        assertEquals(ForgeRunStatus.FAILED, runFrom("completed", "timed_out")?.status)
        assertEquals(ForgeRunStatus.COMPLETED, runFrom("completed", "neutral")?.status)
        assertEquals(ForgeRunStatus.STOPPED, runFrom("completed", "cancelled")?.status)
        assertEquals(ForgeRunStatus.STOPPED, runFrom("completed", "skipped")?.status)
    }

    @Test
    fun `a conclusion this build does not know drops the run instead of guessing a colour`() {
        assertNull(runFrom("completed", "quantum_tunnelled"))
    }

    @Test
    fun `no runs at all is a real answer, distinct from a silence`() {
        assertEquals(
            ForgeAnswer.Known(null),
            GitHubApi.parseLatestRun("""{"total_count": 0, "workflow_runs": []}"""),
        )
    }

    @Test
    fun `the run reply is an envelope, not a bare array`() {
        assertEquals(ForgeAnswer.Silent(ForgeSilence.MALFORMED), GitHubApi.parseLatestRun("""[{"id": 1}]"""))
    }

    private fun runFrom(status: String, conclusion: String?): ForgeRun? {
        val conclusionField = conclusion?.let { """"$it"""" } ?: "null"
        return known(
            GitHubApi.parseLatestRun(
                """
                {"total_count": 7, "workflow_runs": [
                  {"id": 900, "name": "CI", "status": "$status", "conclusion": $conclusionField,
                   "html_url": "https://github.com/acme/widget/actions/runs/900",
                   "head_branch": "feature/x", "event": "push",
                   "run_started_at": "2026-08-17T09:20:00Z", "updated_at": "2026-08-17T09:31:02Z"}
                ]}
                """.trimIndent(),
            ),
        )
    }

    private companion object {

        val TWO_PULLS = """
            [
              {"number": 42, "title": "Add the thing", "html_url": "https://github.com/acme/widget/pull/42",
               "state": "open", "draft": false, "user": {"login": "ada"}, "locked": false},
              {"number": 43, "title": "WIP", "html_url": "https://github.com/acme/widget/pull/43",
               "state": "open", "draft": true, "user": {"login": "grace"}}
            ]
        """.trimIndent()
    }
}
