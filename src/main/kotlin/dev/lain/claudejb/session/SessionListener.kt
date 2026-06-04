package dev.lain.claudejb.session

/** Why a background session is asking for the user's attention. */
enum class AttentionReason { PERMISSION, TURN_DONE, ERROR }

/** UI observer for session state and metadata changes. All callbacks are fired on the EDT. */
interface SessionListener {
    /** Running/idle, turn active, queue, current model/effort/mode/thinking changed. */
    fun onStateChanged() {}

    /** initialize handshake landed: commands, models, agents, output styles, account are now available. */
    fun onMetadataChanged() {}

    /** The set of pending permission requests (awaiting the user's Accept/Reject) changed. */
    fun onPermissionsChanged() {}

    /** A background session wants attention (new permission, finished turn, or error). Fired on the EDT. */
    fun onAttention(reason: AttentionReason) {}

    /** The session title changed (the binary generated/renamed it); the tab should relabel. Fired on the EDT. */
    fun onTitleChanged() {}
}
