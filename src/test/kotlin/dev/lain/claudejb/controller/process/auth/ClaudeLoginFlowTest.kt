package dev.lain.claudejb.controller.process.auth

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaudeLoginFlowTest {

    private val process = FakeProcess()

    private val flow = ClaudeLoginFlow("claude", null, emptyMap(), spawn = { _, _, _ -> process })

    @AfterEach
    fun tearDown() = process.end(0)

    @Test
    fun `a listener that throws on the token does not stop the code prompt from being seen`() {
        val events = CopyOnWriteArrayList<String>()
        val prompted = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val listener = object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                events += "url"
            }

            override fun onCodeRequested() {
                events += "code"
                prompted.countDown()
            }

            override fun onToken(token: String) {
                events += "token"
                error("the safe is locked")
            }

            override fun onResult(success: Boolean, message: String) {
                events += "result:$success"
                ended.countDown()
            }
        }

        assertTrue(flow.start(listener))
        process.emit("Open $AUTH_URL\n")
        process.emit("Your setup token: $TOKEN\n")
        process.emit("Paste code here: ")

        assertTrue(prompted.await(5, TimeUnit.SECONDS)) {
            "the code prompt after a throwing onToken was never reported: $events"
        }
        process.end(0)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(setOf("url", "token", "code"), events.dropLast(1).toSet())
        assertEquals("result:true", events.last())
    }

    @Test
    fun `each signal is reported once however many chunks arrive after it`() {
        val urls = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        val listener = object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                urls += url
            }

            override fun onCodeRequested() = Unit

            override fun onResult(success: Boolean, message: String) = ended.countDown()
        }

        assertTrue(flow.start(listener))
        process.emit("Open $AUTH_URL\n")
        process.emit("still waiting\n")
        process.emit("still waiting\n")
        process.end(0)

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(AUTH_URL), urls)
    }

    @Test
    fun `a cancelled sign-in reports no result, so the card is not told it failed`() {
        val reported = CountDownLatch(1)
        val listener = object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) = Unit

            override fun onCodeRequested() = Unit

            override fun onResult(success: Boolean, message: String) = reported.countDown()
        }

        assertTrue(flow.start(listener))
        process.emit("Open $AUTH_URL\n")
        flow.cancel()

        assertFalse(reported.await(REPORT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            "the user cancelled, and the flow still reported the killed process's exit as a failed sign-in"
        }
    }

    private class FakeProcess : Process() {

        private val stdin = ByteArrayOutputStream()
        private val pipeOut = PipedOutputStream()
        private val pipeIn = PipedInputStream(pipeOut)
        private val exit = CompletableFuture<Int>()

        fun emit(text: String) {
            pipeOut.write(text.toByteArray())
            pipeOut.flush()
        }

        fun end(code: Int) {
            if (exit.complete(code)) pipeOut.close()
        }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = pipeIn

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit.get()

        override fun exitValue(): Int = exit.getNow(null) ?: throw IllegalThreadStateException()

        override fun destroy() = end(EXIT_KILLED)
    }

    private companion object {
        const val AUTH_URL = "https://claude.ai/oauth/authorize?code=true&client_id=abc"
        const val TOKEN = "sk-ant-oat01-abcdefghijklmnopqrstuvwxyz0123456789"
        const val EXIT_KILLED = 130
        const val REPORT_GRACE_MS = 500L
    }
}
