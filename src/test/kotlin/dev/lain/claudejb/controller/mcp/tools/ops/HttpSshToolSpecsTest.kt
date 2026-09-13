package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.controller.mcp.tools.run.Jobs
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpSshToolSpecsTest {

    private val http = listOf(HttpTools.HTTP_FILES, HttpTools.HTTP_RUN, HttpTools.HTTP_OPEN)
    private val ssh = listOf(SshTools.SSH_HOSTS)
    private val all = http + ssh

    @Test
    fun `the tool names are pinned per domain`() {
        assertEquals(listOf("http_files", "http_run", "http_open"), http.map { it.name })
        assertEquals(listOf("ssh_hosts"), ssh.map { it.name })
        assertEquals(all.size, all.map { it.name }.toSet().size, "two ops tools share a name")
    }

    @Test
    fun `only http_run mutates, because it starts a run configuration`() {
        assertTrue(HttpTools.HTTP_RUN.mutates)
        (all - HttpTools.HTTP_RUN).forEach { assertFalse(it.mutates, "${it.name} is read-only") }
    }

    @Test
    fun `http_run can be waited on and re-attached like every long tool`() {
        for (name in listOf("wait", "job", "tail")) {
            assertTrue(HttpTools.HTTP_RUN.params.any { it.name == name && !it.required }) { "http_run lacks optional $name" }
        }
        assertFalse(HttpTools.HTTP_RUN.params.first { it.name == "path" }.required, "path is optional so job alone re-attaches")
        assertTrue(HttpTools.HTTP_RUN.timeoutMillis > Jobs.MAX_WAIT_SECONDS * MILLIS)
    }

    @Test
    fun `no parameter is spelled like a shell command, so the guard never tokenises a path as one`() {
        all.flatMap { spec -> spec.params.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
        }
    }

    @Test
    fun `a request file is always called path, which is what the guard walks`() {
        val locations = all.flatMap { spec -> spec.params.filter { LOCATION.containsMatchIn(it.description) }.map { spec.name to it.name } }
        assertEquals(listOf("http_run" to "path", "http_run" to "paths", "http_open" to "path"), locations)
        all.flatMap { it.params }.map { it.name }.forEach { assertFalse(it in ALIASES, "$it is a location under another name") }
    }

    @Test
    fun `ssh_hosts promises never to return a secret and never to connect`() {
        val description = SshTools.SSH_HOSTS.description
        assertTrue("never returned" in description)
        assertTrue("nothing is connected to" in description)
        assertEquals(listOf("max"), SshTools.SSH_HOSTS.params.map { it.name })
    }

    @Test
    fun `every integer parameter names its default and every type is one the schema accepts`() {
        all.flatMap { spec -> spec.params.filter { it.type == "integer" }.map { spec.name to it } }.forEach { (tool, param) ->
            assertTrue("default" in param.description, "$tool.${param.name} names no default")
        }
        assertTrue(all.flatMap { it.params }.all { it.type in setOf("string", "integer", "boolean", "array") })
        all.forEach { assertEquals(ToolSpec.DEFAULT_TIMEOUT_MILLIS, it.timeoutMillis, it.name) }
    }

    private companion object {
        const val MILLIS = 1000L
        val LOCATION = Regex("""\b(file|files|director)""", RegexOption.IGNORE_CASE)
        val ALIASES = setOf("file", "files", "dir", "directory", "location", "target", "filename")
    }
}
