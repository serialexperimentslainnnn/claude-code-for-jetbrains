package dev.lain.claudejb.model.mcp

object TextEdit {

    class Outcome(val text: String, val count: Int, val lines: List<Int>)

    fun replace(text: String, old: String, new: String, all: Boolean): Outcome {
        val starts = chosen(text, occurrences(text, old, new), all)
        val out = StringBuilder(text.length + (new.length - old.length) * starts.size)
        val lines = ArrayList<Int>(starts.size)
        var from = 0
        for (start in starts) {
            out.append(text, from, start)
            lines += newlines(out) + 1
            out.append(new)
            from = start + old.length
        }
        out.append(text, from, text.length)
        return Outcome(out.toString(), starts.size, lines)
    }

    fun insertAt(text: String, line: Int, content: String): Outcome {
        val lines = newlines(text) + 1
        if (line < 1 || line > lines + 1) throw ToolException("line $line is outside the file ($lines lines; line ${lines + 1} appends)")
        if (content.isEmpty()) throw ToolException("content must not be empty")
        val body = if (content.endsWith('\n')) content else content + "\n"
        val out = StringBuilder(text.length + body.length + 1)
        if (line > lines) {
            out.append(text)
            if (text.isNotEmpty() && !text.endsWith('\n')) out.append('\n')
            out.append(body)
        } else {
            val offset = offsetOfLine(text, line)
            out.append(text, 0, offset).append(body).append(text, offset, text.length)
        }
        return Outcome(out.toString(), newlines(body), listOf(line))
    }

    fun whole(text: String, content: String): Outcome {
        if (text == content) throw ToolException("the file already has exactly that content; nothing to change")
        return Outcome(content, 1, emptyList())
    }

    private fun occurrences(text: String, old: String, new: String): List<Int> {
        if (old.isEmpty()) throw ToolException("old_string must not be empty")
        if (old == new) throw ToolException("old_string and new_string are identical; nothing to change")
        val starts = ArrayList<Int>()
        var at = text.indexOf(old)
        while (at >= 0) {
            starts += at
            at = text.indexOf(old, at + old.length)
        }
        return starts
    }

    private fun chosen(text: String, starts: List<Int>, all: Boolean): List<Int> {
        if (starts.isEmpty()) {
            throw ToolException("old_string not found; it is matched literally, unsaved editor text included")
        }
        if (starts.size > 1 && !all) {
            val lines = starts.joinToString(", ") { (newlines(text.subSequence(0, it)) + 1).toString() }
            throw ToolException("old_string matches ${starts.size} times (lines $lines); add context to make it unique or set replace_all")
        }
        return starts
    }

    private fun offsetOfLine(text: String, line: Int): Int {
        var offset = 0
        repeat(line - 1) { offset = text.indexOf('\n', offset) + 1 }
        return offset
    }

    private fun newlines(text: CharSequence): Int = text.count { it == '\n' }
}
