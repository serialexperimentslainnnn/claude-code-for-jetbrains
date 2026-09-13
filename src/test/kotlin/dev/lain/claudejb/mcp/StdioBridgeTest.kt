package dev.lain.claudejb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StdioBridgeTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `stdin JSON-RPC crosses the socket as TOON with the token attached, and replies come back as JSON lines`() {
        val socket = dir.resolve("code.sock")
        Files.writeString(dir.resolve(StdioBridge.TOKEN_FILE), "secret-token\n")
        val stdout = ByteArrayOutputStream()
        val stdin = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""" + "\n" + "not json\n").toByteArray(),
        )
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        val pool = Executors.newSingleThreadExecutor()
        val bridge = pool.submit { StdioBridge(socket, PrintStream(stdout, true, Charsets.UTF_8)).pump(stdin) }

        server.accept().use { client ->
            val incoming = Frames.read(Channels.newInputStream(client))!!
            val request = Toon.decode(incoming) as Map<*, *>
            assertEquals("tools/list", request["method"])
            val meta = (request["params"] as Map<*, *>)["_meta"] as Map<*, *>
            assertEquals("secret-token", meta[StdioBridge.TOKEN_KEY])
            Frames.write(Channels.newOutputStream(client), Toon.encode(Json.parse("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")))
            assertNull(Frames.read(Channels.newInputStream(client)))
        }
        bridge.get(10, TimeUnit.SECONDS)
        pool.shutdown()
        server.close()

        val lines = stdout.toString(Charsets.UTF_8).trim().lines()
        assertTrue(lines.any { it.contains("\"code\":-32700") }) { lines.toString() }
        assertTrue(lines.any { it == """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""" }) { lines.toString() }
    }

    @Test
    fun `a frame is a byte length, a newline and the payload`() {
        val out = ByteArrayOutputStream()
        Frames.write(out, "a: é")
        assertEquals("5\na: é", out.toString(Charsets.UTF_8))
        assertEquals("a: é", Frames.read(ByteArrayInputStream(out.toByteArray())))
        assertNull(Frames.read(ByteArrayInputStream(ByteArray(0))))
    }
}
