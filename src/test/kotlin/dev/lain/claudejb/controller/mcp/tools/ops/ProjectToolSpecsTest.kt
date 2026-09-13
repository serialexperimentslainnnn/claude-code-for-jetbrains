package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.roots.DependencyScope
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectToolSpecsTest {

    private val all = listOf(ProjectTools.PROJECT, ProjectTools.MODULES, ProjectTools.DEPENDENCIES, ProjectTools.DEPENDENCY_ADD)

    @Test
    fun `the tool names are pinned and fit the domain ceiling`() {
        assertEquals(listOf("project", "modules", "dependencies", "dependency_add"), all.map { it.name })
        assertTrue(all.size <= ToolDomain.MAX_TOOLS)
    }

    @Test
    fun `only dependency_add mutates`() {
        assertTrue(ProjectTools.DEPENDENCY_ADD.mutates)
        (all - ProjectTools.DEPENDENCY_ADD).forEach { assertFalse(it.mutates, "${it.name} is read-only") }
    }

    @Test
    fun `the scopes are the four the project model has, and the parameter names every one`() {
        assertEquals(listOf("compile", "test", "runtime", "provided"), ProjectTools.SCOPES.keys.toList())
        assertEquals(DependencyScope.entries.toSet(), ProjectTools.SCOPES.values.toSet())
        val scope = ProjectTools.DEPENDENCY_ADD.params.first { it.name == "scope" }
        assertFalse(scope.required)
        ProjectTools.SCOPES.keys.forEach { assertTrue(it in scope.description, "scope does not name $it") }
        assertTrue("compile (default)" in scope.description)
    }

    @Test
    fun `no parameter is spelled like a shell command, so the guard never tokenises a module name as one`() {
        all.flatMap { spec -> spec.params.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
        }
    }

    @Test
    fun `no parameter is a filesystem location under another name`() {
        all.flatMap { it.params }.map { it.name }.forEach { assertFalse(it in ALIASES, "$it is a location under another name") }
    }

    @Test
    fun `every integer parameter names its default`() {
        all.flatMap { spec -> spec.params.filter { it.type == "integer" }.map { spec.name to it } }.forEach { (tool, param) ->
            assertTrue("default" in param.description, "$tool.${param.name} names no default")
        }
    }

    @Test
    fun `the tools that take a module require it, and project takes nothing`() {
        assertTrue(ProjectTools.PROJECT.params.isEmpty())
        listOf(ProjectTools.DEPENDENCIES, ProjectTools.DEPENDENCY_ADD).forEach { spec ->
            assertTrue(spec.params.first { it.name == "module" }.required, "${spec.name}.module is required")
        }
        assertTrue(ProjectTools.DEPENDENCY_ADD.params.first { it.name == "library" }.required)
    }

    @Test
    fun `every tool keeps the default timeout`() {
        all.forEach { assertEquals(ToolSpec.DEFAULT_TIMEOUT_MILLIS, it.timeoutMillis, it.name) }
    }

    private companion object {
        val ALIASES = setOf("file", "files", "dir", "directory", "location", "target", "filename", "path", "paths")
    }
}
