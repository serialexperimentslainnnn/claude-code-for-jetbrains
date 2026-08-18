package dev.lain.claudejb.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import dev.lain.claudejb.MainSources
import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefGitData
import dev.lain.claudejb.ui.jcef.JcefStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.io.File
import java.lang.reflect.Method

/**
 * Pins **how [GitIntegration] invokes a platform action**, and pins that none of that API is deprecated.
 *
 * **Why this exists.** 4.4.1 shipped a dead `/login`: `TerminalLauncher` reflected on platform classes that had
 * been removed, every lookup returned `false` instead of throwing, and the feature simply never worked — nothing
 * in `idea.log`, no test red, a user hitting a dead end. The Git view's IDE buttons are the same shape of
 * dependency: they are the platform's own actions, invoked through the platform's own API, and both halves are
 * JetBrains' to move.
 *
 * **The deprecation half is a real gate.** This repository does not ship deprecated API, and `verifyPlugin`
 * enforces it across the declared IDE range with `DEPRECATED_API_USAGES` in its failure levels — against
 * DOWNLOADED IDEs, in minutes. This runs against the build classpath in milliseconds, and it is what caught the
 * choice that matters here: **every `ActionUtil.invokeAction` overload is already deprecated on 253**, the
 * three-argument one telling you to use `performAction(action, event)` instead. So `performAction` is what
 * `GitIntegration` calls, and the last test below is the mutation check that keeps it that way.
 *
 * Reflection rather than a direct call because these are *existence and shape* assertions: a compile-time call
 * proves the symbol is on the compile classpath and nothing about the parameter list we depend on, which is
 * exactly what changed under `TerminalLauncher`.
 *
 * **It also pins the two routes deliberately NOT taken**, because both are what an IDE popup misbehaving over
 * a JCEF tool window tempts the next person into. One is switching the popup weight, which is a JVM-wide
 * setting owned by the IDE and would change every popup in the application to no effect on this path — the
 * platform already makes all of them native windows. The other is having the gear submenu invoke through
 * [GitIntegration] instead of handing the action over, which costs the icon, the shortcut and the platform's
 * own enablement. Neither failure is visible in a diff that looks reasonable, so each has an assertion.
 */
class IdeActionApiContractTest {

    // ── the invocation itself ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `performAction is the non-deprecated way to invoke an action programmatically`() {
        val method = actionUtil()
            .getMethod("performAction", AnAction::class.java, AnActionEvent::class.java)
            .assertNotDeprecated()
        assertEquals(
            "com.intellij.openapi.actionSystem.AnActionResult",
            method.returnType.name,
            "performAction must still report what it did; a void return would mean a different method.",
        )
    }

    @Test
    fun `invokeAction is deprecated, which is why nothing here calls it`() {
        // Asserting the state of an API we deliberately do NOT use, so the reason survives the person who found
        // it. If JetBrains ever un-deprecates it this goes red and the KDoc above gets rewritten — which is the
        // correct outcome, not a false alarm.
        val deprecated = actionUtil().methods
            .filter { it.name == "invokeAction" }
            .filter { it.isAnnotationPresent(java.lang.Deprecated::class.java) || it.isAnnotationPresent(Deprecated::class.java) }
        assertEquals(
            3,
            deprecated.size,
            "Expected all three ActionUtil.invokeAction overloads to be deprecated on this platform. " +
                "Found ${deprecated.size}; re-read the migration note before changing GitIntegration.",
        )
    }

    // ── the event the action is invoked with ──────────────────────────────────────────────────────────────────

    @Test
    fun `an action event can still be built from a data context, a place and a ui kind`() {
        // The six-argument shape IS the contract: `place` and `ActionUiKind` are how an action tells a toolbar
        // click from a programmatic one, and dropping either would silently change how the platform treats it.
        val method = AnActionEvent::class.java.getMethod(
            "createEvent",
            AnAction::class.java,
            DataContext::class.java,
            Presentation::class.java,
            String::class.java,
            load("com.intellij.openapi.actionSystem.ActionUiKind"),
            InputEvent::class.java,
        ).assertNotDeprecated()
        assertTrue(AnActionEvent::class.java.isAssignableFrom(method.returnType))
    }

    @Test
    fun `the ui kind the invocation actually names still exists`() {
        // The one the code passes, not a plausible neighbour: these are pressed as buttons in the page's
        // action bar, so TOOLBAR is what `AnActionEvent.isFromActionToolbar` should answer. Pinning a
        // constant nothing uses is an assertion that stays green while the used one is removed.
        val field = load("com.intellij.openapi.actionSystem.ActionUiKind").getField("TOOLBAR")
        assertFalse(
            field.isAnnotationPresent(java.lang.Deprecated::class.java) || field.isAnnotationPresent(Deprecated::class.java),
            "ActionUiKind.TOOLBAR is deprecated — GitIntegration names it on every IDE invocation.",
        )
        assertTrue(
            codeOf("ui/GitIntegration.kt").any { "ActionUiKind.TOOLBAR" in it },
            "GitIntegration no longer names ActionUiKind.TOOLBAR; pin the kind it does name instead of this one.",
        )
    }

    @Test
    fun `the project data context and the action lookup are still one call each`() {
        load("com.intellij.openapi.actionSystem.impl.SimpleDataContext")
            .getMethod("getProjectContext", Project::class.java)
            .assertNotDeprecated()
        load("com.intellij.openapi.actionSystem.ActionManager")
            .getMethod("getAction", String::class.java)
            .assertNotDeprecated()
        load("com.intellij.openapi.actionSystem.ActionPlaces").getField("TOOLWINDOW_CONTENT")
    }

    // ── the process the plugin runs itself ────────────────────────────────────────────────────────────────────

    @Test
    fun `the working directory and parent environment builders are not deprecated`() {
        // `git init` is the one command this plugin runs. Both of these decide WHERE and WITH WHAT it runs, so
        // a silent removal would change the meaning of the command rather than fail to compile somewhere loud.
        val commandLine = load("com.intellij.execution.configurations.GeneralCommandLine")
        commandLine.getMethod("withWorkingDirectory", java.nio.file.Path::class.java).assertNotDeprecated()
        commandLine
            .getMethod(
                "withParentEnvironmentType",
                load("com.intellij.execution.configurations.GeneralCommandLine\$ParentEnvironmentType"),
            )
            .assertNotDeprecated()
    }

    // ── what the page is told, and what the source actually does ──────────────────────────────────────────────

    @Test
    fun `an action state is spelled with the one status vocabulary the page colours by`() {
        // The Git view is a fourth surface painting `running` / `completed` / `failed`. 5.5.0 collapsed three
        // vocabularies into one because the same finished task was green in one view and grey in another; a
        // fifth word invented here would reopen exactly that.
        assertEquals(JcefStatus.of(AgentStatus.RUNNING), JcefGitData.ActionState.RUNNING.word)
        assertEquals(JcefStatus.of(AgentStatus.COMPLETED), JcefGitData.ActionState.COMPLETED.word)
        assertEquals(JcefStatus.of(AgentStatus.FAILED), JcefGitData.ActionState.FAILED.word)
        assertEquals(
            listOf("running", "completed", "failed"),
            JcefGitData.ActionState.entries.map { it.word },
            "The Git view's states are the shared vocabulary; a new word here has no CSS and paints as nothing.",
        )
    }

    @Test
    fun `the Git view's message parses to an id and carries nothing else`() {
        // Pure, so it is tested on a plain JVM. The narrowness IS the assertion: an id is the entire message,
        // which is what keeps every command line in GitIntegration built from literals.
        assertEquals(JcefBridge.Msg.GitAction("commit"), JcefBridge.parse("""{"type":"gitAction","id":"commit"}"""))
        // A missing id is an empty one, not a crash and not a null — the catalogue lookup drops it either way.
        assertEquals(JcefBridge.Msg.GitAction(""), JcefBridge.parse("""{"type":"gitAction"}"""))
        // Extra fields are ignored rather than trusted: nothing but `id` can ever reach the host.
        assertEquals(
            JcefBridge.Msg.GitAction("init"),
            JcefBridge.parse("""{"type":"gitAction","id":"init","argv":["rm","-rf","/"]}"""),
        )
    }

    @Test
    fun `GitIntegration invokes actions through performAction and nothing else`() {
        // The mutation check for the whole file above: swap `performAction` back for the deprecated
        // `invokeAction` and this goes red immediately, instead of at `verifyPlugin` time weeks later.
        val source = source("src/main/kotlin/dev/lain/claudejb/ui/GitIntegration.kt")
        assertTrue(
            "ActionUtil.performAction(" in source,
            "GitIntegration must invoke platform actions through ActionUtil.performAction.",
        )
        assertFalse(
            "ActionUtil.invokeAction(" in source,
            "ActionUtil.invokeAction is deprecated on every overload; use performAction(action, event).",
        )
    }

    @Test
    fun `the one command the plugin runs is a fixed argument vector, never a shell string`() {
        // A shell would be the whole problem: the Git view's message carries an id, and the moment a command is
        // assembled as text somebody eventually interpolates something into it. Every argument below is a
        // literal, so there is no string for a caller to reach.
        val source = source("src/main/kotlin/dev/lain/claudejb/ui/GitIntegration.kt")
        listOf("/bin/sh", "cmd.exe", "powershell", "-c\"", "ProcessBuilder", "Runtime.getRuntime").forEach {
            assertFalse(it in source, "GitIntegration must not reach a shell or spawn a process by hand: found '$it'")
        }
        assertTrue(
            Regex("""runGit\(root, "init", "-b",""").containsMatchIn(source),
            "The initial-branch form must stay `git init -b <branch>`, as an argv and not a command string.",
        )
    }

    // ── the context the action is handed, and the two shortcuts that must not be taken ────────────────────────

    @Test
    fun `the IDE invocation is given the tool window's own component, not the project alone`() {
        // The regression this guards is silent by construction: with a project-only context the action finds
        // no target, disables itself in `update`, and `performAction` returns `ignored` — a button that does
        // nothing, with no exception and nothing on screen. That is the bug this call site already had once.
        val code = codeOf("ui/GitIntegration.kt")
        assertTrue(
            code.any { "ClaudeToolWindowFactory.contextComponent(" in it },
            "GitIntegration no longer builds its data context from the tool window's component.",
        )
        assertTrue(
            code.any { "DataManager.getInstance().getDataContext(" in it },
            "GitIntegration no longer asks DataManager for the component's context, so every key the tool " +
                "window's providers contribute is gone and the actions are back to deciding on one key.",
        )
    }

    @Test
    fun `nothing in this plugin decides how the IDE draws its popups`() {
        // Popup weight is the reflex fix when a platform popup misbehaves over an embedded browser, and here
        // it is both useless and out of proportion. Useless: every popup on this path is a native window
        // already — `AbstractPopup.init` forces heavyweight, and an action menu extends `JBPopupMenu`, whose
        // constructor calls `setLightWeightPopupEnabled(false)`. Out of proportion: both routes to it, the
        // static Swing switch and the `idea.popup.weight` system property, are JVM-wide and belong to the
        // IDE. The property's name is a string literal and therefore invisible once literals are reduced
        // away, so the guard is on the call that would set any of them.
        val offenders = MainSources.files()
            .flatMap { file -> MainSources.codeOf(file).map { file.name to it } }
            .filter { (_, line) -> "LightWeightPopupEnabled" in line || "System.setProperty(" in line }
            .map { (name, line) -> "$name: ${line.trim()}" }
        assertEquals(
            emptyList<String>(),
            offenders,
            "This plugin sets a JVM-wide UI property. Whatever it is meant to fix, it changes behaviour for " +
                "every window in the user's IDE, and popup weight in particular is already decided by the " +
                "platform. Report the defect instead of reaching for a global switch.",
        )
    }

    @Test
    fun `the gear submenu hands the platform its own actions instead of invoking them`() {
        // The menu and the Git view's buttons are two doors onto one action id, and only the second builds an
        // invocation. Turning an entry here into a wrapper that routes into GitIntegration would look like a
        // fix for anything the menu does wrong, and would quietly cost the icon, the shortcut and the
        // platform's own enablement — the entire reason these are the IDE's action objects.
        val code = codeOf("ui/GitIdeMenu.kt")
        listOf("createEvent", "ActionUtil.", "actionPerformed").forEach { forbidden ->
            assertTrue(
                code.none { forbidden in it },
                "GitIdeMenu now invokes an action itself ('$forbidden'). It resolves ids and hands the " +
                    "objects over; the platform builds the event from the menu the entry was chosen in.",
            )
        }
    }

    private fun actionUtil(): Class<*> = load("com.intellij.openapi.actionSystem.ex.ActionUtil")

    /** [relative]'s code with comments and string literals reduced away, so a KDoc cannot satisfy a scan. */
    private fun codeOf(relative: String): List<String> =
        MainSources.codeOf(File(MainSources.root("src/main/kotlin"), "dev/lain/claudejb/$relative"))

    /**
     * Loads a class **without running its static initializer**: several platform classes refuse to initialize
     * outside a running IDE, and these are existence-and-shape assertions — initializing was never the question.
     */
    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    /** Resolves a repository path whether the test runs from the module dir or the repo root. */
    private fun source(path: String): String =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("could not locate $path from ${File("").absolutePath}")

    /**
     * `java.lang.Deprecated` and Kotlin's `@Deprecated` are both RUNTIME-retained, so reflection sees them.
     * `@ApiStatus.Internal` / `@ApiStatus.ScheduledForRemoval` are CLASS-retained and invisible here — that half
     * is `verifyPlugin`'s job (`INTERNAL_API_USAGES` is in its failure levels). Between the two, nothing slips.
     */
    private fun Method.assertNotDeprecated(): Method = apply {
        assertFalse(
            isAnnotationPresent(java.lang.Deprecated::class.java) || isAnnotationPresent(Deprecated::class.java),
            "$declaringClass.$name is deprecated — this repository does not ship deprecated API. Migrate before release.",
        )
    }
}
