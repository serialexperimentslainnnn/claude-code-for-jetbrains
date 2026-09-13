package dev.lain.claudejb.controller.mcp.tools.run

import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JobsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Jobs<Int>(scope, "t")

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a job that outlives the wait answers null and still delivers on the next await`() = runBlocking {
        val gate = CompletableDeferred<Int>()
        val job = jobs.start(OutputTail()) { gate.await() }
        assertEquals("t-1", job.id)
        assertNull(jobs.await(job, SHORT_WAIT))
        gate.complete(7)
        assertEquals(7, jobs.await(job, LONG_WAIT))
    }

    @Test
    fun `a job is found by id until its result has been delivered`() {
        runBlocking {
            val gate = CompletableDeferred<Int>()
            val job = jobs.start(OutputTail()) { gate.await() }
            assertTrue(jobs.find(job.id) === job)
            gate.complete(1)
            assertEquals(1, jobs.await(jobs.find(job.id), LONG_WAIT))
            assertThrows<ToolException> { jobs.find(job.id) }
            assertThrows<ToolException> { jobs.find("t-99") }
        }
    }

    @Test
    fun `a failing job surfaces its exception and is forgotten`() {
        runBlocking {
            val job = jobs.start(OutputTail()) { throw ToolException("boom") }
            assertEquals("boom", assertThrows<ToolException> { jobs.await(job, LONG_WAIT) }.message)
            assertThrows<ToolException> { jobs.find(job.id) }
        }
    }

    @Test
    fun `wait is bounded to one through one hundred and ten seconds`() {
        assertEquals(45_000L, Jobs.waitMillis(args("{}")))
        assertEquals(110_000L, Jobs.waitMillis(args("""{"wait":"110"}""")))
        assertEquals(1_000L, Jobs.waitMillis(args("""{"wait":"1"}""")))
        assertThrows<ToolException> { Jobs.waitMillis(args("""{"wait":"0"}""")) }
        assertThrows<ToolException> { Jobs.waitMillis(args("""{"wait":"111"}""")) }
    }

    @Test
    fun `the tail keeps the last lines, counts them all and publishes each one`() {
        val published = ArrayList<String>()
        val tail = OutputTail { published += it }
        tail.text("one\ntwo\n")
        tail.text("")
        repeat(OutputTail.KEEP) { tail.line("line $it") }
        assertEquals(OutputTail.KEEP + 2, tail.lines)
        assertEquals(OutputTail.KEEP + 2, published.size)
        assertEquals(listOf("one", "two"), published.take(2))
        assertEquals("line ${OutputTail.KEEP - 2}\nline ${OutputTail.KEEP - 1}", tail.tail(2))
        assertEquals(OutputTail.KEEP, tail.tail(OutputTail.KEEP + 50).lines().size)
        assertEquals(OutputTail.KEEP, OutputTail.lines(args("""{"tail":"999"}""")))
        assertEquals(40, OutputTail.lines(args("{}")))
    }

    private fun args(json: String): ToolArgs = ToolArgs(Json.parseToJsonElement(json).jsonObject)

    private companion object {
        const val SHORT_WAIT = 50L
        const val LONG_WAIT = 5_000L
    }
}
