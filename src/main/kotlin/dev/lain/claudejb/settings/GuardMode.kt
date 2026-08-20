package dev.lain.claudejb.settings

/**
 * What the guard does with a rule it has matched — the same word for one rule and for all of them.
 *
 * This is **not** the same axis as the shield. The shield decides whether the guard judges anything at all
 * (*Allow All* while it is down); the mode decides what a match means while it is up. A rule is Enforcing
 * unless the user says otherwise, and so is the guard as a whole.
 */
enum class GuardMode(val wire: String, val label: String) {

    /** A match is refused outright, in every permission mode and for every caller. */
    ENFORCING("enforcing", "Enforcing"),

    /** A match is put to the user as a card, every time. Detection still runs; nothing is silently allowed. */
    PERMISSIVE("permissive", "Permissive"),
    ;

    companion object {
        val DEFAULT = ENFORCING

        fun from(wire: String?): GuardMode? = entries.firstOrNull { it.wire == wire?.trim() }
    }
}
