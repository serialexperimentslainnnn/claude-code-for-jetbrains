package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.UsageReport
import kotlinx.serialization.json.JsonObject

class SessionQueries(
    private val controlClient: SessionControlClient,
    private val isRunning: () -> Boolean,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    private val quota: QuotaWarnings,
) {

    fun requestContextUsage(onResult: (ContextUsage?) -> Unit) = ask(Asks.CONTEXT_USAGE, onResult)

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

    fun requestSettings(onResult: (JsonObject?) -> Unit) = ask(Asks.SETTINGS, onResult)

    fun requestWorkspaceDiff(onResult: (WorkspaceDiff?) -> Unit) = ask(Asks.WORKSPACE_DIFF, onResult)

    fun requestPlan(onResult: (PlanInfo?) -> Unit) = ask(Asks.PLAN, onResult)

    fun requestBinaryVersion(onResult: (JsonObject?) -> Unit) = ask(Asks.BINARY_VERSION, onResult)

    fun requestGeneratedTitle(description: String, onResult: (String?) -> Unit) =
        ask(Asks.generateTitle(description), onResult)

    fun askSideQuestion(question: String, onResult: (String?) -> Unit) =
        ask(Asks.sideQuestion(question), onResult)

    fun requestRewindFiles(userMessageId: String, dryRun: Boolean, onResult: (RewindResult?) -> Unit) =
        ask(Asks.rewind(userMessageId, dryRun), onResult)

    fun setRemoteControl(enabled: Boolean, onResult: (RemoteControlOutcome) -> Unit) {
        val ask = Asks.remoteControl(enabled)
        if (!isRunning()) {
            edt { onResult(RemoteControlOutcome(enabled, ok = false, sessionUrl = null, error = "the session is not running")) }
            return
        }
        controlClient.send({ id -> ControlProtocol.of(id, ask.subtype, ask.params) }) { res ->
            edt { onResult(RemoteControlOutcome(enabled, res.success, sessionUrlIn(res.payload), res.error)) }
        }
    }

    fun reconnectMcp(name: String) = fireAndForget { id -> ControlProtocol.mcpReconnectRequest(id, name) }

    fun toggleMcp(name: String, enabled: Boolean) = fireAndForget { id -> ControlProtocol.mcpToggleRequest(id, name, enabled) }

    fun stopTask(taskId: String) = fireAndForget { id -> ControlProtocol.stopTaskRequest(id, taskId) }

    fun seedReadState(path: String, mtime: Long) = fireAndForget { id -> ControlProtocol.seedReadStateRequest(id, path, mtime) }

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

    private fun fireAndForget(build: (String) -> String) {
        if (isRunning()) write(build(ControlProtocol.newRequestId()))
    }
}
