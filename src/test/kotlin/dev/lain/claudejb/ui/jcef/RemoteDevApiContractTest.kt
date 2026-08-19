package dev.lain.claudejb.ui.jcef

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RemoteDevApiContractTest {

    private val bannedTypes = listOf(
        "PlatformUtils",
        "AppMode",
        "AppModeAssertions",
        "ClientId",
        "ClientKind",
        "ClientSessionsManager",
        "ClientAppSession",
    )

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

    private fun sourceRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
