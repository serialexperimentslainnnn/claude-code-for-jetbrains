package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.ToolOutput
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal class OutputTail(private val publish: (String) -> Unit = {}) {

    private val lock = Any()
    private val last = ArrayDeque<String>()

    var lines: Int = 0
        private set

    fun text(chunk: String) {
        if (chunk.isEmpty()) return
        chunk.removeSuffix("\n").removeSuffix("\r").split('\n').filterNot { it.startsWith(SERVICE_MESSAGE) }.forEach(::line)
    }

    fun line(text: String) {
        synchronized(lock) {
            lines++
            last.addLast(text)
            if (last.size > KEEP) last.removeFirst()
        }
        publish(text)
    }

    fun tail(count: Int): String = synchronized(lock) { last.takeLast(count).joinToString("\n") }

    companion object {

        const val KEEP = 200
        private const val DEFAULT_TAIL = 40
        private const val SERVICE_MESSAGE = "##teamcity["

        val TAIL = Param(
            "tail",
            "Lines of output to return from the end (default $DEFAULT_TAIL, up to $KEEP)",
            type = "integer",
            required = false,
        )

        fun toCard(project: Project, args: ToolArgs): OutputTail =
            OutputTail { text -> args.toolUseId?.let { ToolOutput.line(project, it, text) } }

        fun lines(args: ToolArgs): Int = args.int("tail", DEFAULT_TAIL).coerceIn(0, KEEP)
    }
}

internal fun JsonObjectBuilder.outcome(status: String, job: String, exitCode: Int?, tail: OutputTail, tailLines: Int) {
    put("status", status)
    put("job", job)
    put("exit_code", exitCode)
    tailOf(tail, tailLines)
}

internal fun JsonObjectBuilder.tailOf(tail: OutputTail, tailLines: Int) {
    put("lines", tail.lines)
    put("tail", tail.tail(tailLines))
}
