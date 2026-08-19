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

class IdeActionApiContractTest {

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

    @Test
    fun `an action event can still be built from a data context, a place and a ui kind`() {
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

    @Test
    fun `the working directory and parent environment builders are not deprecated`() {
        val commandLine = load("com.intellij.execution.configurations.GeneralCommandLine")
        commandLine.getMethod("withWorkingDirectory", java.nio.file.Path::class.java).assertNotDeprecated()
        commandLine
            .getMethod(
                "withParentEnvironmentType",
                load("com.intellij.execution.configurations.GeneralCommandLine\$ParentEnvironmentType"),
            )
            .assertNotDeprecated()
    }

    @Test
    fun `an action state is spelled with the one status vocabulary the page colours by`() {
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
        assertEquals(JcefBridge.Msg.GitAction("commit"), JcefBridge.parse("""{"type":"gitAction","id":"commit"}"""))
        assertEquals(JcefBridge.Msg.GitAction(""), JcefBridge.parse("""{"type":"gitAction"}"""))
        assertEquals(
            JcefBridge.Msg.GitAction("init"),
            JcefBridge.parse("""{"type":"gitAction","id":"init","argv":["rm","-rf","/"]}"""),
        )
    }

    @Test
    fun `GitIntegration invokes actions through performAction and nothing else`() {
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
        val source = source("src/main/kotlin/dev/lain/claudejb/ui/GitIntegration.kt")
        listOf("/bin/sh", "cmd.exe", "powershell", "-c\"", "ProcessBuilder", "Runtime.getRuntime").forEach {
            assertFalse(it in source, "GitIntegration must not reach a shell or spawn a process by hand: found '$it'")
        }
        assertTrue(
            Regex("""runGit\(root, "init", "-b",""").containsMatchIn(source),
            "The initial-branch form must stay `git init -b <branch>`, as an argv and not a command string.",
        )
    }

    @Test
    fun `the IDE invocation is given the tool window's own component, not the project alone`() {
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

    private fun codeOf(relative: String): List<String> =
        MainSources.codeOf(File(MainSources.root("src/main/kotlin"), "dev/lain/claudejb/$relative"))

    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    private fun source(path: String): String =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("could not locate $path from ${File("").absolutePath}")

    private fun Method.assertNotDeprecated(): Method = apply {
        assertFalse(
            isAnnotationPresent(java.lang.Deprecated::class.java) || isAnnotationPresent(Deprecated::class.java),
            "$declaringClass.$name is deprecated — this repository does not ship deprecated API. Migrate before release.",
        )
    }
}
