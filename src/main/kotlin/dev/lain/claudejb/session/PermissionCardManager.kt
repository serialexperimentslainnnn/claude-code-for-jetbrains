package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PendingPermission

/**
 * Holds the queue of permission requests awaiting the user's Accept/Reject (rendered as inline chat cards),
 * extracted verbatim from `ClaudeSession`'s `pending: LinkedHashMap<String, PendingPermission>`.
 *
 * Semantics are identical to the inlined version:
 *  - **EDT-confined**: the backing map is NOT thread-safe; every method must be called on the EDT, exactly as
 *    `ClaudeSession` already did (`presentPermission`/`resolvePermission`/`resolveQuestion`/`clearPending` all
 *    ran under `edt { … }`). Confinement, not a concurrent structure, is the invariant.
 *  - **insertion order preserved** ([LinkedHashMap]), so [all] mirrors the old `pendingPermissions()`.
 *
 * [onChanged] is invoked whenever the visible set changes ([present], [clear] when non-empty) — it replaces the
 * direct `firePermissions()` calls so the manager stays UI-agnostic. [remove]/[get] do NOT fire on their own:
 * `ClaudeSession.resolvePermission`/`resolveQuestion` call `firePermissions()` themselves after answering, so the
 * card removal and the control response are surfaced together (behaviour unchanged from the original).
 */
class PermissionCardManager(private val onChanged: () -> Unit) {

    private val pending = LinkedHashMap<String, PendingPermission>()

    /** Adds (or replaces, by requestId) a pending request and notifies. Mirrors the old `presentPermission`. */
    fun present(p: PendingPermission) {
        pending[p.requestId] = p
        onChanged()
    }

    /**
     * Removes and returns the request for [requestId], or null if unknown. Used by
     * `resolvePermission`/`resolveQuestion`; does not fire [onChanged] (the caller does, after answering).
     */
    fun remove(requestId: String): PendingPermission? = pending.remove(requestId)

    /** The request for [requestId] without removing it, or null. */
    fun get(requestId: String): PendingPermission? = pending[requestId]

    /** Snapshot in insertion order — replaces `ClaudeSession.pendingPermissions()`. */
    fun all(): List<PendingPermission> = pending.values.toList()

    /** Drops every pending request and notifies, but only when there was something to clear (matches `clearPending`). */
    fun clear() {
        if (pending.isEmpty()) return
        pending.clear()
        onChanged()
    }
}
