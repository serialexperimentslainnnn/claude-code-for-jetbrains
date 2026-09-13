package dev.lain.claudejb.controller.session

import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.controller.session.guard.GuardRestore
import dev.lain.claudejb.controller.session.history.SessionHistory
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.session.history.SessionTitling
import dev.lain.claudejb.model.session.transcript.EntryDTO
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.ToolState

class SessionPersistence(
    private val s: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val fireTitleChanged: () -> Unit,
) {

    private val titling = SessionTitling(
        currentTitle = { s.title },
        setTitle = { s.title = it },
        fireTitleChanged = { edt(fireTitleChanged) },
        requestGeneratedTitle = s.queries::requestGeneratedTitle,
    )

    fun restore(savedSessionId: String, dtos: List<EntryDTO>, fork: Boolean = false) {
        s.sessionId = savedSessionId
        s.launch = s.launch.copy(fork = fork)
        s.agentScanner.restoreAdmitted(onTasksReplayed = fireState)
        s.prompts.forgetTurn()
        val saved = s.guard.restore(savedSessionId)
        val withGuard = GuardRestore.reinstate(dtos, GuardRestore.raisedInThisChat(dtos, saved))
        edt {
            s.transcript.clear()
            withGuard.forEach(::replay)
        }
    }

    private fun replay(dto: EntryDTO) {
        val speaker = runCatching { Speaker.valueOf(dto.speaker) }.getOrNull() ?: return
        s.transcript.add(
            speaker,
            dto.text,
            meta = dto.meta,
            toolUseId = dto.toolUseId,
            parentToolUseId = dto.parentToolUseId,
            filePath = dto.filePath,
            commandText = dto.commandText,
            messageText = dto.messageText,
            blockedRule = dto.blockedRule,
            bypassedRule = dto.bypassedRule,
            bypassAction = dto.bypassAction,
            toolState = when {
                dto.failed || dto.inFlight -> ToolState.ERROR
                dto.meta == "Task" || dto.meta == "Agent" -> ToolState.ERROR
                else -> ToolState.FINISHED
            },
        )
    }

    fun recordOpenAndTitle(id: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (!s.gitIntegration) titling.resolve(id)
            SessionHistory.getInstance(project).setOpenSessions(
                ChatSessionManager.getInstance(project).all()
                    .filterNot { it.gitIntegration }
                    .mapNotNull { it.sessionId },
            )
        }
    }

    fun rename(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        if (s.isRunning()) s.write(ControlProtocol.renameSessionRequest(ControlProtocol.newRequestId(), trimmed))
        titling.markRenamed()
        s.title = trimmed
        edt(fireTitleChanged)
    }
}
