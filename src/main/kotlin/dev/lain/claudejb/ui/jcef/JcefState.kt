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

object JcefState {

    private fun compactUsageJson(session: ClaudeSession, usage: UsageReport?) = buildJsonArray {
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
                w.resetsAt?.let { put("resetsAt", it) }
            }
        }
    }

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
            put("running", session.isRunning() && session.initialized)
            put("starting", session.isStarting())
            put("resuming", session.isStarting() && session.sessionId != null)
            put("binaryMissing", session.binaryMissing)
            put("needsLogin", session.needsLogin)

            put("reasoningTokens", session.liveThinkingTokens)

            val suffix = StatusLineFormatter.thinkingSuffix(session.liveThinkingTokens)
            if (session.turnActive && suffix.isNotEmpty()) {
                put("thinkingStatus", "Thinking… · $suffix")
            } else {
                put("thinkingStatus", null as String?)
            }

            put("guardOn", session.guardEnforced)
            put("remoteControlOn", session.remoteControlEnabled)
            put("remoteControlError", session.remoteControlError)

            put("provider", JcefComposerOptions.providerJson(provider))
            put("model", JcefComposerOptions.modelJson(session))
            put("mode", JcefComposerOptions.modeJson(mode))
            put("effort", JcefComposerOptions.effortJson(effort))
            put("thinking", JcefComposerOptions.thinkingJson(thinkingOn))

            put("queue", buildJsonArray { session.queuedPrompts().forEach { add(it) } })
            put("suggestion", session.promptSuggestion)

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
        val pluginCommands = mapOf(
            "btw" to "Ask a side question without disturbing the current turn",
        )
        val binaryNames = session.commands.map { it.name }.toSet()
        val obj = buildJsonObject {
            put(
                "commands",
                buildJsonArray {
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
            put("hostClipboard", hostClipboardPreferred)
            put("gitIntegration", session.gitIntegration)
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

    private val hostClipboardPreferred: Boolean by lazy {
        runCatching { java.awt.Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit" }
            .getOrDefault(false)
    }
}
