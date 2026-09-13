package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class BreakpointTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "breakpoints",
        "Line breakpoints as the gutter shows them: add, remove, list",
        listOf(Tool(BREAKPOINT, ::breakpoint)),
    )

    private suspend fun breakpoint(args: ToolArgs): ToolResult = when (val action = args.string("action")) {
        "add" -> add(args)
        "remove" -> remove(args)
        "list" -> list(args.int("max", DEFAULT_MAX))
        else -> throw ToolException("action must be add, remove or list, not $action")
    }

    private suspend fun add(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val line = lineIndex(args)
        val condition = args.optionalString("condition")
        val temporary = args.boolean("temporary", false)
        val (file, type) = readAction {
            val file = ReadTools.resolveFile(project, path)
            file to XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(file, line, project) }
        }
        if (type == null) throw ToolException("no debugger can break at $path:${line + 1}")
        val added = edtWriteAction {
            manager().addAt(type, file, line, temporary).also { breakpoint -> condition?.let(breakpoint::setCondition) }
        }
        return ToolResult.toon(row(added))
    }

    private fun <P : XBreakpointProperties<*>> XBreakpointManager.addAt(
        type: XLineBreakpointType<P>,
        file: VirtualFile,
        line: Int,
        temporary: Boolean,
    ): XLineBreakpoint<P> = addLineBreakpoint(type, file.url, line, type.createBreakpointProperties(file, line))
        .also { it.isTemporary = temporary }

    private suspend fun remove(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val line = lineIndex(args)
        val found = readAction {
            val url = ReadTools.resolveFile(project, path).url
            lineBreakpoints().filter { it.fileUrl == url && it.line == line }
        }
        edtWriteAction { found.forEach(manager()::removeBreakpoint) }
        return ToolResult.toon(
            buildJsonObject {
                put("file", path)
                put("line", line + 1)
                put("removed", found.size)
            },
        )
    }

    private suspend fun list(max: Int): ToolResult {
        val all = readAction { lineBreakpoints() }
        return ToolResult.toon(
            buildJsonObject {
                put("count", all.size)
                put("truncated", all.size > max)
                put("breakpoints", buildJsonArray { all.take(max).forEach { add(row(it)) } })
            },
        )
    }

    private fun lineIndex(args: ToolArgs): Int {
        val line = args.int("line", 0)
        if (line < 1) throw ToolException("line is 1-based and required")
        return line - 1
    }

    private fun manager(): XBreakpointManager = XDebuggerManager.getInstance(project).breakpointManager

    private fun lineBreakpoints(): List<XLineBreakpoint<*>> = manager().allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()

    private fun row(breakpoint: XLineBreakpoint<*>): JsonObject = buildJsonObject {
        val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl)
        put("file", file?.let { Locations.relative(project, it) } ?: breakpoint.fileUrl)
        put("line", breakpoint.line + 1)
        put("enabled", breakpoint.isEnabled)
        put("condition", breakpoint.conditionExpression?.expression ?: "")
        put("temporary", breakpoint.isTemporary)
    }

    companion object {

        private const val DEFAULT_MAX = 100

        val BREAKPOINT = ToolSpec(
            "breakpoint",
            "Line breakpoints as the gutter shows them. add puts one at path:line, with an optional condition and temporary " +
                "to drop it once hit; remove clears every breakpoint on that line; list shows them all with file and line.",
            listOf(
                Param("action", "add, remove or list"),
                Param("path", "File path, absolute or relative to the project root (add, remove)", required = false),
                Param("line", "1-based line (add, remove)", type = "integer", required = false),
                Param("condition", "Expression that must be true for the breakpoint to stop (add)", required = false),
                Param("temporary", "true to remove the breakpoint once it is hit (add, default false)", type = "boolean", required = false),
                Param("max", "Maximum breakpoints to list (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
            mutates = true,
        )
    }
}
