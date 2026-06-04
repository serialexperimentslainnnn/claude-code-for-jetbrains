package dev.lain.claudejb.session

/**
 * Typed vocabularies for the three settings that used to be free strings (permission mode, effort, MCP transport).
 *
 * Each constant carries its [wire] value — the exact string the `claude` binary expects on the protocol
 * (`set_permission_mode`, `--mcp-config`) and the value persisted in `claude-code.xml`. Enums are the single
 * source of truth for the allowed values and for branching in domain code; strings remain at the persistence
 * and protocol boundaries (so existing saved configs and the wire contract are untouched). Parse with [from],
 * which returns null for an unknown value — callers decide the fallback.
 */
enum class PermissionMode(val wire: String, val label: String) {
    DEFAULT("default", "Ask each time"),
    ACCEPT_EDITS("acceptEdits", "Accept edits"),
    PLAN("plan", "Plan"),
    BYPASS("bypassPermissions", "Bypass permissions"),
    DONT_ASK("dontAsk", "Don't ask"),
    AUTO("auto", "Auto");

    companion object {
        fun from(wire: String?): PermissionMode? = entries.firstOrNull { it.wire == wire }

        /** Human label for a wire mode (e.g. "default" → "Ask each time"), or the raw value if unknown. */
        fun labelFor(wire: String?): String = from(wire)?.label ?: wire.orEmpty()

        /** The Shift+Tab cycle, like the CLI. */
        val CYCLE: List<PermissionMode> = listOf(DEFAULT, ACCEPT_EDITS, PLAN)
    }
}

enum class EffortLevel(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        fun from(wire: String?): EffortLevel? = entries.firstOrNull { it.wire == wire }
    }
}

enum class McpTransport(val wire: String) {
    SSE("sse"),
    STREAMABLE_HTTP("streamable-http"),
    STDIO("stdio");

    companion object {
        fun from(wire: String?): McpTransport? = entries.firstOrNull { it.wire == wire }
    }
}
