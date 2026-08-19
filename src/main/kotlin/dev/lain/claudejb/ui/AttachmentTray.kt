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

internal class AttachmentTray(
    private val project: Project,
    private val exec: (String) -> Unit,
    private val focusInput: () -> Unit,
) {

    private val pending = LinkedHashMap<String, Attachment>()
    private var nextId = 0L

    fun all(): List<Attachment> = pending.values.toList()

    fun take(): List<Attachment> {
        val taken = all()
        pending.clear()
        push()
        return taken
    }

    fun add(attachment: Attachment) {
        pin(attachment)
        push()
        focusInput()
    }

    private fun pin(attachment: Attachment) {
        pending["a" + (nextId++)] = attachment
    }

    fun remove(id: String) {
        pending.remove(id)
        push()
    }

    fun addCurrentFile() {
        val path = EditorContextProvider.currentFilePath(project) ?: return
        addPath(path)
    }

    fun addPath(path: String) = addPaths(listOf(path))

    fun addPaths(paths: List<String>) {
        val known = pending.values.filterIsInstance<Attachment.FileRef>().mapTo(HashSet()) { it.path }
        var pinned = 0
        for (path in paths) {
            if (path.isBlank() || !known.add(path)) continue
            pin(Attachment.FileRef(path, FilePickerHelper.displayName(project, path)))
            pinned++
        }
        if (pinned == 0) return
        push()
        focusInput()
    }

    fun addSelection() = EditorContextProvider.selectionAsAttachment(project)?.let { add(it) }

    fun push() = exec("window.cc.attachments && window.cc.attachments(" + json() + ")")

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
        const val RECENT_FILES_LIMIT = 14
    }
}
