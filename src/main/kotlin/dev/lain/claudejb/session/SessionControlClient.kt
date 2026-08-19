package dev.lain.claudejb.session

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.protocol.ClaudeEvent
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SessionControlClient(
    private val write: (String) -> Unit,
    private val newRequestId: () -> String = { dev.lain.claudejb.protocol.ControlProtocol.newRequestId() },
    private val scheduler: Scheduler = AppExecutorUtilScheduler,
    private val timeoutSeconds: Long = ClaudeSession.CONTROL_TIMEOUT_SECONDS,
) {

    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delaySeconds: Long, task: () -> Unit): Cancellable
    }

    object AppExecutorUtilScheduler : Scheduler {
        override fun schedule(delaySeconds: Long, task: () -> Unit): Cancellable {
            val future: ScheduledFuture<*> =
                AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delaySeconds, TimeUnit.SECONDS)
            return Cancellable { future.cancel(false) }
        }
    }

    private val pending = ConcurrentHashMap<String, (ClaudeEvent.ControlResult) -> Unit>()

    private val log = thisLogger()

    private companion object {
        const val TRACE_MAX = 2000
    }

    private fun requestSubtype(line: String): String =
        Regex("\"subtype\"\\s*:\\s*\"([a-z_]+)\"").find(line)?.groupValues?.get(1) ?: "?"

    fun <T> query(
        buildRequest: (requestId: String) -> String,
        onResult: (T?) -> Unit,
        decode: (JsonObject?) -> T?,
    ) = send(buildRequest) { res -> onResult(decode(res.payload)) }

    fun send(
        buildRequest: (requestId: String) -> String,
        onOutcome: (ClaudeEvent.ControlResult) -> Unit,
    ) {
        val id = newRequestId()
        val watchdog = scheduler.schedule(timeoutSeconds) {
            pending.remove(id)?.invoke(
                ClaudeEvent.ControlResult(requestId = id, success = false, payload = null, error = "control request timed out"),
            )
        }
        val requestLine = buildRequest(id)
        pending[id] = { res ->
            watchdog.cancel()
            log.debug(
                "CC-TRACE control reply ${requestSubtype(requestLine)} id=$id success=${res.success}" +
                    " err=${res.error ?: "-"} payload=${res.payload?.toString()?.take(TRACE_MAX) ?: "null"}",
            )
            onOutcome(res)
        }
        log.debug("CC-TRACE control send ${requestSubtype(requestLine)} id=$id")
        write(requestLine)
    }

    fun onControlResult(event: ClaudeEvent.ControlResult) {
        pending.remove(event.requestId)?.invoke(event)
    }

    fun failAll(reason: String) {
        pending.values.toList().also { pending.clear() }.forEach {
            it(ClaudeEvent.ControlResult(requestId = "", success = false, payload = null, error = reason))
        }
    }
}
