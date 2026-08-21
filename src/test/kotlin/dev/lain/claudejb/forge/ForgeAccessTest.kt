package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForgeAccessTest {

    private val gitlab = ForgeRepo(ForgeProvider.GITLAB, "gitlab.com", "platform/backend", "svc")

    private val github = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")

    private fun gitlabLevel(level: Int) =
        known(GitLabApi.parseAccess("""{"permissions": {"project_access": {"access_level": $level}}}"""))

    @Test
    fun `a GitLab level is read as a threshold, so a level this build has never heard of still works`() {
        assertEquals(ForgeAccessLevel.NONE, gitlabLevel(0))
        assertEquals(ForgeAccessLevel.NONE, gitlabLevel(5), "minimal access is not read access")
        assertEquals(ForgeAccessLevel.READ, gitlabLevel(10))
        assertEquals(ForgeAccessLevel.READ, gitlabLevel(15), "planner arrived after this code was written")
        assertEquals(ForgeAccessLevel.READ, gitlabLevel(20))
        assertEquals(ForgeAccessLevel.WRITE, gitlabLevel(30))
        assertEquals(ForgeAccessLevel.ADMIN, gitlabLevel(40))
        assertEquals(ForgeAccessLevel.ADMIN, gitlabLevel(50))
        assertEquals(ForgeAccessLevel.ADMIN, gitlabLevel(60), "a level above owner is still at least owner")
    }

    @Test
    fun `the higher of the project and the group is the one that counts`() {
        val level = known(
            GitLabApi.parseAccess(
                """{"permissions": {"project_access": {"access_level": 10},
                   "group_access": {"access_level": 40}}}""",
            ),
        )

        assertEquals(ForgeAccessLevel.ADMIN, level)
    }

    @Test
    fun `no membership at all is no access, not a crash`() {
        assertEquals(ForgeAccessLevel.NONE, known(GitLabApi.parseAccess("""{"permissions": null}""")))
        assertEquals(ForgeAccessLevel.NONE, known(GitLabApi.parseAccess("{}")))
    }

    @Test
    fun `GitHub reports what it lets you do, and maintain counts as admin`() {
        fun level(json: String) = known(GitHubApi.parseAccess(json))

        assertEquals(ForgeAccessLevel.ADMIN, level("""{"permissions": {"admin": true}}"""))
        assertEquals(ForgeAccessLevel.ADMIN, level("""{"permissions": {"maintain": true}}"""))
        assertEquals(ForgeAccessLevel.WRITE, level("""{"permissions": {"push": true, "pull": true}}"""))
        assertEquals(ForgeAccessLevel.READ, level("""{"permissions": {"pull": true}}"""))
        assertEquals(ForgeAccessLevel.READ, level("""{"permissions": {"triage": true}}"""))
        assertEquals(ForgeAccessLevel.NONE, level("""{"permissions": {}}"""))
    }

    @Test
    fun `a public repository that reports no permissions block is still readable`() {
        assertEquals(ForgeAccessLevel.READ, known(GitHubApi.parseAccess("""{"name": "widget"}""")))
    }

    @Test
    fun `what you may do follows from the level, and reading is never enough to merge`() {
        val reader = ForgeAccess(ForgeAccessLevel.READ, "ada")
        val writer = ForgeAccess(ForgeAccessLevel.WRITE, "ada")

        assertTrue(reader.canComment)
        assertTrue(reader.canApprove)
        assertFalse(reader.canMerge)
        assertFalse(reader.canRunPipelines)
        assertFalse(reader.canOpen)
        assertTrue(writer.canMerge)
        assertTrue(writer.canRunPipelines)
    }

    @Test
    fun `knowing who you are is what tells a request of yours from someone else's`() {
        val me = ForgeAccess(ForgeAccessLevel.WRITE, "ada")

        assertTrue(me.authored("ada"))
        assertTrue(me.authored("ADA"), "a forge login is not case sensitive")
        assertFalse(me.authored("grace"))
        assertFalse(me.authored(null), "an unknown author is not you")
        assertFalse(ForgeAccess(ForgeAccessLevel.WRITE, null).authored("ada"), "nor are you an unknown viewer")
    }

    @Test
    fun `the viewer is read from whichever name its forge uses`() {
        assertEquals("ada", known(GitLabApi.parseViewer("""{"username": "ada"}""")))
        assertEquals("ada", known(GitHubApi.parseViewer("""{"login": "ada"}""")))
    }

    @Test
    fun `the account URL never carries the project, and the project URL never carries a branch`() {
        assertEquals("https://gitlab.com/api/v4/user", GitLabApi.viewer(gitlab, "t").uri.toString())
        assertEquals("https://api.github.com/user", GitHubApi.viewer(github, "t").uri.toString())
        assertEquals(
            "https://gitlab.com/api/v4/projects/platform%2Fbackend%2Fsvc",
            GitLabApi.access(gitlab, "t").uri.toString(),
        )
        assertEquals("https://api.github.com/repos/acme/widget", GitHubApi.access(github, "t").uri.toString())
    }
}
