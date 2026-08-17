package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The order in which the chat page is attempted, and the fact that the attempts run out.
 *
 * This is the whole of the Remote Development detection: the plugin never asks the platform where it is running,
 * it discovers it by a delivery that did not produce a live web app. That makes the ladder's shape the contract —
 * a rung reached twice is a browser that reloads itself forever, and a ladder with no end is the same thing with
 * more steps. Both are invisible at runtime until a user reports a chat that keeps blanking.
 *
 * Pure and instant on purpose: no browser, no socket, no port, no waiting. The rungs themselves are covered by
 * [LoopbackPageServerTest] and [RemoteDevNoticeTest]; what is pinned here is only the order and the end.
 */
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

    /**
     * The ladder only ever moves forward, which is what makes "at most once per browser" hold without the host
     * tracking which rungs it has already tried — and what makes `maxOf(start, provenRoute)` a legal way to carry
     * the outcome across browsers, since that comparison is on declaration order.
     */
    @Test
    fun `every step lands on a later rung than the one it came from`() {
        for (from in PageRoute.entries) {
            for (bound in listOf(true, false)) {
                val next = nextPageRoute(from, bound) ?: continue
                assertTrue(next.ordinal > from.ordinal, "$from (bound=$bound) went back to $next")
            }
        }
    }

    /**
     * Every rung the ladder delivers from [start] with [bound], [start] itself first and in delivery order —
     * i.e. what a browser starting there would actually be shown, one rung per failed delivery.
     */
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
