package dev.lain.claudejb.headless

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.mcp.IdeMcpService
import dev.lain.claudejb.controller.mcp.ServerEndpoint
import dev.lain.claudejb.mcp.McpClient
import dev.lain.claudejb.model.session.launch.IdeServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class McpClientHeadlessTest : BasePlatformTestCase() {

    private lateinit var greeting: Path

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        greeting = Files.createDirectories(Path.of(project.basePath!!)).resolve("greeting.txt")
        Files.writeString(greeting, "hello from the IDE\n")
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(greeting))
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(greeting)
        } finally {
            super.tearDown()
        }
    }

    fun `test any client that speaks the wire drives the IDE - initialize, domains, read_file`() {
        McpClient.connect(codeSocket()).use { client ->
            val info = client.initialize().getValue("result").jsonObject.getValue("serverInfo").jsonObject
            assertEquals("code", info["name"]?.jsonPrimitive?.content)
            val domains = client.domains()
            assertTrue(domains, "read" in domains)
            val text = client.run("read_file", buildJsonObject { put("path", greeting.toString()) })
            assertTrue(text, "hello from the IDE" in text)
        }
    }

    fun `test a client without the token of this project is refused whatever it asks`() {
        McpClient.connect(codeSocket(), token = "not-the-token").use { client ->
            val reply = client.request("tools/list", buildJsonObject {})
            assertEquals(ServerEndpoint.REJECTED, reply.getValue("error").jsonObject["message"]?.jsonPrimitive?.content)
        }
    }

    fun `test the servers start without a chat and only once`() {
        val service = IdeMcpService.getInstance(project)
        service.serveWithoutChat()
        service.serveWithoutChat()
        assertTrue(service.sockets().containsKey(IdeServer.CODE))
    }

    private fun codeSocket(): Path {
        val service = IdeMcpService.getInstance(project)
        service.expectConnections(1)
        return Path.of(service.sockets().getValue(IdeServer.CODE))
    }
}
