package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XStackFrame
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DebugSessions(private val project: Project) {

    fun resolve(name: String?): XDebugSession {
        val manager = XDebuggerManager.getInstance(project)
        val session = if (name == null) manager.currentSession else manager.debugSessions.firstOrNull { it.sessionName == name }
        return session ?: throw ToolException(
            if (name == null) {
                "no debug session; start one with session(action=start, name=<run configuration>)"
            } else {
                "no debug session named $name; session(action=list) shows the open ones"
            },
        )
    }

    suspend fun awaitPause(session: XDebugSession, waitSeconds: Int, move: (suspend () -> Unit)? = null): Boolean {
        val paused = CompletableDeferred<Boolean>()
        val subscription = Disposer.newDisposable("claude debug pause")
        try {
            session.addSessionListener(
                object : XDebugSessionListener {
                    override fun sessionPaused() {
                        paused.complete(true)
                    }

                    override fun sessionStopped() {
                        paused.complete(false)
                    }
                },
                subscription,
            )
            if (move != null) {
                move()
            } else if (session.isSuspended) {
                return true
            }
            if (session.isStopped) return false
            return withTimeoutOrNull(waitSeconds * MILLIS) { paused.await() } ?: false
        } finally {
            Disposer.dispose(subscription)
        }
    }

    suspend fun status(session: XDebugSession, maxFrames: Int, maxVariables: Int): JsonObject {
        val suspended = session.isSuspended
        val stack = session.suspendContext?.activeExecutionStack?.takeIf { suspended }
        val frames = if (stack == null) emptyList() else DebugValues.stackFrames(stack, maxFrames)
        val current = session.currentStackFrame?.takeIf { suspended }
        val variables = if (current == null) emptyList() else DebugValues.rows(DebugValues.children(current, maxVariables))
        val at = spot(session.currentPosition?.takeIf { suspended })
        return buildJsonObject {
            put("name", session.sessionName)
            put("suspended", suspended)
            put("stopped", session.isStopped)
            put("file", at.file)
            put("line", at.line)
            put("text", at.text)
            put("frames", buildJsonArray { frames.forEachIndexed { index, frame -> add(frameRow(index, frame)) } })
            put("variables", buildJsonArray { variables.forEach { add(it) } })
        }
    }

    fun frameRow(index: Int, frame: XStackFrame): JsonObject = buildJsonObject {
        val position = frame.sourcePosition
        put("index", index)
        put("function", RenderedText().also(frame::customizePresentation).text)
        put("file", position?.let { Locations.relative(project, it.file) } ?: "")
        put("line", position?.line?.plus(1) ?: 0)
    }

    private class Spot(val file: String, val line: Int, val text: String)

    private suspend fun spot(position: XSourcePosition?): Spot {
        if (position == null) return Spot("", 0, "")
        val text = readAction {
            val document = FileDocumentManager.getInstance().getDocument(position.file)
            if (document != null && position.line in 0 until document.lineCount) Locations.lineText(document, position.line) else ""
        }
        return Spot(Locations.relative(project, position.file), position.line + 1, text)
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
