package dev.lain.claudejb.controller.session

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeMcpService

internal object IdeWithoutChat {

    fun serve(project: Project) = IdeMcpService.getInstance(project).serveWithoutChat()
}
