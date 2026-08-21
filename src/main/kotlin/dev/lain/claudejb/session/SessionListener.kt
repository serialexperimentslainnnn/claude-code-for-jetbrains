package dev.lain.claudejb.session

enum class AttentionReason { PERMISSION, TURN_DONE, ERROR, GUARD_BLOCKED }

sealed interface AttentionLanding {
    object Chat : AttentionLanding

    object Elsewhere : AttentionLanding

    data class Agent(val agentId: String) : AttentionLanding
}

interface SessionListener {
    fun onStateChanged() {}

    fun onMetadataChanged() {}

    fun onPermissionsChanged() {}

    fun onAttention(reason: AttentionReason, landing: AttentionLanding = AttentionLanding.Chat) {}

    fun onTitleChanged() {}

    fun onAgentsChanged(freshlyAdmitted: List<String>) {}
}
