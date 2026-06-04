package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [SessionLauncher.buildArgs] (and [SessionLauncher.binaryPermissionMode]). No IDE: these pin the
 * exact flag set and ORDER the `claude` binary is launched with, the regression surface most likely to break silently
 * when options or defaults change. The `--mcp-config` value is injected directly (mcpConfigJson needs the IDE and is
 * covered by McpConfigBuilderTest), so the arg-vector assembly stays a pure function under test here.
 */
class SessionLauncherTest {

    /** A bare snapshot with everything off; individual tests flip the one field they exercise. */
    private fun opts(
        model: String? = null,
        effort: String? = null,
        permissionMode: String = "default",
        thinkingTokens: Int? = null,
        allowedTools: String = "",
        disallowedTools: String = "",
        settingSources: String = "",
        includePartialMessages: Boolean = false,
        sessionId: String? = null,
        maxTurns: Int? = null,
        maxBudgetUsd: Double? = null,
        fallbackModel: String? = null,
        addDirs: List<String> = emptyList(),
        betas: String? = null,
        strictMcpConfig: Boolean = false,
    ) = SessionLauncher.LaunchOptions(
        model = model,
        effort = effort,
        permissionMode = permissionMode,
        thinkingTokens = thinkingTokens,
        allowedTools = allowedTools,
        disallowedTools = disallowedTools,
        settingSources = settingSources,
        includePartialMessages = includePartialMessages,
        ideMcpEnabled = false,
        ideMcpTransport = "sse",
        ideMcpPort = 64342,
        customMcpServers = "",
        sessionId = sessionId,
        maxTurns = maxTurns,
        maxBudgetUsd = maxBudgetUsd,
        fallbackModel = fallbackModel,
        addDirs = addDirs,
        betas = betas,
        strictMcpConfig = strictMcpConfig,
    )

    private val baseHead = listOf(
        "--print",
        "--output-format", "stream-json",
        "--input-format", "stream-json",
        "--verbose",
        "--permission-prompt-tool", "stdio",
        "--permission-mode", "default",
    )

    @Test
    fun `minimal options emit only the mandatory header`() {
        val args = SessionLauncher.buildArgs(opts(), resume = false, mcpConfig = null)
        assertEquals(baseHead, args)
    }

    @Test
    fun `acceptEdits is downgraded to default for the binary`() {
        val args = SessionLauncher.buildArgs(opts(permissionMode = "acceptEdits"), resume = false, mcpConfig = null)
        assertEquals("default", args[args.indexOf("--permission-mode") + 1])
    }

    @Test
    fun `bypassPermissions is downgraded to default for the binary`() {
        assertEquals("default", SessionLauncher.binaryPermissionMode("bypassPermissions"))
        assertEquals("default", SessionLauncher.binaryPermissionMode("acceptEdits"))
        assertEquals("plan", SessionLauncher.binaryPermissionMode("plan"))
        assertEquals("default", SessionLauncher.binaryPermissionMode("default"))
    }

    @Test
    fun `plan mode passes through unchanged`() {
        val args = SessionLauncher.buildArgs(opts(permissionMode = "plan"), resume = false, mcpConfig = null)
        assertEquals("plan", args[args.indexOf("--permission-mode") + 1])
    }

    @Test
    fun `includePartialMessages adds the flag without a value`() {
        val args = SessionLauncher.buildArgs(opts(includePartialMessages = true), resume = false, mcpConfig = null)
        assertTrue(args.contains("--include-partial-messages"))
        // It is a bare flag: the next token (if any) must not be its "value".
        assertEquals(baseHead + "--include-partial-messages", args)
    }

    @Test
    fun `setting sources are passed when non-blank`() {
        val args = SessionLauncher.buildArgs(opts(settingSources = "user,project,local"), resume = false, mcpConfig = null)
        assertEquals("user,project,local", args[args.indexOf("--setting-sources") + 1])
    }

    @Test
    fun `blank setting sources are omitted`() {
        val args = SessionLauncher.buildArgs(opts(settingSources = ""), resume = false, mcpConfig = null)
        assertFalse(args.contains("--setting-sources"))
    }

    @Test
    fun `model and effort are added in order`() {
        val args = SessionLauncher.buildArgs(opts(model = "sonnet", effort = "high"), resume = false, mcpConfig = null)
        assertEquals(baseHead + listOf("--model", "sonnet", "--effort", "high"), args)
    }

    @Test
    fun `effort without model still appears`() {
        val args = SessionLauncher.buildArgs(opts(effort = "low"), resume = false, mcpConfig = null)
        assertEquals(baseHead + listOf("--effort", "low"), args)
        assertFalse(args.contains("--model"))
    }

    @Test
    fun `thinking adaptive is emitted as launch flags when tokens is non-null`() {
        val args = SessionLauncher.buildArgs(opts(thinkingTokens = 1), resume = false, mcpConfig = null)
        assertEquals(
            baseHead + listOf("--thinking", "adaptive", "--thinking-display", "summarized"),
            args,
        )
    }

    @Test
    fun `no thinking flags when tokens is null`() {
        val args = SessionLauncher.buildArgs(opts(thinkingTokens = null), resume = false, mcpConfig = null)
        assertFalse(args.contains("--thinking"))
        assertFalse(args.contains("--thinking-display"))
    }

    @Test
    fun `allowed and disallowed tools are trimmed and passed`() {
        val args = SessionLauncher.buildArgs(
            opts(allowedTools = "  Read,Edit ", disallowedTools = "Bash"),
            resume = false,
            mcpConfig = null,
        )
        assertEquals("Read,Edit", args[args.indexOf("--allowedTools") + 1])
        assertEquals("Bash", args[args.indexOf("--disallowedTools") + 1])
    }

    @Test
    fun `blank tool lists are omitted`() {
        val args = SessionLauncher.buildArgs(
            opts(allowedTools = "   ", disallowedTools = ""),
            resume = false,
            mcpConfig = null,
        )
        assertFalse(args.contains("--allowedTools"))
        assertFalse(args.contains("--disallowedTools"))
    }

    @Test
    fun `mcp config is appended when present`() {
        val cfg = """{"mcpServers":{"jetbrains":{}}}"""
        val args = SessionLauncher.buildArgs(opts(), resume = false, mcpConfig = cfg)
        assertEquals(cfg, args[args.indexOf("--mcp-config") + 1])
    }

    @Test
    fun `null mcp config emits no flag`() {
        val args = SessionLauncher.buildArgs(opts(), resume = false, mcpConfig = null)
        assertFalse(args.contains("--mcp-config"))
    }

    @Test
    fun `resume appends the session id when requested and present`() {
        val args = SessionLauncher.buildArgs(opts(sessionId = "abc-123"), resume = true, mcpConfig = null)
        assertEquals(baseHead + listOf("--resume", "abc-123"), args)
    }

    @Test
    fun `resume is omitted when not requested even with a session id`() {
        val args = SessionLauncher.buildArgs(opts(sessionId = "abc-123"), resume = false, mcpConfig = null)
        assertFalse(args.contains("--resume"))
    }

    @Test
    fun `resume is omitted when there is no session id`() {
        val args = SessionLauncher.buildArgs(opts(sessionId = null), resume = true, mcpConfig = null)
        assertFalse(args.contains("--resume"))
    }

    @Test
    fun `max-turns is emitted when positive and omitted otherwise`() {
        val on = SessionLauncher.buildArgs(opts(maxTurns = 7), resume = false, mcpConfig = null)
        assertEquals("7", on[on.indexOf("--max-turns") + 1])
        val off = SessionLauncher.buildArgs(opts(maxTurns = null), resume = false, mcpConfig = null)
        assertFalse(off.contains("--max-turns"))
    }

    @Test
    fun `max-budget-usd is emitted when set and omitted otherwise`() {
        val on = SessionLauncher.buildArgs(opts(maxBudgetUsd = 2.5), resume = false, mcpConfig = null)
        assertEquals("2.5", on[on.indexOf("--max-budget-usd") + 1])
        val off = SessionLauncher.buildArgs(opts(maxBudgetUsd = null), resume = false, mcpConfig = null)
        assertFalse(off.contains("--max-budget-usd"))
    }

    @Test
    fun `fallback-model is emitted when set and omitted when blank or null`() {
        val on = SessionLauncher.buildArgs(opts(fallbackModel = "sonnet"), resume = false, mcpConfig = null)
        assertEquals("sonnet", on[on.indexOf("--fallback-model") + 1])
        val blank = SessionLauncher.buildArgs(opts(fallbackModel = "   "), resume = false, mcpConfig = null)
        assertFalse(blank.contains("--fallback-model"))
        val off = SessionLauncher.buildArgs(opts(fallbackModel = null), resume = false, mcpConfig = null)
        assertFalse(off.contains("--fallback-model"))
    }

    @Test
    fun `add-dir is emitted once per directory and blanks are skipped`() {
        val args = SessionLauncher.buildArgs(
            opts(addDirs = listOf("/a", "  ", "/b")), resume = false, mcpConfig = null,
        )
        assertEquals(2, args.count { it == "--add-dir" })
        assertEquals(baseHead + listOf("--add-dir", "/a", "--add-dir", "/b"), args)
    }

    @Test
    fun `add-dir is omitted for an empty list`() {
        val args = SessionLauncher.buildArgs(opts(addDirs = emptyList()), resume = false, mcpConfig = null)
        assertFalse(args.contains("--add-dir"))
    }

    @Test
    fun `betas is emitted when set and omitted otherwise`() {
        val on = SessionLauncher.buildArgs(opts(betas = "a,b"), resume = false, mcpConfig = null)
        assertEquals("a,b", on[on.indexOf("--betas") + 1])
        val off = SessionLauncher.buildArgs(opts(betas = null), resume = false, mcpConfig = null)
        assertFalse(off.contains("--betas"))
    }

    @Test
    fun `strict-mcp-config is a bare flag emitted only when enabled`() {
        val on = SessionLauncher.buildArgs(opts(strictMcpConfig = true), resume = false, mcpConfig = null)
        assertEquals(baseHead + "--strict-mcp-config", on)
        val off = SessionLauncher.buildArgs(opts(strictMcpConfig = false), resume = false, mcpConfig = null)
        assertFalse(off.contains("--strict-mcp-config"))
    }

    @Test
    fun `full option set keeps the exact historical order`() {
        val cfg = """{"mcpServers":{"x":{}}}"""
        val args = SessionLauncher.buildArgs(
            opts(
                model = "opus",
                effort = "high",
                permissionMode = "acceptEdits",
                thinkingTokens = 1,
                allowedTools = "Read",
                disallowedTools = "Bash",
                settingSources = "user,project",
                includePartialMessages = true,
                sessionId = "sid",
                maxTurns = 5,
                maxBudgetUsd = 1.5,
                fallbackModel = "sonnet",
                addDirs = listOf("/a", "/b"),
                betas = "x,y",
                strictMcpConfig = true,
            ),
            resume = true,
            mcpConfig = cfg,
        )
        assertEquals(
            listOf(
                "--print",
                "--output-format", "stream-json",
                "--input-format", "stream-json",
                "--verbose",
                "--permission-prompt-tool", "stdio",
                "--permission-mode", "default", // acceptEdits → default
                "--include-partial-messages",
                "--setting-sources", "user,project",
                "--model", "opus",
                "--effort", "high",
                "--thinking", "adaptive", "--thinking-display", "summarized",
                "--allowedTools", "Read",
                "--disallowedTools", "Bash",
                "--max-turns", "5",
                "--max-budget-usd", "1.5",
                "--fallback-model", "sonnet",
                "--add-dir", "/a", "--add-dir", "/b",
                "--betas", "x,y",
                "--strict-mcp-config",
                "--mcp-config", cfg,
                "--resume", "sid",
            ),
            args,
        )
    }
}
