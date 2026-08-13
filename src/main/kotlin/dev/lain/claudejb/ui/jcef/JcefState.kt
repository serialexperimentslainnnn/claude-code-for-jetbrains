package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.StatusLineFormatter
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
 * The five pill option-lists are built by [JcefComposerOptions] and the model naming by [JcefModelLabels],
 * which the Settings combo and the dashboard share so no two controls can name one model differently.
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
            w.utilization?.let { CompactWindow(key, w.title(key), it, w.resetsAt) }
        }
        val fromEvents = session.rateLimits
            .filterKeys { key -> fromReport.none { it.key == key } }
            .mapNotNull { (key, info) ->
                info.utilization?.let {
                    CompactWindow(key, RateLimitInfo.windowTitleFor(key), it * 100, info.resetsAtIso())
                }
            }
        (fromReport + fromEvents).forEach { w ->
            addJsonObject {
                put("key", w.key)
                put("label", w.label)
                put("pct", w.pct)
                // The countdown, so the readout can say how long the window has left. A percentage alone does
                // not tell you whether 90% is urgent; only the dashboard was answering that.
                w.resetsAt?.let { put("resetsAt", it) }
            }
        }
    }

    /** One window as the composer readout needs it; a Triple stopped being readable at four fields. */
    private data class CompactWindow(val key: String, val label: String, val pct: Double, val resetsAt: String?)

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
            put("provider", JcefComposerOptions.providerJson(provider))
            put("model", JcefComposerOptions.modelJson(session))
            put("mode", JcefComposerOptions.modeJson(mode))
            put("effort", JcefComposerOptions.effortJson(effort))
            put("thinking", JcefComposerOptions.thinkingJson(thinkingOn))

            put("queue", buildJsonArray { session.queuedPrompts().forEach { add(it) } })
            put("suggestion", session.promptSuggestion)

            // Plan limits, COMPACT — the composer readout is one line, so it carries label + percentage only.
            // The dashboard's card (JcefUsageData.usageJson) is the full version with reset times and
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
            // trusting the paste event's clipboardData. See JcefBridge.Msg.PasteClipboard, which
            // ChatBridgeRouter dispatches to AttachmentTray.pasteFromClipboard().
            put("hostClipboard", hostClipboardPreferred)
            // Install routes for THIS OS, for the boot card shown when no `claude` binary exists. The host
            // decides the list (it knows the OS and the distro); the web app only renders buttons. `display`
            // is the exact command a button will run — corporate networks block individual installers, so
            // the user must be able to read it, copy it, and take it elsewhere.
            //
            // `shell` names WHERE to paste it (`bash`, `PowerShell`, `cmd`, `sh (as root)`), which is not a
            // detail on Windows: the same route is spelled differently in PowerShell and in cmd, and a
            // command pasted into the wrong one fails in a way that reads as "the installer is broken".
            // The card has always rendered this (`app-composer-boot.js`: `m.shell || 'a shell'`) and the
            // payload never carried it, so every route silently fell back to the generic "a shell".
            put(
                "installMethods",
                buildJsonArray {
                    dev.lain.claudejb.process.BinaryInstall.methods().forEach { m ->
                        addJsonObject {
                            put("id", m.id)
                            put("label", m.label)
                            put("display", m.display)
                            put("shell", m.shell)
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
}
