package dev.lain.claudejb.controller.mcp.tools.run

import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolSpec

internal object DebugSpecs {

    const val DEFAULT_MAX = 50
    const val DEFAULT_FRAMES = 10
    const val STEP_FRAMES = 5
    const val DEFAULT_VARIABLES = 30
    const val DEFAULT_START_WAIT = 45
    const val DEFAULT_STEP_WAIT = 30
    const val MAX_WAIT = 55
    const val RUNNING = "the session is running; pause it with step(kind=pause) or wait for a stop with step(kind=wait)"

    fun waitSeconds(args: ToolArgs, default: Int): Int =
        args.int("wait", default).also { if (it !in 1..MAX_WAIT) throw ToolException("wait must be between 1 and $MAX_WAIT seconds") }

    private val SESSION_NAME = Param("name", "Session name (default: the current session)", required = false)

    val SESSION = ToolSpec(
        "session",
        "A debug session. start runs a configuration with the debugger and waits for its first stop, stop ends a session, " +
            "status says whether it is suspended and where (frames and variables included), list names the open sessions.",
        listOf(
            Param("action", "start, stop, status or list"),
            Param("name", "Run configuration name (start); session name otherwise (default: the current session)", required = false),
            Param(
                "wait",
                "Seconds to wait for the session to start, then to stop (start; 1..$MAX_WAIT, default $DEFAULT_START_WAIT)",
                type = "integer",
                required = false,
            ),
            Param("max_frames", "Maximum frames in the status (default $DEFAULT_FRAMES)", type = "integer", required = false),
            Param("max_variables", "Maximum variables in the status (default $DEFAULT_VARIABLES)", type = "integer", required = false),
        ),
        mutates = true,
    )

    const val STEP_KINDS = "over, into, out, force_into, smart_into, resume, pause, mute, run_to or wait"

    val STEP = ToolSpec(
        "step",
        "Moves the suspended session and waits for the next stop: over, into, out, force_into (into a library), smart_into " +
            "(the IDE's chooser when a line holds several calls), resume, pause, run_to (a path and line) or wait (only " +
            "waits); mute toggles every breakpoint. Answers with the session status; suspended false means nothing " +
            "stopped within wait.",
        listOf(
            Param("kind", STEP_KINDS),
            Param("path", "File path for run_to, absolute or relative to the project root", required = false),
            Param("line", "1-based line for run_to", type = "integer", required = false),
            SESSION_NAME,
            Param(
                "wait",
                "Seconds to wait for the next stop (1..$MAX_WAIT, default $DEFAULT_STEP_WAIT)",
                type = "integer",
                required = false,
            ),
        ),
        mutates = true,
    )

    val FRAMES = ToolSpec(
        "frames",
        "The threads of a suspended session and the stack of one of them; frame selects a frame as current for values.",
        listOf(
            Param("thread", "Thread index (default: the active thread)", type = "integer", required = false),
            Param("frame", "Frame index to select as current", type = "integer", required = false),
            Param("max", "Maximum frames and threads to return (default $DEFAULT_MAX)", type = "integer", required = false),
            SESSION_NAME,
        ),
    )

    val VALUES = ToolSpec(
        "values",
        "Variables of a frame in the current suspended session: list them, eval an expression in the debuggee, " +
            "or set a variable to a new value.",
        listOf(
            Param("action", "list, eval or set"),
            Param("code", "Expression to evaluate in the debuggee (eval)", required = false),
            Param("name", "Variable to set (set)", required = false),
            Param("value", "New value as an expression (set)", required = false),
            Param("frame", "Frame index (default: the current frame)", type = "integer", required = false),
            Param("max", "Maximum variables to return (default $DEFAULT_MAX)", type = "integer", required = false),
        ),
        mutates = true,
    )
}
