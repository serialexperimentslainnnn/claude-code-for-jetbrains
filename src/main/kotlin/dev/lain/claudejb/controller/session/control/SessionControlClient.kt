package dev.lain.claudejb.controller.session.control

import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.util.thisLogger
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SessionControlClient(
    private val write: (String) -> Unit,
    private val newRequestId: () -> String = { dev.lain.claudejb.model.protocol.control.ControlProtocol.newRequestId() },
    private val scheduler: Scheduler = AppExecutorUtilScheduler,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
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

    private class Pending(val watchdog: Cancellable, val onOutcome: (ClaudeEvent.ControlResult) -> Unit)

    private val pending = ConcurrentHashMap<String, Pending>()

    private val log = thisLogger()

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
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
        val requestLine = buildRequest(id)
        val watchdog = scheduler.schedule(timeoutSeconds) {
            settle(id, ClaudeEvent.ControlResult(requestId = id, success = false, payload = null, error = "control request timed out"))
        }
        pending[id] = Pending(watchdog) { res ->
            log.debug {
                "control reply ${requestSubtype(requestLine)} id=$id success=${res.success}" +
                    " err=${res.error ?: "-"} payload=${res.payload ?: "null"}"
            }
            onOutcome(res)
        }
        log.debug { "control send ${requestSubtype(requestLine)} id=$id" }
        write(requestLine)
    }

    fun onControlResult(event: ClaudeEvent.ControlResult) = settle(event.requestId, event)

    fun onProgress(requestId: String) {
        val entry = pending[requestId] ?: return
        entry.watchdog.cancel()
        log.debug { "control request $requestId is long-running; its watchdog is off" }
    }

    fun failAll(reason: String) {
        pending.keys.toList().forEach { id ->
            settle(id, ClaudeEvent.ControlResult(requestId = "", success = false, payload = null, error = reason))
        }
    }

    private fun settle(id: String, result: ClaudeEvent.ControlResult) {
        val entry = pending.remove(id) ?: return
        entry.watchdog.cancel()
        entry.onOutcome(result)
    }
}
