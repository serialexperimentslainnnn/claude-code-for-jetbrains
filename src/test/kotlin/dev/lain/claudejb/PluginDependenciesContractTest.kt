package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PluginDependenciesContractTest {

    private val metaInf = File("src/main/resources/META-INF")
    private val descriptor = metaInf.resolve("plugin.xml").readText()
    private val depends = DEPENDS.findAll(descriptor).map { Dependency(it.groupValues[1], it.groupValues[2].trim()) }.toList()

    @Test
    fun `every mandatory dependency is a platform module that every IntelliJ-based IDE ships`() {
        val mandatory = depends.filterNot { it.optional }.map { it.id }
        assertEquals(
            emptyList<String>(),
            mandatory - MODULES_IN_EVERY_IDE,
            "A mandatory <depends> the target IDE cannot satisfy means the plugin does not load at all, and the " +
                "verifier no longer fails the build over missing dependencies because it cannot tell an optional " +
                "one from a mandatory one. Make it optional with a config-file, or add it here with the proof that " +
                "every IDE in the verified range ships it.",
        )
    }

    @Test
    fun `every optional dependency names a config-file that exists`() {
        depends.filter { it.optional }.forEach { dependency ->
            val configFile = dependency.configFile
            assertTrue(configFile != null, "${dependency.id} is optional but names no config-file")
            assertTrue(
                metaInf.resolve(configFile!!).isFile,
                "META-INF/$configFile is referenced by plugin.xml for ${dependency.id} but does not exist",
            )
        }
    }

    @Test
    fun `the verifier is not asked to fail on missing dependencies, because it fails the optional ones too`() {
        val failureLevels = Regex("""failureLevel\s*=\s*listOf\(([^)]*)\)""").find(File("build.gradle.kts").readText())
        assertTrue(failureLevels != null, "No failureLevel list found in build.gradle.kts")
        assertFalse(
            "MISSING_DEPENDENCIES" in failureLevels!!.groupValues[1],
            "FailureLevel.MISSING_DEPENDENCIES turns every optional dependency a target IDE lacks into a failed " +
                "verification: PyCharm has no com.intellij.modules.java. The mandatory case is guarded by this test.",
        )
    }

    private class Dependency(attributes: String, val id: String) {
        val optional = OPTIONAL.containsMatchIn(attributes)
        val configFile = CONFIG_FILE.find(attributes)?.groupValues?.get(1)
    }

    private companion object {
        val DEPENDS = Regex("""<depends([^>]*)>([^<]+)</depends>""")
        val OPTIONAL = Regex("""optional\s*=\s*"true"""")
        val CONFIG_FILE = Regex("""config-file\s*=\s*"([^"]+)"""")
        val MODULES_IN_EVERY_IDE = setOf("com.intellij.modules.platform", "com.intellij.modules.jcef")
    }
}
