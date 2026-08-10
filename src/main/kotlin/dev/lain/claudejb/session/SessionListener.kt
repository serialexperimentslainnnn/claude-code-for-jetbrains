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

    /**
     * The agent tree changed: a scan of the binary's per-subagent files finished.
     *
     * [freshlyAdmitted] are the agents seen for the FIRST time in this scan, which is what the UI uses to
     * open a tab, blink it and notify **once** — deriving that by diffing snapshots in the panel would put
     * the same bookkeeping in every listener. An empty list still means "re-read the tree": an existing
     * agent's transcript or status may have moved. Fired on the EDT.
     */
    fun onAgentsChanged(freshlyAdmitted: List<String>) {}
}
