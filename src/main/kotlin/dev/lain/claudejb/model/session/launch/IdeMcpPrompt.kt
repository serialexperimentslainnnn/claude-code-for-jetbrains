package dev.lain.claudejb.model.session.launch

object IdeMcpPrompt {

    fun text(servers: Set<IdeServer>, rules: Set<IdeRule> = emptySet()): String {
        val parts = listOfNotNull(serversParagraph(servers), ruleLines(rules, servers))
        if (parts.isEmpty()) return ""
        return (listOf(OPEN) + parts + CLOSE).joinToString("\n")
    }

    fun rulesBlock(rules: Set<IdeRule>, servers: Set<IdeServer>): String =
        ruleLines(rules, servers)?.let { listOf(OPEN, it, CLOSE).joinToString("\n") } ?: ""

    private fun serversParagraph(servers: Set<IdeServer>): String? {
        val on = IdeServer.entries.filter { it in servers }
        if (on.isEmpty()) return null
        val listed = on.joinToString(", ") { it.key + " (" + PURPOSE.getValue(it) + ")" }
        return SERVERS + listed + ". " + HOW
    }

    private fun ruleLines(rules: Set<IdeRule>, servers: Set<IdeServer>): String? {
        val active = IdeRule.active(rules, servers)
        if (active.isEmpty()) return null
        val lines = mutableListOf(HEADER)
        var n = 0
        for (server in IdeServer.entries + null) {
            val own = active.filter { it.server == server }
            if (own.isEmpty()) continue
            lines += SERVER_HEADERS.getValue(server)
            own.forEach { lines += "${++n}. ${RULE_TEXT.getValue(it)}" }
        }
        return lines.joinToString("\n")
    }

    const val OPEN = "<ide-integration>"
    const val CLOSE = "</ide-integration>"

    private const val SERVERS = "The IDE is reachable through MCP servers of this plugin's own: "

    private const val HOW = "Each lists only domains(), tools(domain) and run(tool, args): call domains() first, " +
        "load a domain's tools only when a task needs them, and go through the IDE whenever it has the tool " +
        "instead of Read, Grep, Glob, Edit, Write or a shell."

    private val PURPOSE: Map<IdeServer, String> = mapOf(
        IdeServer.CODE to "read, search, navigate, outline, diagnose, inspect, edit, refactor, format, the editor and " +
            "the hierarchy, all through the IDE's index",
        IdeServer.RUN to "build, run configurations, tests, the Terminal window, the debugger and its breakpoints",
        IdeServer.VCS to "git as the IDE sees it, git writes through the IDE, and the forge through its own views",
        IdeServer.OPS to "the Services panel, the project model, the IDE itself, notifications, databases, the HTTP " +
            "Client and SSH hosts",
    )

    const val HEADER = "The user's IDE rules override your defaults. Their tools go through run(tool, args) over the " +
        "socket, never through a new process: never Read, Grep, Glob, Edit, Write or Bash, not even outside the " +
        "project; ours cost a fraction. One call carries a whole list. A refusal from the guard names the rule and " +
        "the text it refused: correct that text once, in the same tool, never by retrying it unchanged and never by " +
        "switching to another tool. The IDE is revealed, never focused: the user keeps their caret and their " +
        "Terminal tab. What the servers lack is said, never done natively."

    private val SERVER_HEADERS: Map<IdeServer?, String> = mapOf(
        IdeServer.CODE to "code server:",
        IdeServer.RUN to "run server:",
        IdeServer.VCS to "vcs server:",
        IdeServer.OPS to "ops server:",
        null to "Always:",
    )

    private val RULE_TEXT: Map<IdeRule, String> = IdeRuleText.RULES
}
