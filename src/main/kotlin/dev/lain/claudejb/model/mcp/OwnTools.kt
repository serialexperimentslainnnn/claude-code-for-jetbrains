package dev.lain.claudejb.model.mcp

import dev.lain.claudejb.model.mcp.toon.Toon
import dev.lain.claudejb.model.mcp.toon.ToonException
import dev.lain.claudejb.model.mcp.toon.ToonOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

object OwnTools {

    class Call(val server: String, val meta: String, val argument: String?)

    class Review(val toolName: String, val input: JsonObject)

    const val TOOL_USE_ID_KEY = "claudecode/toolUseId"
    const val READ_FILE = "read_file"
    const val INSERT = "InsertText"

    private val META_TOOL = Regex("^mcp__([a-z]+)__(domains|tools|run)$")
    private val EDITS = setOf("replace_text", "insert_text", "create_file", "write_file")
    private val SUBJECT = listOf("path", "name", "query", "hash")

    fun parse(toolName: String, input: JsonObject): Call? {
        val (server, meta) = META_TOOL.matchEntire(toolName)?.destructured ?: return null
        val argument = when (meta) {
            MetaTools.RUN.name -> text(input, "tool")
            MetaTools.TOOLS.name -> text(input, "domain")
            else -> null
        }
        return Call(server, meta, argument)
    }

    fun isOwn(toolName: String?): Boolean = toolName != null && META_TOOL.matches(toolName)

    fun display(toolName: String, input: JsonObject): String? = parse(toolName, input)?.let { label(it, argsOf(input)) }

    fun argsOf(input: JsonObject): JsonObject = (input["args"] as? JsonObject) ?: JsonObject(emptyMap())

    fun guardInput(input: JsonObject): JsonObject {
        val tool = input["tool"] as? JsonPrimitive ?: return input
        val args = input["args"] as? JsonObject ?: return input
        if (!tool.isString) return input
        return JsonObject(args + ("tool" to tool))
    }

    fun label(call: Call, args: JsonObject = JsonObject(emptyMap())): String = when (call.meta) {
        MetaTools.RUN.name -> call.server + " ▸ " + (call.argument ?: "?") + (subject(args)?.let { " ▸ $it" } ?: "")
        MetaTools.TOOLS.name -> call.server + " ▸ tools(" + (call.argument ?: "?") + ")"
        else -> call.server + " ▸ domains"
    }

    private fun subject(args: JsonObject): String? = SUBJECT.firstNotNullOfOrNull { text(args, it) }

    fun path(args: JsonObject): String? = text(args, "path")

    fun isEdit(call: Call): Boolean = call.meta == MetaTools.RUN.name && call.argument in EDITS

    fun isRead(call: Call): Boolean = call.meta == MetaTools.RUN.name && call.argument == READ_FILE

    fun argsToon(args: JsonObject): String? = args.takeIf { it.isNotEmpty() }?.let { Toon.encode(it) }

    fun reviewAs(call: Call, args: JsonObject, projectRoot: String?): Review? {
        if (!isEdit(call)) return null
        val path = text(args, "path") ?: return null
        val absolute = Path.of(path).let { if (it.isAbsolute || projectRoot == null) it else Path.of(projectRoot).resolve(it) }
        val reviewed = buildJsonObject {
            put("file_path", absolute.normalize().toString())
            args.filterKeys { it != "path" }.forEach { (key, value) -> put(key, value) }
        }
        val kind = when (call.argument) {
            "replace_text" -> "Edit"
            "insert_text" -> INSERT
            else -> "Write"
        }
        return Review(kind, reviewed)
    }

    fun asWrite(review: Review, before: String): Review? {
        val line = (review.input["line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val after = runCatching { TextEdit.insertAt(before, line, text(review.input, "content") ?: "").text }.getOrNull() ?: return null
        return Review(
            "Write",
            buildJsonObject {
                put("file_path", review.input.getValue("file_path"))
                put("content", after)
            },
        )
    }

    fun decodeResult(text: String): JsonElement? = try {
        Toon.decode(text, ToonOptions(strict = false))
    } catch (ignored: ToonException) {
        null
    }

    fun readText(decoded: JsonElement?): String? = (decoded as? JsonObject)?.let { text(it, "text") }

    private fun text(input: JsonObject, key: String): String? = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
