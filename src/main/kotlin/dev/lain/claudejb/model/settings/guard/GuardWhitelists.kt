package dev.lain.claudejb.model.settings.guard

import dev.lain.claudejb.model.permission.vocab.SecurityCategory
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.settings.ClaudeSettings

object GuardWhitelists {

    enum class Listed { RULE, CATEGORY, EVERYWHERE }

    fun all(state: ClaudeSettings.State, rule: SecurityRule): List<String> =
        byRule(state.securityRuleWhitelists)[rule].orEmpty().toList() +
            byCategory(state.securityCategoryWhitelists)[rule.category].orEmpty() +
            commands(state.securityCommandWhitelist)

    fun add(state: ClaudeSettings.State, rule: SecurityRule, command: String) {
        state.securityRuleWhitelists = withEntry(state.securityRuleWhitelists, rule.name, command)
    }

    fun listedIn(state: ClaudeSettings.State, rule: SecurityRule, covers: (String) -> Boolean): Set<Listed> = buildSet {
        if (holds(state.securityCommandWhitelist, null, covers)) add(Listed.EVERYWHERE)
        if (holds(state.securityCategoryWhitelists, rule.category.name, covers)) add(Listed.CATEGORY)
        if (holds(state.securityRuleWhitelists, rule.name, covers)) add(Listed.RULE)
    }

    fun remove(state: ClaudeSettings.State, rule: SecurityRule, from: Set<Listed>, covers: (String) -> Boolean) {
        if (Listed.EVERYWHERE in from) state.securityCommandWhitelist = without(state.securityCommandWhitelist, null, covers)
        if (Listed.CATEGORY in from) {
            state.securityCategoryWhitelists = without(state.securityCategoryWhitelists, rule.category.name, covers)
        }
        if (Listed.RULE in from) state.securityRuleWhitelists = without(state.securityRuleWhitelists, rule.name, covers)
    }

    fun entryFor(command: String): String {
        val tokens = command.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val program = tokens.firstOrNull() ?: return ""
        val subcommand = tokens.getOrNull(1)?.takeIf { SUBCOMMAND.matches(it) } ?: return program
        return program + " " + subcommand
    }

    private val WHITESPACE = Regex("""\s+""")

    private val SUBCOMMAND = Regex("""^[a-z][a-z0-9_-]*$""")

    fun commands(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

    fun byRule(text: String): Map<SecurityRule, Set<String>> = keyed(text) { SecurityRule.from(it) }

    fun byCategory(text: String): Map<SecurityCategory, Set<String>> =
        keyed(text) { name -> SecurityCategory.entries.firstOrNull { it.name == name } }

    fun withEntry(text: String, key: String, command: String): String {
        val wanted = command.trim()
        if (wanted.isEmpty()) return text
        val line = "$key=$wanted"
        if (entries(text).any { it == line }) return text
        return if (text.isBlank()) line else text.trimEnd() + "\n" + line
    }

    fun holds(text: String, key: String?, same: (String) -> Boolean): Boolean =
        entries(text).any { matches(it, key, same) }

    fun without(text: String, key: String?, same: (String) -> Boolean): String =
        entries(text).filterNot { matches(it, key, same) }.joinToString("\n")

    private fun matches(entry: String, key: String?, same: (String) -> Boolean): Boolean {
        if (key == null) return same(entry)
        return entry.substringBefore('=', "").trim() == key && same(entry.substringAfter('=', "").trim())
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
