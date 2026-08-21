package dev.lain.claudejb.session

enum class AttentionReason { PERMISSION, TURN_DONE, ERROR, GUARD_BLOCKED }

interface SessionListener {
    fun onStateChanged() {}

    fun onMetadataChanged() {}

    fun onPermissionsChanged() {}

    fun onAttention(reason: AttentionReason) {}

    fun onTitleChanged() {}

    fun onAgentsChanged(freshlyAdmitted: List<String>) {}
}
