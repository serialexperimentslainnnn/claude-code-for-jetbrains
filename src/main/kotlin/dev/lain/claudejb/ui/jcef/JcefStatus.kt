package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus

internal object JcefStatus {

    fun of(status: AgentStatus): String = when (status) {
        AgentStatus.RUNNING -> "running"
        AgentStatus.COMPLETED -> "completed"
        AgentStatus.FAILED -> "failed"
        AgentStatus.STOPPED -> "stopped"
    }

    fun of(running: Boolean): String = if (running) "running" else "completed"
}
