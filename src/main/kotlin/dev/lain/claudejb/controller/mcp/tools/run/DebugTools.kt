package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
import dev.lain.claudejb.controller.mcp.tools.run.DebugSpecs.DEFAULT_FRAMES
import dev.lain.claudejb.controller.mcp.tools.run.DebugSpecs.DEFAULT_MAX
import dev.lain.claudejb.controller.mcp.tools.run.DebugSpecs.DEFAULT_STEP_WAIT
import dev.lain.claudejb.controller.mcp.tools.run.DebugSpecs.DEFAULT_VARIABLES
import dev.lain.claudejb.controller.mcp.tools.run.DebugSpecs.STEP_FRAMES
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DebugTools(private val project: Project) {

    private val sessions = DebugSessions(project)
    private val starter = DebugStart(project, sessions)

    fun domain(): ToolDomain = ToolDomain(
        "debug",
        "The debugger: start or stop a session, step, read frames and threads, inspect and change values",
        listOf(
            Tool(DebugSpecs.SESSION, ::session),
            Tool(DebugSpecs.STEP, ::step),
            Tool(DebugSpecs.FRAMES, ::frames),
            Tool(DebugSpecs.VALUES, ::values),
        ),
    )

    private suspend fun session(args: ToolArgs): ToolResult {
        val maxFrames = args.int("max_frames", DEFAULT_FRAMES)
        val maxVariables = args.int("max_variables", DEFAULT_VARIABLES)
        return when (val action = args.string("action")) {
            "start" -> ToolResult.toon(sessions.status(starter.start(args), maxFrames, maxVariables))
            "stop" -> stop(sessions.resolve(args.optionalString("name")))
            "status" -> ToolResult.toon(sessions.status(sessions.resolve(args.optionalString("name")), maxFrames, maxVariables))
            "list" -> list()
            else -> throw ToolException("action must be start, stop, status or list, not $action")
        }
    }

    private suspend fun stop(session: XDebugSession): ToolResult {
        withContext(Dispatchers.EDT) { session.stop() }
        return ToolResult.toon(
            buildJsonObject {
                put("name", session.sessionName)
                put("stopped", true)
            },
        )
    }

    private fun list(): ToolResult {
        val manager = XDebuggerManager.getInstance(project)
        val rows = manager.debugSessions.map { session ->
            buildJsonObject {
                put("name", session.sessionName)
                put("suspended", session.isSuspended)
                put("current", session === manager.currentSession)
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put("sessions", array(rows))
            },
        )
    }

    private suspend fun step(args: ToolArgs): ToolResult {
        val kind = args.string("kind")
        val session = sessions.resolve(args.optionalString("name"))
        val wait = DebugSpecs.waitSeconds(args, DEFAULT_STEP_WAIT)
        if (kind == "mute") {
            withContext(Dispatchers.EDT) { session.setBreakpointMuted(!session.areBreakpointsMuted()) }
            return ToolResult.toon(sessions.status(session, STEP_FRAMES, DEFAULT_VARIABLES))
        }
        val move: ((XDebugSession) -> Unit)? = when (kind) {
            "wait" -> null
            "pause" -> if (session.isSuspended) null else XDebugSession::pause
            "run_to" -> runTo(args)
            else -> MOVES[kind] ?: throw ToolException("kind must be one of ${DebugSpecs.STEP_KINDS}, not $kind")
        }
        if (move != null && kind != "pause" && !session.isSuspended) throw ToolException(DebugSpecs.RUNNING)
        sessions.awaitPause(session, wait, move?.let { act -> suspend { withContext(Dispatchers.EDT) { act(session) } } })
        return ToolResult.toon(sessions.status(session, STEP_FRAMES, DEFAULT_VARIABLES))
    }

    private suspend fun runTo(args: ToolArgs): (XDebugSession) -> Unit {
        val path = args.string("path")
        val line = args.int("line", 0)
        val position = readAction {
            XDebuggerUtil.getInstance().createPosition(ReadTools.resolveFile(project, path), line - 1)
        } ?: throw ToolException("no position at $path:$line")
        return { it.runToPosition(position, false) }
    }

    private suspend fun frames(args: ToolArgs): ToolResult {
        val session = sessions.resolve(args.optionalString("name"))
        val max = args.int("max", DEFAULT_MAX)
        val context = session.suspendContext?.takeIf { session.isSuspended } ?: throw ToolException(DebugSpecs.RUNNING)
        val stacks = context.executionStacks
        val active = stacks.indexOfFirst { it === context.activeExecutionStack }.coerceAtLeast(0)
        val thread = args.int("thread", active)
        val stack = stacks.getOrNull(thread) ?: throw ToolException("thread $thread is outside 0..${stacks.size - 1}")
        val frames = DebugValues.stackFrames(stack, max + 1)
        val shown = frames.take(max)
        select(session, stack, shown, args.int("frame", -1))
        return ToolResult.toon(
            buildJsonObject {
                put("thread", thread)
                put("selected", shown.indexOfFirst { it === session.currentStackFrame })
                put("threads_count", stacks.size)
                put("threads", array(stacks.take(max).mapIndexed { index, s -> threadRow(index, s, index == active) }))
                put("count", shown.size)
                put("truncated", frames.size > max)
                put("frames", array(shown.mapIndexed(sessions::frameRow)))
            },
        )
    }

    private fun threadRow(index: Int, stack: XExecutionStack, active: Boolean): JsonObject = buildJsonObject {
        put("index", index)
        put("name", stack.displayName)
        put("active", active)
    }

    private suspend fun select(session: XDebugSession, stack: XExecutionStack, frames: List<XStackFrame>, pick: Int) {
        if (pick < 0) return
        val frame = frames.getOrNull(pick) ?: throw ToolException("frame $pick is beyond the ${frames.size} frames listed")
        withContext(Dispatchers.EDT) { session.setCurrentStackFrame(stack, frame) }
    }

    private suspend fun values(args: ToolArgs): ToolResult {
        val session = sessions.resolve(null)
        val max = args.int("max", DEFAULT_MAX)
        val frame = frameOf(session, args.int("frame", -1))
        return when (val action = args.string("action")) {
            "list" -> listValues(frame, max)
            "eval" -> eval(frame, args.string("code"))
            "set" -> set(frame, args.string("name"), args.string("value"))
            else -> throw ToolException("action must be list, eval or set, not $action")
        }
    }

    private suspend fun frameOf(session: XDebugSession, pick: Int): XStackFrame {
        val context = session.suspendContext?.takeIf { session.isSuspended } ?: throw ToolException(DebugSpecs.RUNNING)
        val frame = if (pick < 0) {
            session.currentStackFrame
        } else {
            context.activeExecutionStack?.let { DebugValues.stackFrames(it, pick + 1).getOrNull(pick) }
        }
        return frame ?: throw ToolException(if (pick < 0) "the session has no current frame" else "frame $pick is beyond the stack")
    }

    private suspend fun listValues(frame: XStackFrame, max: Int): ToolResult {
        val children = DebugValues.children(frame, max + 1)
        val rows = DebugValues.rows(children.take(max))
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put("truncated", children.size > max)
                put("values", array(rows))
            },
        )
    }

    private suspend fun eval(frame: XStackFrame, code: String): ToolResult {
        val shown = DebugValues.present(DebugValues.evaluate(frame, code))
        return ToolResult.toon(
            buildJsonObject {
                put("code", code)
                put("type", shown.type)
                put("value", shown.value)
                put("children", shown.hasChildren)
            },
        )
    }

    private suspend fun set(frame: XStackFrame, name: String, text: String): ToolResult {
        val target = variable(frame, name) ?: throw ToolException("no variable named $name in this frame; values(action=list) shows them")
        DebugValues.set(target, name, text)
        val shown = DebugValues.present(variable(frame, name) ?: target)
        return ToolResult.toon(
            buildJsonObject {
                put("name", name)
                put("type", shown.type)
                put("value", shown.value)
            },
        )
    }

    private suspend fun variable(frame: XStackFrame, name: String) =
        DebugValues.children(frame, LOOKUP).firstOrNull { it.first == name }?.second

    private fun array(rows: List<JsonObject>) = buildJsonArray { rows.forEach { add(it) } }

    private companion object {
        const val LOOKUP = 500
        const val SMART_STEP_INTO = "SmartStepInto"

        val MOVES: Map<String, (XDebugSession) -> Unit> = mapOf(
            "resume" to XDebugSession::resume,
            "over" to { s -> s.stepOver(false) },
            "into" to XDebugSession::stepInto,
            "out" to XDebugSession::stepOut,
            "force_into" to XDebugSession::forceStepInto,
            "smart_into" to { _ -> smartStepInto() },
        )

        fun smartStepInto() {
            val action = ActionManager.getInstance().getAction(SMART_STEP_INTO)
                ?: throw ToolException("this IDE has no $SMART_STEP_INTO action")
            ActionManager.getInstance().tryToExecute(action, null, null, ActionPlaces.DEBUGGER_TOOLBAR, true)
        }
    }
}
