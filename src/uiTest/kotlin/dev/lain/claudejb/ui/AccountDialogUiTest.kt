package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * E11 account UI: the gear "Account…" item opens an [AccountInfoPanel]-backed dialog reflecting the signed-in
 * account reported by the binary's `initialize` handshake (email / plan / provider). With the fake fixture's init
 * carrying an account, the dialog should surface one of those fields.
 *
 * Locators are inspector hints — the gear is an ActionButton; the dialog is a JBPopup/DialogWrapper.
 */
class AccountDialogUiTest : UiTestBase() {

    @Test
    fun `gear Account opens the account dialog`() {
        val toolWindow = openClaudeToolWindow()
        // Let the init handshake settle so the account is populated.
        waitForTranscript("expected the session to initialize") { it.isNotBlank() }

        val gear = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[@accessiblename='Show Options Menu' or @accessiblename='More' or @myicon='gearPlain.svg']"),
            shortTimeout,
        )
        gear.click()
        remoteRobot.find(
            ComponentFixture::class.java,
            byXpath("//div[contains(@text,'Account')]"),
            shortTimeout,
        ).click()

        waitFor(longTimeout, Duration.ofMillis(500), "expected an account field (email/plan/provider) in the dialog") {
            runCatching {
                remoteRobot.find(
                    ComponentFixture::class.java,
                    byXpath("//div[contains(@text,'Email') or contains(@text,'Plan') or contains(@text,'Provider') or contains(@text,'Not signed in')]"),
                    shortTimeout,
                )
            }.isSuccess
        }
    }
}
