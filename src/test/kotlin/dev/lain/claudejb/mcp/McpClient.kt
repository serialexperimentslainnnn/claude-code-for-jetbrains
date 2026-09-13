package dev.lain.claudejb.mcp

import dev.lain.claudejb.model.mcp.toon.Toon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

class McpClient private constructor(private val channel: SocketChannel, private val token: String) : AutoCloseable {

    private val input: InputStream = Channels.newInputStream(channel)
    private val output: OutputStream = Channels.newOutputStream(channel)
    private var nextId = 0

    fun initialize(): JsonObject = request("initialize", buildJsonObject { put("protocolVersion", PROTOCOL_VERSION) })

    fun domains(): String = text(call("domains", buildJsonObject {}))

    fun tools(domain: String): String = text(call("tools", buildJsonObject { put("domain", domain) }))

    fun run(tool: String, args: JsonObject): String = text(
        call(
            "run",
            buildJsonObject {
                put("tool", tool)
                put("args", args)
            },
        ),
    )

    fun call(name: String, arguments: JsonObject): JsonObject = request(
        "tools/call",
        buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        },
    )

    fun request(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        val meta = buildJsonObject { put(StdioBridge.TOKEN_KEY, token) }
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", JsonObject(params + ("_meta" to meta)))
        }
        Frames.write(output, Toon.encode(message))
        val reply = Toon.decode(Frames.read(input) ?: error("the server closed the connection")).jsonObject
        check(reply["id"]?.jsonPrimitive?.content == id.toString()) { "reply out of order: $reply" }
        return reply
    }

    override fun close() = channel.close()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        fun connect(socket: Path, token: String = tokenBeside(socket)): McpClient {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.connect(UnixDomainSocketAddress.of(socket))
            return McpClient(channel, token)
        }

        fun tokenBeside(socket: Path): String = Files.readString(socket.resolveSibling(StdioBridge.TOKEN_FILE)).trim()

        fun text(reply: JsonObject): String {
            reply["error"]?.let { error(it.jsonObject["message"]?.jsonPrimitive?.content ?: it.toString()) }
            val content = reply.getValue("result").jsonObject.getValue("content").jsonArray
            return content.single().jsonObject.getValue("text").jsonPrimitive.content
        }
    }
}
