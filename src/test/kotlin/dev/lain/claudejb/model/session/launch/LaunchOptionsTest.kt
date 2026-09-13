package dev.lain.claudejb.model.session.launch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchOptionsTest {

    private val base = LaunchOptions(ideIntegration = true, customMcpServers = """{"a":{}}""")

    @Test
    fun `the MCP configuration is read at launch, so any MCP field that changes differs`() {
        assertTrue(base.relaunchDiffers(base.copy(customMcpServers = """{"b":{}}""")))
        assertTrue(base.relaunchDiffers(base.copy(strictMcpConfig = true)))
    }

    @Test
    fun `our own servers, their sockets and the rules ride the launch, so they differ as well`() {
        assertTrue(base.relaunchDiffers(base.copy(ideIntegration = false)))
        assertTrue(base.relaunchDiffers(base.copy(ideSockets = mapOf(IdeServer.CODE to "/tmp/x/code.sock"))))
        assertTrue(base.relaunchDiffers(base.copy(ideRules = setOf(IdeRule.CODE_READ))))
    }

    @Test
    fun `the flags that only reach the binary as arguments differ`() {
        assertTrue(base.relaunchDiffers(base.copy(settingSources = "user")))
        assertTrue(base.relaunchDiffers(base.copy(includePartialMessages = false)))
        assertTrue(base.relaunchDiffers(base.copy(maxTurns = 3, maxBudgetUsd = 1.0, fallbackModel = "haiku")))
        assertTrue(base.relaunchDiffers(base.copy(addDirs = listOf("/tmp/x"), betas = "b")))
    }

    @Test
    fun `a change the binary takes live, one the plugin's broker takes live, and the session identity are not a relaunch`() {
        assertFalse(base.relaunchDiffers(base.copy(model = "opus", permissionMode = "plan", effort = "high", thinkingTokens = 8)))
        assertFalse(base.relaunchDiffers(base.copy(allowedTools = "Bash,Read", disallowedTools = "WebFetch")))
        assertFalse(base.relaunchDiffers(base.copy(sessionId = "s-1", fork = true)))
        assertFalse(base.relaunchDiffers(base))
    }
}
