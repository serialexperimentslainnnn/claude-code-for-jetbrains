package dev.lain.claudejb.model.protocol

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
