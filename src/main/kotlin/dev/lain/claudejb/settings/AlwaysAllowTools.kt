package dev.lain.claudejb.settings

/**
 * The tools the user chose to auto-approve — the "Always allow" set, kept as one CSV field.
 *
 * Keyed by tool NAME only. Path containment for reviewable writes is enforced independently by the broker
 * (`DiffPresenter.isWithinRoot`), so a remembered write outside the project root still falls through to a
 * manual card, and a remembered tool never widens where it may write.
 *
 * Its own class rather than five methods on [ClaudeSettings]: it is one subject with one representation, and
 * every mutation must persist — a bare `state.alwaysAllowTools = …` is a change that silently does not
 * survive a restart, which is exactly the bug these went through [ClaudeSettings.update] to fix.
 */
class AlwaysAllowTools(private val settings: ClaudeSettings) {

    /** Trimmed, non-empty, de-duplicated, order-stable. */
    fun all(): List<String> =
        settings.state.alwaysAllowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    operator fun contains(toolName: String): Boolean = toolName.isNotBlank() && toolName in all()

    /** Idempotent. */
    fun remember(toolName: String) {
        if (toolName.isBlank() || toolName in this) return
        replace(all() + toolName)
    }

    fun forget(toolName: String) {
        val target = toolName.trim()
        if (target.isEmpty()) return
        replace(all().filterNot { it == target })
    }

    fun replace(tools: List<String>) = settings.update {
        it.alwaysAllowTools = tools.map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.distinct().joinToString(",")
    }
}
