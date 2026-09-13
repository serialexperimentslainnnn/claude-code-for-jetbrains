package dev.lain.claudejb.model.session.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveLinesTest {

    @Test
    fun `renders what was added, in order, with no header while nothing was dropped`() {
        val ring = LiveLines()
        ring.add("one")
        ring.add("two")

        assertEquals("one\ntwo", ring.render())
        assertEquals("one\ntwo", ring.render())
    }

    @Test
    fun `an empty ring renders as nothing`() {
        assertEquals("", LiveLines().render())
    }

    @Test
    fun `keeps only the last 200 lines and says how many fell off the head`() {
        val ring = LiveLines()
        repeat(230) { ring.add("line $it") }

        val rendered = ring.render()
        val lines = rendered.lines()
        assertEquals("… 30 earlier lines not shown", lines.first())
        assertEquals(201, lines.size)
        assertEquals("line 30", lines[1])
        assertEquals("line 229", lines.last())
        assertFalse(rendered.contains("line 29\n"))
    }

    @Test
    fun `a line longer than 400 characters is cut to 400`() {
        val ring = LiveLines()
        ring.add("x".repeat(1000))
        ring.add("short")

        val lines = ring.render().lines()
        assertEquals(400, lines[0].length)
        assertEquals("short", lines[1])
    }

    @Test
    fun `the header counts every dropped line, not only the last batch`() {
        val ring = LiveLines()
        repeat(201) { ring.add("a") }
        assertTrue(ring.render().startsWith("… 1 earlier lines not shown\n"))
        repeat(5) { ring.add("b") }
        assertTrue(ring.render().startsWith("… 6 earlier lines not shown\n"))
    }
}
