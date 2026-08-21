package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabApiTest {

    private val repo = ForgeRepo(ForgeProvider.GITLAB, "gitlab.com", "platform/backend", "svc")

    @Test
    fun `a nested group's project path is one percent-encoded segment`() {
        assertEquals(
            "https://gitlab.com/api/v4/projects/platform%2Fbackend%2Fsvc/merge_requests" +
                "?state=opened&per_page=20&source_branch=feature%2Fx",
            GitLabApi.pullRequests(repo, "feature/x", "t").uri.toString(),
        )
    }

    @Test
    fun `the pipelines URL asks for a page of runs on the branch`() {
        assertEquals(
            "https://gitlab.com/api/v4/projects/platform%2Fbackend%2Fsvc/pipelines?ref=main&per_page=20",
            GitLabApi.runs(repo, "main", "t").uri.toString(),
        )
    }

    @Test
    fun `a self-managed host is the same v4 base under a different name`() {
        val onPrem = repo.copy(host = "git.acme.example")
        assertTrue(
            GitLabApi.runs(onPrem, "main", "t").uri.toString()
                .startsWith("https://git.acme.example/api/v4/projects/"),
        )
    }

    @Test
    fun `every GitLab request names the client, as the GitHub ones already did`() {
        assertEquals(ForgeHttp.USER_AGENT, GitLabApi.pullRequests(repo, "main", "t").headers["User-Agent"])
        assertEquals(ForgeHttp.USER_AGENT, GitLabApi.runs(repo, "main", "t").headers["User-Agent"])
    }

    @Test
    fun `a merge request is read onto the shared model, iid and all`() {
        val mrs = known(GitLabApi.parsePullRequests(TWO_MERGE_REQUESTS))

        assertEquals(7L, mrs[0].number)
        assertEquals("https://gitlab.com/platform/backend/svc/-/merge_requests/7", mrs[0].url)
        assertEquals("open", mrs[0].state)
        assertEquals("ada", mrs[0].author)
        assertTrue(mrs[1].draft)
    }

    @Test
    fun `an empty list is a real answer and not a silence`() {
        assertEquals(ForgeAnswer.Known(emptyList<ForgePullRequest>()), GitLabApi.parsePullRequests("[]"))
    }

    @Test
    fun `a malformed body draws no card`() {
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.MALFORMED),
            GitLabApi.parsePullRequests("""{"message": "404 Project Not Found"}"""),
        )
        assertEquals(
            ForgeAnswer.Silent(ForgeSilence.MALFORMED),
            GitLabApi.parsePullRequests("""[{"iid": {"nested": true}}]"""),
        )
    }

    @Test
    fun `a successful pipeline is completed and dated from updated_at`() {
        val run = pipelineFrom("success")

        assertEquals(ForgeRunStatus.COMPLETED, run?.status)
        assertEquals("2026-08-17T09:31:02.000Z", run?.finishedAtIso)
        assertEquals("Build pipeline", run?.name)
        assertEquals("https://gitlab.com/platform/backend/svc/-/pipelines/500", run?.url)
    }

    @Test
    fun `every state that has not finished is running, and reports no finish time`() {
        listOf("created", "pending", "running", "preparing", "waiting_for_resource", "manual", "scheduled")
            .forEach { state ->
                val run = pipelineFrom(state)
                assertEquals(ForgeRunStatus.RUNNING, run?.status) { state }
                assertNull(run?.finishedAtIso) { state }
            }
    }

    @Test
    fun `the terminal states map onto the four words the page colours by`() {
        assertEquals(ForgeRunStatus.FAILED, pipelineFrom("failed")?.status)
        assertEquals(ForgeRunStatus.STOPPED, pipelineFrom("canceled")?.status)
        assertEquals(ForgeRunStatus.STOPPED, pipelineFrom("canceling")?.status)
        assertEquals(ForgeRunStatus.STOPPED, pipelineFrom("skipped")?.status)
    }

    @Test
    fun `a state this build does not know drops the pipeline instead of guessing a colour`() {
        assertNull(pipelineFrom("hibernating"))
    }

    @Test
    fun `no pipeline at all is a real answer, distinct from a silence`() {
        assertEquals(ForgeAnswer.Known(emptyList<ForgeRun>()), GitLabApi.parseRuns("[]"))
    }

    @Test
    fun `a page of pipelines keeps every run it can place, newest first`() {
        val runs = known(
            GitLabApi.parseRuns(
                """
                [{"status": "running", "name": "Second", "web_url": "https://gitlab.com/p/-/pipelines/501",
                  "updated_at": "2026-08-17T10:00:00.000Z"},
                 {"status": "hibernating", "name": "Unknown", "web_url": "https://gitlab.com/p/-/pipelines/499"},
                 {"status": "failed", "name": "First", "web_url": "https://gitlab.com/p/-/pipelines/498",
                  "updated_at": "2026-08-17T08:00:00.000Z"}]
                """.trimIndent(),
            ),
        )

        assertEquals(2, runs.size)
        assertEquals("Second", runs[0].name)
        assertEquals(ForgeRunStatus.FAILED, runs[1].status)
    }

    private fun pipelineFrom(status: String): ForgeRun? = known(
        GitLabApi.parseRuns(
            """
            [{"id": 500, "iid": 12, "project_id": 3, "sha": "cf73e32", "ref": "feature/x",
              "status": "$status", "source": "push", "name": "Build pipeline",
              "web_url": "https://gitlab.com/platform/backend/svc/-/pipelines/500",
              "created_at": "2026-08-17T09:20:00.000Z", "updated_at": "2026-08-17T09:31:02.000Z"}]
            """.trimIndent(),
        ),
    ).firstOrNull()

    private companion object {

        val TWO_MERGE_REQUESTS = """
            [
              {"id": 90210, "iid": 7, "project_id": 3, "title": "Add the thing",
               "web_url": "https://gitlab.com/platform/backend/svc/-/merge_requests/7",
               "state": "opened", "draft": false, "work_in_progress": false,
               "source_branch": "feature/x", "target_branch": "main", "author": {"username": "ada"}},
              {"id": 90211, "iid": 8, "title": "Draft: WIP",
               "web_url": "https://gitlab.com/platform/backend/svc/-/merge_requests/8",
               "state": "opened", "draft": true, "author": {"username": "grace"}}
            ]
        """.trimIndent()
    }
}
