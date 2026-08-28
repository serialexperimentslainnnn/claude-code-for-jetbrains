package dev.lain.claudejb.settings

enum class GuardMode(val wire: String, val label: String, val perRule: Boolean, val summary: String) {

    ENFORCING(
        "enforcing",
        "Enforcing",
        perRule = true,
        summary = "Refuse the call. Claude is told what it cannot do and why.",
    ),

    PERMISSIVE(
        "permissive",
        "Permissive",
        perRule = true,
        summary = "Ask you instead of refusing. A card, every time — nothing is allowed silently.",
    ),

    ALLOW_ALL(
        "allowAll",
        "Allow All",
        perRule = false,
        summary = "Let the call run: no card, no block. The transcript still records what went unenforced.",
    ),
    ;

    companion object {
        val DEFAULT = ENFORCING

        val PER_RULE = entries.filter { it.perRule }

        fun from(wire: String?): GuardMode? = entries.firstOrNull { it.wire == wire?.trim() }
    }
}
