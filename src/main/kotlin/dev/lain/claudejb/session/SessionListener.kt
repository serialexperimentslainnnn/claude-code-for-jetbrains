package dev.lain.claudejb.session

/** UI observer for session state and metadata changes. All callbacks are fired on the EDT. */
interface SessionListener {
    /** Running/idle, turn active, queue, current model/effort/mode/thinking changed. */
    fun onStateChanged() {}

    /** initialize handshake landed: commands, models, agents, output styles, account are now available. */
    fun onMetadataChanged() {}

    /** The set of pending permission requests (awaiting the user's Accept/Reject) changed. */
    fun onPermissionsChanged() {}
}
