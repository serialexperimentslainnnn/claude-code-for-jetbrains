package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM coverage of the loopback fallback that serves the chat document in Remote Development. Every test here
 * asserts a security property of that open socket rather than an implementation detail: that it is reachable only
 * from this host, that its port is not predictable, that the document is served with its headers intact exactly
 * once, that every refusal looks the same, and that stopping it really closes the port.
 */
class LoopbackPageServerTest {

    private val html = "<!doctype html><html><body>página ✓</body></html>"

    private val headers = linkedMapOf(
        "Content-Type" to "text/html; charset=utf-8",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "require-corp",
        "Cache-Control" to "no-store, max-age=0",
        "Content-Security-Policy" to "default-src 'none'; script-src 'sha256-abc'; style-src 'sha256-def'",
    )

    private val client: HttpClient =
        HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

    private fun bytesOf(url: String, method: String = "GET"): HttpResponse<ByteArray> =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun <T> serving(block: (LoopbackPageServer) -> T): T {
        val server = requireNotNull(LoopbackPageServer.start(html, headers)) { "could not bind loopback" }
        try {
            return block(server)
        } finally {
            server.stop()
        }
    }

    /** Everything after `?k=` in the served URL — the one place the secret is allowed to appear. */
    private fun tokenOf(server: LoopbackPageServer): String = server.url.substringAfter("?k=")

    @Test
    fun `binds a loopback address only`() {
        serving { server ->
            val host = URI.create(server.url).host.trim('[', ']')
            assertTrue(
                InetAddress.getByName(host).isLoopbackAddress,
                "served host must be a loopback address, was $host",
            )
            assertFalse(server.url.contains("0.0.0.0"), "must never advertise a wildcard address")
        }
    }

    @Test
    fun `port is bound and not fixed`() {
        serving { first ->
            serving { second ->
                assertTrue(first.port > 0, "the ephemeral port must be read back after the bind")
                assertNotEquals(first.port, second.port, "an OS-assigned port cannot be the same twice over")
                assertTrue(first.url.contains(":${first.port}/"), "the URL must name the bound port")
            }
        }
    }

    @Test
    fun `serves the exact document with every header`() {
        serving { server ->
            val response = bytesOf(server.url)
            assertEquals(200, response.statusCode())
            assertEquals(html, String(response.body(), StandardCharsets.UTF_8))
            headers.forEach { (name, value) ->
                assertEquals(value, response.headers().firstValue(name).orElse(null), "header $name")
            }
            assertFalse(
                response.headers().firstValue("Access-Control-Allow-Origin").isPresent,
                "there is no cross-origin surface to open",
            )
        }
    }

    @Test
    fun `a wrong token is refused with no body`() {
        serving { server ->
            val response = bytesOf(server.url.substringBefore("?k=") + "?k=wrong")
            assertEquals(403, response.statusCode())
            assertEquals(0, response.body().size)
        }
    }

    @Test
    fun `a missing token is refused with no body`() {
        serving { server ->
            val response = bytesOf(server.url.substringBefore("?k="))
            assertEquals(403, response.statusCode())
            assertEquals(0, response.body().size)
        }
    }

    @Test
    fun `the token is consumed by the request that receives the document`() {
        serving { server ->
            assertEquals(200, bytesOf(server.url).statusCode())
            val second = bytesOf(server.url)
            assertEquals(403, second.statusCode(), "the secret is one-shot")
            assertEquals(0, second.body().size, "a spent token is indistinguishable from a wrong one")
        }
    }

    @Test
    fun `an unknown path is not found and does not spend the token`() {
        serving { server ->
            val token = tokenOf(server)
            val root = URI.create(server.url).let { "http://${it.host}:${it.port}/secrets?k=$token" }
            val response = bytesOf(root)
            assertEquals(404, response.statusCode())
            assertEquals(0, response.body().size)
            assertEquals(200, bytesOf(server.url).statusCode(), "a rejected path must not burn the secret")
        }
    }

    @Test
    fun `a non-GET method is refused and does not spend the token`() {
        serving { server ->
            val response = bytesOf(server.url, method = "POST")
            assertEquals(405, response.statusCode())
            assertEquals(0, response.body().size)
            assertEquals(200, bytesOf(server.url).statusCode(), "a rejected method must not burn the secret")
        }
    }

    @Test
    fun `stop is idempotent and closes the port`() {
        val server = requireNotNull(LoopbackPageServer.start(html, headers))
        val url = server.url
        server.stop()
        server.stop()
        assertThrows<IOException> { bytesOf(url) }
    }

    @Test
    fun `toString does not carry the token`() {
        serving { server ->
            assertFalse(server.toString().contains(tokenOf(server)), "the secret must never reach a log")
            assertTrue(server.toString().contains(server.port.toString()), "the port is what identifies it")
        }
    }
}
