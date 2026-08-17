package dev.lain.claudejb.forge

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The one place in the package that opens a socket: a GET, bounded in every direction, that cannot throw.
 *
 * `java.net.http.HttpClient` from the JDK, so the plugin gains no dependency for two GETs — and no dependency
 * is exactly the point, since the artifact ships to Marketplace users and every jar added here is a jar in
 * somebody's IDE.
 *
 * **Nothing escapes as an exception.** A DNS failure, a TLS failure, a timeout and a 500 are the same event to
 * a card that should simply not draw, so every one of them becomes a [ForgeAnswer.Silent] — a value with an
 * enum in it and no message, no URL and no header. That shape is also what keeps the access token out of an
 * exception message, which is the classic way a credential reaches a log nobody meant to write.
 *
 * **Threading: callers must be on a pooled thread.** This blocks. [ForgeService] refuses on the EDT rather
 * than freezing the IDE, the same way `GitHistoryService.recentCommits` refuses to run `git log` there.
 */
internal object ForgeHttp {

    /**
     * The ceiling on a response body, past which it is abandoned unread.
     *
     * Two pages of JSON are a few tens of kilobytes; anything approaching this is not the answer we asked
     * for. Without a bound, a host that streams forever — a captive portal, a misconfigured proxy, a
     * compromised endpoint — turns a card into unbounded heap on a background thread.
     */
    const val MAX_RESPONSE_BYTES = 512 * 1024

    /**
     * The `User-Agent` these requests carry: **the browser the host would expect from this operating
     * system** — Firefox on Linux, Chrome on Windows, Safari on macOS.
     *
     * Sending one at all is not optional. GitHub Enterprise Server **rejects** a request without a
     * `User-Agent`, and the JDK's default (`Java-http-client/…`) is accepted by api.github.com — so omitting
     * it works for everyone on the public service and fails only for self-hosted users, which is the hardest
     * failure to notice and the one this package would otherwise ship with.
     *
     * WHAT IT COSTS, recorded because the trade is not obvious and the alternative is one line away. GitHub's
     * own guidance is to name the application, and that name is what lets an administrator see in their
     * instance's log which client is calling — so a support question about this plugin cannot be answered
     * from the server side. A browser string on a client that only ever speaks JSON is also the shape some
     * WAFs and rate limiters treat as automation worth throttling. And the version below ages: it is a
     * literal, nothing derives it, and a browser build from years ago is itself unusual traffic.
     *
     * It carries no architecture beyond the platform's own conventional token and nothing about the machine.
     */
    val USER_AGENT: String
        get() = when {
            SystemInfo.isWindows -> WINDOWS_UA
            SystemInfo.isMac -> MAC_UA
            else -> LINUX_UA
        }

    private const val LINUX_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:141.0) Gecko/20100101 Firefox/141.0"

    private const val WINDOWS_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Safari/537.36"

    private const val MAC_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/18.5 Safari/605.1.15"

    private const val CHUNK_BYTES = 8 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val REQUEST_TIMEOUT_SECONDS = 15L

    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404

    private val LOG = logger<ForgeHttp>()

    /**
     * One client for the plugin's lifetime.
     *
     * `Redirect.NEVER`, and this is a security decision rather than a default left alone: the JDK's client
     * replays the original request's headers on a redirect, and this request's headers carry the access
     * token. A repository that has been renamed answers `301` with a `Location`, and following it would hand
     * the token to whatever host that header named. A refused redirect is a card that does not draw; a
     * followed one is a credential leak with no symptom.
     *
     * The proxy selector is the JVM default because the IDE publishes its own proxy configuration through the
     * system properties that back it — without this, every request from behind a corporate proxy is a
     * connect timeout.
     */
    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
        // Null when something has explicitly cleared the JVM's selector; `proxy(null)` would throw, and a
        // client that cannot be built is a package that throws on first use instead of drawing no card.
        ProxySelector.getDefault()?.let(builder::proxy)
        builder.build()
    }

    /**
     * Sends [request] and returns its body, or the reason there is none. Blocking; never throws.
     *
     * The scheme is re-checked here even though every URL in this package is built with a literal `https://`
     * prefix. This is the only function that can put a token on a wire, so it is the right place for the
     * assertion — a plaintext request carrying an access token is not a bug worth discovering downstream.
     */
    fun fetch(request: ForgeRequest): ForgeAnswer<String> {
        if (!request.uri.scheme.equals("https", ignoreCase = true)) {
            return ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST)
        }
        val built = HttpRequest.newBuilder(request.uri)
            .GET()
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        request.headers.forEach { (name, value) -> built.header(name, value) }

        return try {
            val response = client.send(built.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body -> bodyOrSilence(response.statusCode(), body) }
        } catch (e: InterruptedException) {
            // Restore the flag the throw cleared: swallowing an interrupt is how a shutdown hangs.
            Thread.currentThread().interrupt()
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        } catch (e: IOException) {
            // The exception is safe to log — it names a host and a cause, never a request header. Only the
            // ANSWER is redacted, because that is the value that travels back through the UI.
            LOG.warn("Forge request to ${request.uri.host} failed; the card stays empty", e)
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        }
    }

    /**
     * The silence a status code means, or null when the code is a success and there is a body to read.
     *
     * 403 and 404 are one answer on purpose. Both providers return "not found" for a repository the token
     * cannot see, precisely so that a token cannot be used to enumerate private repositories, and inventing a
     * distinction here would only put a wrong diagnosis in the log.
     */
    fun silenceFor(status: Int): ForgeSilence? = when (status) {
        in HTTP_OK_MIN..HTTP_OK_MAX -> null
        HTTP_UNAUTHORIZED -> ForgeSilence.UNAUTHORIZED
        HTTP_FORBIDDEN, HTTP_NOT_FOUND -> ForgeSilence.NOT_VISIBLE
        else -> ForgeSilence.UNREACHABLE
    }

    private fun bodyOrSilence(status: Int, body: InputStream): ForgeAnswer<String> {
        val silence = silenceFor(status)
        return if (silence != null) ForgeAnswer.Silent(silence) else readBounded(body)
    }

    /**
     * Reads at most [MAX_RESPONSE_BYTES], then gives up.
     *
     * Counted as it goes rather than trusted from `Content-Length`: a chunked response declares no length,
     * and a hostile one can declare whatever it likes.
     */
    private fun readBounded(body: InputStream): ForgeAnswer<String> {
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = body.read(chunk)
            if (read < 0) break
            if (collected.size() + read > MAX_RESPONSE_BYTES) return ForgeAnswer.Silent(ForgeSilence.OVERSIZED)
            collected.write(chunk, 0, read)
        }
        return ForgeAnswer.Known(collected.toString(StandardCharsets.UTF_8))
    }
}
