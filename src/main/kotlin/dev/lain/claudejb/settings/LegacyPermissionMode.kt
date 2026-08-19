package dev.lain.claudejb.settings

import dev.lain.claudejb.session.PermissionMode

internal object LegacyPermissionMode {

    val SAFE: String = ClaudeSettings.State().permissionMode

    fun weakensSecurity(wire: String?): Boolean {
        if (wire.isNullOrBlank()) return false
        return when (PermissionMode.from(wire)) {
            PermissionMode.DEFAULT -> false
            PermissionMode.PLAN -> false
            PermissionMode.ACCEPT_EDITS -> true
            PermissionMode.BYPASS -> true
            PermissionMode.DONT_ASK -> true
            PermissionMode.AUTO -> true
            null -> true
        }
    }
}
