package dev.lain.claudejb.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PluginLogTest {

    private class FakeBackend : PluginLog.Backend {
        override var isDebugEnabled = false
        val lines = ArrayList<String>()

        override fun warn(message: String, cause: Throwable?) {
            lines += "WARN $message" + (cause?.let { " !${it.message}" } ?: "")
        }

        override fun info(message: String) {
            lines += "INFO $message"
        }

        override fun debug(message: String) {
            lines += "DEBUG $message"
        }

        override fun setDebug(on: Boolean) {
            isDebugEnabled = on
        }
    }

    private val backend = FakeBackend()
    private val log = PluginLog("session.Probe", backend)

    @BeforeEach
    fun fresh() = LogRing.clear()

    @Test
    fun `a debug lambda is never evaluated while debug is off`() {
        var evaluated = false

        log.debug {
            evaluated = true
            "expensive"
        }

        assertFalse(evaluated)
        assertTrue(backend.lines.isEmpty())
        assertEquals(0, LogRing.size())
    }

    @Test
    fun `switching debug on evaluates the lambda and records the line at DEBUG`() {
        backend.setDebug(true)

        log.debug { "trace detail" }

        assertEquals(listOf("DEBUG trace detail"), backend.lines)
        assertEquals(LogRing.Level.DEBUG, LogRing.snapshot().single().level)
        assertEquals("session.Probe", LogRing.snapshot().single().category)
    }

    @Test
    fun `every line goes through redaction before the platform logger and the ring see it`() {
        LogRedaction.remember(mapOf("MY_SECRET" to "hunter2hunter2"))

        log.warn("token hunter2hunter2 refused", IllegalStateException("boom"))
        log.info("token hunter2hunter2 accepted")

        assertEquals(
            listOf("WARN token ${ReasonSecrecy.PLACEHOLDER} refused !boom", "INFO token ${ReasonSecrecy.PLACEHOLDER} accepted"),
            backend.lines,
        )
        val ring = LogRing.snapshot()
        assertEquals(listOf(LogRing.Level.WARN, LogRing.Level.INFO), ring.map { it.level })
        assertTrue(ring.none { it.text.contains("hunter2") })
        assertTrue(ring.first().text.endsWith("boom"))
    }

    @Test
    fun `the global switch reaches every registered log`() {
        PluginLog.register(log, "probe")

        PluginLog.setDebug(true)
        assertTrue(log.isDebugEnabled)
        assertTrue(PluginLog.debugOn)

        PluginLog.setDebug(false)
        assertFalse(log.isDebugEnabled)
        assertFalse(PluginLog.debugOn)
    }
}
