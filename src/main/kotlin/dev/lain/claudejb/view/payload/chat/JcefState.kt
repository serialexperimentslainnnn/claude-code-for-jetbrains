package dev.lain.claudejb.view.payload.chat

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.models.RateLimitInfo
import dev.lain.claudejb.model.protocol.models.UsageReport
import dev.lain.claudejb.model.session.launch.GodMode
import dev.lain.claudejb.model.session.transcript.StatusLineFormatter
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.guard.guardSuspended
import dev.lain.claudejb.view.payload.composer.JcefComposerOptions
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
        val fromEvents = session.signals.rateLimits
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
        val settings = ClaudeSettings.getInstance(session.project)
        val mode = session.launch.permissionMode
        val effort = session.launch.effort
        val thinkingOn = session.launch.thinkingTokens != null
        val context = session.signals.lastContextUsage

        val obj = buildJsonObject {
            put("turnActive", session.turn.active)
            put("interrupting", session.turn.interrupting)
            put("running", session.isRunning() && session.catalog.initialized)
            put("starting", session.lifecycle.isStarting())
            put("resuming", session.lifecycle.isStarting() && session.sessionId != null)
            put("binaryMissing", session.lifecycle.binaryMissing)
            put("needsLogin", session.lifecycle.needsLogin)

            put("reasoningTokens", session.turn.liveThinkingTokens)

            val suffix = StatusLineFormatter.thinkingSuffix(session.turn.liveThinkingTokens)
            if (session.turn.active && suffix.isNotEmpty()) {
                put("thinkingStatus", "Thinking… · $suffix")
            } else {
                put("thinkingStatus", null as String?)
            }

            put("guardOn", !settings.guardSuspended())
            put("remoteControlOn", session.remote.enabled)
            put("remoteControlError", session.remote.error)

            put("godModeOn", GodMode.isOn(settings.state))

            put("provider", JcefComposerOptions.providerJson(settings.provider))
            put("model", JcefComposerOptions.modelJson(session))
            put("mode", JcefComposerOptions.modeJson(mode))
            put("effort", JcefComposerOptions.effortJson(effort))
            put("thinking", JcefComposerOptions.thinkingJson(thinkingOn))

            put("queue", buildJsonArray { session.prompts.queued().forEach { add(it) } })
            put("suggestion", session.prompts.suggestion)

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

            put("tokensOut", session.tokens.sessionOutputTokens)
            put("costUsd", null as String?)
        }
        return obj.toString()
    }

    fun metaJson(session: ClaudeSession): String {
        val pluginCommands = mapOf(
            "btw" to "Ask a side question without disturbing the current turn",
        )
        val binaryNames = session.catalog.commands.map { it.name }.toSet()
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
                    session.catalog.commands.forEach { cmd ->
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
                    dev.lain.claudejb.controller.process.BinaryInstall.methods().forEach { m ->
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
