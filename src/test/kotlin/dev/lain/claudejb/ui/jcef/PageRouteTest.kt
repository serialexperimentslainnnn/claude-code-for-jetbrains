package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageRouteTest {

    @Test
    fun `the ladder runs scheme then loopback then inline`() {
        assertEquals(PageRoute.LOOPBACK, nextPageRoute(PageRoute.SCHEME, loopbackBound = false))
        assertEquals(PageRoute.INLINE, nextPageRoute(PageRoute.LOOPBACK, loopbackBound = false))
    }

    @Test
    fun `the notice is the last rung, and only when a port is actually being served`() {
        assertEquals(PageRoute.NOTICE, nextPageRoute(PageRoute.INLINE, loopbackBound = true))
        assertNull(
            nextPageRoute(PageRoute.INLINE, loopbackBound = false),
            "with nothing bound the notice has no port to name, so there is nothing to show",
        )
    }

    @Test
    fun `the notice is terminal`() {
        assertNull(nextPageRoute(PageRoute.NOTICE, loopbackBound = true))
        assertNull(nextPageRoute(PageRoute.NOTICE, loopbackBound = false))
    }

    @Test
    fun `every step lands on a later rung than the one it came from`() {
        for (from in PageRoute.entries) {
            for (bound in listOf(true, false)) {
                val next = nextPageRoute(from, bound) ?: continue
                assertTrue(next.ordinal > from.ordinal, "$from (bound=$bound) went back to $next")
            }
        }
    }

    private fun rungsDeliveredFrom(start: PageRoute, bound: Boolean): List<PageRoute> =
        generateSequence(start) { nextPageRoute(it, bound) }.toList()

    @Test
    fun `the ladder ends from any starting point, and visits nothing twice`() {
        for (start in PageRoute.entries) {
            for (bound in listOf(true, false)) {
                val visited = rungsDeliveredFrom(start, bound)
                assertEquals(
                    visited.distinct(),
                    visited,
                    "the ladder from $start (bound=$bound) delivered a rung twice: $visited",
                )
                assertTrue(
                    visited.size <= PageRoute.entries.size,
                    "the ladder from $start (bound=$bound) ran longer than there are rungs: $visited",
                )
            }
        }
    }
}
