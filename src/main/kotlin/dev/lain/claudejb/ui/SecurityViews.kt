package dev.lain.claudejb.ui

import dev.lain.claudejb.vuln.VulnService

internal class SecurityViews(private val panel: JcefChatPanel) {

    fun pushGuard() = panel.guard.push()

    fun pushVuln() = VulnService.getInstance(panel.project).refresh(panel::pushSession)

    fun openGuardView() {
        pushGuard()
        panel.host.exec("window.cc.openGuardView && window.cc.openGuardView()")
    }

    fun showVulnView() {
        pushVuln()
        panel.host.exec("window.cc.showVulnView && window.cc.showVulnView()")
    }
}
