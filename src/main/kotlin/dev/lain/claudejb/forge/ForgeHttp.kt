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

internal object ForgeHttp {

    const val MAX_RESPONSE_BYTES = 512 * 1024

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

    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
        ProxySelector.getDefault()?.let(builder::proxy)
        builder.build()
    }

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
            Thread.currentThread().interrupt()
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        } catch (e: IOException) {
            LOG.warn("Forge request to ${request.uri.host} failed; the card stays empty", e)
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        }
    }

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
