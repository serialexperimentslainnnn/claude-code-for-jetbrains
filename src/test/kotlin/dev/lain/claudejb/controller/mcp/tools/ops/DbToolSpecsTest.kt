package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DbToolSpecsTest {

    private val all = listOf(DbTools.DB_CONNECTIONS, DbTools.DB_SCHEMA, DbTools.DB_QUERY)

    @Test
    fun `the tool names are pinned and fit the domain ceiling`() {
        assertEquals(listOf("db_connections", "db_schema", "db_query"), all.map { it.name })
        assertTrue(all.size <= ToolDomain.MAX_TOOLS)
    }

    @Test
    fun `only the query mutates, and it says so`() {
        assertTrue(DbTools.DB_QUERY.mutates)
        (all - DbTools.DB_QUERY).forEach { assertFalse(it.mutates, "${it.name} is read-only") }
    }

    @Test
    fun `the SQL travels under code, which the guard scans as a command, alone or as a list`() {
        assertEquals("code", Batch.STATEMENTS.identity)
        assertEquals("statements", DbTools.DB_QUERY.params.single { it.type == "array" }.name)
        assertNotNull(ToolInputScanner.commandText(buildJsonObject { put("code", "select 1") }))
        assertNull(ToolInputScanner.commandText(buildJsonObject { put("sql", "select 1") }), "sql is not a key the guard scans")
        assertEquals(listOf("connection"), DbTools.DB_QUERY.params.filter { it.required }.map { it.name })
    }

    @Test
    fun `no other parameter reads as a command, so a connection name is never judged as one`() {
        all.flatMap { spec -> spec.params.filter { it.name != "code" }.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
        }
    }

    @Test
    fun `every tool that enumerates takes max and names its default`() {
        all.forEach { spec ->
            val max = spec.params.single { it.name == "max" }
            assertEquals("integer", max.type, spec.name)
            assertFalse(max.required, spec.name)
            assertTrue("default" in max.description, "${spec.name}.max names no default")
        }
    }

    @Test
    fun `the data source is always called connection and the schema filter table`() {
        assertEquals(listOf("connection", "table", "max"), DbTools.DB_SCHEMA.params.map { it.name })
        assertEquals(listOf("connection", "code", "statements", "max"), DbTools.DB_QUERY.params.map { it.name })
        assertEquals(listOf("max"), DbTools.DB_CONNECTIONS.params.map { it.name })
    }

    @Test
    fun `descriptions are bounded and every parameter is typed and described`() {
        all.forEach { spec ->
            assertTrue(spec.description.length in DESCRIPTION_RANGE) { "${spec.name}: ${spec.description.length} chars" }
            spec.params.forEach { param ->
                assertTrue(param.type in setOf("string", "integer", "boolean", "array")) { "${spec.name}.${param.name}: ${param.type}" }
                assertTrue(param.description.isNotBlank()) { "${spec.name}.${param.name} has no description" }
            }
        }
    }

    @Test
    fun `the query's statement timeout is the tool's own timeout, so the driver gives up before the server does`() {
        assertEquals((ToolSpec.DEFAULT_TIMEOUT_MILLIS / MILLIS).toInt(), DbTools.QUERY_TIMEOUT_SECONDS)
        assertEquals(ToolSpec.DEFAULT_TIMEOUT_MILLIS, DbTools.DB_QUERY.timeoutMillis)
    }

    @Test
    fun `result labels are unique and never blank, so every row keeps every cell`() {
        assertEquals(listOf("id", "name", "name_2", "col4", "col4_2"), DbTools.labels(listOf("id", "name", "name", "", "col4")))
        assertEquals(emptyList<String>(), DbTools.labels(emptyList()))
    }

    private companion object {
        const val MILLIS = 1000L
        val DESCRIPTION_RANGE = 40..300
    }
}
