package dev.lain.claudejb.model.session.launch

import dev.lain.claudejb.model.settings.ClaudeSettings

object GodMode {
    const val LABEL = "Claude God Mode"
    const val TAGLINE = "Claude becomes one with your IDE"

    fun isOn(s: ClaudeSettings.State): Boolean = s.ideMcp.enabled && IdeRule.parse(s.ideMcp.rules) == IdeRule.entries.toSet()

    fun set(s: ClaudeSettings.State, on: Boolean) {
        s.ideMcp.enabled = on
        s.ideMcp.rules = if (on) IdeRule.csv(IdeRule.entries) else ""
    }
}
