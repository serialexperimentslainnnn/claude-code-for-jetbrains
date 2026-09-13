package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemProcessHandler
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager

internal class ExternalTaskOutput private constructor(private val tail: OutputTail) : ExternalSystemTaskNotificationListener {

    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
        if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) tail.text(text)
    }

    fun detach() {
        ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(this)
    }

    companion object {

        fun attach(handler: ProcessHandler, tail: OutputTail): ExternalTaskOutput? {
            val id = (handler as? ExternalSystemProcessHandler)?.task?.id ?: return null
            return ExternalTaskOutput(tail).also { ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(id, it) }
        }
    }
}
