package dev.lain.claudejb.session

enum class PermissionMode(val wire: String, val label: String) {
    DEFAULT("default", "Ask each time"),
    ACCEPT_EDITS("acceptEdits", "Accept edits"),
    PLAN("plan", "Plan"),
    BYPASS("bypassPermissions", "Bypass permissions"),
    DONT_ASK("dontAsk", "Don't ask"),
    AUTO("auto", "Auto"),
    ;

    companion object {
        fun from(wire: String?): PermissionMode? = entries.firstOrNull { it.wire == wire }

        fun labelFor(wire: String?): String = from(wire)?.label ?: wire.orEmpty()

        val CYCLE: List<PermissionMode> = listOf(DEFAULT, ACCEPT_EDITS, PLAN)
    }
}

enum class EffortLevel(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    ;

    companion object {
        fun from(wire: String?): EffortLevel? = entries.firstOrNull { it.wire == wire }
    }
}

enum class McpTransport(val wire: String) {
    SSE("sse"),
    STREAMABLE_HTTP("streamable-http"),
    STDIO("stdio"),
    ;

    companion object {
        fun from(wire: String?): McpTransport? = entries.firstOrNull { it.wire == wire }
    }
}
