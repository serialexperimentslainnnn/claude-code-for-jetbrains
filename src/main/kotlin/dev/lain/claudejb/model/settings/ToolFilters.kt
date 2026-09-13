package dev.lain.claudejb.model.settings

object ToolFilters {

    fun isAllowed(state: ClaudeSettings.State, tool: String): Boolean = tool in listed(state.allowedTools)

    fun isDisallowed(state: ClaudeSettings.State, tool: String): Boolean = tool in listed(state.disallowedTools)

    private fun listed(csv: String): Set<String> = csv.split(',').map { it.trim() }.filterTo(HashSet()) { it.isNotEmpty() }
}
