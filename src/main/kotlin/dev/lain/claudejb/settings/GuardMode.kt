package dev.lain.claudejb.settings

/**
 * What happens when the guard matches something.
 *
 * One vocabulary for the whole feature: the guard as a whole has a mode, and so does every individual rule.
 * The rules only get the first two — [ALLOW_ALL] is a statement about the guard, not about one rule, and a
 * rule that allowed silently would be a rule that may as well not exist.
 */
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

        /** The two a single rule may be set to. */
        val PER_RULE = entries.filter { it.perRule }

        fun from(wire: String?): GuardMode? = entries.firstOrNull { it.wire == wire?.trim() }
    }
}
