package dev.lain.claudejb.settings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Export, import, and copying one scope onto another.
 *
 * The load-bearing test is the first one. Everything else here can be re-derived by reading the code; that
 * one is a tripwire for a change nobody will connect to this file — somebody adds a field that holds a
 * secret, and the export starts writing it into a JSON in the user's Downloads folder.
 */
class SettingsTransferTest {

    private lateinit var safe: MutableMap<String, String>

    @BeforeEach
    fun useAFakeSafe() {
        safe = mutableMapOf()
        SecretStore.storeOverride = safe
    }

    @AfterEach
    fun releaseTheSafe() {
        SecretStore.storeOverride = null
    }

    private fun stateFields() = ClaudeSettings.State::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filterNot { it.name.startsWith("$") }
        .map { it.name }

    /**
     * Only `String` fields, and that is the whole heuristic rather than a loophole in it: a secret is text.
     * `thinkingTokens` is an `Int` and `securityBlockCredentials` is a `Boolean`, and neither can hold one
     * however much their names read like they could.
     */
    @Test
    fun `no field that could carry a secret is written to an exported file`() {
        val suspicious = ClaudeSettings.State::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.name.startsWith("$") }
            .filter { it.type == String::class.java }
            .map { it.name }
            .filter { name -> SECRET_WORDS.any { name.contains(it, ignoreCase = true) } }

        assertTrue(suspicious.isNotEmpty(), "if this is empty the heuristic stopped matching and proves nothing")
        assertEquals(
            emptyList<String>(),
            suspicious - SettingsTransfer.WITHHELD,
            "a settings field whose name says it holds a secret must be withheld from an export, or this " +
                "list must say in writing why it does not: $suspicious",
        )
    }

    @Test
    fun `the exported document is every field except the withheld ones`() {
        val body = SettingsTransfer.export(configured())

        SettingsTransfer.WITHHELD.forEach { withheld ->
            assertFalse(body.contains("\"$withheld\""), "'$withheld' must not appear in an export at all")
        }
        assertFalse(body.contains("super-secret-token"), "and neither must anything it was holding")
        (stateFields() - SettingsTransfer.WITHHELD).forEach { kept ->
            assertTrue(body.contains("\"$kept\""), "the export dropped '$kept'")
        }
    }

    @Test
    fun `a round trip preserves everything the file is allowed to carry`() {
        val back = imported(SettingsTransfer.export(configured()))

        assertEquals("opus-pinned", back.model)
        assertEquals(7, back.maxTurns)
        assertEquals("CREDENTIALS", back.disabledSecurityRules)
        assertEquals("terraform destroy", back.securityCommandWhitelist)
        assertEquals("", back.envVars, "the export never carried it, so the import cannot invent it")
    }

    @Test
    fun `an import cannot set the withheld field even when the file names it`() {
        val forged = """{"format":1,"settings":{"envVars":"ANTHROPIC_API_KEY=sk-ant-stolen"}}"""

        val back = imported(forged)

        assertEquals("", back.envVars, "a file handed to the plugin must not be able to inject an environment")
    }

    @Test
    fun `an import refuses a permission mode that would weaken security`() {
        val forged = """{"format":1,"settings":{"permissionMode":"bypassPermissions"}}"""

        val back = imported(forged)

        assertEquals(LegacyPermissionMode.SAFE, back.permissionMode)
    }

    @Test
    fun `anything that is not one of these files reads as nothing, rather than as defaults`() {
        assertNull(SettingsTransfer.import(""))
        assertNull(SettingsTransfer.import("{not json"))
        assertNull(SettingsTransfer.import("""{"format":1}"""), "no settings block is not an empty one")
        assertNull(SettingsTransfer.import("""["a","list"]"""))
    }

    @Test
    fun `copying the guard's part leaves the rest of the target alone`() {
        val from = SettingsScope("the-other-ide")
        val to = SettingsScope("this-one")
        safe[from.secretName] = """{"model":"opus-pinned","disabledSecurityRules":"CREDENTIALS"}"""
        safe[to.secretName] = """{"model":"mine","maxTurns":3}"""

        assertTrue(SettingsTransfer.copyScope(from, to, setOf(SettingsTransfer.Part.GUARD)))

        val after = safe.getValue(to.secretName)
        assertTrue(after.contains("\"disabledSecurityRules\": \"CREDENTIALS\""), after)
        assertTrue(after.contains("\"model\": \"mine\""), "the general half was not asked for: $after")
        assertFalse(after.contains("opus-pinned"), after)
    }

    @Test
    fun `copying the general part brings the environment across, because it never leaves the safe`() {
        val from = SettingsScope("the-other-ide")
        val to = SettingsScope("this-one")
        safe[from.secretName] = """{"model":"opus-pinned","envVars":"TOKEN=super-secret-token"}"""

        assertTrue(SettingsTransfer.copyScope(from, to, setOf(SettingsTransfer.Part.GENERAL)))

        assertTrue(
            safe.getValue(to.secretName).contains("super-secret-token"),
            "keychain to keychain, same user, same machine: this is the case where it does travel",
        )
    }

    @Test
    fun `the alert log is copied only when it is asked for`() {
        val from = SettingsScope("the-other-ide")
        val to = SettingsScope("this-one")
        safe[from.secretName] = """{"model":"opus-pinned"}"""
        safe[from.guardLogName] = """[{"at":1,"rule":"CREDENTIALS","category":"SENSITIVE_DATA","verdict":"DENIED"}]"""

        SettingsTransfer.copyScope(from, to, setOf(SettingsTransfer.Part.GENERAL))
        assertNull(safe[to.guardLogName])

        SettingsTransfer.copyScope(from, to, setOf(SettingsTransfer.Part.ALERT_LOG))
        assertEquals(safe[from.guardLogName], safe[to.guardLogName])
    }

    @Test
    fun `a scope with nothing stored is not offered as a source`() {
        val empty = SettingsScope("never-configured")
        val full = SettingsScope("configured")
        safe[full.secretName] = """{"model":"opus-pinned"}"""

        assertFalse(SettingsTransfer.holdsSettings(empty))
        assertTrue(SettingsTransfer.holdsSettings(full))
    }

    private fun imported(body: String) =
        SettingsTransfer.import(body) ?: error("expected that to import, and it did not")

    private fun configured() = ClaudeSettings.State().apply {
        model = "opus-pinned"
        maxTurns = 7
        envVars = "TOKEN=super-secret-token"
        disabledSecurityRules = "CREDENTIALS"
        securityCommandWhitelist = "terraform destroy"
    }

    private companion object {
        /** What a field name looks like when it holds something that must not leave the machine. */
        val SECRET_WORDS = listOf("env", "key", "token", "secret", "password", "credential")
    }
}
