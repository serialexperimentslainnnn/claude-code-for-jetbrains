package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LegacyModels
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.settings.Provider
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One builder per composer pill — each is an independent `{ label, options[…] }` shape.
 *
 * They live apart from [JcefState] for the reason its own comment gave: inlining all five made `stateJson`
 * longer than the whole rest of that file. The web layer stays a pure renderer — the option lists arrive with
 * the selected flag already computed, and every branching rule is decided here.
 */
internal object JcefComposerOptions {

    /** provider { id, label, options[{id,label,selected}] } */
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

    /**
     * model { label, options[{value,label,selected}] }
     *
     * The list is autodetected from the binary's `initialize` catalog. We drop the floating "default" alias (it
     * duplicated the concrete tier and showed no version) and label each entry with its version (see
     * [JcefModelLabels.modelDisplayLabel]), so Opus 5 vs Sonnet 5 vs Haiku 4.5 read at a glance.
     */
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
                // Previous generations, tagged so the composer can fold them into an "Other models" submenu
                // instead of burying the four current models in a list of seventeen. The GROUPING is decided
                // here, not in the web app: the host owns which models exist and what they are called, and a
                // frontend that had to recognise "old" ids would be a second, divergent copy of that rule.
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

    /** mode { wire, label, options[{wire,label,selected}] } */
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

    /** effort { label, options[{value:String?,label,selected}] } — includes a null "Default" option. */
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

    /** thinking { on, options[{on,label,selected}] } */
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
