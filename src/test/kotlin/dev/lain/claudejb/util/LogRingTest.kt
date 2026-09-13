package dev.lain.claudejb.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogRingTest {

    @BeforeEach
    fun empty() = LogRing.clear()

    @Test
    fun `lines are numbered in order and carry their level and category`() {
        val first = LogRing.add(LogRing.Level.INFO, "session.Foo", "started", at = 10L)
        val second = LogRing.add(LogRing.Level.WARN, "session.Foo", "hiccup", at = 11L)

        assertEquals(0L, first.seq)
        assertEquals(1L, second.seq)
        assertEquals(listOf("started", "hiccup"), LogRing.snapshot().map { it.text })
        assertEquals(LogRing.Level.WARN, second.level)
        assertEquals(10L, first.at)
    }

    @Test
    fun `the ring keeps the newest MAX_LINES and counts what it dropped`() {
        repeat(LogRing.MAX_LINES + 5) { LogRing.add(LogRing.Level.DEBUG, "c", "line $it") }

        assertEquals(LogRing.MAX_LINES, LogRing.size())
        val snapshot = LogRing.since(-1)
        assertEquals(5L, snapshot.dropped)
        assertEquals("line 5", snapshot.lines.first().text)
        assertEquals("line ${LogRing.MAX_LINES + 4}", snapshot.lines.last().text)
    }

    @Test
    fun `since returns only the lines after the cursor`() {
        repeat(4) { LogRing.add(LogRing.Level.INFO, "c", "line $it") }

        val snapshot = LogRing.since(1)

        assertEquals(listOf("line 2", "line 3"), snapshot.lines.map { it.text })
        assertFalse(snapshot.reset)
    }

    @Test
    fun `a cursor older than the window means the reader missed lines, so the answer is a reset`() {
        repeat(LogRing.MAX_LINES + 10) { LogRing.add(LogRing.Level.INFO, "c", "line $it") }

        val snapshot = LogRing.since(3)

        assertTrue(snapshot.reset)
        assertEquals(LogRing.MAX_LINES, snapshot.lines.size)
    }

    @Test
    fun `a cursor at the newest line is not a reset and yields nothing`() {
        repeat(3) { LogRing.add(LogRing.Level.INFO, "c", "line $it") }

        val snapshot = LogRing.since(2)

        assertFalse(snapshot.reset)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test
    fun `a negative cursor asks for everything and is a reset`() {
        LogRing.add(LogRing.Level.INFO, "c", "only")

        val snapshot = LogRing.since(-1)

        assertTrue(snapshot.reset)
        assertEquals(1, snapshot.lines.size)
    }

    @Test
    fun `a level filter keeps that level and the ones above it`() {
        LogRing.add(LogRing.Level.DEBUG, "c", "d")
        LogRing.add(LogRing.Level.INFO, "c", "i")
        LogRing.add(LogRing.Level.WARN, "c", "w")

        assertEquals(listOf("w"), LogRing.snapshot(LogRing.Level.WARN).map { it.text })
        assertEquals(listOf("i", "w"), LogRing.snapshot(LogRing.Level.INFO).map { it.text })
        assertEquals(listOf("d", "i", "w"), LogRing.snapshot(LogRing.Level.DEBUG).map { it.text })
    }
}
