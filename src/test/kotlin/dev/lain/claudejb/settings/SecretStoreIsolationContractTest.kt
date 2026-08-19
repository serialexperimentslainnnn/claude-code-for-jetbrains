package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SecretStoreIsolationContractTest {

    private val touchesTheStore = listOf("SecretStore.", "SettingsStore.", "setProviderApiKey(")

    private val installsAStore = Regex("""SecretStore\.storeOverride\s*=\s*mutableMapOf""")
    private val releasesTheStore = Regex("""SecretStore\.storeOverride\s*=\s*null""")

    @Test
    fun `every platform test that touches the credential store installs one of its own`() {
        val offenders = testRoots().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .mapNotNull { file ->
                    val body = file.readText()
                    val touches = touchesTheStore.any { body.contains(it) }
                    val isolates = installsAStore.containsMatchIn(body) && releasesTheStore.containsMatchIn(body)
                    file.name.takeIf { touches && !isolates }
                }
                .toList()
        }

        assertTrue(offenders.isEmpty()) {
            "These tests reach the credential store without installing one of their own, so they read and " +
                "write the Application-wide store every other test class in the JVM shares. Add " +
                "`SecretStore.storeOverride = mutableMapOf()` in setUp and `= null` in tearDown:\n" +
                offenders.joinToString("\n")
        }
    }

    private fun testRoots(): List<File> =
        listOf("headless", "integration").map { pkg ->
            sequenceOf(File("src/test/kotlin/dev/lain/claudejb/$pkg"), File("../src/test/kotlin/dev/lain/claudejb/$pkg"))
                .firstOrNull { it.isDirectory }
                ?: error("could not locate the $pkg test package from ${File("").absolutePath}")
        }
}
