package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.github.GitHubAvailability
import dev.lain.claudejb.model.mcp.ToolException

class GitHubAvailabilityHeadlessTest : BasePlatformTestCase() {

    fun `test require passes exactly when the GitHub plugin is enabled, and names the plugin otherwise`() {
        if (GitHubAvailability.isEnabled()) {
            GitHubAvailability.require()
            return
        }
        val refusal = runCatching { GitHubAvailability.require() }.exceptionOrNull()
        assertTrue(refusal is ToolException)
        assertEquals(GitHubAvailability.MISSING, refusal!!.message)
        assertTrue(GitHubAvailability.MISSING.contains(GitHubAvailability.PLUGIN_ID))
    }
}
