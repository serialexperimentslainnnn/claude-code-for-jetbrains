package dev.lain.claudejb.controller.session.events

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.models.FailedFile
import dev.lain.claudejb.model.protocol.models.FilesPersistedInfo
import dev.lain.claudejb.model.protocol.models.InformationalInfo
import dev.lain.claudejb.model.protocol.models.MemoryRecallInfo
import dev.lain.claudejb.model.protocol.models.MirrorErrorInfo
import dev.lain.claudejb.model.protocol.models.ModelRefusalFallbackInfo
import dev.lain.claudejb.model.protocol.models.ModelRefusalNoFallbackInfo
import dev.lain.claudejb.model.protocol.models.NotificationInfo
import dev.lain.claudejb.model.protocol.models.PermissionDeniedInfo
import dev.lain.claudejb.model.protocol.models.PersistedFile
import dev.lain.claudejb.model.protocol.models.PluginInstallInfo
import dev.lain.claudejb.model.protocol.models.RecalledMemory
import dev.lain.claudejb.model.protocol.models.WorkerShuttingDownInfo
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.util.PluginLog
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoticeNarratorTest {

    private val notices = ArrayList<String>()
    private val rows = ArrayList<Triple<Speaker, String, String?>>()
    private val balloons = ArrayList<String>()

    private val narrator = NoticeNarrator(
        log = PluginLog.of(NoticeNarratorTest::class),
        systemNotice = { notices += it },
        addRow = { speaker, text, summary -> rows += Triple(speaker, text, summary) },
        notifyInfo = { balloons += it },
        edt = { it() },
    )

    @Test
    fun `a status notice and a notification go to the chat, and a loud notification also to a balloon`() {
        narrator.onNotice(ClaudeEvent.StatusNotice("compacting"))
        narrator.onNotice(ClaudeEvent.Notification(NotificationInfo(text = "quiet", priority = "low")))
        narrator.onNotice(ClaudeEvent.Notification(NotificationInfo(text = "loud", priority = "high")))
        narrator.onNotice(ClaudeEvent.Notification(NotificationInfo(text = "now", priority = "immediate")))
        narrator.onNotice(ClaudeEvent.Notification(NotificationInfo(text = " ")))
        assertEquals(listOf("compacting", "quiet", "loud", "now"), notices)
        assertEquals(listOf("loud", "now"), balloons)
    }

    @Test
    fun `a denied permission is an error row with the best reason available`() {
        narrator.onNotice(ClaudeEvent.PermissionDenied(PermissionDeniedInfo(toolName = "Bash", message = "blocked by hook")))
        narrator.onNotice(ClaudeEvent.PermissionDenied(PermissionDeniedInfo(toolName = "Edit", decisionReason = "rule")))
        narrator.onNotice(ClaudeEvent.PermissionDenied(PermissionDeniedInfo(toolName = "Write", decisionReasonType = "type")))
        narrator.onNotice(ClaudeEvent.PermissionDenied(PermissionDeniedInfo(toolName = "Read")))
        assertEquals(
            listOf("Denied Bash: blocked by hook", "Denied Edit: rule", "Denied Write: type", "Denied Read: denied"),
            rows.map { it.second },
        )
        assertTrue(rows.all { it.first == Speaker.ERROR })
    }

    @Test
    fun `recalled memories become a memory row, and none becomes nothing`() {
        narrator.onNotice(ClaudeEvent.MemoryRecall(MemoryRecallInfo("auto")))
        assertTrue(rows.isEmpty())
        narrator.onNotice(ClaudeEvent.MemoryRecall(MemoryRecallInfo("auto", listOf(RecalledMemory("a.md", "user", "remember me")))))
        assertEquals(Speaker.MEMORY, rows.single().first)
        assertTrue(rows.single().second.contains("remember me"))
    }

    @Test
    fun `persisted and failed files are counted, and plugin installs are told by status`() {
        narrator.onNotice(ClaudeEvent.FilesPersisted(FilesPersistedInfo(listOf(PersistedFile("a.txt"), PersistedFile("b.txt")), listOf(FailedFile("c")))))
        narrator.onNotice(ClaudeEvent.FilesPersisted(FilesPersistedInfo()))
        narrator.onNotice(ClaudeEvent.PluginInstall(PluginInstallInfo("installed", "linter")))
        narrator.onNotice(ClaudeEvent.PluginInstall(PluginInstallInfo("failed", error = "no network")))
        narrator.onNotice(ClaudeEvent.PluginInstall(PluginInstallInfo("installed")))
        narrator.onNotice(ClaudeEvent.PluginInstall(PluginInstallInfo("pending")))
        assertEquals(
            listOf(
                "Uploaded 2 file(s): a.txt, b.txt",
                "Failed to persist 1 file(s)",
                "Plugin installed: linter",
                "Plugin install failed: no network",
                "Plugin installed",
            ),
            notices,
        )
    }

    @Test
    fun `a refusal names its category and fallback, and one without a fallback is an error row`() {
        narrator.onNotice(ClaudeEvent.ModelRefusalFallback(ModelRefusalFallbackInfo(apiRefusalCategory = "safety", fallbackModel = "sonnet")))
        narrator.onNotice(ClaudeEvent.ModelRefusalFallback(ModelRefusalFallbackInfo()))
        narrator.onNotice(ClaudeEvent.ModelRefusalNoFallback(ModelRefusalNoFallbackInfo(content = "I cannot help with that.")))
        narrator.onNotice(ClaudeEvent.ModelRefusalNoFallback(ModelRefusalNoFallbackInfo(apiRefusalCategory = "policy")))
        assertEquals(
            listOf("The model declined to respond (safety) → retried on sonnet.", "The model declined to respond → retried on a fallback model."),
            notices,
        )
        assertEquals(
            listOf("I cannot help with that.", "The model declined to respond (policy) and no fallback model was configured."),
            rows.map { it.second },
        )
    }

    @Test
    fun `informational notices reach the chat only when they matter`() {
        narrator.onNotice(ClaudeEvent.Informational(InformationalInfo(content = "fyi", level = "info")))
        narrator.onNotice(ClaudeEvent.Informational(InformationalInfo(content = "careful", level = "warning")))
        narrator.onNotice(ClaudeEvent.Informational(InformationalInfo(content = "try this", level = "suggestion")))
        narrator.onNotice(ClaudeEvent.Informational(InformationalInfo(content = "stop", level = "info", preventContinuation = true)))
        narrator.onNotice(ClaudeEvent.Informational(InformationalInfo(content = " ", level = "warning")))
        assertEquals(listOf("Warning: careful", "try this", "stop"), notices)
    }

    @Test
    fun `the rest is logged, and a mirror error also warns the user`() {
        narrator.onNotice(ClaudeEvent.MirrorError(MirrorErrorInfo("disk full")))
        narrator.onNotice(ClaudeEvent.WorkerShuttingDown(WorkerShuttingDownInfo("idle")))
        narrator.onNotice(ClaudeEvent.Other("system", "unknown", JsonObject(emptyMap())))
        narrator.onNotice(ClaudeEvent.Other("system", "broken", JsonObject(emptyMap()), cause = "bad json"))
        assertEquals(listOf("Warning: failed to persist part of the session transcript."), notices)
    }
}
