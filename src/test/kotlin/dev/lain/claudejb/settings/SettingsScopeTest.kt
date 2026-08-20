package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which document a window writes to, pinned on the pure half.
 *
 * The property that matters is separation in both directions — two projects in one IDE, and one project in
 * two IDEs — because getting either wrong is silent: the user simply finds a setting they never made.
 */
class SettingsScopeTest {

    private val ide = "/home/u/.config/JetBrains/IntelliJIdea2026.1"
    private val otherIde = "/home/u/.config/JetBrains/PyCharm2026.1"

    @Test
    fun `two projects in the same IDE do not share a document`() {
        assertNotEquals(
            SettingsScope.of(ide, "/src/alpha").id,
            SettingsScope.of(ide, "/src/beta").id,
        )
    }

    @Test
    fun `one project opened in two IDEs does not share a document`() {
        assertNotEquals(
            SettingsScope.of(ide, "/src/alpha").id,
            SettingsScope.of(otherIde, "/src/alpha").id,
            "an IDE is not a neighbour's settings server",
        )
    }

    @Test
    fun `the same pair always resolves to the same document`() {
        assertEquals(
            SettingsScope.of(ide, "/src/alpha").id,
            SettingsScope.of(ide, "/src/alpha").id,
        )
    }

    @Test
    fun `a window with no directory falls back rather than inventing an identity`() {
        assertEquals("default", SettingsScope.of(ide, null).id)
        assertEquals("default", SettingsScope.of(ide, "   ").id)
    }

    @Test
    fun `the entry name carries the scope and never the path`() {
        val scope = SettingsScope.of(ide, "/home/someone/secret-client-work")

        assertTrue(scope.secretName.startsWith(SecretStore.SETTINGS_JSON + "@"))
        assertTrue(
            "secret-client-work" !in scope.secretName,
            "a keyring label is shown to the user; a home directory does not belong in one",
        )
        assertTrue(scope.id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `the shared pre-5-6 entry is not a scope's entry`() {
        assertNotEquals(
            SecretStore.SETTINGS_JSON,
            SettingsScope.of(ide, "/src/alpha").secretName,
            "inheriting from the shared document only works while it is a different key",
        )
    }
}
