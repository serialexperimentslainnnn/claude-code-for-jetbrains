package dev.lain.claudejb.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogRedactionTest {

    private val homeBefore = LogRedaction.home

    @BeforeEach
    fun fresh() {
        LogRedaction.home = "/home/lain"
    }

    @AfterEach
    fun restore() {
        LogRedaction.home = homeBefore
    }

    @Test
    fun `a sensitive environment value registered by the process launch never reaches a line`() {
        LogRedaction.remember(mapOf("ANTHROPIC_AUTH_TOKEN" to "tok-ABCDEFGHIJ", "LANG" to "C.UTF-8"))

        val out = LogRedaction.apply("sending Authorization: Bearer tok-ABCDEFGHIJ with LANG=C.UTF-8")

        assertEquals("sending Authorization: Bearer ${ReasonSecrecy.PLACEHOLDER} with LANG=C.UTF-8", out)
    }

    @Test
    fun `a short value under a sensitive name is not a secret worth blanking common words for`() {
        LogRedaction.remember(mapOf("PASS" to "ok"))

        assertEquals("looks ok to me", LogRedaction.apply("looks ok to me"))
    }

    @Test
    fun `an Anthropic API key is masked wherever it appears`() {
        val key = "sk-ant-api03-" + "Q".repeat(40)

        val out = LogRedaction.apply("the binary printed $key twice: $key")

        assertFalse(out.contains(key))
        assertEquals("the binary printed sk-ant-… twice: sk-ant-…", out)
    }

    @Test
    fun `the home directory collapses to a tilde, in either slash direction`() {
        assertEquals("read ~/.claude.json", LogRedaction.apply("read /home/lain/.claude.json"))

        LogRedaction.home = "C:\\Users\\Lain"
        assertEquals("read ~\\x and ~/y", LogRedaction.apply("read C:\\Users\\Lain\\x and C:/Users/Lain/y"))
    }

    @Test
    fun `a line is cut at the cap so one payload cannot fill the ring`() {
        val out = LogRedaction.apply("x".repeat(LogRedaction.MAX_TEXT + 500))

        assertEquals(LogRedaction.MAX_TEXT + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `paths outside the project stay, because a report needs them`() {
        assertEquals("refused /etc/passwd", LogRedaction.apply("refused /etc/passwd"))
    }
}
