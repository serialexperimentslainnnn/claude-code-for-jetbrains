package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.UsageReport
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

    /**
     * `[{ key, label, pct }]` for the composer readout — the windows that actually have a percentage.
     *
     * Windows without one are dropped here rather than shown as "—": the readout is a glanceable line, and a
     * placeholder that can never resolve is worse than one fewer indicator. The dashboard card keeps them,
     * because there the distinction between "unknown" and "unused" is worth the row.
     */
    private fun compactUsageJson(session: ClaudeSession, usage: UsageReport?) = buildJsonArray {
        val fromReport = usage?.windows.orEmpty().mapNotNull { (key, w) ->
            w.utilization?.let { key to normalizePercent(it) }
        }
        val fromEvents = session.rateLimits
            .filterKeys { key -> fromReport.none { it.first == key } }
            .mapNotNull { (key, info) -> info.utilizationPercent()?.let { key to it } }
        (fromReport + fromEvents).forEach { (key, pct) ->
            addJsonObject {
                put("key", key)
                put("label", RateLimitInfo.windowTitleFor(key))
                put("pct", pct)
            }
        }
    }

    /** The wire has sent both 0..100 and 0..1 historically; accept either, clamp, never crash. */
    private fun normalizePercent(raw: Double): Int =
        (if (raw <= 1.0) raw * 100 else raw).toInt().coerceIn(0, 100)

    fun stateJson(session: ClaudeSession, usage: UsageReport? = null): String {
        val provider = session.provider
        val mode = session.permissionMode
        val effort = session.effort
        val thinkingOn = session.thinkingTokens != null
        val context = session.lastContextUsage

        val obj = buildJsonObject {
            put("turnActive", session.turnActive)
            put("interrupting", session.interrupting)
            put("running", session.isRunning())

            // Live reasoning suffix while a thinking block is accumulating; null when there's nothing to show.
            val suffix = StatusLineFormatter.thinkingSuffix(session.liveThinkingTokens)
            if (session.turnActive && suffix.isNotEmpty()) {
                put("thinkingStatus", "Thinking… · $suffix")
            } else {
                put("thinkingStatus", null as String?)
            }

            // One builder per composer pill — each is an independent { label, options[…] } shape, and inlining
            // all five made this one function longer than the whole rest of the file.
            put("provider", providerJson(provider))
            put("model", modelJson(session))
            put("mode", modeJson(mode))
            put("effort", effortJson(effort))
            put("thinking", thinkingJson(thinkingOn))

            put("queue", buildJsonArray { session.queuedPrompts().forEach { add(it) } })
            put("suggestion", session.promptSuggestion)

            // Plan limits, COMPACT — the composer readout is one line, so it carries label + percentage only.
            // The dashboard's card (JcefSessionData.usageJson) is the full version with reset times and
            // credits. Deliberately duplicated rather than shared: the two views answer different questions
            // ("am I close to a wall right now?" vs "where did my week go?") and forcing one shape on both is
            // how the readout ends up wrapping to three lines on a narrow tool window.
            put("usage", compactUsageJson(session, usage))

            if (context != null) {
                put(
                    "context",
                    buildJsonObject {
                        put("used", context.totalTokens)
                        put("max", context.maxTokens)
                        put("pct", context.percentage)
                    },
                )
            } else {
                put("context", null as String?)
            }

            put("tokensOut", session.sessionOutputTokens)
            put("costUsd", null as String?)
        }
        return obj.toString()
    }

    /** provider { id, label, options[{id,label,selected}] } */
    private fun providerJson(provider: Provider) = buildJsonObject {
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
     * [modelDisplayLabel]), so Opus 5 vs Sonnet 5 vs Haiku 4.5 read at a glance.
     */
    private fun modelJson(session: ClaudeSession) = buildJsonObject {
        val selectedModel = session.model ?: session.preferredDefaultModel()
        put("label", modelLabel(session))
        put(
            "options",
            buildJsonArray {
                session.models
                    .filter { it.value != ClaudeSession.RECOMMENDED_ALIAS }
                    .forEach { m ->
                        addJsonObject {
                            put("value", m.value)
                            put("label", modelDisplayLabel(m))
                            put("selected", m.value == selectedModel)
                        }
                    }
            },
        )
    }

    /** mode { wire, label, options[{wire,label,selected}] } */
    private fun modeJson(mode: String) = buildJsonObject {
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
    private fun effortJson(effort: String?) = buildJsonObject {
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
    private fun thinkingJson(thinkingOn: Boolean) = buildJsonObject {
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

    fun metaJson(session: ClaudeSession): String {
        // Commands the plugin handles itself (not reported by the binary's slash_commands).
        val pluginCommands = mapOf(
            "login" to "Sign in to Claude (Anthropic OAuth)",
            "btw" to "Ask a side question without disturbing the current turn",
        )
        val binaryNames = session.commands.map { it.name }.toSet()
        val obj = buildJsonObject {
            put(
                "commands",
                buildJsonArray {
                    // Plugin commands first, skipping any the binary already reports.
                    pluginCommands.forEach { (name, desc) ->
                        if (name !in binaryNames) {
                            addJsonObject {
                                put("name", name)
                                put("description", desc)
                            }
                        }
                    }
                    session.commands.forEach { cmd ->
                        addJsonObject {
                            put("name", cmd.name)
                            put("description", cmd.description.ifBlank { cmd.name })
                        }
                    }
                },
            )
            // Under the native Wayland toolkit CEF's web clipboard is isolated from the system clipboard,
            // so the composer must route Ctrl+V through the host (which reads via wl-paste) instead of
            // trusting the paste event's clipboardData. See JcefChatPanel.PasteClipboard.
            put("hostClipboard", hostClipboardPreferred)
        }
        return obj.toString()
    }

    /**
     * True under the native Wayland toolkit (`sun.awt.wl.WLToolkit`), where CEF's web clipboard is isolated
     * from the system clipboard and AWT clipboard *reads* are broken (even the IDE's own editors can't paste
     * external content). The composer then routes paste through the host, which reads via `wl-paste`/`xclip`
     * — the only mechanism that reaches the Wayland clipboard. Cached: the toolkit can't change at runtime.
     */
    private val hostClipboardPreferred: Boolean by lazy {
        runCatching { java.awt.Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit" }
            .getOrDefault(false)
    }

    /**
     * The pill/menu label for a model, WITH its version — everything derived from the binary's own catalog, so it
     * stays correct as tiers change (no hardcoded version anywhere). The binary's `displayName` omits the version
     * ("Opus (1M context)"); the version lives in `description` ("Opus 5 with 1M context · Best for everyday…").
     * We prefer the description's lead segment (before the " · " tagline) because it carries the version; fall back
     * to `displayName`, then to a label derived from the id.
     */
    internal fun modelDisplayLabel(m: ModelInfo): String {
        val descHead = m.description.substringBefore(" · ").trim()
        return when {
            descHead.isNotBlank() -> descHead
            m.displayName.isNotBlank() -> m.displayName
            else -> deriveModelLabel(m.value)
        }
    }

    /** The model pill label: the catalog's versioned label for the selected model, else derived from its id. */
    fun modelLabel(session: ClaudeSession): String {
        val id = session.model ?: session.preferredDefaultModel()
        session.models.firstOrNull { it.value == id }?.let { return modelDisplayLabel(it) }
        return deriveModelLabel(id)
    }

    /** Turns a model id like "claude-opus-4-8" into a friendly label like "Opus 4.8"; a last resort when the binary
     *  catalog carries no metadata for it. Strips an alias suffix like "opus[1m]" → "Opus" (no version to show). */
    internal fun deriveModelLabel(id: String): String {
        val core = id.removePrefix("claude-").substringBefore('[').trim()
        if (core.isBlank()) return "Claude"
        // Split family from the version digits: "opus-4-8" → family "opus", version ["4","8"].
        val parts = core.split('-')
        val versionStart = parts.indexOfFirst { it.toIntOrNull() != null }
        if (versionStart <= 0) {
            return parts.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        }
        val family = parts.subList(0, versionStart)
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        val version = parts.subList(versionStart, parts.size)
            .takeWhile { it.toIntOrNull() != null }
            .joinToString(".")
        return if (version.isNotEmpty()) "$family $version" else family
    }
}
