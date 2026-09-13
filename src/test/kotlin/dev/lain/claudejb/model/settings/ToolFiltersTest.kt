package dev.lain.claudejb.model.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolFiltersTest {

    private val state = ClaudeSettings.State().apply {
        allowedTools = "Bash, Read"
        disallowedTools = "WebFetch"
    }

    @Test
    fun `the lists are read as they are stored, whitespace tolerated, names exact`() {
        assertTrue(ToolFilters.isAllowed(state, "Bash"))
        assertTrue(ToolFilters.isAllowed(state, "Read"))
        assertFalse(ToolFilters.isAllowed(state, "bash"))
        assertFalse(ToolFilters.isAllowed(state, "WebFetch"))
        assertTrue(ToolFilters.isDisallowed(state, "WebFetch"))
        assertFalse(ToolFilters.isDisallowed(state, "Bash"))
    }

    @Test
    fun `empty lists match nothing`() {
        val empty = ClaudeSettings.State()
        assertFalse(ToolFilters.isAllowed(empty, "Bash"))
        assertFalse(ToolFilters.isDisallowed(empty, ""))
    }
}
