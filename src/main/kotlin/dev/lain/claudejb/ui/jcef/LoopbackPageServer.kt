package dev.lain.claudejb.ui.jcef

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LoopbackPageServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    val port: Int,
    val url: String,
) {

    private val stopped = AtomicBoolean(false)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdownNow()
    }

    override fun toString(): String = "LoopbackPageServer(port=$port)"

    companion object {
        private const val PATH = "/index.html"
        private const val PARAM = "k"

        private const val ROOT = "/"

        private const val TOKEN_BYTES = 32

        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405

        private const val NO_BODY = -1L

        fun start(html: String, headers: Map<String, String>): LoopbackPageServer? {
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            val snapshot = LinkedHashMap(headers)
            val token = newToken()
            val loopback = InetAddress.getLoopbackAddress()
            val server = try {
                HttpServer.create(InetSocketAddress(loopback, 0), 0)
            } catch (_: IOException) {
                return null
            }
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "claude-loopback-page").apply { isDaemon = true }
            }
            val spent = AtomicBoolean(false)
            server.createContext(ROOT) { exchange -> serve(exchange, bytes, snapshot, token, spent) }
            server.executor = executor
            server.start()
            val host = loopback.hostAddress.let { if (it.contains(':')) "[$it]" else it }
            val port = server.address.port
            return LoopbackPageServer(server, executor, port, "http://$host:$port$PATH?$PARAM=$token")
        }

        private fun serve(
            exchange: HttpExchange,
            bytes: ByteArray,
            headers: Map<String, String>,
            token: String,
            spent: AtomicBoolean,
        ) {
            exchange.use {
                if (exchange.requestURI.path != PATH) {
                    exchange.sendResponseHeaders(HTTP_NOT_FOUND, NO_BODY)
                    return
                }
                if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                    exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, NO_BODY)
                    return
                }
                val presented = tokenIn(exchange.requestURI.rawQuery)
                val authorised = MessageDigest.isEqual(
                    presented.toByteArray(StandardCharsets.UTF_8),
                    token.toByteArray(StandardCharsets.UTF_8),
                ) && spent.compareAndSet(false, true)
                if (!authorised) {
                    exchange.sendResponseHeaders(HTTP_FORBIDDEN, NO_BODY)
                    return
                }
                headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }

        private fun tokenIn(rawQuery: String?): String =
            rawQuery?.split('&')?.firstOrNull { it.startsWith("$PARAM=") }?.substring(PARAM.length + 1) ?: ""

        private fun newToken(): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })
    }
}
