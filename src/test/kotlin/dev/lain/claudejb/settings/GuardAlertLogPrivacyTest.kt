package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **What the guard's alert log is allowed to contain, and why it is the opposite of the rule next door.**
 *
 * `AgentIndexPrivacyTest` says the persisted form carries the tree and never the content. This one says the
 * persisted form carries the content, the command verbatim included. Both are right, and since 6.0 the
 * difference is no longer where they live — both are encrypted entries in the OS keychain, beside the
 * credentials the plugin already keeps there. It is what each is for: an index of who spawned whom has
 * never needed the content, and a log that cannot say what was attempted is not a log.
 *
 * The reason to record the command at all is that a security log which cannot say what was attempted can be
 * counted but not audited, and auditing is the entire point of keeping one.
 *
 * So this test exists to make that a decision somebody took rather than an oversight, and to make the next
 * person argue with it deliberately: **if this log ever moves out of the safe, it must stop carrying
 * commands on the same day.**
 */
class GuardAlertLogPrivacyTest {

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true }
    }

    private val rule = SecurityRule.CREDENTIALS

    private val alert = GuardAlert(
        at = 1_700_000_000_000,
        rule = rule.name,
        category = rule.category.name,
        verdict = GuardAlert.DENIED,
        sessionId = "5f2b-session",
        toolUseId = "toolu_x",
        via = null,
        tool = "Bash",
        detail = "reads credentials or sensitive data: /home/u/.ssh/id_ed25519",
        command = "cat ~/.ssh/id_ed25519",
    )

    private val encoded: String
        get() = Json.encodeToString(ListSerializer(GuardAlert.serializer()), listOf(alert))

    @Test
    fun `the persisted form carries what was attempted, on purpose`() {
        val json = encoded

        assertTrue(json.contains("cat ~/.ssh/id_ed25519"), "a log without the command cannot audit anything")
        assertTrue(json.contains("/home/u/.ssh/id_ed25519"), "and the finding is half of what makes it readable")
        assertTrue(json.contains(rule.name))
        assertTrue(json.contains(GuardAlert.DENIED))
        assertTrue(json.contains("toolu_x"), "the anchor a restored conversation puts the row back on")
    }

    @Test
    fun `it goes in the safe, and the entry name says which project it belongs to`() {
        val name = SettingsScope("abc123").guardLogName

        assertTrue(name.startsWith(SecretStore.GUARD_LOG + "@"), "one log per IDE installation per project")
        assertEquals("${SecretStore.GUARD_LOG}@abc123", name)
    }

    @Test
    fun `nothing in the plugin writes this log to a file`() {
        val source = java.io.File("src/main/kotlin/dev/lain/claudejb/settings/GuardAlertLog.kt")
        assertTrue(source.isFile, "the log moved: this contract has to move with it")
        val code = source.readLines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
            .joinToString("\n")

        listOf("Files.write", "writeText", "FileWriter", "Paths.get", "File(").forEach { writing ->
            assertTrue(
                writing !in code,
                "the command is recorded verbatim ONLY because this lives encrypted in the safe — `$writing` " +
                    "would put it on disk in the clear and this contract would be a lie",
            )
        }
    }

    @Test
    fun `a decoded entry survives a field this build does not know`() {
        val fromTheFuture = """[{"at":1,"rule":"${rule.name}","category":"${rule.category.name}",""" +
            """"verdict":"DENIED","somethingNew":{"a":1}}]"""

        val kept = LENIENT.decodeFromString(ListSerializer(GuardAlert.serializer()), fromTheFuture)

        assertEquals(rule.name, kept.single().rule)
    }
}
