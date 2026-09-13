package dev.lain.claudejb.model.session.transcript

data class EntryDTO(
    val speaker: String,
    val text: String,
    val meta: String? = null,
    val toolUseId: String? = null,
    val parentToolUseId: String? = null,
    val atMillis: Long? = null,
    val filePath: String? = null,
    val commandText: String? = null,
    val messageText: String? = null,
    val inFlight: Boolean = false,
    val failed: Boolean = false,
    val blockedRule: String? = null,
    val bypassedRule: String? = null,
    val bypassAction: String? = null,
)

data class SessionRef(
    val sessionId: String,
    val title: String,
    val lastModified: Long,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val createdAt: String? = null,
)
