package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonObject

/**
 * How a tool call is named in the transcript, and what the IDE has to refresh afterwards.
 *
 * PURE by design — no IDE, no session, no project: the project root is passed in. That is what lets the
 * live stream and the on-disk restore ([SessionTranscriptReader]) label the same call identically instead
 * of each growing its own formatter, which is how a restored transcript ends up disagreeing with the one
 * the user just watched.
 */
object ToolNaming {

    /** Standard built-in tools, for the allow/deny checkboxes in Settings. */
    val BUILTIN_TOOLS = listOf(
        "Bash", "Read", "Edit", "Write", "Glob", "Grep",
        "WebFetch", "WebSearch", "Task", "TodoWrite", "NotebookEdit",
    )

    /** Tools whose `file_path` names a project file the transcript can hyperlink (jump-to-code). */
    val FILE_TOOLS = setOf("Read", "Edit", "Write", "MultiEdit", "NotebookEdit")

    /**
     * A name that reads like a mutation, for tools we do NOT know — i.e. MCP ones (`replace_text_in_file`,
     * `create_new_file`, `apply_patch`, `reformat_file`, `rename_refactoring`…). Applied ONLY to unknown tools:
     * on a built-in it would misfire (`TodoWrite` contains "write" and touches no file at all).
     *
     * Generous on purpose. A false positive costs one async VFS refresh the IDE coalesces away; a false
     * negative means the IDE keeps showing stale files, so we err towards refreshing.
     */
    private val MUTATING_TOOL_NAME = Regex(
        // Mutations AND executors: an MCP `execute_terminal_command` / `run_configuration` can write anything,
        // just like Bash — so it must trigger a project-tree refresh too (a real gap the code review caught).
        "(edit|write|create|delete|remove|move|rename|patch|format|refactor|replace|insert|save|" +
            "exec|execute|run|terminal|shell|command|apply|generate|build|install)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when [toolName] may have changed files we cannot name — so the IDE must re-scan the project tree
     * rather than a known list of paths.
     *
     * `Bash` always qualifies (a `mv`, a formatter, a codegen script). The file tools never do: their paths are
     * known and refreshed exactly. Every other built-in reads. Anything else is an MCP tool, judged by name.
     */
    fun mayHaveWrittenUnknownFiles(toolName: String?): Boolean {
        val name = toolName?.takeIf { it.isNotBlank() } ?: return false
        if (name == "Bash") return true
        if (name in FILE_TOOLS || name in BUILTIN_TOOLS) return false
        return MUTATING_TOOL_NAME.containsMatchIn(name)
    }

    /**
     * The tool call's file argument as a path **relative to [projectRoot]**, or null when the tool takes no
     * file / the path escapes the project (an absolute path outside the root stays absolute — we show the
     * truth, and the jump-to-code gate refuses to open it anyway).
     */
    fun toolFilePath(name: String, input: JsonObject, projectRoot: String?): String? {
        if (name !in FILE_TOOLS) return null
        val path = input.str("file_path")?.takeIf { it.isNotBlank() } ?: return null
        return relativizeToRoot(path, projectRoot)
    }

    /** `/abs/root/src/Foo.kt` + root `/abs/root` → `src/Foo.kt`. Leaves anything outside the root untouched. */
    fun relativizeToRoot(path: String, projectRoot: String?): String {
        val root = projectRoot?.takeIf { it.isNotBlank() } ?: return path
        // Compare with the platform separator normalised, so Windows paths relativise too.
        val p = path.replace('\\', '/')
        val r = root.trimEnd('/', '\\').replace('\\', '/')
        if (!p.startsWith("$r/")) return path
        return p.removePrefix("$r/")
    }

    /**
     * Concise one-line representation of a tool call, mirroring the CLI's "Tool(arg)" bullets. File tools show
     * the path **relative to the project** — `Read(src/main/kotlin/permission/PermissionBroker.kt)` — rather
     * than a bare file name, so the row says *which* file and the frontend can hyperlink it.
     */
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
