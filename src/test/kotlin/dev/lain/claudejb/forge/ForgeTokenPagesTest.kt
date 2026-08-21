package dev.lain.claudejb.forge

import dev.lain.claudejb.git.GitRemoteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForgeTokenPagesTest {

    @Test
    fun `GitLab is asked for the narrow scope when only reading is wanted`() {
        val page = ForgeTokenPages.of(GitRemoteProvider.GITLAB, "gitlab.com", ForgeTokenReach.READ).single()

        assertEquals(
            "https://gitlab.com/-/user_settings/personal_access_tokens" +
                "?name=Claude+Code+Native&scopes=read_api",
            page.url,
        )
    }

    @Test
    fun `GitLab writing says out loud that its only write scope is the whole API`() {
        val page = ForgeTokenPages.of(GitRemoteProvider.GITLAB, "gitlab.com", ForgeTokenReach.WRITE).single()

        assertTrue(page.url.endsWith("scopes=api"))
        assertTrue(page.note.contains("no narrower write scope"))
    }

    @Test
    fun `a self-managed GitLab keeps its own host`() {
        val page = ForgeTokenPages.of(GitRemoteProvider.GITLAB, "git.acme.example", ForgeTokenReach.READ).single()

        assertTrue(page.url.startsWith("https://git.acme.example/-/user_settings/"))
    }

    @Test
    fun `GitHub offers the pre-filled classic token and the fine-grained one it cannot pre-fill`() {
        val pages = ForgeTokenPages.of(GitRemoteProvider.GITHUB, "github.com", ForgeTokenReach.WRITE)

        assertEquals(2, pages.size)
        assertTrue(pages[0].url.contains("/settings/tokens/new?description=Claude+Code+Native&scopes="))
        assertTrue(pages[1].url.endsWith("/settings/personal-access-tokens/new"))
        assertFalse(pages[1].url.contains("scopes="), "the fine-grained page ignores query parameters")
        assertTrue(pages[1].note.contains("tick these yourself"))
    }

    @Test
    fun `the fine-grained note warns that merging needs the permission that also lets it push`() {
        val write = ForgeTokenPages.of(GitRemoteProvider.GITHUB, "github.com", ForgeTokenReach.WRITE)[1]
        val read = ForgeTokenPages.of(GitRemoteProvider.GITHUB, "github.com", ForgeTokenReach.READ)[1]

        assertTrue(write.note.contains("Contents write, which also lets the token push"))
        assertFalse(read.note.contains("push"), "reading is never told about pushing")
    }

    @Test
    fun `a host this build does not recognise is offered nothing to click`() {
        assertTrue(ForgeTokenPages.of(GitRemoteProvider.OTHER, "git.acme.example", ForgeTokenReach.READ).isEmpty())
    }
}
