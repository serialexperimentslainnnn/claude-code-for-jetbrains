package dev.lain.claudejb.settings

class AlwaysAllowTools(private val settings: ClaudeSettings) {

    fun all(): List<String> =
        settings.state.alwaysAllowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    operator fun contains(toolName: String): Boolean = toolName.isNotBlank() && toolName in all()

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
