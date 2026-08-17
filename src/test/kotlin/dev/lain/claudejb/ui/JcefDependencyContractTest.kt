package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **The plugin must declare the JCEF plugin as a dependency, because the whole UI IS that browser.**
 *
 * THE INCIDENT THIS PINS (reported against 5.1.1 on IU-262.9437.185). Since build 262 the platform ships the
 * embedded browser as a bundled plugin of its own — `plugins/jcef-plugin`, id `com.intellij.modules.jcef`,
 * "Web Browser (JCEF)" — instead of inside the core. A plugin that does not declare it no longer gets
 * `com.intellij.ui.jcef.*` in its classloader, so opening a chat died on:
 *
 * ```
 * java.lang.NoClassDefFoundError: com/intellij/ui/jcef/JBCefApp
 *     at dev.lain.claudejb.ui.jcef.JcefHost.<init>(JcefHost.kt:62)
 * ```
 *
 * Not a degraded feature: the chat UI has been a JCEF page since 4.0.0, so the plugin was unusable on the
 * current IDE. The same split broke AsciiDoc, Cline and others on the 2026.2 line.
 *
 * **Why a source contract and not the verifier.** `verifyPlugin` reported Compatible against 262 the whole
 * time, and was right to: it resolves references against the entire IDE distribution, where those classes
 * plainly exist. What it does not model is the plugin's own CLASSLOADER, which is where the failure lives.
 * The gate that would have caught this is exactly this one — if the source touches `com.intellij.ui.jcef`,
 * the descriptor must say so.
 */
class JcefDependencyContractTest {

    private val root = File("src/main")

    @Test
    fun `the descriptor declares the JCEF plugin whenever the sources use it`() {
        val usesJcef = root.resolve("kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { "com.intellij.ui.jcef" in it.readText() }
        if (!usesJcef) return // nothing to declare

        val descriptor = root.resolve("resources/META-INF/plugin.xml").readText()
        assertTrue(
            "<depends>com.intellij.modules.jcef</depends>" in descriptor.replace(" ", ""),
            "The sources import com.intellij.ui.jcef, so META-INF/plugin.xml MUST declare " +
                "<depends>com.intellij.modules.jcef</depends> — since 262 those classes come from a bundled " +
                "plugin and are NOT on an undeclared plugin's classpath.",
        )
    }

    /**
     * The dependency is worthless if it is optional: an optional one that cannot be satisfied is skipped, and
     * the plugin would load on 262 exactly as broken as before — with no interface and no explanation.
     */
    @Test
    fun `the JCEF dependency is hard, never optional`() {
        val descriptor = root.resolve("resources/META-INF/plugin.xml").readText()
        val optional = Regex("""<depends[^>]*optional[^>]*>com\.intellij\.modules\.jcef</depends>""")
        assertTrue(
            !optional.containsMatchIn(descriptor),
            "com.intellij.modules.jcef must be a HARD dependency: there is no browser-less mode to fall back to.",
        )
    }

    /**
     * The floor moves with it — **and a branch number is not a floor.**
     *
     * This assertion used to read `sinceBuild >= 253`, on the belief that the whole 253 branch declares the
     * module. It does not, and the ten days in between are the entire bug: `com.intellij.modules.jcef` is
     * absent from **2025.3 (253.28294.334)** — its `product-backend.jar` carries 38 `com.intellij.modules.*`
     * aliases and none is that one — and present in **2025.3.1 (253.29346.138)**, which carries 39, the extra
     * one being exactly `jcef`. With the dependency declared mandatory, a plugin offered to 253.28294.334 is
     * one the IDE refuses to load: same failure as 5.1.1 on 2026.2, at the other end of the range.
     *
     * So the check is on the FULL build number, and a bare `"253"` now fails on purpose: it is precisely the
     * value that promises the build this does not work on.
     */
    @Test
    fun `sinceBuild is not lower than the first build that has the JCEF module`() {
        val build = File("build.gradle.kts").readText()
        // The first `sinceBuild` in the file is the DECLARED one (inside `ideaVersion`); the later one belongs
        // to the verifier's IDE `select`, which is a different claim — which EAP/RC builds to download.
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

    /** True when build number [actual] is greater than or equal to [floor], compared component by component. */
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
        /** IntelliJ IDEA **2025.3.1** — the first build whose platform declares `com.intellij.modules.jcef`. */
        const val FIRST_BUILD_WITH_JCEF_MODULE = "253.29346.138"
    }
}
