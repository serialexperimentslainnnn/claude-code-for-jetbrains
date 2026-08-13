package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.UsageReport
import kotlinx.serialization.json.JsonObject

/**
 * Everything the UI ASKS the binary on demand.
 *
 * Not part of [ClaudeSession] because none of it is session state: these neither read nor write a turn,
 * they are a client for the other end of the pipe. What each request IS lives in [Asks]; this is the engine
 * that sends one, and it is the only place the shape lives:
 *
 *  - no process → answer null, **on the EDT**, rather than never calling back at all (a caller that gets no
 *    callback cannot tell "still waiting" from "there was nothing to ask", and both look like a spinner
 *    that never stops);
 *  - a correlated request id and a watchdog, from [SessionControlClient];
 *  - the answer decoded by the request's own rule, delivered on the EDT.
 *
 * Adding a request is a line in [Asks] plus a line here — and it inherits all of the above rather than
 * re-implementing it.
 */
class SessionQueries(
    private val controlClient: SessionControlClient,
    private val isRunning: () -> Boolean,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    /** Whether a window is worth interrupting the user about — see [QuotaWarnings]. */
    private val quota: QuotaWarnings,
) {

    fun requestContextUsage(onResult: (ContextUsage?) -> Unit) = ask(Asks.CONTEXT_USAGE, onResult)

    /**
     * The FULL usage picture — every rate-limit window plus the extra-credit balance.
     *
     * Preferred over the event stream as the dashboard's source of truth: one round-trip returns every
     * window at once, whereas an event only tells you about a window when it happens to move. The events
     * remain the live nudge that something changed and it is worth re-asking.
     */
    fun requestUsage(onResult: (UsageReport?) -> Unit) = ask(
        Ask(Asks.USAGE.subtype, Asks.USAGE.params) { payload ->
            quota.logReply(payload)
            Asks.USAGE.decode(payload)
        },
    ) { report ->
        report?.let { quota.onReport(it) }
        onResult(report)
    }

    fun requestSessionCost(onResult: (JsonObject?) -> Unit) = ask(Asks.SESSION_COST, onResult)

    fun requestMcpStatus(onResult: (JsonObject?) -> Unit) = ask(Asks.MCP_STATUS, onResult)

    /** Effective merged settings + per-source breakdown (diagnostics dialog). */
    fun requestSettings(onResult: (JsonObject?) -> Unit) = ask(Asks.SETTINGS, onResult)

    /**
     * Everything this session changed on disk, in one round-trip. Null means there is nothing to review —
     * a clean tree, a non-git directory, or a git state the binary refused to diff.
     */
    fun requestWorkspaceDiff(onResult: (WorkspaceDiff?) -> Unit) = ask(Asks.WORKSPACE_DIFF, onResult)

    /** The session's current plan-mode plan, or null when there is none. */
    fun requestPlan(onResult: (PlanInfo?) -> Unit) = ask(Asks.PLAN, onResult)

    /** The responder's CLI binary version (diagnostics dialog). */
    fun requestBinaryVersion(onResult: (JsonObject?) -> Unit) = ask(Asks.BINARY_VERSION, onResult)

    /**
     * Rewind tracked files to the state at [userMessageId] (a turn anchor). With [dryRun] the binary only
     * reports feasibility without touching files.
     */
    fun requestRewindFiles(userMessageId: String, dryRun: Boolean, onResult: (RewindResult?) -> Unit) =
        ask(Asks.rewind(userMessageId, dryRun), onResult)

    /** Reconnects a disconnected/failed MCP server; fire-and-forget (the UI re-queries mcp_status after). */
    fun reconnectMcp(name: String) = fireAndForget { id -> ControlProtocol.mcpReconnectRequest(id, name) }

    /** Enables/disables an MCP server; fire-and-forget (the UI re-queries mcp_status after). */
    fun toggleMcp(name: String, enabled: Boolean) = fireAndForget { id -> ControlProtocol.mcpToggleRequest(id, name, enabled) }

    /** Stops a running background task/subagent by id. */
    fun stopTask(taskId: String) = fireAndForget { id -> ControlProtocol.stopTaskRequest(id, taskId) }

    /** Reseeds the binary's read-state for a file (path + mtime) after an IDE-side rollback. */
    fun seedReadState(path: String, mtime: Long) = fireAndForget { id -> ControlProtocol.seedReadStateRequest(id, path, mtime) }

    /** Sends [ask] and delivers its answer on the EDT — including the null a dead process yields. */
    fun <T> ask(ask: Ask<T>, onResult: (T?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(
            buildRequest = { id -> ControlProtocol.of(id, ask.subtype, ask.params) },
            onResult = { mapped: T? -> edt { onResult(mapped) } },
            decode = ask.decode,
        )
    }

    /** No reply expected and none waited for; silently dropped when the process is down. */
    private fun fireAndForget(build: (String) -> String) {
        if (isRunning()) write(build(ControlProtocol.newRequestId()))
    }
}
