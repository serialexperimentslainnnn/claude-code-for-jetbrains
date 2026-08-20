package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule

/**
 * The three lists of commands the user has decided may run, and how they are written down.
 *
 * They differ only in **reach**, and the guard asks them narrowest-first — the rule that actually fired,
 * then that rule's category, then the global list — so a verdict can always be explained by pointing at one
 * entry rather than at "it is whitelisted somewhere".
 *
 * - **Global** (`securityCommandWhitelist`): one command per line, `#` comments a line. Lifts any rule.
 * - **Per category** (`securityCategoryWhitelists`): `CATEGORY=command`, one per line.
 * - **Per rule** (`securityRuleWhitelists`): `RULE=command`, one per line. What the *Whitelist Command* link
 *   on a block writes, because the rule that stopped the call is the narrowest true statement available.
 *
 * An unresolvable key is dropped rather than guessed, which can only ever fail to WIDEN a permission — the
 * same direction of failure the disabled-rule CSV chose, and for the same reason.
 */
object GuardWhitelists {

    /** The global list: every non-blank, non-comment line. */
    fun commands(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

    fun byRule(text: String): Map<SecurityRule, Set<String>> = keyed(text) { SecurityRule.from(it) }

    fun byCategory(text: String): Map<SecurityCategory, Set<String>> =
        keyed(text) { name -> SecurityCategory.entries.firstOrNull { it.name == name } }

    /** [text] with `key=command` appended, or [text] unchanged when that pair is already in it. */
    fun withEntry(text: String, key: String, command: String): String {
        val wanted = command.trim()
        if (wanted.isEmpty()) return text
        val line = "$key=$wanted"
        if (entries(text).any { it == line }) return text
        return if (text.isBlank()) line else text.trimEnd() + "\n" + line
    }

    private fun entries(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

    private fun <K> keyed(text: String, resolve: (String) -> K?): Map<K, Set<String>> {
        val out = LinkedHashMap<K, MutableSet<String>>()
        entries(text).forEach { entry ->
            val command = entry.substringAfter('=', "").trim()
            if (command.isEmpty()) return@forEach
            val key = resolve(entry.substringBefore('=', "").trim()) ?: return@forEach
            out.getOrPut(key) { LinkedHashSet() }.add(command)
        }
        return out
    }
}
