package dev.lain.claudejb.controller.bridge

import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.util.thisLogger
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeLifecycle(private val panel: JcefChatPanel) {

    private val log = thisLogger()

    fun handle(m: Msg.Lifecycle) {
        when (m) {
            Msg.Ready -> {
                panel.host.markWebReady()
                panel.pushTheme()
                panel.pushSettingsMenu()
                panel.pushMetaState()
                panel.pushPermissions()
                panel.tray.push()
                panel.pushSession()
                panel.feed.requestMcp()
                panel.feed.requestVersion()
                panel.agentTabs.render()
                panel.pushGit()
                panel.security.pushGuard()
                panel.security.pushVuln()
                panel.transcript.fullResync()
            }

            is Msg.Diagnostics ->
                if (m.report.startsWith("uncaught ")) {
                    log.warn("Claude Code chat page: ${m.report}")
                } else {
                    log.info("JCEF diagnostics: ${m.report}")
                }

            is Msg.Unknown -> log.warn("the chat page sent a message this build does not parse: ${m.type}")
        }
    }
}
