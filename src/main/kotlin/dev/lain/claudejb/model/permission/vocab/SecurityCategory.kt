package dev.lain.claudejb.model.permission.vocab

enum class SecurityCategory(val label: String) {
    SENSITIVE_DATA("Sensitive data"),

    FILESYSTEM_BOUNDARY("Filesystem boundary"),

    FOREIGN_TERRITORY("Foreign territory"),

    SYSTEM_INTEGRITY("System integrity"),

    NETWORK_EGRESS("Network egress"),

    DESTRUCTIVE_OPERATION("Destructive operations"),

    CODE_EXECUTION("Code execution & persistence"),

    INTRUSION_TECHNIQUE("Intrusion techniques"),

    DEFENCE_EVASION("Defence evasion"),

    OPAQUE("Opaque to the guard"),
}

internal const val MAX_ANALYSIS_DEPTH = 5
