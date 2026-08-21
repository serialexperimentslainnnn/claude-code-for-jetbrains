package dev.lain.claudejb.forge

import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal object ForgeHttp {

    const val MAX_RESPONSE_BYTES = 512 * 1024

    const val PLUGIN_VERSION = "6.0.0"

    private const val PROJECT_URL = "https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains"

    const val USER_AGENT = "ClaudeCodeNative/$PLUGIN_VERSION (+$PROJECT_URL)"

    private const val CHUNK_BYTES = 8 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val REQUEST_TIMEOUT_SECONDS = 15L

    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429

    private const val GITHUB_REMAINING = "x-ratelimit-remaining"
    private const val GITLAB_REMAINING = "ratelimit-remaining"

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
        return try {
            val response = client.send(built(request), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body -> bodyOrSilence(response.statusCode(), response.headers(), body) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        } catch (e: IOException) {
            LOG.warn("Forge request to ${request.uri.host} failed; the card stays empty", e)
            ForgeAnswer.Silent(ForgeSilence.UNREACHABLE)
        }
    }

    fun act(request: ForgeRequest): ForgeOutcome {
        if (!request.uri.scheme.equals("https", ignoreCase = true)) {
            return ForgeOutcome.Refused(ForgeRefusal.UNREACHABLE)
        }
        return try {
            val response = client.send(built(request), HttpResponse.BodyHandlers.discarding())
            refusalFor(response.statusCode(), response.headers())
                ?.let { ForgeOutcome.Refused(it) }
                ?: ForgeOutcome.Done
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ForgeOutcome.Refused(ForgeRefusal.UNREACHABLE)
        } catch (e: IOException) {
            LOG.warn("A forge action on ${request.uri.host} could not be sent", e)
            ForgeOutcome.Refused(ForgeRefusal.UNREACHABLE)
        }
    }

    private fun built(request: ForgeRequest): HttpRequest {
        val payload = request.body
            ?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
            ?: HttpRequest.BodyPublishers.noBody()
        val built = HttpRequest.newBuilder(request.uri)
            .method(request.method, payload)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        request.headers.forEach { (name, value) -> built.header(name, value) }
        return built.build()
    }

    fun silenceFor(status: Int, headers: HttpHeaders): ForgeSilence? = when {
        status in HTTP_OK_MIN..HTTP_OK_MAX -> null
        status == HTTP_UNAUTHORIZED -> ForgeSilence.UNAUTHORIZED
        status == HTTP_TOO_MANY_REQUESTS -> ForgeSilence.RATE_LIMITED
        status == HTTP_FORBIDDEN && quotaExhausted(headers) -> ForgeSilence.RATE_LIMITED
        status == HTTP_FORBIDDEN || status == HTTP_NOT_FOUND -> ForgeSilence.NOT_VISIBLE
        else -> ForgeSilence.UNREACHABLE
    }

    fun refusalFor(status: Int, headers: HttpHeaders): ForgeRefusal? = when {
        status in HTTP_OK_MIN..HTTP_OK_MAX -> null
        status == HTTP_UNAUTHORIZED -> ForgeRefusal.TOKEN_TOO_NARROW
        status == HTTP_TOO_MANY_REQUESTS -> ForgeRefusal.RATE_LIMITED
        status == HTTP_FORBIDDEN && quotaExhausted(headers) -> ForgeRefusal.RATE_LIMITED
        status == HTTP_FORBIDDEN -> ForgeRefusal.NO_PERMISSION
        status == HTTP_NOT_FOUND -> ForgeRefusal.NO_PERMISSION
        status == HTTP_METHOD_NOT_ALLOWED -> ForgeRefusal.NOT_MERGEABLE
        status == HTTP_NOT_ACCEPTABLE -> ForgeRefusal.CONFLICTED
        status == HTTP_CONFLICT -> ForgeRefusal.STALE
        status == HTTP_UNPROCESSABLE -> ForgeRefusal.SELF_APPROVAL
        else -> ForgeRefusal.REFUSED
    }

    private const val HTTP_METHOD_NOT_ALLOWED = 405
    private const val HTTP_NOT_ACCEPTABLE = 406
    private const val HTTP_CONFLICT = 409
    private const val HTTP_UNPROCESSABLE = 422

    private fun quotaExhausted(headers: HttpHeaders): Boolean =
        exhaustedBy(headers, GITHUB_REMAINING) ?: exhaustedBy(headers, GITLAB_REMAINING) ?: false

    private fun exhaustedBy(headers: HttpHeaders, name: String): Boolean? =
        headers.firstValue(name).orElse(null)?.trim()?.toIntOrNull()?.let { it <= 0 }

    private fun bodyOrSilence(status: Int, headers: HttpHeaders, body: InputStream): ForgeAnswer<String> {
        val silence = silenceFor(status, headers)
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
