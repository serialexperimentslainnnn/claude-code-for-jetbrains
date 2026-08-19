package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PendingPermission

class PermissionCardManager(private val onChanged: () -> Unit) {

    private val pending = LinkedHashMap<String, PendingPermission>()

    fun present(p: PendingPermission) {
        pending[p.requestId] = p
        onChanged()
    }

    fun remove(requestId: String): PendingPermission? = pending.remove(requestId)

    fun get(requestId: String): PendingPermission? = pending[requestId]

    fun all(): List<PendingPermission> = pending.values.toList()

    fun clear() {
        if (pending.isEmpty()) return
        pending.clear()
        onChanged()
    }
}
