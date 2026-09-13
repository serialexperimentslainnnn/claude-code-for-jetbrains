package dev.lain.claudejb.controller.mcp.tools.ops

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

class ServiceToolSpecsTest {

    private val all = listOf(ServiceTools.SERVICES, ServiceTools.SERVICE_ACTIONS, ServiceTools.SERVICE_ACTION, ServiceTools.SERVICE_OPEN)

    @Test
    fun `the tool names are pinned and fit one domain`() {
        assertEquals(listOf("services", "service_actions", "service_action", "service_open"), all.map(ToolSpec::name))
        assertTrue(all.size <= ToolDomain.MAX_TOOLS)
    }

    @Test
    fun `performing an action is the one tool that mutates`() {
        assertTrue(ServiceTools.SERVICE_ACTION.mutates)
        (all - ServiceTools.SERVICE_ACTION).forEach { assertFalse(it.mutates, "${it.name} only reads or reveals") }
    }

    @Test
    fun `no parameter is spelled like a shell command, so the guard never tokenises a node name as one`() {
        all.flatMap { spec -> spec.params.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
        }
    }

    @Test
    fun `every tool that names a node takes it as path, and services is the one that does not`() {
        (all - ServiceTools.SERVICES).forEach { spec ->
            assertTrue(spec.params.any { it.name == "path" && it.required }, "${spec.name} must take a required path")
        }
        assertTrue(ServiceTools.SERVICES.params.none { it.required })
    }

    @Test
    fun `every integer parameter names its default and every type is one the schema accepts`() {
        all.flatMap { it.params }.forEach { param ->
            assertTrue(param.type in setOf("string", "integer", "boolean"), param.name)
            if (param.type == "integer") assertTrue("default" in param.description, "${param.name} names no default")
        }
    }

    @Test
    fun `every description says when to use the tool, not only what it does`() {
        all.forEach { assertTrue("Use it" in it.description, "${it.name} never says when") }
        assertTrue("service_actions" in ServiceTools.SERVICE_ACTION.description)
        assertTrue("services" in ServiceTools.SERVICE_OPEN.description)
    }

    @Test
    fun `a unique path is the name under its parent, and a twin gets an ordinal`() {
        assertEquals("Docker", ServiceTree.uniquePath("", "Docker", emptySet()))
        assertEquals("Docker/Containers", ServiceTree.uniquePath("Docker", "Containers", emptySet()))
        assertEquals("Docker/nginx (2)", ServiceTree.uniquePath("Docker", "nginx", setOf("Docker/nginx")))
        assertEquals("Docker/nginx (3)", ServiceTree.uniquePath("Docker", "nginx", setOf("Docker/nginx", "Docker/nginx (2)")))
    }

    @Test
    fun `a node without a presentable text still gets a path`() {
        assertEquals("Docker/${ServiceTree.UNNAMED}", ServiceTree.uniquePath("Docker", "", emptySet()))
        assertEquals(ServiceTree.UNNAMED, ServiceTree.uniquePath("", "   ", emptySet()))
    }
}
