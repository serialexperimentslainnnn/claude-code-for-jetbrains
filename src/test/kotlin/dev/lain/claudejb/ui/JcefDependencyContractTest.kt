package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JcefDependencyContractTest {

    private val root = File("src/main")

    @Test
    fun `the descriptor declares the JCEF plugin whenever the sources use it`() {
        val usesJcef = root.resolve("kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { "com.intellij.ui.jcef" in it.readText() }
        if (!usesJcef) return

        val descriptor = root.resolve("resources/META-INF/plugin.xml").readText()
        assertTrue(
            "<depends>com.intellij.modules.jcef</depends>" in descriptor.replace(" ", ""),
            "The sources import com.intellij.ui.jcef, so META-INF/plugin.xml MUST declare " +
                "<depends>com.intellij.modules.jcef</depends> — since 262 those classes come from a bundled " +
                "plugin and are NOT on an undeclared plugin's classpath.",
        )
    }

    @Test
    fun `the JCEF dependency is hard, never optional`() {
        val descriptor = root.resolve("resources/META-INF/plugin.xml").readText()
        val optional = Regex("""<depends[^>]*optional[^>]*>com\.intellij\.modules\.jcef</depends>""")
        assertTrue(
            !optional.containsMatchIn(descriptor),
            "com.intellij.modules.jcef must be a HARD dependency: there is no browser-less mode to fall back to.",
        )
    }

    @Test
    fun `sinceBuild is not lower than the first build that has the JCEF module`() {
        val build = File("build.gradle.kts").readText()
        val since = Regex("""sinceBuild\s*=\s*"([^"]+)"""").find(build)?.groupValues?.get(1)
        assertNotNull(since, "No sinceBuild found in build.gradle.kts")
        assertTrue(
            since!!.count { it == '.' } >= 2,
            "sinceBuild=\"$since\" is a branch, not a build. `253` includes 253.28294.334, where " +
                "com.intellij.modules.jcef does not exist and the plugin cannot load. Pin the full number.",
        )
        assertTrue(
            atLeast(since, FIRST_BUILD_WITH_JCEF_MODULE),
            "sinceBuild=\"$since\" is below $FIRST_BUILD_WITH_JCEF_MODULE, the first build that ships " +
                "com.intellij.modules.jcef. Below it the IDE refuses to load this plugin outright.",
        )
    }

    private fun atLeast(actual: String, floor: String): Boolean {
        val a = actual.split('.').mapNotNull { it.toIntOrNull() }
        val f = floor.split('.').mapNotNull { it.toIntOrNull() }
        for (i in f.indices) {
            val left = a.getOrNull(i) ?: return false
            if (left != f[i]) return left > f[i]
        }
        return true
    }

    private companion object {
        const val FIRST_BUILD_WITH_JCEF_MODULE = "253.29346.138"
    }
}
