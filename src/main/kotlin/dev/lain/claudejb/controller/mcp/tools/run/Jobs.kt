package dev.lain.claudejb.controller.mcp.tools.run

import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class Job<T>(val id: String, val tail: OutputTail, val deferred: Deferred<T>)

internal class Deadline(millis: Long) {

    private val end = System.nanoTime() + millis * NANOS_PER_MILLI

    fun remaining(): Long = ((end - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)

    fun expired(): Boolean = remaining() == 0L

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal class Jobs<T>(private val scope: CoroutineScope, private val prefix: String) {

    private val counter = AtomicInteger()
    private val running = ConcurrentHashMap<String, Job<T>>()

    fun start(tail: OutputTail, block: suspend () -> T): Job<T> {
        val id = prefix + "-" + counter.incrementAndGet()
        val job = Job(id, tail, scope.async { block() })
        running[id] = job
        return job
    }

    fun find(id: String): Job<T> =
        running[id] ?: throw ToolException("no job $id: it never started, or its result was already delivered")

    suspend fun await(job: Job<T>, waitMillis: Long): T? = try {
        withTimeoutOrNull(waitMillis) { job.deferred.await() }
    } finally {
        if (job.deferred.isCompleted) running.remove(job.id)
    }

    companion object {

        const val DEFAULT_WAIT_SECONDS = 45
        const val MAX_WAIT_SECONDS = 110
        private const val MILLIS = 1000L

        val WAIT = Param(
            "wait",
            "Seconds to wait before answering with status running (1..$MAX_WAIT_SECONDS, default $DEFAULT_WAIT_SECONDS)",
            type = "integer",
            required = false,
        )

        val JOB = Param(
            "job",
            "The job id from an earlier answer with status running, to keep waiting on it instead of starting again",
            required = false,
        )

        fun status(result: Any?): String = if (result == null) "running" else "finished"

        fun deadline(args: ToolArgs): Deadline = Deadline(waitMillis(args))

        const val NOT_STARTED = "not started: the wait ran out on an earlier item; call again with the rest"

        fun waitMillis(args: ToolArgs): Long {
            val seconds = args.int("wait", DEFAULT_WAIT_SECONDS)
            if (seconds !in 1..MAX_WAIT_SECONDS) throw ToolException("wait must be between 1 and $MAX_WAIT_SECONDS seconds")
            return seconds * MILLIS
        }
    }
}
