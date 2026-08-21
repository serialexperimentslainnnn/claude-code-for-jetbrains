package dev.lain.claudejb.vuln

import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.forge.ForgeHttp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal sealed interface OsvAnswer {

    data class Body(val json: String) : OsvAnswer

    data class Silent(val reason: ScanSilence) : OsvAnswer
}

internal object OsvHttp {

    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private const val CHUNK_BYTES = 8 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429

    private val LOG = logger<OsvHttp>()

    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
        ProxySelector.getDefault()?.let(builder::proxy)
        builder.build()
    }

    fun post(uri: URI, body: String): OsvAnswer = send(
        HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .header("Content-Type", "application/json"),
        uri,
    )

    private fun send(builder: HttpRequest.Builder, uri: URI): OsvAnswer {
        if (!uri.scheme.equals("https", ignoreCase = true)) return OsvAnswer.Silent(ScanSilence.REFUSED)
        val request = builder
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("User-Agent", ForgeHttp.USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body -> bodyOrSilence(response.statusCode(), body) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            OsvAnswer.Silent(ScanSilence.UNREACHABLE)
        } catch (e: IOException) {
            LOG.warn("The vulnerability database could not be reached; no findings are shown", e)
            OsvAnswer.Silent(ScanSilence.UNREACHABLE)
        }
    }

    private fun bodyOrSilence(status: Int, body: InputStream): OsvAnswer = when {
        status == HTTP_TOO_MANY_REQUESTS -> OsvAnswer.Silent(ScanSilence.REFUSED)
        status !in HTTP_OK_MIN..HTTP_OK_MAX -> OsvAnswer.Silent(ScanSilence.REFUSED)
        else -> readBounded(body)
    }

    private fun readBounded(body: InputStream): OsvAnswer {
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = body.read(chunk)
            if (read < 0) break
            if (collected.size() + read > MAX_RESPONSE_BYTES) return OsvAnswer.Silent(ScanSilence.OVERSIZED)
            collected.write(chunk, 0, read)
        }
        return OsvAnswer.Body(collected.toString(StandardCharsets.UTF_8))
    }
}
