package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The plugin does not ask the platform whether it is running under Remote Development. It finds out by failing to
 * deliver the page, and [nextPageRoute] is the whole answer.
 *
 * Every platform type that can answer the question directly is `@ApiStatus.Internal`, verified with
 * `javap -v` against the compiled build classpath — `PlatformUtils`, `AppMode`, `AppModeAssertions`, `ClientId`,
 * `ClientKind`, `ClientSessionsManager` and `ClientAppSession`. `INTERNAL_API_USAGES` is a `failureLevel` in
 * `build.gradle.kts`, so naming one is a red build today; the Marketplace has already blocked a release of this
 * plugin over an internal API once, so it is also a publication risk rather than a lint.
 *
 * The reason this is a source scan and not a review note is the *other* way of answering the question — reading
 * `idea.platform.prefix` out of the system properties. That passes the verifier by being invisible to it: no type
 * is named, so nothing is flagged, and when the property changes the detection silently returns the wrong answer
 * with no error anywhere. That is the exact shape of the 4.4.1 terminal regression, where three reflective lookups
 * for classes that were not in the IDE at all each returned false instead of throwing and `/login` quietly stopped
 * working for a whole release. So the ban is on the API, and the escape hatch is banned with it.
 *
 * A hit here means the plugin has started asking the question directly again. The answer is not to widen this
 * list: it is to get the information from a delivery that failed.
 */
class RemoteDevApiContractTest {

    /** The types that answer "is this a remote frontend?", all of them internal. */
    private val bannedTypes = listOf(
        "PlatformUtils",
        "AppMode",
        "AppModeAssertions",
        "ClientId",
        "ClientKind",
        "ClientSessionsManager",
        "ClientAppSession",
    )

    /** The property those types are read from, and therefore the way round them that must not appear either. */
    private val bannedProperty = "idea.platform.prefix"

    @Test
    fun `no source file asks the platform whether this is remote development`() {
        val banned = Regex("""\b(${bannedTypes.joinToString("|")})\b""")
        val offenders = ktFiles().flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                banned.find(line)?.let { "${file.name}:${index + 1}: ${it.value} — ${line.trim()}" }
            }
        }

        assertTrue(offenders.isEmpty()) {
            "These are @ApiStatus.Internal and `INTERNAL_API_USAGES` fails the build. Remote Development is " +
                "detected by delivery failure (JcefHost's PageRoute ladder), not by asking the platform.\n" +
                offenders.joinToString("\n")
        }
    }

    @Test
    fun `no source file reads the platform prefix instead`() {
        val offenders = ktFiles().flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { line.contains(bannedProperty) }
            }
        }

        assertTrue(offenders.isEmpty()) {
            "Reading `$bannedProperty` answers the same question while hiding from the verifier, and it fails " +
                "silently when the value changes. Use the delivery ladder.\n" + offenders.joinToString("\n")
        }
    }

    private fun ktFiles(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Resolves `src/main/kotlin` whether the test runs from the module dir or the repo root. */
    private fun sourceRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
