package dev.lain.claudejb.permission

import dev.lain.claudejb.protocol.CanUseToolRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PermissionBrokerMatrixTest {

    private class Observation {
        var respond: String? = null
        var presented: PendingPermission? = null
        var autoReviewed: Triple<String, JsonObject, String>? = null
        var approvedWritePath: String? = null

        val autoApproved: Boolean get() = respond != null && presented == null
        val manualCard: Boolean get() = presented != null
    }

    private fun run(
        mode: String,
        request: CanUseToolRequest,
        projectRoot: String?,
        alwaysAllowedTools: Set<String> = emptySet(),
        forceAsk: Boolean = false,
    ): Observation {
        val obs = Observation()
        val broker = PermissionBroker(
            permissionMode = { mode },
            respond = { obs.respond = it },
            onApprovedWrite = { obs.approvedWritePath = it },
            present = { obs.presented = it },
            onAutoReviewed = { tool, input, id -> obs.autoReviewed = Triple(tool, input, id) },
            isRemembered = { tool, _ -> tool in alwaysAllowedTools },
            projectRoot = projectRoot,
            forceAsk = { forceAsk },
        )
        broker.handle("req-x", request)
        return obs
    }

    private fun editReq(path: String, toolUseId: String = "tu_1"): CanUseToolRequest =
        CanUseToolRequest(
            toolName = "Edit",
            input = buildJsonObject {
                put("file_path", path)
                put("old_string", "a")
                put("new_string", "b")
            },
            toolUseId = toolUseId,
        )

    private fun writeReq(path: String): CanUseToolRequest =
        CanUseToolRequest(
            toolName = "Write",
            input = buildJsonObject {
                put("file_path", path)
                put("content", "hello")
            },
            toolUseId = "tu_w",
        )

    private fun multiEditReq(path: String): CanUseToolRequest =
        CanUseToolRequest(
            toolName = "MultiEdit",
            input = buildJsonObject {
                put("file_path", path)
                put("edits", kotlinx.serialization.json.JsonArray(emptyList()))
            },
            toolUseId = "tu_me",
        )

    private fun bashReq(cmd: String): CanUseToolRequest =
        CanUseToolRequest(
            toolName = "Bash",
            input = buildJsonObject { put("command", cmd) },
            toolUseId = "tu_b",
        )

    private fun readReq(path: String): CanUseToolRequest =
        CanUseToolRequest(
            toolName = "Read",
            input = buildJsonObject { put("file_path", path) },
            toolUseId = "tu_r",
        )

    @Test
    fun `bypass + Edit inside root auto-approves and captures snapshot`(@TempDir root: Path) {
        val f = File(root.toFile(), "a.kt").apply { writeText("a") }
        val obs = run("bypassPermissions", editReq(f.canonicalPath), root.toFile().canonicalPath)

        assertTrue(obs.autoApproved, "should auto-approve")
        assertNotNull(obs.autoReviewed, "snapshot callback must fire for reviewable writes")
        assertEquals("Edit", obs.autoReviewed?.first)
        assertEquals("tu_1", obs.autoReviewed?.third)
        assertEquals(f.canonicalPath, obs.approvedWritePath)
    }

    @Test
    fun `bypass + Edit OUTSIDE root falls through to a manual card (containment beats mode)`(@TempDir root: Path) {
        val obs = run("bypassPermissions", editReq("/etc/hosts"), root.toFile().canonicalPath)

        assertTrue(obs.manualCard, "outside-root writes must never auto-approve, even on bypass")
        assertNull(obs.autoReviewed)
    }

    @Test
    fun `bypass + Bash auto-approves (non-reviewable, no containment check)`(@TempDir root: Path) {
        val obs = run("bypassPermissions", bashReq("ls"), root.toFile().canonicalPath)

        assertTrue(obs.autoApproved)
        assertNull(obs.autoReviewed, "Bash is not reviewable → no snapshot")
    }

    @Test
    fun `bypass + Read auto-approves with no snapshot`(@TempDir root: Path) {
        val obs = run("bypassPermissions", readReq("/etc/hosts"), root.toFile().canonicalPath)
        assertTrue(obs.autoApproved)
        assertNull(obs.autoReviewed)
    }

    @Test
    fun `acceptEdits + Write inside root auto-approves`(@TempDir root: Path) {
        val f = File(root.toFile(), "new.txt")
        val obs = run("acceptEdits", writeReq(f.canonicalPath), root.toFile().canonicalPath)
        assertTrue(obs.autoApproved)
        assertEquals("Write", obs.autoReviewed?.first)
    }

    @Test
    fun `acceptEdits + MultiEdit inside root auto-approves`(@TempDir root: Path) {
        val f = File(root.toFile(), "x.kt").apply { writeText("a") }
        val obs = run("acceptEdits", multiEditReq(f.canonicalPath), root.toFile().canonicalPath)
        assertTrue(obs.autoApproved)
        assertEquals("MultiEdit", obs.autoReviewed?.first)
    }

    @Test
    fun `acceptEdits + Write OUTSIDE root degrades to manual card`(@TempDir root: Path) {
        val obs = run("acceptEdits", writeReq("/etc/passwd"), root.toFile().canonicalPath)
        assertTrue(obs.manualCard)
        assertNull(obs.autoReviewed)
    }

    @Test
    fun `acceptEdits + Bash does NOT auto-approve (mode only covers edits)`(@TempDir root: Path) {
        val obs = run("acceptEdits", bashReq("rm -rf /"), root.toFile().canonicalPath)
        assertTrue(obs.manualCard, "Bash under acceptEdits must surface a card")
    }

    @Test
    fun `default mode surfaces manual card for any tool`(@TempDir root: Path) {
        val f = File(root.toFile(), "a.kt").apply { writeText("a") }
        assertTrue(run("default", editReq(f.canonicalPath), root.toFile().canonicalPath).manualCard)
        assertTrue(run("default", bashReq("ls"), root.toFile().canonicalPath).manualCard)
        assertTrue(run("default", readReq(f.canonicalPath), root.toFile().canonicalPath).manualCard)
    }

    @Test
    fun `plan mode surfaces manual card even for inside-root edits`(@TempDir root: Path) {
        val f = File(root.toFile(), "a.kt").apply { writeText("a") }
        val obs = run("plan", editReq(f.canonicalPath), root.toFile().canonicalPath)
        assertTrue(obs.manualCard)
        assertNull(obs.autoReviewed)
    }

    @Test
    fun `unknown mode string falls through to manual card`(@TempDir root: Path) {
        val obs = run("auto", bashReq("ls"), root.toFile().canonicalPath)
        assertTrue(obs.manualCard, "auto/dontAsk are not auto-approve modes in the broker")
    }

    @Test
    fun `always-allowed Bash auto-approves under default mode`(@TempDir root: Path) {
        val obs = run(
            "default",
            bashReq("git status"),
            root.toFile().canonicalPath,
            alwaysAllowedTools = setOf("Bash"),
        )
        assertTrue(obs.autoApproved)
    }

    @Test
    fun `always-allowed Edit INSIDE root auto-approves`(@TempDir root: Path) {
        val f = File(root.toFile(), "x.kt").apply { writeText("a") }
        val obs = run(
            "default",
            editReq(f.canonicalPath),
            root.toFile().canonicalPath,
            alwaysAllowedTools = setOf("Edit"),
        )
        assertTrue(obs.autoApproved)
        assertNotNull(obs.autoReviewed)
    }

    @Test
    fun `always-allowed Edit OUTSIDE root still surfaces a manual card`(@TempDir root: Path) {
        val obs = run(
            "default",
            editReq("/etc/hosts"),
            root.toFile().canonicalPath,
            alwaysAllowedTools = setOf("Edit"),
        )
        assertTrue(obs.manualCard)
        assertNull(obs.autoReviewed)
    }

    @Test
    fun `forceAsk beats bypassPermissions`(@TempDir root: Path) {
        val obs = run("bypassPermissions", bashReq("git init"), root.toFile().canonicalPath, forceAsk = true)
        assertTrue(obs.manualCard, "a plugin-started turn must be put to the user even on bypass")
        assertFalse(obs.autoApproved)
    }

    @Test
    fun `forceAsk beats a remembered always-allow`(@TempDir root: Path) {
        val obs = run(
            "default",
            bashReq("git init"),
            root.toFile().canonicalPath,
            alwaysAllowedTools = setOf("Bash"),
            forceAsk = true,
        )
        assertTrue(obs.manualCard, "forceAsk must be checked before isRemembered, not after")
    }

    @Test
    fun `forceAsk beats acceptEdits for an inside-root write`(@TempDir root: Path) {
        val f = File(root.toFile(), "a.kt").apply { writeText("a") }
        val obs = run("acceptEdits", editReq(f.canonicalPath), root.toFile().canonicalPath, forceAsk = true)
        assertTrue(obs.manualCard)
        assertNull(obs.autoReviewed, "nothing was auto-approved, so no snapshot is captured for it")
    }

    @Test
    fun `forceAsk only ever downgrades an approval — it never denies`(@TempDir root: Path) {
        val obs = run("bypassPermissions", bashReq("git commit"), root.toFile().canonicalPath, forceAsk = true)
        assertNull(obs.respond, "forceAsk turns an auto-approval into a question, not into a refusal")
        assertNotNull(obs.presented)
    }

    @Test
    fun `AskUserQuestion is always presented, never auto-approved`(@TempDir root: Path) {
        val req = CanUseToolRequest(
            toolName = "AskUserQuestion",
            input = buildJsonObject { put("questions", kotlinx.serialization.json.JsonArray(emptyList())) },
            toolUseId = "tu_ask",
        )
        val obs = run("bypassPermissions", req, root.toFile().canonicalPath, alwaysAllowedTools = setOf("AskUserQuestion"))
        assertTrue(obs.manualCard)
        assertFalse(obs.autoApproved)
        assertEquals("tu_ask", obs.presented?.toolUseId)
        assertNotNull(obs.presented?.questions, "AskUserQuestion card must carry parsed questions")
    }

    @Test
    fun `auto-approved edit forwards tool_use id verbatim`(@TempDir root: Path) {
        val f = File(root.toFile(), "a.kt").apply { writeText("a") }
        val obs = run("acceptEdits", editReq(f.canonicalPath, toolUseId = "tu_abc"), root.toFile().canonicalPath)
        assertEquals("tu_abc", obs.autoReviewed?.third)
    }
}
