package dev.lain.claudejb.controller.mcp

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

internal fun interface ToolOutputListener {
    fun line(toolUseId: String, line: String)
}

internal object ToolOutput {

    val TOPIC: Topic<ToolOutputListener> = Topic(ToolOutputListener::class.java, Topic.BroadcastDirection.NONE)

    fun line(project: Project, toolUseId: String, line: String) {
        project.messageBus.syncPublisher(TOPIC).line(toolUseId, line)
    }
}
