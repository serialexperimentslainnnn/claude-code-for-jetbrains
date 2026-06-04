package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.session.StatusLineFormatter
import dev.lain.claudejb.settings.Provider
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes a [ClaudeSession]'s composer-relevant runtime state into the JSON payloads the JCEF web layer's
 * `cc.state(s)` / `cc.meta(m)` consume (see JCEF_CONTRACT §COMPOSER). The web layer is a pure renderer: it
 * receives the live labels, the option lists (with the selected flag pre-computed), and nothing else — all
 * branching/state lives in the Kotlin backend, which is the single source of truth.
 *
 * All reads happen on the EDT (the panel calls this from listener callbacks); these are plain volatile getters.
 */
object JcefState {

    fun stateJson(session: ClaudeSession): String {
        val provider = session.provider
        val mode = session.permissionMode
        val effort = session.effort
        val thinkingOn = session.thinkingTokens != null
        val context = session.lastContextUsage

        val obj = buildJsonObject {
            put("turnActive", session.turnActive)
            put("running", session.isRunning())

            // Live reasoning suffix while a thinking block is accumulating; null when there's nothing to show.
            val suffix = StatusLineFormatter.thinkingSuffix(session.liveThinkingTokens)
            if (session.turnActive && suffix.isNotEmpty()) put("thinkingStatus", "Thinking… · $suffix")
            else put("thinkingStatus", null as String?)

            // provider { id, label, options[{id,label,selected}] }
            put("provider", buildJsonObject {
                put("id", provider.id)
                put("label", provider.label)
                put("options", buildJsonArray {
                    Provider.entries.forEach { p ->
                        addJsonObject {
                            put("id", p.id)
                            put("label", p.label)
                            put("selected", p == provider)
                        }
                    }
                })
            })

            // model { label, options[{value,label,selected}] }
            put("model", buildJsonObject {
                put("label", modelLabel(session))
                put("options", buildJsonArray {
                    session.models.forEach { m ->
                        addJsonObject {
                            put("value", m.value)
                            put("label", m.displayName.ifBlank { deriveModelLabel(m.value) })
                            put("selected", m.value == session.model)
                        }
                    }
                })
            })

            // mode { wire, label, options[{wire,label,selected}] }
            put("mode", buildJsonObject {
                put("wire", mode)
                put("label", PermissionMode.labelFor(mode))
                put("options", buildJsonArray {
                    ClaudeSession.PERMISSION_MODES.forEach { wire ->
                        addJsonObject {
                            put("wire", wire)
                            put("label", PermissionMode.labelFor(wire))
                            put("selected", wire == mode)
                        }
                    }
                })
            })

            // effort { label, options[{value:String?,label,selected}] } — include a null "Default" option.
            put("effort", buildJsonObject {
                put("label", effort?.replaceFirstChar { it.uppercase() } ?: "Default")
                put("options", buildJsonArray {
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
                })
            })

            // thinking { on, options[{on,label,selected}] }
            put("thinking", buildJsonObject {
                put("on", thinkingOn)
                put("options", buildJsonArray {
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
                })
            })

            put("queue", buildJsonArray { session.queuedPrompts().forEach { add(it) } })
            put("suggestion", session.promptSuggestion)

            if (context != null) {
                put("context", buildJsonObject {
                    put("used", context.totalTokens)
                    put("max", context.maxTokens)
                    put("pct", context.percentage)
                })
            } else {
                put("context", null as String?)
            }

            put("tokensOut", session.sessionOutputTokens)
            put("costUsd", null as String?)
        }
        return obj.toString()
    }

    fun metaJson(session: ClaudeSession): String {
        // Commands the plugin handles itself (not reported by the binary's slash_commands).
        val pluginCommands = mapOf(
            "login" to "Sign in to Claude (Anthropic OAuth)",
            "btw" to "Ask a side question without disturbing the current turn",
        )
        val binaryNames = session.commands.map { it.name }.toSet()
        val obj = buildJsonObject {
            put("commands", buildJsonArray {
                // Plugin commands first, skipping any the binary already reports.
                pluginCommands.forEach { (name, desc) ->
                    if (name !in binaryNames) addJsonObject {
                        put("name", name)
                        put("description", desc)
                    }
                }
                session.commands.forEach { cmd ->
                    addJsonObject {
                        put("name", cmd.name)
                        put("description", cmd.description.ifBlank { cmd.name })
                    }
                }
            })
        }
        return obj.toString()
    }

    /** The model pill label: the binary's displayName when available, else derived from the id, else a default. */
    fun modelLabel(session: ClaudeSession): String {
        val id = session.model
        val fromBinary = session.models.firstOrNull { it.value == id }?.displayName?.takeIf { it.isNotBlank() }
        if (fromBinary != null) return fromBinary
        if (id == null) return "Default · Opus 4.8"
        return deriveModelLabel(id)
    }

    /** Turns a model id like "claude-opus-4-8" into a friendly label like "Opus 4.8". */
    private fun deriveModelLabel(id: String): String {
        if (id.isBlank() || id == "default") return "Default · Opus 4.8"
        val core = id.removePrefix("claude-")
        // Split family from the version digits: "opus-4-8" → family "opus", version ["4","8"].
        val parts = core.split('-')
        val versionStart = parts.indexOfFirst { it.toIntOrNull() != null }
        if (versionStart <= 0) {
            return core.split('-').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        }
        val family = parts.subList(0, versionStart)
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        val version = parts.subList(versionStart, parts.size)
            .takeWhile { it.toIntOrNull() != null }
            .joinToString(".")
        return if (version.isNotEmpty()) "$family $version" else family
    }
}
