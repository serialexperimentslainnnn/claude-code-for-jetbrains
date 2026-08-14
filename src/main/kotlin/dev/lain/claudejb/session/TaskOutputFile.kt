package dev.lain.claudejb.session

/**
 * Where a backgrounded command's output actually lives — for the moments the STRUCTURED field is not there.
 *
 * **Structured first, always.** `system/task_notification` carries `output_file` and the plugin has modelled
 * it since 3.0.0 ([TaskNotificationInfo]) without ever reading it; that is now the primary source and this
 * object is the fallback for the window BEFORE a task settles, where the only place the path appears is the
 * launching `tool_result`'s prose — and, when replaying a past session from its transcript, inside the
 * `<task-notification>` block, because a replay has no events to listen to.
 *
 * **The finding behind all of it.** The plugin showed "this task reported no output" for tasks that had
 * clearly produced some, on the reasoning that a backgrounded shell command publishes nothing until the model
 * queries it. That was wrong. The binary writes every background task's output to a file and says so:
 *
 * ```
 * Command running in background with ID: b3zr2hxpp. Output is being written to:
 * /tmp/claude-1000/<cwd-encoded>/<sessionId>/tasks/b3zr2hxpp.output.
 * You will be notified when it completes. To check interim output, use Read on that file path.
 * ```
 *
 * and, when it settles, inside the `<task-notification>` block as `<output-file>`. Verified against `claude`
 * 2.1.226 on a real session: the directory exists, one `.output` file per task, with the content.
 *
 * So the output is tailable exactly like a backgrounded agent's, it is live, and — because the file outlives
 * the IDE — it comes back after a restart. The parse is deliberately anchored on the binary's own wording and
 * on the `<output-file>` tag; anything else yields null and the caller says it has nothing rather than
 * guessing a path.
 */
object TaskOutputFile {

    /** `Output is being written to: <path>.` — the sentence the launching tool_result carries. */
    private val PROSE = Regex("""Output is being written to:\s*(\S+?)\.?(?:\s|$)""")

    /** `<output-file>…</output-file>` — the same path inside a `<task-notification>`. */
    private val TAG = Regex("""<output-file>\s*(.+?)\s*</output-file>""", RegexOption.DOT_MATCHES_ALL)

    /** The output file [text] names, or null when it names none. Pure. */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        TAG.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return PROSE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
