package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.mcp.Frames
import dev.lain.claudejb.mcp.StdioBridge
import dev.lain.claudejb.model.mcp.JsonRpc
import dev.lain.claudejb.model.mcp.McpServer
import dev.lain.claudejb.model.mcp.TokenRing
import dev.lain.claudejb.model.mcp.toon.Toon
import dev.lain.claudejb.model.mcp.toon.ToonException
import dev.lain.claudejb.model.session.launch.IdeServer
import dev.lain.claudejb.util.thisLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ServerEndpoint(
    val server: IdeServer,
    val socket: Path,
    private val mcp: McpServer,
    private val tokens: TokenRing,
    private val scope: CoroutineScope,
    private val admit: suspend () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = thisLogger()
    private val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        .also { ownerOnly(socket) }
    private val inFlight = AtomicInteger()

    fun start(): Job = scope.launch(io) {
        while (isActive) {
            val client = runCatching { channel.accept() }.getOrNull() ?: break
            launch { if (admit()) Connection(client).serve() else runCatching { client.close() } }
        }
    }

    fun close() {
        runCatching { channel.close() }
    }

    private inner class Connection(private val client: SocketChannel) {

        private val output: OutputStream = Channels.newOutputStream(client)
        private val writing = Mutex()
        private val jobs = ConcurrentHashMap<String, Job>()

        suspend fun serve() = withContext(io) {
            val input: InputStream = Channels.newInputStream(client)
            try {
                while (true) {
                    val frame = runCatching { Frames.read(input) }.getOrNull() ?: break
                    receive(frame)
                }
            } finally {
                jobs.values.forEach { it.cancel() }
                runCatching { client.close() }
            }
        }

        private suspend fun receive(frame: String) {
            val message = try {
                Toon.decode(frame)
            } catch (ignored: ToonException) {
                log.debug { "${server.key}: unreadable frame" }
                send(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error"))
                return
            }
            when (val parsed = JsonRpc.parse(message)) {
                is JsonRpc.Request -> request(parsed, message)
                is JsonRpc.Notification -> notification(parsed, message)
                else -> mcp.handle(message)?.let { send(it) }
            }
        }

        private suspend fun request(request: JsonRpc.Request, message: JsonElement) {
            if (!authorized(request.params)) {
                log.warn("${server.key}: rejected a request to ${request.method} without a valid token")
                send(JsonRpc.error(request.id, JsonRpc.INVALID_REQUEST, REJECTED))
                return
            }
            if (inFlight.get() >= QUEUE_DEPTH) {
                val busy = "$QUEUE_DEPTH requests are already in flight on ${server.key}; retry when one answers"
                send(JsonRpc.error(request.id, JsonRpc.INTERNAL_ERROR, busy))
                return
            }
            val key = request.id.toString()
            inFlight.incrementAndGet()
            jobs[key] = scope.launch {
                try {
                    mcp.handle(message)?.let { send(it) }
                } finally {
                    inFlight.decrementAndGet()
                    jobs.remove(key)
                }
            }
        }

        private suspend fun notification(notification: JsonRpc.Notification, message: JsonElement) {
            if (!authorized(notification.params)) return
            if (notification.method == CANCELLED) {
                notification.params["requestId"]?.let { jobs[it.toString()]?.cancel() }
                return
            }
            mcp.handle(message)
        }

        private fun authorized(params: JsonObject): Boolean =
            tokens.accepts((JsonRpc.meta(params)[StdioBridge.TOKEN_KEY] as? JsonPrimitive)?.content)

        private suspend fun send(reply: JsonObject) = writing.withLock {
            withContext(io) { runCatching { Frames.write(output, Toon.encode(reply)) } }
        }
    }

    companion object {
        const val QUEUE_DEPTH = 16
        const val REJECTED = "request rejected"
        private const val CANCELLED = "notifications/cancelled"
        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

        private fun ownerOnly(socket: Path) {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(socket, OWNER_ONLY)
        }
    }
}
