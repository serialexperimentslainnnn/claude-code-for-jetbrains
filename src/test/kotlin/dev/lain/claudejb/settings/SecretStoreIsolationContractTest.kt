package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A platform test that touches the credential store must install one of its own.
 *
 * **Why a source scan.** `SecretStore.storeOverride` being absent does not fail: it makes the store inert,
 * which is safe but silent, and an assertion like `assertNull(SecretStore.get(…))` then passes for the wrong
 * reason. The loud failures (a refused save, a migration that reports nothing) only catch the tests that
 * write. This catches the rest, and it catches the next one somebody adds — which is the actual risk, since
 * the shared-Application store is invisible from inside a test and the symptom shows up in a DIFFERENT
 * class hours later.
 *
 * The rule is deliberately crude: reach the store, install a store. A file that genuinely wants the no-store
 * behaviour (`SecretStoreIsolationHeadlessTest`) sets `storeOverride` explicitly anyway, which is the honest
 * way to want it.
 *
 * **It asks for the ASSIGNMENTS, not for the name.** Requiring only that `storeOverride` be mentioned was
 * the first version and it was already wrong: a class KDoc that merely links `[SecretStore.storeOverride]`
 * satisfied it, so deleting the setUp that installs the store left the scan green. Both halves are demanded
 * now — install and release — because a store installed and never released is the same shared state one test
 * later.
 *
 * **The residual gap, stated rather than papered over.** The trigger list and the required forms are string
 * matches, and a string match that stops matching passes forever. What holds it up is that every one of
 * these names is a compile-time identifier used by the very files being scanned: renaming `SecretStore`,
 * `storeOverride` or `setProviderApiKey` breaks the tests' own compilation long before this scan can go
 * quietly green. What it genuinely does NOT see is a new headless test that reaches the safe by some other
 * route — `PasswordSafe.instance` directly, say. That is a hole; it is not one this test can close without
 * becoming a linter.
 */
class SecretStoreIsolationContractTest {

    /** Anything that reaches the credential store, directly or through the settings document behind it. */
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

    /** The platform-backed test packages, from the module dir or the repo root. */
    private fun testRoots(): List<File> =
        listOf("headless", "integration").map { pkg ->
            sequenceOf(File("src/test/kotlin/dev/lain/claudejb/$pkg"), File("../src/test/kotlin/dev/lain/claudejb/$pkg"))
                .firstOrNull { it.isDirectory }
                ?: error("could not locate the $pkg test package from ${File("").absolutePath}")
        }
}
