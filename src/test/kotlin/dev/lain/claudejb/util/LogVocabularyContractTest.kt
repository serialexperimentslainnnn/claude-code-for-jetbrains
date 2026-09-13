package dev.lain.claudejb.util

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LogVocabularyContractTest {

    private val sources: List<Pair<File, String>> = MainSources.files().map { it to MainSources.codeOf(it).joinToString("\n") }

    private fun path(file: File): String = file.path.replace('\\', '/').substringAfter("dev/lain/claudejb/")

    @Test
    fun `the platform logger is obtained in one place`() {
        val offenders = sources
            .filter { (_, code) -> PLATFORM_LOGGER.any { code.contains(it) } }
            .map { (file, _) -> path(file) }

        assertEquals(listOf("util/PluginLog.kt"), offenders) {
            "A platform Logger obtained outside PluginLog bypasses redaction and the ring the Log view reads."
        }
    }

    @Test
    fun `the ad-hoc trace channel is gone`() {
        val offenders = sources.filter { (_, code) -> code.contains("CC-TRACE") }.map { (file, _) -> path(file) }

        assertTrue(offenders.isEmpty()) { "CC-TRACE is a prefix on a dead debug line; PluginLog.debug {} is the trace: $offenders" }
    }

    @Test
    fun `the wire and the guard log nothing, and know no platform`() {
        val offenders = sources
            .filter { (file, _) -> path(file).startsWith("model/permission/") || path(file).startsWith("model/protocol/") }
            .filter { (_, code) -> code.contains("import com.intellij") || code.contains("PluginLog") }
            .map { (file, _) -> path(file) }

        assertTrue(offenders.isEmpty()) {
            "permission/ and protocol/ speak through their return values; a log line there is a second channel: $offenders"
        }
    }

    @Test
    fun `the process never logs a preview of a line, because a line can be a prompt`() {
        val (_, code) = sources.single { (file, _) -> path(file) == "controller/process/ClaudeProcess.kt" }

        assertTrue(!code.contains("line.take(")) { "ClaudeProcess logs part of a stdin or stdout line; log its length and type" }
    }

    private companion object {
        val PLATFORM_LOGGER = listOf("import com.intellij.openapi.diagnostic", "Logger.getInstance(")
    }
}
