package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LegacyModels
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
        // EXPERIMENT (Lain's comma test): carry the raw decimals here too, so the composer readout does not
        // round to Int either — otherwise the decimal never shows and the test can't see a comma vs a dot.
        val fromReport = usage?.windows.orEmpty().mapNotNull { (key, w) ->
            w.utilization?.let { Triple(key, w.title(key), it) }
        }
        val fromEvents = session.rateLimits
            .filterKeys { key -> fromReport.none { it.first == key } }
            .mapNotNull { (key, info) ->
                info.utilization?.let { Triple(key, RateLimitInfo.windowTitleFor(key), it * 100) }
            }
        (fromReport + fromEvents).forEach { (key, label, pct) ->
            addJsonObject {
                put("key", key)
                put("label", label)
                put("pct", pct)
            }
        }
    }

    fun stateJson(session: ClaudeSession, usage: UsageReport? = null): String {
        val provider = session.provider
        val mode = session.permissionMode
        val effort = session.effort
        val thinkingOn = session.thinkingTokens != null
        val context = session.lastContextUsage

        val obj = buildJsonObject {
            put("turnActive", session.turnActive)
            put("interrupting", session.interrupting)
            // "Running" for the GUI means the handshake answered, not just that a process exists. The boot
            // screen hangs off this, and coming down on a bare spawn showed a chat with empty menus and an
            // empty dashboard that populated a beat later.
            put("running", session.isRunning() && session.initialized)
            // "Booting" is a THIRD state, not the absence of `running`: the web app blocks input behind a loading
            // screen while this is true, and a session that failed to launch must fall out of it (both flags
            // false) rather than wait forever.
            put("starting", session.isStarting())
            // Resuming reads an existing transcript back and is the slower of the two waits, so the boot screen
            // labels it differently rather than calling both "Starting" and making the long one look hung.
            put("resuming", session.isStarting() && session.sessionId != null)
            // A FOURTH boot state: the launch found no `claude` binary at all. The web app swaps the spinner
            // for the install/path card instead of clearing into an empty tab explained only by a toast.
            put("binaryMissing", session.binaryMissing)
            // The binary looks unauthenticated (proactive `auth status` probe, or a turn failed on auth).
            // Raises the sign-in card — subscription OAuth or API key — wherever it is detected.
            put("needsLogin", session.needsLogin)

            // The live reasoning estimate as a NUMBER, always present (0 when nothing is being reasoned about),
            // so the readout can render a settled "0" instead of omitting the item. An item that only exists
            // once it is non-zero is indistinguishable from one that failed to load.
            put("reasoningTokens", session.liveThinkingTokens)

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
                val catalog = session.models.filter { it.value != ClaudeSession.RECOMMENDED_ALIAS }
                catalog.forEach { m ->
                    addJsonObject {
                        put("value", m.value)
                        put("label", modelDisplayLabel(m))
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
        //
        // "login" is deliberately NOT here any more: signing in is a BUTTON (the sign-in card, and the
        // dashboard's account row), not a command to know about. A typed /login still works — the intercept
        // stays as a silent alias, because removing an entry point people have used since 4.0 without any
        // notice is how muscle memory gets punished — it is just no longer advertised in the palette.
        val pluginCommands = mapOf(
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
            // Install routes for THIS OS, for the boot card shown when no `claude` binary exists. The host
            // decides the list (it knows the OS and the distro); the web app only renders buttons. `display`
            // is the exact command a button will run — corporate networks block individual installers, so
            // the user must be able to read it, copy it, and take it elsewhere.
            put(
                "installMethods",
                buildJsonArray {
                    dev.lain.claudejb.process.BinaryInstall.methods().forEach { m ->
                        addJsonObject {
                            put("id", m.id)
                            put("label", m.label)
                            put("display", m.display)
                        }
                    }
                },
            )
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
        // A model picked from "Other models" is not in the catalog, so the pill would fall through to
        // deriveModelLabel — which is right for `claude-opus-4-7` and wrong for `claude-3-5-sonnet`, where the
        // version leads the family and it renders "3 5 Sonnet". The curated label is the authority for ours.
        LegacyModels.labelFor(id)?.let { return it }
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
