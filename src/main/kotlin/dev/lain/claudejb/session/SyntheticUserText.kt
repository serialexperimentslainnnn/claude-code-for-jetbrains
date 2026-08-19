package dev.lain.claudejb.session

internal object SyntheticUserText {

    sealed interface Kind {
        data class Prompt(val text: String) : Kind

        data class Command(val text: String) : Kind

        data class SystemNote(val text: String) : Kind

        data object Hidden : Kind
    }

    fun classify(text: String, isMeta: Boolean = false): Kind {
        val body = text.trim()
        if (body.isEmpty()) return Kind.Hidden
        return syntheticKind(withoutPreamble(body)) ?: if (isMeta) Kind.Hidden else Kind.Prompt(body)
    }

    private fun syntheticKind(body: String): Kind? = when (leadingTag(body)) {
        COMMAND_NAME, COMMAND_MESSAGE -> commandOf(body)?.let(Kind::Command) ?: Kind.Hidden
        LOCAL_COMMAND_STDOUT -> inner(body, LOCAL_COMMAND_STDOUT)?.let(Kind::SystemNote) ?: Kind.Hidden
        TASK_NOTIFICATION -> taskNotice(body)?.let(Kind::SystemNote) ?: Kind.Hidden
        AGENT_MESSAGE, CROSS_SESSION_MESSAGE -> agentMessage(body)?.let(Kind::SystemNote) ?: Kind.Hidden
        LOCAL_COMMAND_CAVEAT -> Kind.Hidden
        else -> null
    }

    private fun commandOf(body: String): String? {
        val name = inner(body, COMMAND_NAME) ?: inner(body, COMMAND_MESSAGE) ?: return null
        val args = inner(body, COMMAND_ARGS).orEmpty()
        val command = if (name.startsWith("/")) name else "/$name"
        return listOf(command, args).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun taskNotice(body: String): String? {
        val summary = inner(body, "summary")?.takeIf { it.isNotBlank() } ?: return null
        val status = inner(body, "status")?.takeIf { it.isNotBlank() }
        return if (status == null) summary else "Subagent $status: $summary"
    }

    private fun agentMessage(body: String): String? {
        val message = wrappedBody(body)?.takeIf { it.isNotBlank() } ?: return null
        return "Message from ${senderOf(body)}:\n$message"
    }

    private fun wrappedBody(body: String): String? {
        val open = LEADING_TAG.find(body) ?: return null
        val rest = body.substring(open.value.length)
        val close = rest.lastIndexOf("</${open.groupValues[1]}>")
        return (if (close < 0) rest else rest.take(close)).trim()
    }

    private fun senderOf(body: String): String {
        val open = LEADING_TAG.find(body)?.value.orEmpty()
        return attribute(open, "from-name") ?: attribute(open, "from") ?: UNNAMED_SENDER
    }

    private fun attribute(openingTag: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"").find(openingTag)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun withoutPreamble(body: String): String =
        PREAMBLES.firstOrNull(body::startsWith)?.let { body.substring(it.length) } ?: body

    private fun leadingTag(body: String): String? =
        LEADING_TAG.find(body)?.groupValues?.get(1)

    private fun inner(body: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()

    private val LEADING_TAG = Regex("^<([a-z][a-z0-9-]*)(?:[ \\t][^>\\r\\n]*)?>")

    private val PREAMBLES = listOf(
        "Another Claude session sent a message while you were working:\n",
        "Another Claude session sent a message:\n",
        "A peer session sent a message while you were working:\n",
    )

    private const val TASK_NOTIFICATION = "task-notification"
    private const val LOCAL_COMMAND_CAVEAT = "local-command-caveat"
    private const val LOCAL_COMMAND_STDOUT = "local-command-stdout"
    private const val COMMAND_NAME = "command-name"
    private const val COMMAND_MESSAGE = "command-message"
    private const val COMMAND_ARGS = "command-args"
    private const val AGENT_MESSAGE = "agent-message"
    private const val CROSS_SESSION_MESSAGE = "cross-session-message"

    private const val UNNAMED_SENDER = "another agent"
}
