package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageArrivalTest {

    @Test
    fun `a load that reported an error delivered nothing, whatever the status says`() {
        assertFalse(pageArrived(httpStatusCode = 0, loadFailed = true))
        assertFalse(pageArrived(httpStatusCode = 200, loadFailed = true))
    }

    @Test
    fun `an http error is not the page, even though the load itself succeeded`() {
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
