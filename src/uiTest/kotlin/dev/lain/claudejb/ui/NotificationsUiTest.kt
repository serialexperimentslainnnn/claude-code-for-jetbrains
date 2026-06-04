package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Attention notifications: when a **background** chat tab (not the one currently on screen) needs attention —
 * a pending permission, a finished turn, or an error — the plugin raises a "Claude Code" balloon notification
 * (with an "Open" action) and badges the tab. The balloon is suppressed for the tab that is visible+selected.
 *
 * ## Why this is `@Disabled` here
 * Reliably orchestrating "a *background* tab receives attention" needs two tabs plus a way to make the
 * non-foreground one act mid-turn — which in turn needs a second prompt round and careful tab focus control.
 * That is brittle in a single linear RemoteRobot flow, so the scenario is documented and the assertion is
 * written, but the test is disabled until the fixture grows a deterministic "background attention" trigger
 * (e.g. fake-claude emitting a permission request on tab #1 while tab #2 is focused). Remove `@Disabled` once
 * that fixture lands.
 *
 * The body below is the real assertion: it waits for the balloon and its "Open" action. Configure the
 * IDE-under-test per [UiTestBase].
 */
class NotificationsUiTest : UiTestBase() {

    @Test
    @Disabled("Needs a fixture that triggers attention on a background tab; see KDoc.")
    fun `background tab attention raises a Claude Code balloon`() {
        val toolWindow = openClaudeToolWindow()

        // 1) Open a second chat tab via the "New Chat" title action, so we have a background tab.
        // inspector: the New Chat action is an ActionButton; tooltip "New Chat".
        toolWindow.find<ComponentFixture>(
            byXpath("//div[@accessiblename='New Chat' or @tooltiptext='New Chat']"),
            shortTimeout,
        ).click()

        // 2) Trigger work on the *first* (now background) tab. With the current fixtures this requires a
        //    deterministic background-attention scenario — hence @Disabled. When available, kick it off here.

        // 3) Assert the balloon notification appears. Balloons render in a BalloonLayout as a
        //    NotificationCenterPanel / JEditorPane carrying the group title "Claude Code"; the action is a link
        //    labelled "Open".
        // inspector: confirm the balloon component class on your build (`NotificationBalloonRoundShadowBorder`,
        //    `BalloonImpl$MyComponent`, etc.) and tighten the OR-set below.
        waitFor(longTimeout, Duration.ofMillis(500), "expected a 'Claude Code' attention balloon to appear") {
            val balloon = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[contains(@class,'Balloon') or contains(@class,'Notification')]"),
            )
            balloon.any { runCatching { it.hasText("Claude Code") }.getOrDefault(false) }
        }

        // The balloon offers an "Open" action that focuses the tab and dismisses the toast.
        remoteRobot.find<ComponentFixture>(
            byXpath("//div[@text='Open' or @accessiblename='Open']"),
            shortTimeout,
        ).click()
    }
}
