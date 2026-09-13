package dev.lain.claudejb.controller.commands

import dev.lain.claudejb.view.window.JcefChatPanel
import java.util.concurrent.CopyOnWriteArrayList

internal object LivePanels {

    private val panels = CopyOnWriteArrayList<JcefChatPanel>()

    fun add(panel: JcefChatPanel) {
        panels += panel
    }

    fun remove(panel: JcefChatPanel) {
        panels -= panel
    }

    fun pushTheme() = panels.forEach { it.pushTheme() }

    fun pushSession() = panels.forEach { it.pushSession() }

    fun pushSettingsMenu() = panels.forEach { it.pushSettingsMenu() }

    fun pushState() = panels.forEach { it.pushMetaState() }
}
