package dev.lain.claudejb.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.context.FilePickerHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chips pinned to the next turn: files, selections and images, wherever they came from — an editor
 * action, the 📎 menu, a drag-and-drop or a paste.
 *
 * Extracted from `JcefChatPanel` (an assembler): this owns one thing, the pending set, and everything that
 * fills it. EDT-confined, except the two clipboard reads, which say so.
 */
internal class AttachmentTray(
    private val project: Project,
    /** Runs a snippet in the web view. */
    private val exec: (String) -> Unit,
    /** After anything is pinned, the caret goes back where the user was typing. */
    private val focusInput: () -> Unit,
) {

    private val pending = LinkedHashMap<String, Attachment>()
    private var nextId = 0L

    /** Everything pinned, in the order it was pinned. */
    fun all(): List<Attachment> = pending.values.toList()

    /** Hands over the pinned set and clears it — what a send does. */
    fun take(): List<Attachment> {
        val taken = all()
        pending.clear()
        push()
        return taken
    }

    /** Pins an attachment (file / selection / image) as a chip; it travels with the next send. */
    fun add(attachment: Attachment) {
        pending["a" + (nextId++)] = attachment
        push()
        focusInput()
    }

    fun remove(id: String) {
        pending.remove(id)
        push()
    }

    /** Pins the current editor file (editor "Add … to Claude Context"). */
    fun addCurrentFile() {
        val path = EditorContextProvider.currentFilePath(project) ?: return
        addPath(path)
    }

    fun addPath(path: String) = add(Attachment.FileRef(path, FilePickerHelper.displayName(project, path)))

    /** Pins the editor's current selection, when there is one. */
    fun addSelection() = EditorContextProvider.selectionAsAttachment(project)?.let { add(it) }

    fun push() = exec("window.cc.attachments && window.cc.attachments(" + json() + ")")

    /** Data for the rich 📎 attach menu: recent files (newest-first) + what context is available right now. */
    fun pushMenuData() {
        val recent = FilePickerHelper.recentFiles(project, RECENT_FILES_LIMIT).map { path ->
            buildJsonObject {
                put("path", path)
                put("name", FilePickerHelper.displayName(project, path))
                put("ext", path.substringAfterLast('.', "").lowercase())
            }
        }
        val payload = buildJsonObject {
            put("recent", JsonArray(recent))
            put("hasSelection", EditorContextProvider.currentSelection(project) != null)
            put("hasFile", EditorContextProvider.currentFilePath(project) != null)
        }
        exec("window.cc.attachData && window.cc.attachData($payload)")
    }

    /**
     * Ctrl+V: read the system clipboard host-side (reliable on Wayland) on a POOLED thread, then apply on the
     * EDT. The Wayland fallback shells out to `wl-paste`/`xclip` and reads their stdout with a deadline —
     * doing that on the EDT froze the IDE whenever the clipboard owner was slow or hung. Image → attach;
     * else text → insert at the caret.
     */
    fun pasteFromClipboard() {
        offEdt {
            val img = EditorContextProvider.imageFromClipboard()
            val text = if (img == null) EditorContextProvider.clipboardText() else null
            val help = if (img == null && text.isNullOrEmpty()) EditorContextProvider.clipboardImageHelp() else null
            onEdt {
                when {
                    img != null -> add(img)

                    !text.isNullOrEmpty() ->
                        exec("window.cc.insertText && window.cc.insertText(" + JsonPrimitive(text) + ")")

                    else -> notify(
                        if (help != null) "Couldn't read the clipboard — $help" else "Clipboard is empty or unreadable.",
                    )
                }
            }
        }
    }

    /** Explicit "Paste image" / image-only Ctrl+V — same off-EDT read, image-only handling. */
    fun pasteImageFromClipboard(alwaysNotify: Boolean) {
        offEdt {
            val img = EditorContextProvider.imageFromClipboard()
            val shouldNotify = img == null && (alwaysNotify || !EditorContextProvider.clipboardHasText())
            val help = if (shouldNotify) EditorContextProvider.clipboardImageHelp() else null
            onEdt {
                when {
                    img != null -> add(img)

                    shouldNotify -> notify(
                        if (help != null) {
                            "Couldn't read an image from the clipboard — $help"
                        } else {
                            "No image found in the clipboard."
                        },
                    )
                }
            }
        }
    }

    /** A small balloon for clipboard feedback (e.g. when "Paste image" finds nothing to paste). */
    fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun json(): String = JsonArray(
        pending.map { (id, a) ->
            buildJsonObject {
                put("id", id)
                put("label", a.displayName)
                put(
                    "kind",
                    when (a) {
                        is Attachment.Image -> "image"
                        is Attachment.Selection -> "selection"
                        is Attachment.FileRef -> "file"
                    },
                )
            }
        },
    ).toString()

    private fun offEdt(block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(block)

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private companion object {
        /** How many recently-opened files the attach menu offers before the user has to search. */
        const val RECENT_FILES_LIMIT = 14
    }
}
