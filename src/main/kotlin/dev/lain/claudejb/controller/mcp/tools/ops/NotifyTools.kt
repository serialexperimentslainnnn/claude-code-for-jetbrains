package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.util.PluginIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NotifyTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "notify",
        "A balloon in the IDE's own notification area, so the user sees a message without reading the chat",
        listOf(Tool(NOTIFY, ::notify)),
    )

    private suspend fun notify(args: ToolArgs): ToolResult {
        val title = args.string("title")
        val message = args.string("message")
        val kind = args.optionalString("kind") ?: "info"
        val type = KINDS[kind] ?: throw ToolException("kind must be ${KINDS.keys.joinToString()}")
        if (title.length > MAX_TITLE_CHARS || message.length > MAX_MESSAGE_CHARS) {
            throw ToolException("title is limited to $MAX_TITLE_CHARS characters and message to $MAX_MESSAGE_CHARS; shorten them")
        }
        withContext(Dispatchers.EDT) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
                .createNotification(StringUtil.escapeXmlEntities(title), StringUtil.escapeXmlEntities(message), type)
                .notify(project)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("title", title)
                put("kind", kind)
                put("shown", true)
            },
        )
    }

    companion object {

        private const val MAX_TITLE_CHARS = 120
        private const val MAX_MESSAGE_CHARS = 1000

        val KINDS: Map<String, NotificationType> = linkedMapOf(
            "info" to NotificationType.INFORMATION,
            "warning" to NotificationType.WARNING,
            "error" to NotificationType.ERROR,
        )

        val NOTIFY = ToolSpec(
            "notify",
            "Shows a balloon notification in the IDE with a title and a plain-text message (markup is shown literally, " +
                "never rendered). Use it when the user must notice something even if the chat is hidden: a finished long " +
                "task, a decision needed, a failure. Not for progress or chatter; each call interrupts the user.",
            listOf(
                Param("title", "Short title, up to $MAX_TITLE_CHARS characters"),
                Param("message", "Plain-text body, up to $MAX_MESSAGE_CHARS characters"),
                Param("kind", "info (default), warning or error", required = false),
            ),
            mutates = true,
        )
    }
}
