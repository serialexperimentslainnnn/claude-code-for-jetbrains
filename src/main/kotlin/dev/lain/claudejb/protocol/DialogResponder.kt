package dev.lain.claudejb.protocol

/**
 * Host-side answer for `request_user_dialog`: a binary->host control request asking the host to render a
 * tool-driven blocking dialog of an open-union `dialog_kind`. The plugin implements no custom kinds, so the
 * protocol-correct reply is always {behavior:"cancelled"} (the CLI then applies the dialog's own default).
 *
 * Pure + stateless so both the reply line and the transparency note are unit-testable. Kept separate from
 * [ControlProtocol] only to host the user-facing [notice] text alongside the builder it pairs with.
 */
object DialogResponder {

    /** The control_response line to write for [requestId] — always a cancel. */
    fun response(requestId: String): String = ControlProtocol.userDialogCancelled(requestId)

    /** A short transcript note for transparency when the agent requested a dialog the host doesn't render. */
    fun notice(dialogKind: String?): String {
        val kind = dialogKind?.takeIf { it.isNotBlank() }
        return if (kind != null) "Claude requested a \"$kind\" dialog (using its default)."
        else "Claude requested a dialog (using its default)."
    }
}
