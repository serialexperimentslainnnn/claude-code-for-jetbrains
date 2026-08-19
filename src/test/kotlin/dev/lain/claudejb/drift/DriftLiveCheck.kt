package dev.lain.claudejb.drift

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

@Tag("driftLive")
class DriftLiveCheck {

    @Test
    fun `update tools then report protocol drift`() {
        val projectDir = File(requireProp("claudejb.drift.projectDir"))
        val sdkDir = File(requireProp("claudejb.drift.sdkDir"))
        val binary = File(requireProp("claudejb.drift.binary"))
        val baseline = loadBaseline(File(requireProp("claudejb.drift.baseline")))

        updateSdk(projectDir)
        updateBinary(binary)

        val latestDts = File(sdkDir, "sdk.d.ts").readText()
        val sdkLatestVersion = readJsonVersion(File(sdkDir, "package.json"))
        val installedBinary = binaryVersion(binary)
        val capture = probeBinary(binary)

        val report = DriftReport(
            sdkBaselineVersion = baseline.getProperty("sdk", "?"),
            sdkLatestVersion = sdkLatestVersion,
            binaryBaselineVersion = baseline.getProperty("binary", "?"),
            binaryInstalledVersion = installedBinary,
            sdk = DriftDetector.sdkDrift(latestDts),
            binary = DriftDetector.binaryDrift(capture),
        )

        println()
        println("================= DRIFT REPORT =================")
        println(report.render())
        println("===============================================")

        assertFalse(
            report.actionable,
            "Protocol drift detected — see the report above and reconcile the protocol models.",
        )
    }

    private fun updateSdk(projectDir: File) {
        runProcess(
            listOf("npm", "update", SDK_PKG),
            timeoutSec = 180,
            cwd = projectDir,
            env = mapOf("OPENSSL_CONF" to "/dev/null"),
        )
    }

    private fun updateBinary(binary: File) {
        runCatching {
            runProcess(listOf(binary.absolutePath, "--update"), timeoutSec = 120, ignoreExit = true)
        }
    }

    private fun binaryVersion(binary: File): String =
        runProcess(listOf(binary.absolutePath, "--version"), timeoutSec = 20)
            .trim().substringBefore(' ').ifBlank { "?" }

    private fun probeBinary(binary: File): String {
        val proc = ProcessBuilder(
            binary.absolutePath,
            "--print", "--output-format", "stream-json",
            "--input-format", "stream-json", "--verbose",
            "--permission-prompt-tool", "stdio",
        ).redirectErrorStream(false).start()

        proc.outputStream.bufferedWriter().use { w ->
            w.write("""{"type":"user","message":{"role":"user","content":"Say hi and exit."},"parent_tool_use_id":null}""")
            w.newLine()
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }.apply {
            isDaemon = true
            start()
        }

        if (!proc.waitFor(60, TimeUnit.SECONDS)) proc.destroyForcibly()
        reader.join(2_000)
        return out.toString()
    }

    private fun readJsonVersion(packageJson: File): String =
        Regex(""""version"\s*:\s*"([^"]+)"""").find(packageJson.readText())?.groupValues?.get(1) ?: "?"

    private fun loadBaseline(file: File): Properties =
        Properties().apply { file.inputStream().use { load(it) } }

    private fun runProcess(
        command: List<String>,
        timeoutSec: Long,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        ignoreExit: Boolean = false,
    ): String {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        cwd?.let { pb.directory(it) }
        if (env.isNotEmpty()) pb.environment().putAll(env)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            error("command timed out after ${timeoutSec}s: ${command.joinToString(" ")}\n$out")
        }
        if (!ignoreExit) {
            check(proc.exitValue() == 0) { "command failed (${proc.exitValue()}): ${command.joinToString(" ")}\n$out" }
        }
        return out
    }

    private fun requireProp(key: String): String =
        System.getProperty(key) ?: error("missing system property $key (run via ./gradlew checkDrift)")

    private companion object {
        const val SDK_PKG = "@anthropic-ai/claude-agent-sdk"
    }
}
