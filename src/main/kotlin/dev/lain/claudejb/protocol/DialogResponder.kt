package dev.lain.claudejb.protocol

object DialogResponder {

    fun response(requestId: String): String = ControlProtocol.userDialogCancelled(requestId)

    fun notice(dialogKind: String?): String {
        val kind = dialogKind?.takeIf { it.isNotBlank() }
        return if (kind != null) {
            "Claude requested a \"$kind\" dialog (using its default)."
        } else {
            "Claude requested a dialog (using its default)."
        }
    }
}
