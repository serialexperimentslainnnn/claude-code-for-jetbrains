package dev.lain.claudejb.view.feed

import dev.lain.claudejb.controller.vuln.VulnService
import dev.lain.claudejb.view.window.JcefChatPanel

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
