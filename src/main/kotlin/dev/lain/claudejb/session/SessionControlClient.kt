package dev.lain.claudejb.session

import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.protocol.ClaudeEvent
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the correlation of **host-initiated control requests** with the binary's `control_response` replies.
 * Extracted from [ClaudeSession] so the request-id bookkeeping, the per-request watchdog and the failure
 * draining live in one small, unit-testable place; [ClaudeSession] delegates and keeps its public API
 * (`requestContextUsage`/`requestSessionCost`/`requestMcpStatus`, the `ControlResult` event branch, stop/crash)
 * unchanged.
 *
 * Contract (identical to the previous inline implementation):
 *  - [query] mints a fresh request id, registers a pending handler, arms a watchdog and writes the request line;
 *    when the reply arrives the watchdog is cancelled, the payload is decoded and `onResult` is invoked. A
 *    semi-stuck binary trips the watchdog, which removes the handler and delivers `onResult(null)`.
 *  - [onControlResult] resolves (removes + invokes) the pending handler matching `requestId`; unknown ids are
 *    ignored (idempotent — a watchdog may have fired first).
 *  - [failAll] drains every in-flight handler with a failure so their callbacks (which map a null payload to
 *    `onResult(null)`) don't hang once the process is gone. Called on stop / termination / dispose.
 *
 * Threading is inherited from the caller: handlers run on whatever thread delivers the reply (the process reader
 * thread for [onControlResult], a scheduler thread for the watchdog, the caller's thread for [failAll]). Callers
 * are responsible for hopping to the EDT inside `onResult` if they touch UI state — exactly as before. The pending
 * map is a [ConcurrentHashMap] so concurrent register / resolve / drain are safe.
 *
 * @param write       sends one NDJSON line to the binary's stdin (delegates to `ClaudeProcess.writeLine`).
 * @param newRequestId mints a fresh, unique control request id (defaults to the protocol generator).
 * @param scheduler   arms the per-request timeout (defaults to the app scheduled executor, as before).
 * @param timeoutSeconds watchdog timeout (defaults to [ClaudeSession.CONTROL_TIMEOUT_SECONDS]).
 */
class SessionControlClient(
    private val write: (String) -> Unit,
    private val newRequestId: () -> String = { dev.lain.claudejb.protocol.ControlProtocol.newRequestId() },
    private val scheduler: Scheduler = AppExecutorUtilScheduler,
    private val timeoutSeconds: Long = ClaudeSession.CONTROL_TIMEOUT_SECONDS,
) {

    /** A cancellable delayed task abstraction so tests can drive timeouts without the real executor. */
    fun interface Cancellable {
        fun cancel()
    }

    /** Schedules [task] to run after [delaySeconds]; the returned handle cancels it if the reply wins the race. */
    fun interface Scheduler {
        fun schedule(delaySeconds: Long, task: () -> Unit): Cancellable
    }

    /** Production scheduler: the same `AppExecutorUtil.getAppScheduledExecutorService()` used inline before. */
    object AppExecutorUtilScheduler : Scheduler {
        override fun schedule(delaySeconds: Long, task: () -> Unit): Cancellable {
            val future: ScheduledFuture<*> =
                AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delaySeconds, TimeUnit.SECONDS)
            return Cancellable { future.cancel(false) }
        }
    }

    /** In-flight control requests, keyed by request id; the value resolves the awaiting caller. */
    private val pending = ConcurrentHashMap<String, (ClaudeEvent.ControlResult) -> Unit>()

    /**
     * Shared plumbing: register a pending handler keyed by a fresh id, arm the watchdog, send the request line,
     * and map the payload to [T] when the reply arrives. [decode] turns the (nullable) `response` payload into the
     * caller's type; on timeout/failure the payload is null so `decode(null)` (typically `→ null`) flows through.
     *
     * @param buildRequest builds the NDJSON control_request line for the given request id.
     * @param onResult     receives the decoded result (or null on failure/timeout). Not hopped to any thread here.
     * @param decode       maps the raw `response` JSON object (null on failure) to [T].
     */
    fun <T> query(
        buildRequest: (requestId: String) -> String,
        onResult: (T?) -> Unit,
        decode: (JsonObject?) -> T?,
    ) {
        val id = newRequestId()
        // Watchdog: a semi-stuck binary could otherwise leave this callback pending forever (eternal "Loading…").
        val watchdog = scheduler.schedule(timeoutSeconds) {
            pending.remove(id)?.invoke(
                ClaudeEvent.ControlResult(requestId = id, success = false, payload = null, error = "control request timed out"),
            )
        }
        pending[id] = { res ->
            watchdog.cancel()
            onResult(decode(res.payload))
        }
        write(buildRequest(id))
    }

    /** Resolves the pending handler correlated by [event].requestId. Unknown ids are ignored (watchdog may have won). */
    fun onControlResult(event: ClaudeEvent.ControlResult) {
        pending.remove(event.requestId)?.invoke(event)
    }

    /**
     * Resolves every in-flight request with a failure so their callbacks (which map a null payload to
     * `onResult(null)`) don't hang forever once the process is gone. Called on stop / termination / dispose.
     */
    fun failAll(reason: String) {
        pending.values.toList().also { pending.clear() }.forEach {
            it(ClaudeEvent.ControlResult(requestId = "", success = false, payload = null, error = reason))
        }
    }
}
