package dev.lain.claudejb.ui

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
     * The floor moves with it. `com.intellij.modules.jcef` does not exist before 253 — verified in the IDE
     * distributions: on 251/252 `JBCefApp` ships inside `lib/app-client.jar` and nothing declares the module,
     * while 253 and 261 declare `<module value="com.intellij.modules.jcef"/>` in `product-backend.jar`. A
     * `sinceBuild` below 253 with this dependency declared is a plugin that refuses to load at all.
     */
    @Test
    fun `sinceBuild is not lower than the first build that has the JCEF module`() {
        val build = File("build.gradle.kts").readText()
        val since = Regex("""sinceBuild\s*=\s*"(\d+)"""").find(build)?.groupValues?.get(1)?.toInt()
        assertTrue(since != null && since >= FIRST_BUILD_WITH_JCEF_MODULE, "sinceBuild=$since is below 253")
    }

    private companion object {
        /** 2025.3 — the first branch whose platform declares the `com.intellij.modules.jcef` module. */
        const val FIRST_BUILD_WITH_JCEF_MODULE = 253
    }
}
