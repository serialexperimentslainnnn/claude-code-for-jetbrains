package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a finished load actually delivered the chat page — the question [pageArrived] answers and
 * `onLoadEnd` does not.
 *
 * REGRESSION THIS PINS: the load handler set `ready = true` and drained the queued host→web pushes on EVERY
 * load-end, without looking at the status or at whether the navigation had failed at all. So a rung of the
 * [PageRoute] ladder that never came up still SPENT the queue, and `promote()` handed the next rung a browser
 * with nothing left to draw. Under Remote Development — where [PageRoute.SCHEME] resolves in the thin client
 * to nothing — that is the ordinary path, and what it cost was the tab bar: the page came up with an empty
 * chat list and hid `#tabsbar`, which owns the dashboard's own view buttons.
 *
 * Pure and instant, like [PageRouteTest]: no browser, no socket, no waiting.
 */
class PageArrivalTest {

    @Test
    fun `a load that reported an error delivered nothing, whatever the status says`() {
        assertFalse(pageArrived(httpStatusCode = 0, loadFailed = true))
        // Chromium serves its own error page after a failed navigation, so a plausible status can arrive
        // alongside the failure. The error is the verdict.
        assertFalse(pageArrived(httpStatusCode = 200, loadFailed = true))
    }

    @Test
    fun `an http error is not the page, even though the load itself succeeded`() {
        // What the loopback server answers to any path that is not the one-shot URL.
        assertFalse(pageArrived(httpStatusCode = 404, loadFailed = false))
        assertFalse(pageArrived(httpStatusCode = 500, loadFailed = false))
        assertFalse(pageArrived(httpStatusCode = 403, loadFailed = false))
    }

    @Test
    fun `a status of zero is a non-http load, not a failure`() {
        assertTrue(
            pageArrived(httpStatusCode = 0, loadFailed = false),
            "0 is what a non-HTTP load reports; reading it as a failure would strand the queue forever",
        )
    }

    /**
     * The two `loadHTML` rungs of the ladder ([PageRoute.INLINE] and [PageRoute.NOTICE]) must pass. They are
     * served at 200 by the platform's own scheme handler today, and at 0 if it ever stops being an HTTP-shaped
     * load — both are the page arriving, and a rule keyed on `== 200` would break the fallback that exists
     * precisely for when everything above it is broken.
     */
    @Test
    fun `the page arriving is anything that is not an error`() {
        listOf(0, 200, 204, 301, 304, 399).forEach {
            assertTrue(pageArrived(httpStatusCode = it, loadFailed = false), "status $it should count as arrived")
        }
    }

    @Test
    fun `a negative status is not trusted`() {
        assertFalse(pageArrived(httpStatusCode = -1, loadFailed = false))
    }
}
