package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LegacyModels
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.settings.Provider
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JcefComposerOptions {

    fun providerJson(provider: Provider) = buildJsonObject {
        put("id", provider.id)
        put("label", provider.label)
        put(
            "options",
            buildJsonArray {
                Provider.entries.forEach { p ->
                    addJsonObject {
                        put("id", p.id)
                        put("label", p.label)
                        put("selected", p == provider)
                    }
                }
            },
        )
    }

    fun modelJson(session: ClaudeSession) = buildJsonObject {
        val selectedModel = session.model ?: session.preferredDefaultModel()
        put("label", JcefModelLabels.modelLabel(session))
        put(
            "options",
            buildJsonArray {
                val catalog = session.models.filter { it.value != ClaudeSession.RECOMMENDED_ALIAS }
                catalog.forEach { m ->
                    addJsonObject {
                        put("value", m.value)
                        put("label", JcefModelLabels.modelDisplayLabel(m))
                        put("selected", m.value == selectedModel)
                    }
                }
                LegacyModels.offeredAlongside(catalog.map { it.value }).forEach { entry ->
                    addJsonObject {
                        put("value", entry.value)
                        put("label", entry.label)
                        put("selected", entry.value == selectedModel)
                        put("group", "other")
                    }
                }
            },
        )
    }

    fun modeJson(mode: String) = buildJsonObject {
        put("wire", mode)
        put("label", PermissionMode.labelFor(mode))
        put(
            "options",
            buildJsonArray {
                ClaudeSession.PERMISSION_MODES.forEach { wire ->
                    addJsonObject {
                        put("wire", wire)
                        put("label", PermissionMode.labelFor(wire))
                        put("selected", wire == mode)
                    }
                }
            },
        )
    }

    fun effortJson(effort: String?) = buildJsonObject {
        put("label", effort?.replaceFirstChar { it.uppercase() } ?: "Default")
        put(
            "options",
            buildJsonArray {
                addJsonObject {
                    put("value", null as String?)
                    put("label", "Default")
                    put("selected", effort == null)
                }
                ClaudeSession.EFFORT_LEVELS.forEach { lvl ->
                    addJsonObject {
                        put("value", lvl)
                        put("label", lvl.replaceFirstChar { it.uppercase() })
                        put("selected", lvl == effort)
                    }
                }
            },
        )
    }

    fun thinkingJson(thinkingOn: Boolean) = buildJsonObject {
        put("on", thinkingOn)
        put(
            "options",
            buildJsonArray {
                addJsonObject {
                    put("on", false)
                    put("label", "Off")
                    put("selected", !thinkingOn)
                }
                addJsonObject {
                    put("on", true)
                    put("label", "Extended")
                    put("selected", thinkingOn)
                }
            },
        )
    }
}
