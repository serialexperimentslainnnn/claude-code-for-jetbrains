package dev.lain.claudejb.drift

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The live half of drift detection: **updates both tools to latest first** (`npm update` for the vendored
 * SDK, `claude --update` for the binary), then measures the resulting protocol surface and diffs it against
 * what the plugin models via the pure [DriftDetector]. Prints an agent-consumable [DriftReport] and **fails**
 * when the latest surface exposes a kind the parser doesn't handle — so `./gradlew checkDrift` exits non-zero
 * on real, actionable drift (a bare version bump with a covered surface passes).
 *
 * Tagged `driftLive` so it is **excluded from the normal `test` task** (it touches the network and spawns the
 * binary) and runs only via the on-demand `checkDrift` task, which feeds it these system properties:
 *   - `claudejb.drift.projectDir` — repo root (cwd for `npm update`)
 *   - `claudejb.drift.sdkDir`     — vendored SDK dir (`sdk.d.ts` + `package.json`, read AFTER the update)
 *   - `claudejb.drift.binary`     — path to the `claude` executable
 *   - `claudejb.drift.baseline`   — `scripts/drift-baseline.properties` (last-reconciled sdk + binary versions)
 *
 * Reconciling a report: add the missing branches/serializers in `protocol/`, extend the `KNOWN_*` sets in
 * [ProtocolSurface], bump the baseline versions, then re-run to confirm green.
 */
@Tag("driftLive")
class DriftLiveCheck {

    @Test
    fun `update tools then report protocol drift`() {
        val projectDir = File(requireProp("claudejb.drift.projectDir"))
        val sdkDir = File(requireProp("claudejb.drift.sdkDir"))
        val binary = File(requireProp("claudejb.drift.binary"))
        val baseline = loadBaseline(File(requireProp("claudejb.drift.baseline")))

        // 1) Bring both tools to latest BEFORE measuring (the whole point: test against current reality).
        updateSdk(projectDir)
        updateBinary(binary)

        // 2) Read the now-updated surfaces.
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

    /** `npm update` the vendored SDK in-place (within its semver range) so node_modules holds the latest. */
    private fun updateSdk(projectDir: File) {
        runProcess(
            listOf("npm", "update", SDK_PKG),
            timeoutSec = 180,
            cwd = projectDir,
            // node-24 on this host aborts on the system openssl.cnf; an empty config sidesteps it and is a
            // harmless no-op elsewhere. (The JVM does its own TLS, so this only affects the spawned npm.)
            env = mapOf("OPENSSL_CONF" to "/dev/null"),
        )
    }

    /** `claude --update`: best-effort self-update. Already-latest or a transient failure must not fail the check. */
    private fun updateBinary(binary: File) {
        runCatching {
            runProcess(listOf(binary.absolutePath, "--update"), timeoutSec = 120, ignoreExit = true)
        }
    }

    /** `claude --version | awk '{print $1}'` — the version is the first whitespace-delimited token. */
    private fun binaryVersion(binary: File): String =
        runProcess(listOf(binary.absolutePath, "--version"), timeoutSec = 20)
            .trim().substringBefore(' ').ifBlank { "?" }

    /**
     * Drives the binary exactly as the plugin does (stream-json in/out, stdio permission tool), feeds one
     * canned user message, closes stdin, and captures stdout. Mirrors `scripts/probe-binary.sh`. The point is
     * only *which* event `type`s the updated binary emits — even an auth error surfaces as recognizable
     * `system`/`result` frames.
     */
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
        }.apply { isDaemon = true; start() }

        if (!proc.waitFor(60, TimeUnit.SECONDS)) proc.destroyForcibly()
        reader.join(2_000)
        return out.toString()
    }

    private fun readJsonVersion(packageJson: File): String =
        Regex(""""version"\s*:\s*"([^"]+)"""").find(packageJson.readText())?.groupValues?.get(1) ?: "?"

    private fun loadBaseline(file: File): Properties =
        Properties().apply { file.inputStream().use { load(it) } }

    /**
     * Runs [command] (optionally in [cwd] with extra [env]), returns combined stdout/stderr. Throws on timeout
     * and, unless [ignoreExit], on a non-zero exit — surfacing the captured output for diagnosis.
     */
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
