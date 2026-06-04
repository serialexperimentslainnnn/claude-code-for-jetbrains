package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.HookProgressInfo
import dev.lain.claudejb.protocol.HookResponseInfo
import dev.lain.claudejb.protocol.HookStartedInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hook lifecycle is narrated as ONE evolving transcript row per hook: started inserts it, progress mutates
 * the SAME entry, response finalizes it and drops the key. [TranscriptModel] is a plain observable list, so this
 * needs no IDE fixture.
 */
class HookActivityNarratorTest {

    private fun model() = TranscriptModel()

    @Test
    fun `started then progress then response mutate a single row`() {
        val t = model()
        val n = HookActivityNarrator(t)

        n.onStarted(HookStartedInfo(hookId = "h1", hookName = "fmt", hookEvent = "PostToolUse"))
        assertEquals(1, t.entries.size)
        val entry = t.entries.single()
        assertEquals(Speaker.SYSTEM, entry.speaker)
        assertTrue(entry.text.contains("running"))

        n.onProgress(HookProgressInfo(hookId = "h1", stdout = "line1\nline2"))
        assertEquals(1, t.entries.size)          // SAME entry, no new row
        assertTrue(t.entries.single().text.contains("line2"))

        n.onResponse(HookResponseInfo(hookId = "h1", outcome = "success"))
        assertEquals(1, t.entries.size)
        assertTrue(t.entries.single().text.startsWith("✓"))
    }

    @Test
    fun `error response renders the failure and exit code`() {
        val t = model()
        val n = HookActivityNarrator(t)
        n.onStarted(HookStartedInfo(hookId = "h2", hookName = "lint"))
        n.onResponse(HookResponseInfo(hookId = "h2", outcome = "error", exitCode = 2))
        val text = t.entries.single().text
        assertTrue(text.startsWith("✗"))
        assertTrue(text.contains("exit 2"))
    }

    @Test
    fun `progress for an unknown hook is a no-op`() {
        val t = model()
        val n = HookActivityNarrator(t)
        n.onProgress(HookProgressInfo(hookId = "ghost", stdout = "x"))
        assertEquals(0, t.entries.size)
    }

    @Test
    fun `orphan response drops a success but surfaces a failure`() {
        val t = model()
        val n = HookActivityNarrator(t)
        n.onResponse(HookResponseInfo(hookId = "ghost", outcome = "success"))
        assertEquals(0, t.entries.size)
        n.onResponse(HookResponseInfo(hookId = "ghost", hookName = "deploy", outcome = "error"))
        assertEquals(1, t.entries.size)
        assertTrue(t.entries.single().text.contains("failed"))
    }

    @Test
    fun `clear forgets tracked rows so later progress is a no-op`() {
        val t = model()
        val n = HookActivityNarrator(t)
        n.onStarted(HookStartedInfo(hookId = "h3", hookName = "fmt"))
        n.clear()
        n.onProgress(HookProgressInfo(hookId = "h3", stdout = "later"))
        // The started row stays, but the (now-untracked) progress neither mutates nor adds anything.
        assertEquals(1, t.entries.size)
        assertTrue(t.entries.single().text.contains("running"))
    }
}
