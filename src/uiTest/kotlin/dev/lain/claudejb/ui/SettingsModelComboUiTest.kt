package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComboBoxFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The Settings ▸ Claude Code page exposes a model combo that always lists at least the known fallbacks,
 * including "Default (recommended)" (Opus with 1M context). This test opens Settings, navigates to the Claude
 * Code page, and asserts the model combo contains that entry.
 *
 * Configure the IDE-under-test per [UiTestBase]. The combo lists fallbacks even before the `initialize`
 * handshake, so this test does not need a warmed-up session.
 */
class SettingsModelComboUiTest : UiTestBase() {

    @Test
    fun `model combo lists Default (recommended)`() {
        // Open Settings. Ctrl+Alt+S is the default IDE shortcut on Linux/Windows; on macOS it's Cmd+, — the
        // robot exposes the OS so we branch.
        if (remoteRobot.isMac()) {
            remoteRobot.keyboard { hotKey(KeyEvent.VK_META, KeyEvent.VK_COMMA) }
        } else {
            remoteRobot.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_S) }
        }

        // The Settings dialog is a HeavyWeightWindow with a search field + settings tree. Navigate to the
        // "Claude Code" page via the search box (most robust against tree restructuring).
        // inspector: confirm the settings search field class (`SettingsSearch`/`SearchTextField`).
        val search = remoteRobot.find<ComponentFixture>(
            byXpath("//div[@class='SettingsSearch'] | //div[@class='TextFieldWithProcessing'] | //div[@accessiblename='Search settings']"),
            longTimeout,
        )
        search.click()
        search.keyboard { enterText("Claude Code") }

        // Click the matching node in the settings tree.
        waitFor(longTimeout, Duration.ofMillis(500), "expected the Claude Code settings page to be reachable") {
            remoteRobot.findAll<ComponentFixture>(byXpath("//div[contains(@text,'Claude Code')]")).isNotEmpty()
        }
        remoteRobot.find<ComponentFixture>(byXpath("//div[@class='MyTree']//div[contains(@text,'Claude Code')] | //div[contains(@text,'Claude Code')]"), shortTimeout)
            .click()

        // The model combo. inspector: there are several combos on the page (model/mode/effort/transport);
        // narrow by the adjacent "Model" label once you confirm the field name.
        val modelCombo: ComboBoxFixture = remoteRobot.find(
            ComboBoxFixture::class.java,
            byXpath("//div[@class='ComboBox' or @class='JComboBox']"),
            longTimeout,
        )

        waitFor(longTimeout, Duration.ofMillis(500), "expected the model combo to list 'Default (recommended)'") {
            runCatching { modelCombo.listValues().any { it.contains("Default (recommended)") } }.getOrDefault(false)
        }
        assertTrue(
            modelCombo.listValues().any { it.contains("Default (recommended)") },
            "model combo should offer the recommended default",
        )
    }
}
