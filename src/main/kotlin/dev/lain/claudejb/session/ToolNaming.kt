package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonObject

object ToolNaming {

    val BUILTIN_TOOLS = listOf(
        "Bash", "Read", "Edit", "Write", "Glob", "Grep",
        "WebFetch", "WebSearch", "Task", "TodoWrite", "NotebookEdit",
    )

    val FILE_TOOLS = setOf("Read", "Edit", "Write", "MultiEdit", "NotebookEdit")

    private val MUTATING_TOOL_NAME = Regex(
        "(edit|write|create|delete|remove|move|rename|patch|format|refactor|replace|insert|save|" +
            "exec|execute|run|terminal|shell|command|apply|generate|build|install)",
        RegexOption.IGNORE_CASE,
    )

    fun mayHaveWrittenUnknownFiles(toolName: String?): Boolean {
        val name = toolName?.takeIf { it.isNotBlank() } ?: return false
        if (name == "Bash") return true
        if (name in FILE_TOOLS || name in BUILTIN_TOOLS) return false
        return MUTATING_TOOL_NAME.containsMatchIn(name)
    }

    fun toolFilePath(name: String, input: JsonObject, projectRoot: String?): String? {
        if (name !in FILE_TOOLS) return null
        val path = input.str("file_path")?.takeIf { it.isNotBlank() } ?: return null
        return relativizeToRoot(path, projectRoot)
    }

    fun relativizeToRoot(path: String, projectRoot: String?): String {
        val root = projectRoot?.takeIf { it.isNotBlank() } ?: return path
        val p = path.replace('\\', '/')
        val r = root.trimEnd('/', '\\').replace('\\', '/')
        if (!p.startsWith("$r/")) return path
        return p.removePrefix("$r/")
    }

    fun formatToolUse(name: String, input: JsonObject, projectRoot: String? = null): String {
        val arg = when (name) {
            "Bash" -> input.str("command")
            in FILE_TOOLS -> toolFilePath(name, input, projectRoot)
            "Glob", "Grep" -> input.str("pattern")
            "Task" -> input.str("description")
            "WebFetch" -> input.str("url")
            "WebSearch" -> input.str("query")
            else -> input.str("file_path")?.let { relativizeToRoot(it, projectRoot) } ?: input.str("path")
        }
        return if (!arg.isNullOrBlank()) "$name($arg)" else name
    }
}
