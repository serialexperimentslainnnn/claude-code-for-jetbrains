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

/**
 * Serves the one inlined chat document over real loopback HTTP, for the case where a CEF scheme handler cannot
 * reach the browser: in Remote Development the browser lives in the thin client process while the scheme is
 * registered in the backend, so the client resolves nothing. A real socket the client reaches through the SSH
 * port forward does, and — unlike `loadHTML` — it still carries the **response headers**, which is the entire
 * reason this exists: the CSP, `Cross-Origin-*` and `Permissions-Policy` values only take effect as headers.
 *
 * The document and its headers are supplied by the caller and emitted verbatim; this class decides nothing about
 * either. What it does decide is the four properties that make an open socket acceptable:
 *
 * - **Loopback only.** Bound to [InetAddress.getLoopbackAddress], never a wildcard: nothing off-host can connect,
 *   whatever the machine's firewall says. The port forward is the only route in from outside.
 * - **An OS-assigned ephemeral port.** A fixed port collides with the other services an IDE runs and is guessable
 *   by anything watching for it; the number is read back after the bind and is the one the user forwards.
 * - **A one-shot secret.** Loopback is not private on a shared host — every local account reaches 127.0.0.1 — so
 *   knowing the port must not be enough. A 256-bit [SecureRandom] token gates the document, and it is *consumed*:
 *   the first request that receives the page invalidates it, so a token observed in a process list or a forwarded
 *   URL buys nothing afterwards.
 * - **A uniform refusal.** No refusal carries a body of any kind. Wrong token, spent token and a request
 *   arriving after the page was served all leave through the same empty `403`; any other path or method leaves
 *   through an equally empty `404`/`405`. From outside, probing learns neither what exists nor why it failed.
 *
 * The token is compared with [MessageDigest.isEqual] on the UTF-8 bytes, which is constant-time in both content
 * and length: `==` on the strings leaks the length of the shared prefix through timing. It appears in [url] and
 * nowhere else — not in [toString], not in a log, not in an exception message, since the caller logs [port].
 */
internal class LoopbackPageServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    /**
     * The bound ephemeral port — the number the user must forward. Secret-free, safe to show and to log. Read
     * off the socket once, at the bind, rather than on each access: [HttpServer.getAddress] is null once the
     * server is stopped, and the port is exactly what a caller still wants to name after that.
     */
    val port: Int,
    /** Absolute URL the browser must open, secret included: `http://127.0.0.1:<port>/index.html?k=<token>`. */
    val url: String,
) {

    private val stopped = AtomicBoolean(false)

    /** Idempotent shutdown; releases the port immediately. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdownNow()
    }

    /** Deliberately secret-free: the port identifies the server, the token must never reach a log. */
    override fun toString(): String = "LoopbackPageServer(port=$port)"

    companion object {
        private const val PATH = "/index.html"
        private const val PARAM = "k"

        /**
         * The context covers the whole namespace, so every request reaches [serve] and leaves under its refusal
         * policy. A context mounted on [PATH] alone would leave every other path to the server's own built-in
         * rejection, which answers with a body naming the reason — the one thing a refusal here must never say.
         */
        private const val ROOT = "/"

        /** 32 bytes = 256 bits of entropy, so the token cannot be searched even by something on the same host. */
        private const val TOKEN_BYTES = 32

        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405

        /** `sendResponseHeaders` length meaning "no body at all". */
        private const val NO_BODY = -1L

        /**
         * Binds 127.0.0.1 on an OS-assigned ephemeral port and serves exactly [html] with exactly [headers].
         * Returns null if it cannot bind (the caller then explains the failure instead of showing a dead page).
         */
        fun start(html: String, headers: Map<String, String>): LoopbackPageServer? {
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            val snapshot = LinkedHashMap(headers)
            val token = newToken()
            val loopback = InetAddress.getLoopbackAddress()
            // `create` performs the bind and nothing else; the single request is then served on the executor
            // below, so no caller thread — least of all the EDT — ever waits on network I/O here.
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

        /**
         * The whole request policy. Only `GET` on exactly [PATH] with the live token yields the document; every
         * other outcome is a bare status with no body and no detail about why, and nothing from the request —
         * path, query or body — is ever echoed back.
         */
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
                // The comparison runs before the claim, so a wrong token never spends the real one; the claim
                // then makes success unrepeatable. Both failures leave through the same branch.
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

        /**
         * The `k` parameter, raw. The token's base64url alphabet is URL-safe by construction, so there is nothing
         * to percent-decode — and not decoding is what keeps an encoded variant of the token from matching.
         */
        private fun tokenIn(rawQuery: String?): String =
            rawQuery?.split('&')?.firstOrNull { it.startsWith("$PARAM=") }?.substring(PARAM.length + 1) ?: ""

        private fun newToken(): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })
    }
}
