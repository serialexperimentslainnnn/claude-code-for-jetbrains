package dev.lain.claudejb.permission

import dev.lain.claudejb.protocol.CanUseToolRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests the [PermissionBroker]'s decision wiring through its injected lambdas (no process, no UI). The focus is
 * the tool_use id plumbing that keys the persistent edit snapshot: it must reach the [PendingPermission] (manual
 * card) and the [onAutoReviewed] callback (auto-approved edit), and a blank id must degrade to null.
 */
class PermissionBrokerTest {

    private fun broker(
        mode: String,
        present: (PendingPermission) -> Unit = {},
        onAutoReviewed: (String, JsonObject, String) -> Unit = { _, _, _ -> },
        projectRoot: String? = null,
    ) = PermissionBroker(
        permissionMode = { mode },
        respond = {},
        onApprovedWrite = {},
        present = present,
        onAutoReviewed = onAutoReviewed,
        projectRoot = projectRoot,
    )

    @Test
    fun `manual card carries the tool_use id`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "ls") }, toolUseId = "tu_42")

        broker(mode = "default", present = { captured = it }).handle("req-1", request)

        assertEquals("tu_42", captured?.toolUseId)
    }

    @Test
    fun `blank tool_use id degrades to null`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "ls") }, toolUseId = "")

        broker(mode = "default", present = { captured = it }).handle("req-2", request)

        assertNotNull(captured)
        assertNull(captured?.toolUseId)
    }

    @Test
    fun `AskUserQuestion card carries the tool_use id`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(
            toolName = "AskUserQuestion",
            input = buildJsonObject { put("questions", kotlinx.serialization.json.JsonArray(emptyList())) },
            toolUseId = "tu_ask",
        )

        broker(mode = "default", present = { captured = it }).handle("req-3", request)

        assertEquals("tu_ask", captured?.toolUseId)
    }

    @Test
    fun `auto-reviewed edit forwards the tool_use id before the write`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "a.kt")
        var seenId: String? = null
        val request = CanUseToolRequest(
            toolName = "Edit",
            input = buildJsonObject {
                put("file_path", file.path)
                put("old_string", "a")
                put("new_string", "b")
            },
            toolUseId = "tu_edit",
        )

        broker(
            mode = "acceptEdits",
            present = { error("should auto-approve, not present a card") },
            onAutoReviewed = { _, _, id -> seenId = id },
            projectRoot = dir.toFile().path,
        ).handle("req-4", request)

        assertEquals("tu_edit", seenId)
    }
}
