package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.mcp.Frames
import dev.lain.claudejb.mcp.StdioBridge
import dev.lain.claudejb.model.mcp.McpServer
import dev.lain.claudejb.model.mcp.MetaTools
import dev.lain.claudejb.model.mcp.OutputBudget
import dev.lain.claudejb.model.mcp.TokenRing
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolCatalog
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.mcp.toon.Toon
import dev.lain.claudejb.model.session.launch.IdeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ServerEndpointTest {

    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob())
    private val tokens = TokenRing()
    private val gateOpened = CompletableDeferred<Unit>()
    private var admitted = true
    private lateinit var endpoint: ServerEndpoint

    @BeforeEach
    fun listen() {
        val slow = Tool(ToolSpec("slow", "waits for the gate")) {
            gateOpened.await()
            ToolResult("slow: done")
        }
        val fast = Tool(ToolSpec("fast", "answers at once")) { ToolResult("fast: done") }
        val catalog = ToolCatalog(listOf(ToolDomain("d", "", listOf(slow, fast))))
        val mcp = McpServer("code", "test", MetaTools(catalog, { _, _ -> null }, OutputBudget()))
        endpoint = ServerEndpoint(IdeServer.CODE, dir.resolve("code.sock"), mcp, tokens, scope, { admitted })
        endpoint.start()
    }

    @AfterEach
    fun stop() {
        endpoint.close()
        scope.cancel()
    }

    @Test
    fun `the socket file is reachable by its owner only`() {
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("code.sock"))))
    }

    @Test
    fun `a request without the token is rejected without a reason`() = connect { input, output ->
        send(output, """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        val reply = receive(input)
        assertEquals(ServerEndpoint.REJECTED, reply["error"]!!.jsonObject["message"]?.jsonPrimitive?.content)
        send(output, request(2, "tools/list", "{}", token = "wrong"))
        assertEquals(ServerEndpoint.REJECTED, receive(input)["error"]!!.jsonObject["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a request with the token is served`() = connect { input, output ->
        send(output, request(1, "tools/list", "{}"))
        val reply = receive(input)
        assertEquals(3, reply["result"]!!.jsonObject["tools"]!!.jsonArray.size)
    }

    @Test
    fun `replies come back as the tools finish, not in request order`() = connect { input, output ->
        send(output, request(1, "tools/call", """{"name":"run","arguments":{"tool":"slow"}}"""))
        send(output, request(2, "tools/call", """{"name":"run","arguments":{"tool":"fast"}}"""))
        val first = receive(input)
        assertEquals("2", first["id"]?.jsonPrimitive?.content)
        gateOpened.complete(Unit)
        val second = receive(input)
        assertEquals("1", second["id"]?.jsonPrimitive?.content)
        assertEquals("slow: done", text(second))
    }

    @Test
    fun `a cancellation notification drops the pending request`() = connect { input, output ->
        send(output, request(7, "tools/call", """{"name":"run","arguments":{"tool":"slow"}}"""))
        send(output, notification("notifications/cancelled", """{"requestId":7}"""))
        send(output, request(8, "ping", "{}"))
        assertEquals("8", receive(input)["id"]?.jsonPrimitive?.content)
        gateOpened.complete(Unit)
        send(output, request(9, "ping", "{}"))
        assertEquals("9", receive(input)["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `beyond the queue depth a request is refused at once`() = connect { input, output ->
        repeat(ServerEndpoint.QUEUE_DEPTH) { send(output, request(it + 1, "tools/call", """{"name":"run","arguments":{"tool":"slow"}}""")) }
        send(output, request(99, "ping", "{}"))
        val refused = receive(input)
        assertEquals("99", refused["id"]?.jsonPrimitive?.content)
        assertTrue("in flight" in refused["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        gateOpened.complete(Unit)
        repeat(ServerEndpoint.QUEUE_DEPTH) { receive(input) }
    }

    @Test
    fun `a connection the project does not admit is closed`() {
        admitted = false
        connect { input, _ -> assertNull(Frames.read(input)) }
    }

    private fun connect(body: (InputStream, OutputStream) -> Unit) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(dir.resolve("code.sock")))
            body(Channels.newInputStream(channel), Channels.newOutputStream(channel))
        }
    }

    private fun request(id: Int, method: String, params: String, token: String = tokens.token): String {
        val meta = """"_meta":{"${StdioBridge.TOKEN_KEY}":"$token"}"""
        val body = if (params == "{}") "{$meta}" else params.substring(0, params.lastIndex) + ",$meta}"
        return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$body}"""
    }

    private fun notification(method: String, params: String): String =
        """{"jsonrpc":"2.0","method":"$method","params":${params.substring(0, params.lastIndex)},"_meta":{"${StdioBridge.TOKEN_KEY}":"${tokens.token}"}}}"""

    private fun send(output: OutputStream, json: String) = Frames.write(output, Toon.encode(Json.parseToJsonElement(json)))

    private fun receive(input: InputStream): JsonObject = Toon.decode(Frames.read(input)!!).jsonObject

    private fun text(reply: JsonObject): String =
        reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
}
