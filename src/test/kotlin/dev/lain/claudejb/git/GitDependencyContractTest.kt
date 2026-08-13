package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **The Git dependency must be declared, and declared OPTIONAL.**
 *
 * Two failure modes, opposite in direction, and this pins both.
 *
 * *Undeclared* is the failure `JcefDependencyContractTest` exists for: the Gradle `bundledPlugin(…)` covers
 * COMPILATION only. Without the matching `<depends>` in the descriptor, `git4idea.*` is not in this plugin's
 * classloader at runtime, and the first call dies with `NoClassDefFoundError` — a break `verifyPlugin` cannot
 * see, because it resolves against the whole IDE distribution rather than against our classloader.
 *
 * *Hard* is the failure specific to THIS dependency. JCEF is declared hard because there is no browser-less mode
 * to degrade to. Git is the opposite: a hard `<depends>Git4Idea</depends>` would make the plugin refuse to load
 * in any IDE where the user has disabled Git — taking the chat, which has nothing to do with version control,
 * down with it. So it is optional, with `claude-git.xml` as its config file, and the code degrades instead.
 */
class GitDependencyContractTest {

    private val main = File("src/main")
    private val descriptor = main.resolve("resources/META-INF/plugin.xml").readText()

    @Test
    fun `the descriptor declares Git4Idea whenever the sources use it`() {
        val usesGit = gitPackageSources().any { "git4idea" in it.readText() }
        if (!usesGit) return // nothing to declare
        assertTrue(
            DEPENDS.containsMatchIn(descriptor),
            "The sources import git4idea, so META-INF/plugin.xml MUST declare Git4Idea as a dependency — the " +
                "Gradle bundledPlugin() only covers compilation, not the runtime classloader.",
        )
    }

    @Test
    fun `the Git dependency is optional, never hard`() {
        val match = DEPENDS.find(descriptor)
        assertTrue(match != null, "No <depends…>Git4Idea</depends> found in plugin.xml")
        assertTrue(
            "optional=\"true\"" in match!!.value,
            "Git4Idea must be an OPTIONAL dependency: an IDE with Git disabled, or a project that is not a " +
                "working copy, must still load this plugin and run every chat. Found: ${match.value}",
        )
    }

    @Test
    fun `the optional dependency's config-file exists, or the platform refuses the descriptor`() {
        val configFile = Regex("""config-file="([^"]+)"[^>]*>Git4Idea<""").find(descriptor)?.groupValues?.get(1)
        assertTrue(configFile != null, "An optional <depends> must name a config-file")
        assertTrue(
            main.resolve("resources/META-INF/$configFile").isFile,
            "META-INF/$configFile is referenced by plugin.xml but does not exist",
        )
    }

    @Test
    fun `the build declares the bundled Git plugin, or nothing compiles against it`() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            Regex("""bundledPlugin\(\s*"Git4Idea"\s*\)""").containsMatchIn(build),
            "build.gradle.kts must declare bundledPlugin(\"Git4Idea\") in the intellijPlatform dependencies.",
        )
    }

    private fun gitPackageSources(): List<File> =
        main.resolve("kotlin/dev/lain/claudejb/git").listFiles()?.filter { it.isFile && it.extension == "kt" }.orEmpty()

    private companion object {
        val DEPENDS = Regex("""<depends[^>]*>Git4Idea</depends>""")
    }
}
