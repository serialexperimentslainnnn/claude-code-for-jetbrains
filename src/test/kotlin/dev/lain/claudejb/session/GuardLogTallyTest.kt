package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuardLogTallyTest {

    @Test
    fun `a fresh session has recorded nothing and lost nothing`() {
        val tally = GuardLogTally()

        assertEquals(0, tally.recorded)
        assertEquals(0, tally.dropped)
    }

    @Test
    fun `an alert the store took counts as recorded and not as lost`() {
        val tally = GuardLogTally()

        tally.submitted(accepted = true)

        assertEquals(1, tally.recorded)
        assertEquals(0, tally.dropped)
    }

    @Test
    fun `an alert the store refused is still counted — that is the whole point of counting`() {
        val tally = GuardLogTally()

        tally.submitted(accepted = true)
        tally.submitted(accepted = false)
        tally.submitted(accepted = false)

        assertEquals(3, tally.recorded, "a dropped alert still happened; only the record of it is gone")
        assertEquals(2, tally.dropped)
    }

    @Test
    fun `alerts arrive from the EDT and from the control thread, so the count has to survive both`() {
        val tally = GuardLogTally()
        val threads = 8
        val each = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            repeat(threads) { index ->
                pool.execute {
                    start.await()
                    repeat(each) { tally.submitted(accepted = index % 2 == 0) }
                }
            }
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * each, tally.recorded)
        assertEquals(threads / 2 * each, tally.dropped)
    }
}
