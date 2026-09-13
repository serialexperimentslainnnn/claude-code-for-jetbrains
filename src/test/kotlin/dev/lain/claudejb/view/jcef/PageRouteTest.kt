package dev.lain.claudejb.view.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PageRouteTest {

    @Test
    fun `the ladder is scheme, then loopback, then nothing`() {
        assertEquals(PageRoute.LOOPBACK, nextPageRoute(PageRoute.SCHEME))
        assertNull(nextPageRoute(PageRoute.LOOPBACK))
    }

    @Test
    fun `there are exactly two rungs, both of them URL loads`() {
        assertEquals(listOf("SCHEME", "LOOPBACK"), PageRoute.entries.map { it.name }) {
            "A rung delivered through loadHTML navigates to file:///jbcefbrowser/…, which the host's own " +
                "navigation guard refuses; a watchdog that falls onto it leaves the page never running."
        }
    }

    @Test
    fun `no rung is delivered through loadHTML, which the navigation guard refuses`() {
        val delivery = source("PageDelivery.kt").readText()
        assertFalse(delivery.contains("loadHTML(")) {
            "PageDelivery navigates through loadHTML. isOwnPageUrl only admits the scheme page and the loopback " +
                "URL, so that load is cancelled before it starts."
        }
    }

    @Test
    fun `every step lands on a later rung than the one it came from, and the ladder ends`() {
        for (from in PageRoute.entries) {
            val visited = generateSequence(from) { nextPageRoute(it) }.toList()
            assertEquals(visited.distinct(), visited, "the ladder from $from delivered a rung twice: $visited")
            assertTrue(visited.size <= PageRoute.entries.size, "the ladder from $from ran longer than there are rungs")
            visited.zipWithNext().forEach { (a, b) -> assertTrue(b.ordinal > a.ordinal, "$a went back to $b") }
        }
    }

    private fun source(name: String): File {
        val path = "src/main/kotlin/dev/lain/claudejb/view/jcef/$name"
        return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?: error("could not locate $path from ${File("").absolutePath}")
    }
}
