package dev.lain.claudejb.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [InfoDialogs]: the binary-version and effective-settings formatting. No session, no Swing
 * display — these pin the contract the dialogs render.
 */
class InfoDialogsTest {

    // --- formatBinaryVersion ---

    @Test
    fun `formats the version key`() {
        assertEquals("claude 2.1.161", InfoDialogs.formatBinaryVersion(buildJsonObject { put("version", "2.1.161") }))
    }

    @Test
    fun `falls back to binary_version and claude_code_version keys`() {
        assertEquals("claude 9.9", InfoDialogs.formatBinaryVersion(buildJsonObject { put("binary_version", "9.9") }))
        assertEquals("claude 3.0", InfoDialogs.formatBinaryVersion(buildJsonObject { put("claude_code_version", "3.0") }))
    }

    @Test
    fun `binary version placeholder when absent`() {
        assertEquals("Binary version unavailable.", InfoDialogs.formatBinaryVersion(null))
        assertEquals("Binary version unavailable.", InfoDialogs.formatBinaryVersion(buildJsonObject {}))
    }

    // --- formatEffectiveSettings ---

    @Test
    fun `formats top-level settings sorted by key, scalars inline`() {
        val payload = buildJsonObject {
            put("model", "opus")
            put("verbose", true)
        }
        assertEquals("model: opus\nverbose: true", InfoDialogs.formatEffectiveSettings(payload))
    }

    @Test
    fun `unwraps a nested settings object`() {
        val payload = buildJsonObject { putJsonObject("settings") { put("a", "1") } }
        assertEquals("a: 1", InfoDialogs.formatEffectiveSettings(payload))
    }

    @Test
    fun `renders nested objects as compact json`() {
        val payload = buildJsonObject { putJsonObject("env") { put("FOO", "bar") } }
        assertTrue(InfoDialogs.formatEffectiveSettings(payload).startsWith("env: {"))
    }

    @Test
    fun `settings placeholder when empty`() {
        assertEquals("No settings reported.", InfoDialogs.formatEffectiveSettings(null))
        assertEquals("No settings reported.", InfoDialogs.formatEffectiveSettings(buildJsonObject {}))
    }
}
